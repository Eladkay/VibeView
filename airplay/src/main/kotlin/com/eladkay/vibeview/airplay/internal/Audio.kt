package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.AirPlayListener
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * RTP receiver for the AirPlay audio stream (UDP). Packets are 12-byte RTP headers
 * followed by the encrypted audio frame. Reorders out-of-order packets with a small
 * ring buffer before decrypting and forwarding, tolerating 16-bit sequence wraparound.
 */
internal class RtpAudioHandler(
    private val session: Session,
    private val listener: AirPlayListener,
) : SimpleChannelInboundHandler<DatagramPacket>() {

    private class Slot {
        var seq = -1
        var length = 0
        val data = ByteArray(MAX_FRAME)
    }

    private val ring = Array(RING_SIZE) { Slot() }
    private var expectedSeq = -1

    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
        val content = msg.content()
        if (content.readableBytes() < RTP_HEADER_SIZE) return

        val header = ByteArray(RTP_HEADER_SIZE)
        content.readBytes(header)
        val payloadType = header[1].toInt() and 0x7F
        if (payloadType != PAYLOAD_TYPE_AUDIO) {
            log.debug("Ignoring RTP payload type {}", payloadType)
            return
        }

        val seq = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val length = content.readableBytes()
        if (length > MAX_FRAME) {
            log.warn("Oversized audio frame ({} bytes), dropping", length)
            return
        }

        if (expectedSeq == -1) {
            expectedSeq = seq
        } else if (!isAhead(seq, expectedSeq)) {
            return // duplicate or too old
        }

        val slot = ring[seq % RING_SIZE]
        slot.seq = seq
        slot.length = length
        content.readBytes(slot.data, 0, length)

        drainInOrder()
    }

    private fun drainInOrder() {
        var stalled = 0
        while (true) {
            val slot = ring[expectedSeq % RING_SIZE]
            if (slot.seq != expectedSeq) {
                // Allow a small gap to fill in; skip ahead if the stream has moved on.
                if (stalled++ > 0 || !anyNewerBuffered()) return
                expectedSeq = (expectedSeq + 1) and 0xFFFF
                continue
            }
            emit(slot)
            slot.seq = -1
            expectedSeq = (expectedSeq + 1) and 0xFFFF
        }
    }

    private fun anyNewerBuffered(): Boolean =
        ring.any { it.seq != -1 && isAhead(it.seq, expectedSeq) }

    private fun emit(slot: Slot) {
        try {
            session.airPlay.decryptAudio(slot.data, slot.length)
        } catch (e: Exception) {
            log.warn("Audio decrypt failed: {}", e.toString())
            return
        }
        listener.onAudioData(slot.data.copyOfRange(0, slot.length))
    }

    /** True when [seq] is at or ahead of [reference] in 16-bit sequence space. */
    private fun isAhead(seq: Int, reference: Int): Boolean =
        ((seq - reference) and 0xFFFF) < 0x8000

    companion object {
        private val log = LoggerFactory.getLogger(RtpAudioHandler::class.java)
        private const val RTP_HEADER_SIZE = 12
        private const val PAYLOAD_TYPE_AUDIO = 96
        private const val RING_SIZE = 512
        private const val MAX_FRAME = 8 * 1024
    }
}

/** Logs audio control (sync/retransmit) packets; nothing needs answering for playback to work. */
internal class AudioControlHandler : SimpleChannelInboundHandler<DatagramPacket>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
        if (msg.content().readableBytes() >= 2) {
            val type = msg.content().getByte(msg.content().readerIndex() + 1).toInt() and 0x7F
            log.debug("Audio control packet type {}", type)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AudioControlHandler::class.java)
    }
}

internal object AudioReceivers {

    private val log = LoggerFactory.getLogger(AudioReceivers::class.java)

    /** Binds a UDP data socket on an ephemeral port; returns the bound channel. */
    fun startData(group: EventLoopGroup, session: Session, listener: AirPlayListener): Channel {
        val channel = bindUdp(group) { RtpAudioHandler(session, listener) }
        log.info("Audio receiver listening on port {}", channel.localPort())
        return channel
    }

    fun startControl(group: EventLoopGroup): Channel {
        val channel = bindUdp(group) { AudioControlHandler() }
        log.info("Audio control listening on port {}", channel.localPort())
        return channel
    }

    private fun bindUdp(group: EventLoopGroup, handler: () -> SimpleChannelInboundHandler<DatagramPacket>): Channel {
        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .localAddress(InetSocketAddress(0))
            .handler(object : ChannelInitializer<DatagramChannel>() {
                override fun initChannel(ch: DatagramChannel) {
                    ch.pipeline().addLast(handler())
                }
            })
        return bootstrap.bind().sync().channel()
    }

    fun Channel.localPort(): Int = (localAddress() as InetSocketAddress).port
}
