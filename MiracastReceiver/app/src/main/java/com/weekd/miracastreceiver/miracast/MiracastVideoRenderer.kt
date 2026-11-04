package com.weekd.miracastreceiver.miracast

import android.view.Surface
import com.weekd.miracastreceiver.BuildConfig
import com.weekd.miracastreceiver.airplay.VideoDecoder
import com.weekd.miracastreceiver.miracast.probe.RenderProbeController
import timber.log.Timber

/**
 * Miracast 视频送显：把 [TsDemuxer] 解出的 H.264 访问单元直接喂给 MediaCodec。
 *
 * 刻意不经过 ExoPlayer —— 播放器的缓冲和时钟同步对第二屏幕这种实时用途是负担，
 * 实测会引入 10 秒以上延迟。这里沿用 AirPlay 镜像同样的做法（见
 * [com.weekd.miracastreceiver.airplay.handshake.MirrorStreamServer]）：收到即解码、解完即送显。
 *
 * H.264 的 SPS/PPS 在 TS 码流里是带内传输的（每个 IDR 前会重复），所以这里先攒够
 * SPS+PPS 才能初始化解码器，在那之前的帧只能丢弃 —— 通常一两百毫秒内就会等到。
 *
 * @param surfaceProvider 取当前可用的 Surface；应用切后台时会返回 null
 */
/** 重建退避的起点；连续失败时逐次翻倍，直到上限。 */
private const val INITIAL_REBUILD_BACKOFF_MS = 1_000L
private const val MAX_REBUILD_BACKOFF_MS = 10_000L

