package top.jast.miracastreceiver.miracast

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

class WfdVideoCapabilitiesTest {
    @Test fun nativeResolutionAndRotationAreDynamic() {
        val landscape = requireNotNull(chooseWfdCustomVideoMode(1080, 2160, false, true) { _, _, _, _ -> true })
        val portrait = requireNotNull(chooseWfdCustomVideoMode(1080, 2160, true, true) { _, _, _, _ -> true })
        assertEquals(2160 to 1080, landscape.width to landscape.height)
        assertEquals(1080 to 2160, portrait.width to portrait.height)
        assertEquals("0438 0870 003C", portrait.customFormat())
        assertEquals(0x20, portrait.levelBit)
        assertEquals(1440 to 3120, requireNotNull(chooseWfdCustomVideoMode(1440, 3120, true, true)
            { _, _, _, _ -> true }).let { it.width to it.height })
    }

    @Test fun legacyCodecLimitsTriggerProportionalFallback() {
        val mode = requireNotNull(chooseWfdCustomVideoMode(1080, 2160, true, false) { _, _, _, _ -> true })
        assertEquals(960 to 1920, mode.width to mode.height)
        assertEquals(0x10, mode.levelBit)
    }

    @Test fun hardwareRateAndSizeLimitsAreRespected() {
        val mode = requireNotNull(chooseWfdCustomVideoMode(3840, 2160, false, true) { w, h, fps, _ ->
            w <= 1920 && h <= 1080 && fps <= 30
        })
        assertEquals(1920 to 1080, mode.width to mode.height)
        assertEquals(30, mode.fps)
        assertNull(chooseWfdCustomVideoMode(3840, 2160, false, true) { _, _, _, _ -> false })
    }

    @Test fun codecLevelAccountsForMacroblocksAndFrameRate() {
        assertEquals(0x10, wfdAvcLevel(1920, 1080, 60)?.first)
        assertEquals(0x20, wfdAvcLevel(2160, 1080, 60)?.first)
        assertEquals(0x40, wfdAvcLevel(3840, 2160, 30)?.first)
        assertEquals(0x80, wfdAvcLevel(3840, 2160, 60)?.first)
    }

    @Test fun extensionFieldsHaveTheSpecifiedWidths() {
        val fields = WfdCustomVideoMode(2160, 1080, 60, 0x20).extendedFormats().split(' ')
        assertEquals(4, fields[0].length)
        assertEquals(4, fields[2].length)
        assertEquals(4, fields[3].length)
        assertEquals(10, fields[4].length)
        assertEquals(10, fields[5].length)
        assertEquals("00000001C0", fields[4])
    }

    @Test fun unsupportedExtensionsAreNeverSentToLegacyPeers() {
        val mode = WfdCustomVideoMode(1080, 2160, 60, 0x20)
        val legacy = wfdVideoCapabilityLines(setOf("wfd_video_formats"), mode)
        assertEquals(1, legacy.size)
        assertTrue(legacy.single().startsWith("wfd_video_formats:"))
        assertFalse(legacy.single().contains("microsoft"))
        assertEquals("microsoft_custom_video_formats: none",
            wfdVideoCapabilityLines(setOf("microsoft_custom_video_formats"), mode).last())
    }

    @Test fun selectedCustomModeMustMatchTheAdvertisedSizeAndRate() {
        val mode = WfdCustomVideoMode(1080, 2160, 60, 0x20)
        assertTrue(matchesWfdCustomMode("0438 0870 003c", mode))
        assertFalse(matchesWfdCustomMode("0870 0438 003C", mode))
        assertFalse(matchesWfdCustomMode("0438 0870 001E", mode))
        assertFalse(matchesWfdCustomMode("1080 2160", mode))
    }

    private class WireSocket(input: String) : Socket() {
        val replies = ByteArrayOutputStream()
        private val requests = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
        private var closed = false
        override fun getInputStream() = requests
        override fun getOutputStream() = replies
        override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun isClosed() = closed
        override fun setSoTimeout(timeout: Int) = Unit
        override fun close() { closed = true }
    }

    private fun getParameters(names: String): String =
        "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\nContent-Length: ${names.toByteArray().size}\r\n\r\n$names"

