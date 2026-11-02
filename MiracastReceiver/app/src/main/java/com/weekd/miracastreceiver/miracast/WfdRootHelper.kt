package com.weekd.miracastreceiver.miracast

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * 通过 root 向 wpa_supplicant 注入 Wi-Fi Display Sink 的 WFD IE。
 *
 * 为什么必须走 root：Windows 靠扫描 beacon/probe response 里的 WFD IE 来发现无线显示器，
 * 而应用层调 `WifiP2pManager.setWFDInfo()` 需要 signature 级的 `CONFIGURE_WIFI_DISPLAY`
 * 权限，普通应用必定失败（实测：`SecurityException: Wifi Display Permission denied`）。
 * 唯一的绕法是直接和 wpa_supplicant 的控制 socket 对话，而那个 socket 需要 root + 合适的
 * SELinux 上下文，所以借助随 APK 打包的 [BINARY_NAME]（见 cpp/wfdctl.c）由 su 拉起。
 *
 * 没有 root 时全部方法安全失败并返回 false —— Miracast 发现不了，其余投屏方式不受影响。
 */
object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"

    /**
     * wpa_supplicant 控制 socket 所在目录，按新旧系统顺序尝试。
     *
     * 注意这里只给目录，具体用哪个 socket 靠 [resolveSocketPath] 动态枚举。
     * 早期版本把接口名硬编码成 `p2p0`，但不同厂商的命名完全不同：
     *   - 老机型 / 标准实现：p2p0
     *   - Amlogic / 展锐等定制 ROM：p2p-dev-wlan0 + p2p-wlan0-N（N 每次建组都变）
     * 硬编码会导致兜底落到 wlan0 —— 那是 STA 接口，写入后 WFD IE 只进全局配置，
     * P2P 接口上拿不到，Source 的 Provision Discovery 请求就永远等不到应答。
     */
    private val CTRL_SOCKET_DIRS = listOf(
        "/data/vendor/wifi/wpa/sockets",
        "/data/misc/wifi/sockets"
    )

    /**
     * 解析实际可用的控制 socket。
     *
     * 优先级：p2p-dev-* > p2p0 > p2p-wlan0-N > wlan0。
     * 实测（Amlogic S905L3 + 展锐 WiFi）：写 p2p-dev-wlan0 之后，wpa_supplicant
     * 会自动把 WFD_SUBELEM 同步到所有 p2p-wlan0-N（GO）接口，因此只需要命中它。
     */
    private fun resolveSocketPath(): String? {
        for (dir in CTRL_SOCKET_DIRS) {
            val listing = runAsRootOutput("ls -1 $dir 2>/dev/null") ?: continue
            val names = listing.lines().map { it.trim() }.filter { it.isNotEmpty() }

            // p2p-dev-*：P2P device 接口，写入后会同步到 GO 接口，最优选
            names.firstOrNull { it.startsWith("p2p-dev") }?.let { return "$dir/$it" }
            names.firstOrNull { it == "p2p0" }?.let { return "$dir/$it" }
            names.firstOrNull { it.startsWith("p2p-wlan") }?.let { return "$dir/$it" }
        }
        // 最后才退回 STA 接口：能广播 WFD IE，但 P2P 握手仍然不可用
        for (dir in CTRL_SOCKET_DIRS) {
            if (runAsRoot("test -S $dir/wlan0")) return "$dir/wlan0"
        }
        return null
    }

    /**
     * WFD 设备信息子元素（子元素 0），共 6 字节负载：
     *   0011 → 设备类型 primary sink(01) + 会话可用(bits4-5=01)
     *   1c44 → RTSP 会话管理控制端口 7236
     *   0032 → 最大吞吐 50 Mbps
     * 前面的 0006 是子元素长度字段，wpa_supplicant 要求连长度一起给。
     */
    private fun subelemHex(controlPort: Int, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort) + "%04x".format(maxThroughputMbps)

    /**
     * 注入 WFD IE，让本机以 Miracast Sink 的身份被发现。
     *
     * @param controlPort 我们在 IE 里宣告的 RTSP 控制端口（WFD 标准 7236）
     * @return 是否成功执行（不代表 Windows 一定能发现，还取决于驱动是否支持 sink 模式）
     */
    fun advertiseSink(context: Context, controlPort: Int = 7236): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            Timber.w("WFD: $BINARY_NAME not found in nativeLibraryDir")
            return false
        }

        val socketPath = resolveSocketPath()
        if (socketPath == null) {
            Timber.w("WFD: no wpa_supplicant control socket found (no root?)")
            return false
        }

        val cmd = "${binary.absolutePath} $socketPath " +
            "\"SET wifi_display 1\" " +
            "\"WFD_SUBELEM_SET 0 ${subelemHex(controlPort)}\""

        return if (runAsRoot(cmd)) {
            Timber.i("WFD: sink IE injected via $socketPath (control port $controlPort)")
            true
        } else {
            Timber.w("WFD: failed to inject sink IE — device may not be rooted")
            false
        }
    }

    /** 撤销 WFD 广播（关闭投屏接收时调用，避免一直对外宣告自己是显示器）。 */
    fun stopAdvertising(context: Context): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socketPath = resolveSocketPath() ?: return false
        return runAsRoot("${binary.absolutePath} $socketPath \"SET wifi_display 0\"")
    }

    /** 设备是否可用 root（用于 UI 提示 Miracast 是否可能工作）。 */
    fun isRootAvailable(): Boolean = runAsRoot("id")

    private fun runAsRoot(command: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (output.isNotEmpty()) Timber.d("WFD root: $output")
        exit == 0
    } catch (e: Exception) {
        Timber.d("WFD root unavailable: ${e.message}")
        false
    }

    /** 同 [runAsRoot]，但返回命令输出（用于枚举 socket 目录）。 */
    private fun runAsRootOutput(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output
    } catch (e: Exception) {
        Timber.d("WFD root unavailable: ${e.message}")
        null
    }
}