class MiracastVideoRenderer(
    private val surfaceProvider: () -> Surface?,
    /** 重建解码器后向 Source 要一个 IDR，避免干等 2~3 秒的自然 GOP。 */
    private val idrRequester: () -> Unit = {},
    private val renderProbe: RenderProbeController? = null
) {

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var awaitingKeyframe = true
    private var droppedBeforeConfig = 0

    /** 自上次健康检查以来的不连续次数，用于诊断时区分「偶发抖动」和「链路持续丢包」。 */
    private var discontinuitiesSinceHealthCheck = 0

    // ─── 重建退避 ─────────────────────────────────────────────────────────
    // 硬解一旦真的坏掉（SPS/PPS 不兼容等），无退避的重建会变成每 800ms 创建销毁一次，
    // 比冻结更糟：画面上不来还持续消耗 CPU。拿到首帧输出后退避自动归零。
    private var consecutiveRebuilds = 0
    private var rebuildBackoffMs = 0L
    private var lastRebuildAt = 0L

    /**
     * 数据有丢失时调用。
     *
     * 丢的如果是 IDR 之前的参考帧，后续 P 帧确实会花屏并沿帧间预测扩散，需要等下一个 IDR。
     * 但「等到 IDR」的代价很高：实测 Windows 的 GOP 约 2~3 秒，期间画面完全静止。
     *
     * 这里只丢弃**已经开始拼装的那一帧**（TsDemuxer 的 discardPending 已经做了），
     * 不把后续所有帧一起判死 —— 让 MediaCodec 自己应对个别缺失的参考帧，
     * 它的错误隐藏比「整段冻结」体验好得多。
     * 真正的解码错误会由 VideoDecoder 自己的异常路径处理并重建解码器。
     */
    fun onDiscontinuity() {
        if (BuildConfig.DEBUG) statDiscontinuities++
        // 真丢包（TS continuity gap）意味着当前 GOP 的参考链断了，后续 P 帧解出来必然花屏。
        // 但「等下一个 IDR」代价太高：Windows 的 GOP 约 2~3 秒，期间完全静止。
        //
        // 折中：让 MediaCodec 自己错误隐藏 —— 它能撑过个别缺失的参考帧，画质略降但不冻结。
        // 真正撑不住时的表现是「输出停摆而不抛异常」，由 isStalled() 看门狗兜底重建。
        // 这样偶发丢包只花屏一瞬，积重难返时才重建（代价是一个 GOP）。
        discontinuitiesSinceHealthCheck++
    }

    // ─── 诊断计数器（仅 debug 构建，release 会被编译掉）────────────────────
    private var statUnits = 0L
    private var statDecoded = 0L
    private var statDroppedNoSurface = 0L
    private var statDroppedNoConfig = 0L
    private var statDroppedNoIdr = 0L
    private var statDiscontinuities = 0L
    private var statDecoderStalls = 0L
    private var statStallsSuppressed = 0L
    /** 累计 dequeue 到的输出 buffer（跨重建）；不是物理呈现或像素更新证据。 */
    private var statOutputFrames = 0L
    private var statLastDecoderFrames = 0L
    private var statRenderCallbacks = 0L
    private var statLastDecoderCallbacks = 0L
    private var statLastReport = System.currentTimeMillis()

    private fun reportStats() {
        val now = System.currentTimeMillis()
        if (now - statLastReport < 1000) return
        statLastReport = now

        // 两类计数都跨重建累计；回调表示 MediaCodec 报告呈现，不证明屏幕像素在变化。
        decoder?.let { d ->
            accumulateDecoderStats(d)
            val current = d.outputFrameCount
            // 维持既有退避行为：有输出 buffer 就归零，不把它当作物理呈现证明。
            if (current > 0 && consecutiveRebuilds > 0) {
                consecutiveRebuilds = 0
                rebuildBackoffMs = 0L
            }
        }
        val render = decoder?.frameRenderStats()
        Timber.i(
            "Miracast video stats: units=$statUnits decoded=$statDecoded " +
                "noSurface=$statDroppedNoSurface noConfig=$statDroppedNoConfig " +
                "noIdr=$statDroppedNoIdr disc=$statDiscontinuities stalls=$statDecoderStalls " +
                "stallSkip=$statStallsSuppressed backoff=${rebuildBackoffMs}ms " +
                "out=$statOutputFrames renderCb=$statRenderCallbacks " +
                "renderCbSupported=${render?.supported ?: false} renderCbAgeMs=${render?.lastCallbackAgeMs ?: -1L} " +
                "cbPtsUs=${render?.callbackPresentationTimeUs ?: -1L} cbRenderNs=${render?.callbackRenderTimeNs ?: -1L} " +
                "cbSubmitNs=${render?.callbackSubmitTimeNs ?: -1L} " +
                "submitPtsUs=${render?.lastSubmitPresentationTimeUs ?: -1L} submitNs=${render?.lastSubmitTimeNs ?: -1L} " +
                "hasSps=${sps != null} hasPps=${pps != null} " +
                "decoder=${decoder != null} awaitingKeyframe=$awaitingKeyframe"
        )
    }

    private fun accumulateDecoderStats(d: VideoDecoder) {
        val output = d.outputFrameCount
        statOutputFrames += (output - statLastDecoderFrames).coerceAtLeast(0L)
        statLastDecoderFrames = output
        val callbacks = d.frameRenderStats().callbackCount
        statRenderCallbacks += (callbacks - statLastDecoderCallbacks).coerceAtLeast(0L)
        statLastDecoderCallbacks = callbacks
    }

    /** [TsDemuxer] 的回调入口。 */
    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        if (BuildConfig.DEBUG) { statUnits++; reportStats() }
        captureParameterSets(unit)

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            if (BuildConfig.DEBUG) statDroppedNoSurface++
            releaseDecoder()
            return
        }

        // Surface 变了（手动旋转切换渲染视图）先尝试原地换输出，不用等关键帧
        val current = decoder
        if (current != null && surface !== configuredSurface && current.switchOutputSurface(surface)) {
            Timber.i("Miracast: surface changed — switched decoder output in place")
            configuredSurface = surface
        }

        // 解码器卡死检测：这台电视的硬解在码流不连续后会内部死锁 —— 输出端不再吐帧，
        // 输入端缓冲区全占满，每帧都丢。它不抛异常，isHealthy 一直是 true，所以只能靠
        // 「多久没出帧」发现。重建后从下一个 IDR 恢复（GOP 约 2~3 秒）。
        val stalled = current?.isStalled() == true
        if (stalled) {
            if (BuildConfig.DEBUG) statDecoderStalls++
            val sinceLast = System.currentTimeMillis() - lastRebuildAt
            if (sinceLast < rebuildBackoffMs) {
                // 退避期内：判定卡死但不重建，避免反复创建/销毁这颗不稳定的硬解。
                // 硬解实例创建本身要几百毫秒且可能失败，抖动式重建只会让画面更糟。
                if (BuildConfig.DEBUG) statStallsSuppressed++
            } else {
                Timber.w(
                    "Miracast: decoder stalled (${current.inputsSinceLastOutput} inputs with no output, " +
                        "disc=$discontinuitiesSinceHealthCheck) — recreating (backoff ${rebuildBackoffMs}ms)"
                )
                releaseDecoder()
                // 重建成功才升级退避；拿到首帧输出后由 onAccessUnit 里的首帧路径清零。
                consecutiveRebuilds++
                rebuildBackoffMs = minOf(
                    INITIAL_REBUILD_BACKOFF_MS shl (consecutiveRebuilds - 1),
                    MAX_REBUILD_BACKOFF_MS
                )
                lastRebuildAt = System.currentTimeMillis()
            }
        }
        discontinuitiesSinceHealthCheck = 0

        // Surface 变了（应用切后台再回前台会换新的）就重建解码器，否则画面会一直黑
        if (decoder == null || surface !== configuredSurface) {
            if (!rebuildDecoder(surface)) {
                if (BuildConfig.DEBUG) statDroppedNoConfig++
                droppedBeforeConfig++
                if (droppedBeforeConfig % 60 == 0) {
                    Timber.d("Miracast: waiting for SPS/PPS, dropped $droppedBeforeConfig units")
                }
                return
            }
        }

        // 新解码器必须从 IDR 开始，否则解出来是花的
        if (awaitingKeyframe) {
            if (!containsIdr(unit)) {
                if (BuildConfig.DEBUG) statDroppedNoIdr++
                return
            }
            awaitingKeyframe = false
            Timber.i("Miracast: keyframe found, decoding started")
        }

        if (BuildConfig.DEBUG) statDecoded++
        decoder?.decodeNalUnit(unit, ptsUs)
    }

    private fun rebuildDecoder(surface: Surface): Boolean {
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
        val next = VideoDecoder(surface, renderProbe?.codecName, renderProbe?.newQueueObserver())
        try {
            next.initialize(spsBytes, ppsBytes, width, height)
        } catch (e: Exception) {
            next.release()
            throw e
        }
        decoder = next
        configuredSurface = surface
        statLastDecoderFrames = 0L
        statLastDecoderCallbacks = 0L
        // 注意：不要在这里清零 consecutiveRebuilds —— 退避只有在取到输出 buffer 后才撤销，
        // 否则每次重建都会把计数归零，退避形同虚设。
        awaitingKeyframe = true
        Timber.i("Miracast decoder initialized: ${width}x$height")
        // 干等自然 GOP 要 2~3 秒，主动要一个 IDR 通常几百毫秒就到。
        // 放在重建之后：此时 awaitingKeyframe 已置位，收到的第一帧若是 IDR 立刻开始解码。
        idrRequester()
        return true
    }

    /** 从访问单元里提取 SPS(type 7) / PPS(type 8)，带起始码保存以便直接作为 csd 使用。 */
    private fun captureParameterSets(unit: ByteArray) {
        if (sps != null && pps != null) return
        forEachNal(unit) { start, end ->
            val nalType = unit[start].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                val withStartCode = ByteArray(4 + (end - start))
                withStartCode[3] = 1
                System.arraycopy(unit, start, withStartCode, 4, end - start)
                if (nalType == 7 && sps == null) {
                    sps = withStartCode
                    Timber.i("Miracast: SPS captured (${end - start} bytes)")
                } else if (nalType == 8 && pps == null) {
                    pps = withStartCode
                    Timber.i("Miracast: PPS captured (${end - start} bytes)")
                }
            }
        }
    }

    private fun containsIdr(unit: ByteArray): Boolean {
        var found = false
        forEachNal(unit) { start, _ ->
            if ((unit[start].toInt() and 0x1F) == 5) found = true
        }
        return found
    }

    /** 遍历 Annex B 码流里的每个 NAL（回调收到的是去掉起始码后的区间）。 */
    private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 3 <= data.size) {
            val isStartCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            val isStartCode4 = i + 4 <= data.size && data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (isStartCode3 || isStartCode4) {
                if (nalStart >= 0) action(nalStart, i)
                i += if (isStartCode4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) action(nalStart, data.size)
    }

    private fun releaseDecoder() {
        decoder?.let { d ->
            d.release()
            if (BuildConfig.DEBUG) accumulateDecoderStats(d)
        }
        decoder = null
        configuredSurface = null
        statLastDecoderFrames = 0L
        statLastDecoderCallbacks = 0L
    }

    fun release() {
        releaseDecoder()
        sps = null
        pps = null
        awaitingKeyframe = true
    }
}
