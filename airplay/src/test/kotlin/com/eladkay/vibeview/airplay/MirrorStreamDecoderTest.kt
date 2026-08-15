package com.eladkay.vibeview.airplay

import com.eladkay.vibeview.airplay.internal.MirrorPacket
import com.eladkay.vibeview.airplay.internal.MirrorStreamDecoder
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MirrorStreamDecoderTest {

    private fun frame(payloadType: Int, payload: ByteArray, dims: FloatArray? = null): ByteArray {
        val buf = ByteBuffer.allocate(MirrorPacket.HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(payload.size)
        buf.putShort(payloadType.toShort())
        buf.putShort(0)
        if (dims != null) {
            buf.position(40)
            buf.putFloat(dims[0])
            buf.putFloat(dims[1])
            buf.position(56)
            buf.putFloat(dims[2])
            buf.putFloat(dims[3])
        }
        buf.position(MirrorPacket.HEADER_SIZE)
        buf.put(payload)
        return buf.array()
    }

    @Test
    fun `decodes complete frame`() {
        val channel = EmbeddedChannel(MirrorStreamDecoder())
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        channel.writeInbound(Unpooled.wrappedBuffer(frame(0, payload)))
        val packet = channel.readInbound<MirrorPacket>()
        assertEquals(0, packet.payloadType)
        assertArrayEquals(payload, packet.payload)
        assertNull(channel.readInbound())
    }

    @Test
    fun `reassembles frame split across tcp segments`() {
        val channel = EmbeddedChannel(MirrorStreamDecoder())
        val payload = ByteArray(300) { it.toByte() }
        val bytes = frame(0, payload)
        channel.writeInbound(Unpooled.wrappedBuffer(bytes.copyOfRange(0, 50)))
        assertNull(channel.readInbound())
        channel.writeInbound(Unpooled.wrappedBuffer(bytes.copyOfRange(50, 200)))
        assertNull(channel.readInbound())
        channel.writeInbound(Unpooled.wrappedBuffer(bytes.copyOfRange(200, bytes.size)))
        val packet = channel.readInbound<MirrorPacket>()
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun `decodes back to back frames and codec data dimensions`() {
        val channel = EmbeddedChannel(MirrorStreamDecoder())
        val video = frame(0, byteArrayOf(9, 9))
        val codec = frame(1, byteArrayOf(1), floatArrayOf(1920f, 1080f, 1280f, 720f))
        val combined = video + codec
        channel.writeInbound(Unpooled.wrappedBuffer(combined))

        val first = channel.readInbound<MirrorPacket>()
        assertEquals(0, first.payloadType)

        val second = channel.readInbound<MirrorPacket>()
        assertEquals(1, second.payloadType)
        assertEquals(1920, second.widthSource)
        assertEquals(1080, second.heightSource)
        assertEquals(1280, second.width)
        assertEquals(720, second.height)
    }
}
