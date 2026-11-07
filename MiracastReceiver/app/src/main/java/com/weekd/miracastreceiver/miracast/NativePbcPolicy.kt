package com.weekd.miracastreceiver.miracast

internal const val NATIVE_PBC_TARGET = "ca:94:02:7d:c2:71"
internal const val NATIVE_PBC_PACKAGE = "com.weekd.miracastreceiver.miracasttest"

internal fun nativePbcEnabled(debug: Boolean, packageName: String, marker: Boolean) =
    debug && packageName == NATIVE_PBC_PACKAGE && marker

internal fun nativePbcSocket(iface: String?, isGo: Boolean): String? =
    if (isGo && iface != null && Regex("p2p-wlan0-[0-9]{1,5}").matches(iface))
        "/data/vendor/wifi/wpa/sockets/$iface" else null

internal fun nativePbcGroupChanged(old: String?, new: String?) = old != null && old != new

internal fun isStrictWpaOk(exitCode: Int?, output: String) = exitCode == 0 && output.trim() == "OK"

internal inline fun cancelNativePbcIfConfirmed(confirmed: Boolean, cancel: () -> Unit) {
    if (confirmed) cancel()
}

internal class NativePbcEventGate(private val pid: Int, private val nonce: String, private val target: String, private val iface: String, private val now: () -> Long) {
    private var armed = false
    private val line = Regex("^(\\d+)\\.(\\d{3})\\d*\\s+(\\d+)\\s+\\d+\\s+[A-Z]\\s+([^:]+):\\s*(.*)$")
    private val request = Regex("^P2P-PROV-DISC-PBC-REQ\\s+([0-9a-fA-F:]{17})(?:\\s|$).*")
    fun accepts(input: String): Boolean {
        val match = line.matchEntire(input.trim()) ?: return false
        val (seconds, millis, process, tag, body) = match.destructured
        val eventMillis = (seconds.toLongOrNull() ?: return false) * 1000 + (millis.toLongOrNull() ?: return false)
        val age = now() - eventMillis
        if (age !in 0..1500) return false
        if (tag.trim() == "WFD_DIAG_SENTINEL" && body == nonce) { armed = true; return false }
        if (!armed || tag.trim() != "wpa_supplicant" || process.toIntOrNull() != pid) return false
        val addressMatches = request.matchEntire(body)?.groupValues?.get(1)?.equals(target, ignoreCase = true) == true
        return addressMatches && body.split(' ').contains("group=$iface")
    }
}

internal inline fun runNativePbcAttempt(start: () -> Boolean, waitForWindow: () -> Unit, cancel: () -> Unit): Boolean {
    try {
        val ok = start()
        if (ok) waitForWindow()
        return ok
    } finally { cancel() }
}
