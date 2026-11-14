package com.weekd.miracastreceiver.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicReference

class MirrorStreamServerTest {
    private val aesKey = ByteArray(16) { it.toByte() }
    private val sharedSecret = ByteArray(32) { (it + 16).toByte() }
    private val connectionId = 1234L
    private val avccFrame = byteArrayOf(0, 0, 0, 3, 0x65, 0x12, 0x34)
    private val annexBFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0x34)

    @Test fun emptyControlPacketDoesNotStopTheNextVideoFrame() {
        val encrypted = MirrorCrypto.streamCipher(aesKey, sharedSecret, connectionId).update(avccFrame)
        val frames = receive(packet(2) + packet(0, encrypted))

        assertEquals(1, frames.size)
        assertArrayEquals(annexBFrame, frames.single())
    }

    @Test fun controlPacketsDoNotAdvanceTheVideoCipher() {
        val cipher = MirrorCrypto.streamCipher(aesKey, sharedSecret, connectionId)
        val first = cipher.update(avccFrame)
        val second = cipher.update(avccFrame)
        val frames = receive(
            packet(0, first) + packet(2) + packet(2, byteArrayOf(1, 2, 3)) + packet(0, second)
        )

        assertEquals(2, frames.size)
        frames.forEach { assertArrayEquals(annexBFrame, it) }
    }

    @Test fun controlTypeUsesOnlyTheLowByteOfTheHeader() {
        val encrypted = MirrorCrypto.streamCipher(aesKey, sharedSecret, connectionId).update(avccFrame)
        val frames = receive(packet(0x1002) + packet(0, encrypted))

        assertEquals(1, frames.size)
        assertArrayEquals(annexBFrame, frames.single())
    }

    @Test fun emptyVideoOrConfigStillStopsTheStream() {
        for (type in listOf(0, 1)) {
            val encrypted = MirrorCrypto.streamCipher(aesKey, sharedSecret, connectionId).update(avccFrame)
            assertEquals(emptyList<ByteArray>(), receive(packet(type) + packet(0, encrypted)))
        }
    }

    @Test fun invalidPayloadLengthsStillStopTheStream() {
        for (length in listOf(-1, 8 * 1024 * 1024 + 1)) {
            val encrypted = MirrorCrypto.streamCipher(aesKey, sharedSecret, connectionId).update(avccFrame)
            assertEquals(emptyList<ByteArray>(), receive(header(2, length) + packet(0, encrypted)))
        }
    }

    @Test fun truncatedControlPayloadDoesNotReadAPartialVideoFrame() {
        assertEquals(emptyList<ByteArray>(), receive(header(2, 4) + byteArrayOf(1, 2)))
    }

    private fun packet(type: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        header(type, payload.size) + payload

    private fun header(type: Int, length: Int): ByteArray =
        ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(length).putShort(type.toShort()).array()

    // 使用真实 TCP 读取与加密数据，只绕过 Android 解码线程，以检查协议边界和帧内容。
    private fun receive(bytes: ByteArray): List<ByteArray> {
        val server = MirrorStreamServer(aesKey, sharedSecret, connectionId, { null })
        val running = field("running").apply { setBoolean(server, true) }
        val serverSocket = field("serverSocket").get(server) as ServerSocket
        val reader = MirrorStreamServer::class.java.getDeclaredMethod("runReader").apply {
            isAccessible = true
        }
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                reader.invoke(server)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, "MirrorReaderTest").apply {
            isDaemon = true
            start()
        }
        try {
            Socket("127.0.0.1", serverSocket.localPort).use { socket ->
                socket.getOutputStream().write(bytes)
                socket.shutdownOutput()
                thread.join(2000)
                assertFalse("镜像读取线程未在输入结束后退出", thread.isAlive)
                assertNull(failure.get())
                val queue = field("queue").get(server) as BlockingQueue<*>
                return queue.map { item ->
                    item!!.javaClass.getDeclaredField("annexB").apply {
                        isAccessible = true
                    }.get(item) as ByteArray
                }
            }
        } finally {
            running.setBoolean(server, false)
            (field("client").get(server) as Socket?)?.close()
            serverSocket.close()
            thread.join(2000)
        }
    }

    private fun field(name: String) = MirrorStreamServer::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
