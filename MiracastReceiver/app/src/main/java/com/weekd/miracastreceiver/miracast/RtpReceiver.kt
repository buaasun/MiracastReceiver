package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.view.Surface
import com.weekd.miracastreceiver.BuildConfig
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Miracast RTP 接收器。
 *
 * 实测确认（对 Windows 11 的 MSMiracastSource）：Miracast 的 RTP 负载类型是 33 = MP2T，
 * 也就是 **H.264 封装在 MPEG-2 传输流里**，而不是裸 H.264 分片。每个 RTP 包 1328 字节 =
 * 12 字节 RTP 头 + 7 × 188 字节 TS 包。
 *
 * 所以这里剥掉 RTP 头后交给 [TsDemuxer] 解复用，解出的 H.264 访问单元由
 * [MiracastVideoRenderer] 直接送进 MediaCodec，音频 PES 交给 [MiracastAudioPlayer] 播放。
 *
 * 刻意不经过 ExoPlayer：播放器的缓冲策略对第二屏幕这种实时用途会引入 10 秒以上延迟，
 * 而这条路径「收到即解码、解完即送显」，延迟只剩编码 + 传输 + 解码的固有开销。
 *
 * @param surfaceProvider 送显目标，应用切后台时返回 null
 */
class RtpReceiver(
    private val context: Context,
    private val port: Int,
    private val surfaceProvider: () -> Surface?
) {

    private var socket: DatagramSocket? = null
    /** RTCP 接收端口（port+1）。内容不解析，只是为了满足 Source 的探测。 */
    private var rtcpSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    private val renderer = MiracastVideoRenderer(surfaceProvider)
    private val audioPlayer = MiracastAudioPlayer()
    private val demuxer = TsDemuxer(
        onAccessUnit = renderer::onAccessUnit,
        onDiscontinuity = renderer::onDiscontinuity,
        // 和视频保持一致：播放页不在前台（没有 Surface）时不出声
        onAudioPes = { codec, data -> if (surfaceProvider() != null) audioPlayer.onAudioPes(codec, data) },
        onAudioDiscontinuity = audioPlayer::onDiscontinuity
    )

    /**
     * 接收线程与解码线程之间的缓冲。
     *
     * 不能在接收线程上直接解码：MediaCodec 一旦卡顿就会反压到 UDP 接收，内核缓冲区溢出
     * 丢包，而丢包必然花屏。这里让接收线程只做「收包 + 入队」，保证它永远跑得飞快。
     *
     * 深度取 64 而不是 256：本机实测 229 包/秒，64 包 ≈ 280ms，够吸收解码器的瞬时抖动，
     * 又不会把延迟推到秒级。屏幕镜像对延迟敏感，宁可丢帧也不要排队 —— 256 时一次解码
     * 卡顿就能堆出 1.2 秒延迟，这会让「画面看着卡」直接变成「画面延迟一秒」。
     */
    private val payloadQueue = ArrayBlockingQueue<ByteArray>(64)

    /** 队列溢出丢掉的包数（仅 debug）：用来区分「网络丢包」和「自己丢包」。 */
    private var overflowDrops = 0L

    @Volatile
    var packetsLost = 0L
        private set

    // 统计量，供视频信息面板做差分（累加即可，UI 侧换算速率）
    @Volatile
    var packetsReceived = 0L
        private set

    @Volatile
    var bytesReceived = 0L
        private set

    var onError: ((String) -> Unit)? = null

    companion object {
        /** 当前会话的接收器，供视频信息面板读取统计量。 */
        @Volatile
        var active: RtpReceiver? = null
    }

    fun start() {
        if (isRunning) {
            Timber.w("RTP Receiver already running")
            return
        }

        active = this

        scope.launch { runDecoder() }

        scope.launch {
            try {
                socket = DatagramSocket(port).apply {
                    // 默认接收缓冲只有几十 KB，2.5Mbps 的流稍有调度延迟就会溢出丢包
                    runCatching { receiveBufferSize = 1024 * 1024 }
                }
                // 必须先置位再起 RTCP drain 协程：drain 循环拿它当退出条件，
                // 晚一步的话协程可能读到 false 直接退出
                isRunning = true

                // SETUP 声明了 client_port=<rtp>-<rtcp> 时，Source 会真的往 RTCP 端口发包
                // （实测 Windows 11 每 ~2.5s 一个 RTCP SR，尾部还带 MF_SEND_RATE 私有扩展）。
                // 端口必须绑定**并且被消费**：只绑不读，内核 rx_queue 会一直涨到满，之后
                // 开始丢包。这里只排空不解析。
                if (MiracastTuning.isRtcpPortEnabled(context)) {
                    rtcpSocket = runCatching { DatagramSocket(port + 1) }.getOrNull()
                    if (rtcpSocket == null) {
                        Timber.w("RTCP socket on UDP ${port + 1} unavailable (port busy?)")
                    } else {
                        startRtcpDrain(rtcpSocket!!)
                    }
                }
                Timber.i("RTP Receiver listening on UDP $port (expecting MPEG-2 TS, PT=33), " +
                    "rtcp=${if (rtcpSocket != null) port + 1 else "none"}, " +
                    "recvBuf=${socket?.receiveBufferSize}")

                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)
                var loggedFirst = false
                var expectedSeq = -1

                while (isRunning) {
                    try {
                        packet.length = buffer.size    // receive() 会缩短 length，每次要复位
                        socket?.receive(packet)
                        if (packet.length <= 0) continue

                        packetsReceived++
                        bytesReceived += packet.length

                        val payloadOffset = rtpHeaderLength(packet.data, packet.length)
                        if (payloadOffset <= 0 || payloadOffset >= packet.length) continue

                        if (!loggedFirst) {
                            loggedFirst = true
                            val pt = packet.data[1].toInt() and 0x7F
                            Timber.i("First RTP packet: ${packet.length}B payloadType=$pt " +
                                "payload=${packet.length - payloadOffset}B")
                        }

                        // RTP 序号跳变 = 网络丢包，残缺的帧不能喂给解码器
                        val seq = ((packet.data[2].toInt() and 0xFF) shl 8) or
                            (packet.data[3].toInt() and 0xFF)
                        if (expectedSeq >= 0 && seq != expectedSeq) {
                            val lost = (seq - expectedSeq + 0x10000) and 0xFFFF
                            packetsLost += lost
                            Timber.w("RTP: lost $lost packets (seq $expectedSeq → $seq)")
                            renderer.onDiscontinuity()
                            audioPlayer.onDiscontinuity()
                        }
                        expectedSeq = (seq + 1) and 0xFFFF

                        // 接收线程只入队，解复用和解码在另一条线程上做
                        val payload = packet.data.copyOfRange(payloadOffset, packet.length)
                        if (!payloadQueue.offer(payload)) {
                            payloadQueue.poll()
                            payloadQueue.offer(payload)
                            // 队列溢出只说明「消费端暂时慢」，不代表码流坏了 —— TS 层的
                            // continuity counter 会精确指出真正丢掉的那个包并触发
                            // onDiscontinuity()。这里再调一次只会让 renderer 提前进入
                            // 等关键帧状态，把后面本来能正常解的帧也一并扔掉，
                            // 实测这会吃掉 27%~31% 的帧。
                            if (BuildConfig.DEBUG) overflowDrops++
                        }

                        if (packetsReceived % 1000L == 0L) {
                            Timber.i("RTP stats: $packetsReceived packets, ${bytesReceived / 1024}KB, " +
                                "lost=$packetsLost, queue=${payloadQueue.size}" +
                                if (BuildConfig.DEBUG) ", overflow=$overflowDrops" else "")
                        }
                    } catch (e: Exception) {
                        if (isRunning) Timber.e(e, "Error receiving RTP packet")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start RTP Receiver")
                onError?.invoke("RTP 接收启动失败: ${e.message}")
                isRunning = false
            }
        }
    }

    /**
     * 排空 RTCP 端口。
     *
     * 只消费不解析：这些包的用途是让 Source 认为 sink 的 RTCP 通道活着，
     * 内容（Sender Report + 厂商私有扩展）对画面没有影响。不排空的话
     * 内核 rx_queue 会持续堆积到上限后开始丢包。
     */
    private fun startRtcpDrain(sock: DatagramSocket) {
        scope.launch {
            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            var count = 0L
            var lastLog = System.currentTimeMillis()
            while (isRunning) {
                try {
                    pkt.length = buf.size
                    sock.receive(pkt)
                    count++
                    val now = System.currentTimeMillis()
                    if (BuildConfig.DEBUG && now - lastLog >= 5000) {
                        lastLog = now
                        Timber.d("RTCP drained $count packets (last ${pkt.length}B)")
                    }
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "RTCP drain error")
                    break
                }
            }
            Timber.i("RTCP drain stopped after $count packets")
        }
    }

    /** 解码线程：从队列取 TS 负载做解复用，解出的访问单元由 renderer 送进 MediaCodec。 */
    private suspend fun runDecoder() {
        while (scope.isActive) {
            val payload = withContext(Dispatchers.IO) {
                payloadQueue.poll(200, TimeUnit.MILLISECONDS)
            } ?: continue
            try {
                demuxer.feed(payload, 0, payload.size)
            } catch (e: Exception) {
                Timber.e(e, "Error demuxing TS payload")
                renderer.onDiscontinuity()
            }
        }
    }

    /**
     * 计算 RTP 头长度：固定 12 字节 + CSRC 列表 + 可选扩展头。
     * @return 负载起始偏移；包不合法时返回 -1
     */
    private fun rtpHeaderLength(data: ByteArray, length: Int): Int {
        if (length < 12) return -1
        val version = (data[0].toInt() shr 6) and 0x03
        if (version != 2) return -1

        val csrcCount = data[0].toInt() and 0x0F
        val hasExtension = ((data[0].toInt() shr 4) and 0x01) == 1
        var headerLength = 12 + csrcCount * 4

        if (hasExtension) {
            if (length < headerLength + 4) return -1
            val extWords = ((data[headerLength + 2].toInt() and 0xFF) shl 8) or
                (data[headerLength + 3].toInt() and 0xFF)
            headerLength += 4 + extWords * 4
        }
        return if (headerLength < length) headerLength else -1
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        renderer.release()
        audioPlayer.release()
        demuxer.reset()
        if (active === this) active = null

        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Timber.e(e, "Error closing RTP socket")
        }
        try {
            rtcpSocket?.close()
            rtcpSocket = null
        } catch (e: Exception) {
            Timber.e(e, "Error closing RTCP socket")
        }
        Timber.i("RTP Receiver stopped: $packetsReceived packets, ${bytesReceived / 1024}KB")
    }

    fun isRunning(): Boolean = isRunning
}
