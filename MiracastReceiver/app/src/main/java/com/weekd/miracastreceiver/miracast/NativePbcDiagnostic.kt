package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.weekd.miracastreceiver.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import timber.log.Timber

/** Explicitly opted-in diagnostic only; wpa_cli OK does not prove peer filtering.
 * This bypasses the Android framework's acceptance state machine and cannot dismiss its dialog.
 */
internal class NativePbcDiagnostic(private val context: Context, private val queryGroup: ((WifiP2pGroup?) -> Unit) -> Unit) {
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var logProcess: Process? = null
    @Volatile private var commandProcess: Process? = null
    private var worker: Thread? = null
    private var groupIdentity: String? = null

    private fun enabled(): Boolean = try {
        nativePbcEnabled(BuildConfig.DEBUG, context.packageName,
            File(context.filesDir, "wfd-auto-pbc.enable").isFile)
    } catch (_: Exception) { false }

    private fun identity(group: WifiP2pGroup?): String? {
        val socket = nativePbcSocket(group?.`interface`, group?.isGroupOwner == true) ?: return null
        return "$socket|${group?.networkName}|${group?.owner?.deviceAddress}"
    }

    fun onGroup(group: WifiP2pGroup) {
        if (Build.VERSION.SDK_INT < 26) return
        val socket = nativePbcSocket(group.`interface`, group.isGroupOwner)
        val identity = identity(group)
        val changed = synchronized(lock) { nativePbcGroupChanged(groupIdentity, identity) }
        if (changed) stop()
        if (socket == null || identity == null || !enabled()) return
        synchronized(lock) {
            if (worker?.isAlive == true) return
            running = true
            groupIdentity = identity
            worker = Thread({ watch(socket) }, "wfd-native-pbc").also { it.start() }
        }
    }

    fun stop() {
        val thread = synchronized(lock) {
            running = false
            groupIdentity = null
            logProcess?.destroy()
            commandProcess?.destroy()
            worker?.also { it.interrupt() }
        }
        if (thread != Thread.currentThread()) try { thread?.join(3500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private data class Result(val code: Int?, val output: String)

    private fun command(shell: String, requireRunning: Boolean = false): Result {
        var process: Process? = null
        return try {
            // timeout also bounds the child if su outlives the Java process.
            process = synchronized(lock) {
                if (requireRunning && (!running || !enabled())) return Result(null, "")
                ProcessBuilder("su", "-c", "exec timeout 2 sh -c '$shell'").redirectErrorStream(true).start().also {
                    commandProcess = it
                }
            }
            val child = process
            val output = StringBuilder()
            val reader = Thread({
                try { child.inputStream.bufferedReader().use { input ->
                    val chars = CharArray(1024)
                    while (true) {
                        val n = input.read(chars)
                        if (n < 0) break
                        if (output.length < 8192) output.append(chars, 0, minOf(n, 8192 - output.length))
                    }
                } } catch (_: Exception) { }
            }, "wfd-pbc-output").apply { isDaemon = true; start() }
            val ended = child.waitFor(2500, TimeUnit.MILLISECONDS)
            if (!ended) child.destroy()
            reader.join(200)
            if (!ended || reader.isAlive) Result(null, "") else Result(child.exitValue(), output.toString())
        } catch (_: Exception) { Result(null, "") }
        finally {
            process?.destroy()
            runCatching { process?.inputStream?.close() }
            synchronized(lock) { if (commandProcess === process) commandProcess = null }
        }
    }

    private fun isCurrentGroup(expectedIdentity: String): Boolean {
        val done = CountDownLatch(1)
        var matches = false
        Handler(Looper.getMainLooper()).post {
            queryGroup { group ->
                matches = identity(group) == expectedIdentity
                done.countDown()
            }
        }
        return done.await(750, TimeUnit.MILLISECONDS) && matches
    }

    private fun watch(socket: String) {
        try {
            val pidResult = command("pidof wpa_supplicant", requireRunning = true)
            val pid = if (pidResult.code == 0) pidResult.output.trim().toIntOrNull() else null
            if (pid == null || pid <= 0 || !running || !enabled()) return
            val nonce = UUID.randomUUID().toString()
            val gate = NativePbcEventGate(pid, nonce, NATIVE_PBC_TARGET, socket.substringAfterLast('/')) { SystemClock.uptimeMillis() }
            val process = synchronized(lock) {
                if (!running || !enabled()) return
                ProcessBuilder("su", "-c", "exec logcat -v monotonic -T 1 -s wpa_supplicant:I WFD_DIAG_SENTINEL:I")
                    .redirectErrorStream(true).start().also { logProcess = it }
            }
            if (!running) { process.destroy(); return }
            Thread.sleep(250)
            Log.i("WFD_DIAG_SENTINEL", nonce)
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!running || !enabled()) break
                    if (gate.accepts(line)) attempt(socket)
                }
            }
        } catch (_: InterruptedException) {
            // stop() interrupts a pending wait; the attempt's finally still cancels WPS.
        } catch (e: Exception) {
            Timber.w(e, "Native PBC diagnostic stopped")
        } finally {
            logProcess?.destroy()
            logProcess = null
            running = false
        }
    }

    private fun attempt(socket: String) {
        if (!running || !enabled()) return
        val expectedIdentity = synchronized(lock) { groupIdentity } ?: return
        val observedAt = SystemClock.uptimeMillis()
        val iface = socket.substringAfterLast('/') // Created exclusively by nativePbcSocket.
        val prefix = "/vendor/bin/wpa_cli -p /data/vendor/wifi/wpa/sockets -i $iface"
        val status = command("test -S $socket && $prefix status", requireRunning = true)
        val lines = status.output.lines().map { it.trim() }.toSet()
        if (status.code != 0 || "mode=P2P GO" !in lines || "wpa_state=COMPLETED" !in lines || !isCurrentGroup(expectedIdentity) ||
            SystemClock.uptimeMillis() - observedAt > 1500 || !running || !enabled()) return
        val deadline = SystemClock.uptimeMillis() + 5000
        var interrupted = false
        var confirmed = false
        try {
            runNativePbcAttempt(
                start = {
                    if (!running || !enabled()) false else {
                        val result = command("$prefix wps_pbc p2p_dev_addr=$NATIVE_PBC_TARGET", requireRunning = true)
                        confirmed = isStrictWpaOk(result.code, result.output)
                        if (confirmed) Timber.i("Native PBC start confirmed")
                        else Timber.w("Native PBC start not confirmed (FAIL, timeout, or interrupted); no cancellation will be sent")
                        confirmed
                    }
                },
                waitForWindow = {
                    while (running && enabled() && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                },
                cancel = {
                    // Clear interruption so cancellation gets a bounded opportunity to run.
                    interrupted = Thread.interrupted()
                    cancelNativePbcIfConfirmed(confirmed) {
                        val result = command("$prefix wps_cancel")
                        Timber.i("Native PBC cancellation accepted=%s", isStrictWpaOk(result.code, result.output))
                    }
                }
            )
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
