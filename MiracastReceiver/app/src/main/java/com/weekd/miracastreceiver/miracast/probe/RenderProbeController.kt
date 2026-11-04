package com.weekd.miracastreceiver.miracast.probe

import android.content.Context
import com.weekd.miracastreceiver.BuildConfig
import com.weekd.miracastreceiver.airplay.VideoDecoder
import com.weekd.miracastreceiver.util.Logger
import java.io.File

/** Miracast-only adapter. Production builds do not read a probe file or select a test codec. */
class RenderProbeController private constructor(
    private val filesDir: File,
    private val config: RenderProbeConfig
) {
    val codecName: String? get() = config.decoderName()

    fun newQueueObserver(): VideoDecoder.QueuedInputObserver? =
        if (config.captureSeconds == 0) null else CaptureObserver(filesDir, config)

    companion object {
        fun load(context: Context): RenderProbeController? {
            if (!BuildConfig.RENDER_CLOCK_PROBE) return null
            return try {
                val config = RenderProbeConfig.load(File(context.filesDir, "render-probe.properties"))
                Logger.i("Render probe settings: codec=${config.codec} captureSeconds=${config.captureSeconds} " +
                    "maxTotalBytes=${config.captureMaxBytes} label=${config.label}")
                RenderProbeController(context.filesDir, config)
            } catch (e: Exception) {
                Logger.e("Invalid render-probe.properties; probe disabled", e)
                null
            }
        }
    }

    private class CaptureObserver(
        private val filesDir: File,
        private val config: RenderProbeConfig
    ) : VideoDecoder.QueuedInputObserver {
        @Volatile private var capture: QueuedInputCapture? = null
        private var closed = false

        @Synchronized
        override fun onCodecStarted(actualCodecName: String, csd0: ByteArray, csd1: ByteArray) {
            if (closed) return
            capture = QueuedInputCapture(File(filesDir, "render-probe"), config.label, actualCodecName,
                config.captureSeconds * 1000L, config.captureMaxBytes, 16, csd0, csd1)
            Logger.i("Queued-input capture started: ${capture!!.directory().absolutePath}; " +
                "payload=successful queue bytes only, CSD stored separately")
        }

        override fun onQueuedInput(bytes: ByteArray, presentationTimeUs: Long, queuedMonoNs: Long) {
            capture?.record(bytes, presentationTimeUs, queuedMonoNs)
        }

        override fun close() {
            val current = synchronized(this) {
                if (closed) return
                closed = true
                capture
            }
            current?.close()
        }
    }
}
