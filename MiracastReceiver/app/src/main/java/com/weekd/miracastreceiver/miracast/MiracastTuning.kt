package com.weekd.miracastreceiver.miracast

import android.content.Context
import com.weekd.miracastreceiver.util.AppSettings

/**
 * Miracast 的可调开关，用于在真机上逐项验证「哪个修复才是必要的」。
 *
 * 背景：这套 Wi-Fi Display 实现跨设备差异极大（不同厂商的 wpa_supplicant socket
 * 命名、Source 对 SETUP Transport 头的容忍度、是否真的往 RTCP 端口发包都不一样），
 * 而现场只有一台机器可测。把有争议的几处做成开关，就能在同一台设备上分别关掉
 * 再跑一次，用日志确认每项是否必需，而不是靠猜。
 *
 * 默认值全部等于「已验证可用」的那一套；关掉即回到改动前的行为。
 */
object MiracastTuning {

    private const val KEY_RTCP_PORT_ENABLED = "miracast_rtcp_port_enabled"
    private const val KEY_DUAL_CLIENT_PORT = "miracast_dual_client_port"
    private const val KEY_SOCKET_DYNAMIC = "miracast_socket_dynamic"

    /**
     * SETUP 的 Transport 头里是否声明 RTCP 端口（`client_port=<rtp>-<rtcp>`）。
     *
     * 关掉 = 只声明 RTP 单端口（改前的行为）。
     * RFC 2326 §12.39 的语法允许单端口，但 Windows 实测会往 RTCP 端口发
     * `MF_SEND_RATE` 私有扩展，声明单端口时它可能因此不启动推流。
     */
    fun isDualClientPort(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DUAL_CLIENT_PORT, true)

    fun setDualClientPort(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_DUAL_CLIENT_PORT, enabled).apply()

    /**
     * 是否绑定并消费 RTCP 端口（rtpPort + 1）。
     *
     * 独立于 [isDualClientPort]：声明了端口却没人接收，内核会持续丢包并占着
     * 160KB 缓冲；所以「声明」和「绑定」必须分开验证。
     */
    fun isRtcpPortEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RTCP_PORT_ENABLED, true)

    fun setRtcpPortEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_RTCP_PORT_ENABLED, enabled).apply()

    /**
     * 是否动态枚举 wpa_supplicant 控制 socket。
     *
     * 关掉 = 只用 `p2p0`（改前的硬编码列表，找不到就退回 wlan0）。
     * 用于验证「非标准 socket 命名」到底是不是某些设备连不上的原因。
     */
    fun isSocketPathDynamic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOCKET_DYNAMIC, true)

    fun setSocketPathDynamic(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_SOCKET_DYNAMIC, enabled).apply()

    private fun prefs(context: Context) = AppSettings.prefs(context)
}