    @Test fun actualM3ResponseAdvertisesCustomPortraitModeAndCorrectByteLength() {
        val request = "wfd_video_formats\r\nwfdx_video_formats\r\nmicrosoft_custom_video_formats\r\n"
        val socket = WireSocket(getParameters(request))
        sessionHandler(socket) { extended ->
            assertTrue(extended)
            WfdCustomVideoMode(1080, 2160, 60, 0x20)
        }.handleSession()
        val response = socket.replies.toString("UTF-8")
        val body = response.substringAfter("\r\n\r\n")
        assertTrue(body.contains("microsoft_custom_video_formats: 0438 0870 003C\r\n"))
        assertTrue(body.contains("wfdx_video_formats: 0040 00 0002 0020"))
        assertTrue(body.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000"))
        assertTrue(response.contains("Content-Length: ${body.toByteArray().size}\r\n"))
    }

    @Test fun legacyM3DoesNotEvenQueryCustomHardwareCapabilities() {
        val socket = WireSocket(getParameters("wfd_video_formats\r\nwfd_client_rtp_ports\r\n"))
        sessionHandler(socket) { error("Unexpected extension query") }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertTrue(response.contains("wfd_video_formats:"))
        assertFalse(response.contains("wfdx_video_formats:"))
        assertFalse(response.contains("microsoft_custom_video_formats:"))
    }

    @Test fun m4RejectsAnUnadvertisedCustomResolution() {
        val body = "microsoft_custom_video_formats: 0438 0870 003C\r\n"
        val socket = WireSocket("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\nContent-Length: ${body.length}\r\n\r\n$body")
        sessionHandler(socket).handleSession()
        assertTrue(socket.replies.toString("UTF-8").startsWith("RTSP/1.0 400 Bad Request"))
    }

    // 注入接口尚未实现时回落到旧构造器，让 RED 验证现有协议行为而非编译失败。
    private fun sessionHandler(
        socket: WireSocket,
        compatibilityModeProvider: () -> Boolean = { false },
        customModeProvider: (Boolean) -> WfdCustomVideoMode? = { null }
    ): WfdSessionHandler {
        val constructor = WfdSessionHandler::class.java.constructors.firstOrNull {
            it.parameterTypes.toList() == listOf(
                android.content.Context::class.java, Socket::class.java, Int::class.javaPrimitiveType,
                kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java
            )
        }
        return if (constructor != null) {
            constructor.newInstance(ContextWrapper(null), socket, 19000,
                compatibilityModeProvider, customModeProvider) as WfdSessionHandler
        } else {
            // 此调用同时确保新增 provider 后仍支持原来的 trailing lambda。
            WfdSessionHandler(ContextWrapper(null), socket, 19000) { customModeProvider(it) }
        }
    }

    private val allVideoParameters =
        "wfd_video_formats\r\nwfdx_video_formats\r\nmicrosoft_custom_video_formats\r\n"
    private val custom60 = WfdCustomVideoMode(2160, 1080, 60, 0x20)
    private val legacy30 = "00 00 02 10 00000080 00000000 00000000 00 0000 0000 00 none none"
    private val legacy60 = "00 00 02 10 00000100 00000000 00000000 00 0000 0000 00 none none"
    private val extended60 = "0040 00 0002 0020 0000000100 0000000000 00000000 00 0000 0000 00 none none"

    private fun setParameters(body: String, cseq: Int = 4): String =
        "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: $cseq\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"

    private fun parameterValues(response: String, name: String): List<String> =
        response.lineSequence().filter { it.startsWith("$name:") }
            .map { it.substringAfter(':').trim() }.toList()

    private fun assertOnly1080p30(response: String) {
        val values = parameterValues(response, "wfd_video_formats")
        assertFalse("Missing legacy video capability", values.isEmpty())
        values.forEach { value ->
            val fields = value.split(Regex("\\s+"))
            assertEquals("38", fields[0])
            assertEquals(0x80L, fields[4].toLong(16))
            assertEquals(0L, fields[5].toLong(16))
            assertEquals(0L, fields[6].toLong(16))
        }
    }

    @Test fun compatibilityLegacyM3AdvertisesOnly1080p30() {
        val socket = WireSocket(getParameters("wfd_video_formats\r\n"))
        sessionHandler(socket, { true }) { error("Legacy query must not probe custom codecs") }.handleSession()
        assertOnly1080p30(socket.replies.toString("UTF-8"))
    }

    @Test fun compatibilityExtensionsCannotAdvertiseCustom60OrLargerModes() {
        val socket = WireSocket(getParameters(allVideoParameters))
        var customQueries = 0
        sessionHandler(socket, { true }) { customQueries++; custom60 }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertOnly1080p30(response)
        for (name in listOf("wfdx_video_formats", "microsoft_custom_video_formats")) {
            assertTrue("$name must be omitted or none", parameterValues(response, name).all { it == "none" })
        }
        assertEquals("Compatibility mode must bypass dynamic custom hardware selection", 0, customQueries)
    }

    @Test fun compatibilityOffPreservesLegacyAndCustomCapabilitiesExactly() {
        val socket = WireSocket(getParameters(allVideoParameters))
        var customQueries = 0
        sessionHandler(socket, { false }) { extended ->
            assertTrue(extended)
            customQueries++
            custom60
        }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertEquals(listOf("00 00 02 10 000001C0 00000000 00000000 00 0000 0000 00 none none"),
            parameterValues(response, "wfd_video_formats"))
        assertEquals(listOf(custom60.extendedFormats()), parameterValues(response, "wfdx_video_formats"))
        assertEquals(listOf(custom60.customFormat()), parameterValues(response, "microsoft_custom_video_formats"))
        assertEquals(1, customQueries)
    }

    private fun assertCompatibilityRejects(selection: String) {
        val socket = WireSocket(getParameters(allVideoParameters) +
            setParameters("$selection\r\nwfd_presentation_URL: rtsp://localhost/wfd1.0/streamid=0 none\r\n") +
            setParameters("wfd_trigger_method: SETUP\r\n", 5) +
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: unsupported\r\n\r\n")
        var established = false
        var started = false
        val handler = sessionHandler(socket, { true }) { custom60 }
        handler.onSessionEstablished = { established = true }
        handler.onStreamStart = { started = true }
        handler.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertTrue("M4 must reject the unadvertised video mode", response.contains("RTSP/1.0 400 Bad Request\r\nCSeq: 4\r\n"))
        assertFalse("Rejected M4 must not advance to SETUP", response.lineSequence().any { it.startsWith("SETUP ") })
        assertFalse("Rejected M4 must not advance to PLAY", response.lineSequence().any { it.startsWith("PLAY ") })
        assertFalse(established)
        assertFalse(started)
    }

    @Test fun compatibilityM4RejectsMalformedProfileAndLevel() =
        assertCompatibilityRejects("wfd_video_formats: ${legacy30.replace("02 10", "ZZ ZZ")}")

    @Test fun compatibilityM4RejectsUnadvertisedProfileOrLevel() {
        for (profileAndLevel in listOf("04 10", "02 80")) {
            assertCompatibilityRejects("wfd_video_formats: ${legacy30.replace("02 10", profileAndLevel)}")
        }
    }

    @Test fun compatibilityM4RejectsMalformedOtherCodecFields() {
        for (index in listOf(0, 1, 7, 8, 9, 10, 11, 12)) {
            val fields = legacy30.split(' ').toMutableList()
            fields[index] = "ZZ"
            assertCompatibilityRejects("wfd_video_formats: ${fields.joinToString(" ")}")
        }
    }

    @Test fun compatibilityM4RejectsLegacy1080p60AndDoesNotPlay() =
        assertCompatibilityRejects("wfd_video_formats: $legacy60")

    @Test fun compatibilityM4RejectsExtended1080p60AndDoesNotPlay() =
        assertCompatibilityRejects("wfdx_video_formats: $extended60")

    @Test fun compatibilityM4RejectsCustomModeEvenIfHardwareSupportsIt() =
        assertCompatibilityRejects("microsoft_custom_video_formats: ${custom60.customFormat()}")

    @Test fun compatibilityM4RejectsCustomEvenAlongsideValidLegacy30() =
        assertCompatibilityRejects("wfd_video_formats: $legacy30\r\nmicrosoft_custom_video_formats: ${custom60.customFormat()}")

    @Test fun compatibilityM4RejectsCombined30And60Bitmask() =
        assertCompatibilityRejects("wfd_video_formats: ${legacy30.replace("00000080", "00000180")}")

    @Test fun compatibilityM4Rejects720p30() =
        assertCompatibilityRejects("wfd_video_formats: ${legacy30.replace("00000080", "00000020")}")

    @Test fun compatibilityM4RejectsAdditionalVesaMode() =
        assertCompatibilityRejects("wfd_video_formats: 00 00 02 10 00000080 00000001 00000000 00 0000 0000 00 none none")

    @Test fun compatibilityM4RejectsUnadvertisedExtensionsEvenAt30Fps() {
        assertCompatibilityRejects("wfdx_video_formats: ${extended60.replace("0000000100", "0000000080")}")
        assertCompatibilityRejects("microsoft_custom_video_formats: 0780 0438 001E")
        assertCompatibilityRejects("wfd_video_formats: $legacy30\r\nwfdx_video_formats: $extended60")
    }

    @Test fun compatibilityM4RejectsMalformedOrAdditionalHandheldModes() {
        assertCompatibilityRejects("wfd_video_formats: invalid")
        assertCompatibilityRejects("wfd_video_formats: 00 00 02 10 00000080 00000000 00000001 00 0000 0000 00 none none")
    }

    @Test fun compatibilityCannotSetupOrPlayWithoutSelectingAnAdvertisedMode() {
        val socket = WireSocket(getParameters(allVideoParameters) +
            setParameters("wfd_trigger_method: SETUP\r\n") +
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: missing-mode\r\n\r\n")
        sessionHandler(socket, { true }) { custom60 }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertTrue(response.contains("RTSP/1.0 400 Bad Request\r\nCSeq: 4\r\n"))
        assertFalse(response.lineSequence().any { it.startsWith("SETUP ") || it.startsWith("PLAY ") })
    }

    @Test fun compatibilityM4AcceptsAdvertised1080p30AndAdvancesToPlay() {
        val socket = WireSocket(getParameters(allVideoParameters) +
            setParameters("wfd_video_formats: $legacy30\r\nmicrosoft_custom_video_formats: none\r\n" +
                "wfd_presentation_URL: rtsp://localhost/wfd1.0/streamid=0 none\r\n") +
            setParameters("wfd_trigger_method: SETUP\r\n", 5) +
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: compatible\r\n\r\n")
        var established = false
        val handler = sessionHandler(socket, { true }) { custom60 }
        handler.onSessionEstablished = { established = true }
        handler.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertFalse(response.contains("400 Bad Request"))
        assertTrue(response.lineSequence().any { it == "SETUP rtsp://localhost/wfd1.0/streamid=0 RTSP/1.0" })
        assertTrue(response.lineSequence().any { it == "PLAY rtsp://localhost/wfd1.0/streamid=0 RTSP/1.0" })
        assertTrue(established)
    }

    @Test fun compatibilityOffStillAcceptsPreviouslySupported60FpsCustomMode() {
        val socket = WireSocket(getParameters(allVideoParameters) +
            setParameters("wfdx_video_formats: $extended60\r\n" +
                "microsoft_custom_video_formats: ${custom60.customFormat()}\r\nwfd_trigger_method: SETUP\r\n") +
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: original\r\n\r\n")
        sessionHandler(socket, { false }) { custom60 }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertFalse(response.contains("400 Bad Request"))
        assertTrue(response.lineSequence().any { it.startsWith("PLAY ") })
    }

    @Test fun disabledCompatibilitySettingAlsoRemainsFixedForTheSession() {
        var reads = 0
        val socket = WireSocket(getParameters(allVideoParameters) + getParameters(allVideoParameters) +
            setParameters("wfd_video_formats: $legacy60\r\nmicrosoft_custom_video_formats: ${custom60.customFormat()}\r\n" +
                "wfd_trigger_method: SETUP\r\n") +
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: unchanged\r\n\r\n")
        sessionHandler(socket, { ++reads != 1 }) { custom60 }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertEquals(1, reads)
        assertEquals(listOf(custom60.customFormat(), custom60.customFormat()),
            parameterValues(response, "microsoft_custom_video_formats"))
        assertFalse(response.contains("400 Bad Request"))
        assertTrue(response.lineSequence().any { it.startsWith("PLAY ") })
    }

    @Test fun compatibilitySettingIsReadOnceAcrossRepeatedM3AndM4() {
        var reads = 0
        val socket = WireSocket(getParameters(allVideoParameters) + getParameters(allVideoParameters) +
            setParameters("wfd_video_formats: $legacy60\r\n"))
        sessionHandler(socket, { ++reads == 1 }) { custom60 }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertEquals("Compatibility setting must be snapshotted for each session", 1, reads)
        assertEquals(2, parameterValues(response, "wfd_video_formats").size)
        assertOnly1080p30(response)
        assertTrue(response.contains("RTSP/1.0 400 Bad Request\r\nCSeq: 4\r\n"))
    }
}
