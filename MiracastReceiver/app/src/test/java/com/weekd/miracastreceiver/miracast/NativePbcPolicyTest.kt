package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePbcPolicyTest {
    private val target = "ca:94:02:7d:c2:71"

    @Test fun diagnosticRequiresDebugPackageAndExplicitMarker() {
        assertTrue(nativePbcEnabled(true, "com.weekd.miracastreceiver.miracasttest", true))
        assertFalse(nativePbcEnabled(false, "com.weekd.miracastreceiver.miracasttest", true))
        assertFalse(nativePbcEnabled(true, "com.weekd.miracastreceiver", true))
        assertFalse(nativePbcEnabled(true, "com.weekd.miracastreceiver.miracasttest", false))
    }

    @Test fun matchesOnlyFreshTargetEventFromSupplicantPid() {
        var now = 102_000L
        val gate = NativePbcEventGate(3672, "unique-sentinel", target, "p2p-wlan0-5") { now }
        val event = " 101.797  3672  3672 I wpa_supplicant: P2P-PROV-DISC-PBC-REQ CA:94:02:7D:C2:71 p2p_dev_addr=$target group=p2p-wlan0-5"
        assertFalse(gate.accepts(event))
        assertFalse(gate.accepts(" 101.700  10  10 I WFD_DIAG_SENTINEL: unique-sentinel"))
        assertTrue(gate.accepts(event))
        assertFalse(gate.accepts(event.replace("3672  3672", "1234  1234")))
        assertFalse(gate.accepts(event.replace("CA:94:02:7D:C2:71", "00:11:22:33:44:55")))
        assertFalse(gate.accepts(event.replace("group=p2p-wlan0-5", "group=p2p-wlan0-4")))
        now += 2000
        assertFalse(gate.accepts(event))
    }

    @Test fun onlyCurrentGroupOwnerInterfaceCanBecomeSocket() {
        assertEquals("/data/vendor/wifi/wpa/sockets/p2p-wlan0-5", nativePbcSocket("p2p-wlan0-5", true))
        assertNull(nativePbcSocket("p2p-wlan0-5", false))
        assertNull(nativePbcSocket("p2p-dev-wlan0", true))
        assertNull(nativePbcSocket("p2p-wlan0-5;reboot", true))
        assertNull(nativePbcSocket("wlan0", true))
    }

    @Test fun successRequiresUnambiguousOkAndZeroExit() {
        assertTrue(isStrictWpaOk(0, "OK\n"))
        assertFalse(isStrictWpaOk(0, ""))
        assertFalse(isStrictWpaOk(0, "FAIL\n"))
        assertFalse(isStrictWpaOk(0, "OK\nFAIL\n"))
        assertFalse(isStrictWpaOk(1, "OK\n"))
    }

    @Test fun alwaysCancelsAfterAttemptEvenOnFailureOrInterruption() {
        val calls = mutableListOf<String>()
        val result = runNativePbcAttempt(
            start = { calls += "start"; false },
            waitForWindow = { calls += "wait" },
            cancel = { calls += "cancel" }
        )
        assertFalse(result)
        assertEquals(listOf("start", "cancel"), calls)

        calls.clear()
        try {
            runNativePbcAttempt(
                start = { calls += "start"; true },
                waitForWindow = { throw InterruptedException() },
                cancel = { calls += "cancel" }
            )
        } catch (_: InterruptedException) { }
        assertEquals(listOf("start", "cancel"), calls)
    }

    @Test fun cancellationRequiresConfirmedStart() {
        var calls = 0
        cancelNativePbcIfConfirmed(false) { calls++ }
        assertEquals(0, calls)
        cancelNativePbcIfConfirmed(true) { calls++ }
        assertEquals(1, calls)
    }

    @Test fun connectionBroadcastAloneDoesNotInvalidateGroup() {
        assertFalse(nativePbcGroupChanged("iface|ssid|owner", "iface|ssid|owner"))
        assertTrue(nativePbcGroupChanged("iface|ssid|owner", null))
        assertTrue(nativePbcGroupChanged("iface|ssid|owner", "other|ssid|owner"))
        assertFalse(nativePbcGroupChanged(null, "iface|ssid|owner"))
    }
}
