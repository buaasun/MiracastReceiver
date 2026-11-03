package com.weekd.miracastreceiver.miracast

import timber.log.Timber

/**
 * 仅 debug 构建使用：Windows 发起 Wi-Fi Direct 配对时，电视会弹出系统「连接邀请」对话框，
 * 必须在约 60 秒内点「接受」，Windows 才不会放弃。遥控器操作经常来不及，所以这里监听
 * wpa_supplicant 的 `P2P-PROV-DISC-PBC-REQ` 事件，延迟 [DELAY_MS] 后用 root 点掉「接受」。
 *
 * 坐标按 1920x1080 屏幕写死（「接受」按钮中心），其他分辨率不适用。
 */
class P2pAutoAccept {

    private val lock = Any()
    private var generation = 0
    private var thread: Thread? = null
    private var logcat: Process? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        val gen = ++generation
        thread = Thread({ watch(gen) }, "P2pAutoAccept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        generation++ // 让旧线程在下一次检查时退出
        logcat?.destroy()
        logcat = null
        thread?.interrupt()
        thread = null
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { gen == generation }

    private fun watch(gen: Int) {
        var lastTapAt = 0L
        while (isCurrent(gen)) {
            var process: Process? = null
            try {
                // -T 1：只看启动之后的新日志，避免重放旧的 PBC-REQ
                process = ProcessBuilder("su", "-c", "logcat -T 1 -v brief")
                    .redirectErrorStream(true)
                    .start()
                synchronized(lock) {
                    if (gen != generation) return
                    logcat = process
                }
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isCurrent(gen)) break
                        if (!line.contains("P2P-PROV-DISC-PBC-REQ")) continue
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < DEBOUNCE_MS) continue
                        lastTapAt = now
                        Timber.i("P2P auto-accept: PBC-REQ seen, tapping accept in ${DELAY_MS}ms")
                        Thread.sleep(DELAY_MS)
                        if (!isCurrent(gen)) break
                        tapAccept()
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Timber.w(e, "P2P auto-accept watcher failed")
            } finally {
                process?.destroy()
                synchronized(lock) { if (logcat === process) logcat = null }
            }
            if (isCurrent(gen)) {
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun tapAccept() {
        try {
            val p = ProcessBuilder("su", "-c", "input tap $ACCEPT_X $ACCEPT_Y")
                .redirectErrorStream(true)
                .start()
            val exit = p.waitFor()
            Timber.i("P2P auto-accept: tap exit=$exit")
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "P2P auto-accept tap failed")
        }
    }

    private companion object {
        const val DELAY_MS = 2_000L
        const val DEBOUNCE_MS = 30_000L
        const val RETRY_MS = 3_000L
        const val ACCEPT_X = 1302
        const val ACCEPT_Y = 613
    }
}
