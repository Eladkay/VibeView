package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.AirPlayListener
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * One frame of the mirror stream: a 128-byte header followed by `payloadSize` bytes.
 *
 * Header layout (little-endian):
 *  - bytes 0..3   payload size
 *  - bytes 4..5   payload type (masked with 0xFF: 0 = encrypted H.264, 1 = avcC codec data, 2 = heartbeat)
 *  - bytes 6..7   payload option
 *  - type 1 only: floats at 40/44 = source width/height, 56/60 = display width/height
 */
internal class MirrorPacket(val header: ByteArray, val payload: ByteArray) {
    val payloadType: Int get() = (header[4].toInt() and 0xFF)

    private fun floatAt(offset: Int): Float {
        var bits = 0
        for (i in 3 downTo 0) {
            bits = (bits shl 8) or (header[offset + i].toInt() and 0xFF)
        }
        return Float.fromBits(bits)
    }

    val widthSource: Int get() = floatAt(40).toInt()
    val heightSource: Int get() = floatAt(44).toInt()
    val width: Int get() = floatAt(56).toInt()
    val height: Int get() = floatAt(60).toInt()

    companion object {
        const val HEADER_SIZE = 128
        const val TYPE_VIDEO = 0
        const val TYPE_CODEC_DATA = 1
        const val TYPE_HEARTBEAT = 2
    }
}

/** Reassembles the raw TCP mirror stream into [MirrorPacket]s. */
internal class MirrorStreamDecoder : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        while (input.readableBytes() >= MirrorPacket.HEADER_SIZE) {
            val payloadSize = input.getUnsignedIntLE(input.readerIndex()).toInt()
            if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
                ctx.close()
                return
            }
            if (input.readableBytes() < MirrorPacket.HEADER_SIZE + payloadSize) return
            val header = ByteArray(MirrorPacket.HEADER_SIZE)
            input.readBytes(header)
            val payload = ByteArray(payloadSize)
            input.readBytes(payload)
            out.add(MirrorPacket(header, payload))
        }
    }

    companion object {
        // A mirror frame is at most one video frame; 4 MB is far above anything legitimate.
        const val MAX_PAYLOAD = 4 * 1024 * 1024
    }
}

/** Decrypts mirror packets and forwards Annex-B video to the listener. */
internal class MirrorPacketHandler(
    private val session: Session,
    private val listener: AirPlayListener,
) : SimpleChannelInboundHandler<MirrorPacket>() {

    private var packetsSeen = 0L
    private var videoFramesForwarded = 0L
    private var reportedVideo = false
    private var reportedConfig = false
    private var reportedDecryptFailure = false
    private val unknownTypes = HashSet<Int>()

    override fun channelActive(ctx: ChannelHandlerContext) {
        listener.onProtocolEvent("── mirror stream connected")
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        listener.onProtocolEvent("── mirror stream closed after $packetsSeen packets")
        super.channelInactive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, packet: MirrorPacket) {
        packetsSeen++
        when (packet.payloadType) {
            MirrorPacket.TYPE_VIDEO -> {
                try {
                    session.airPlay.decryptVideo(packet.payload)
                } catch (e: Exception) {
                    log.warn("Video decrypt failed: {}", e.toString())
                    if (!reportedDecryptFailure) {
                        reportedDecryptFailure = true
                        listener.onProtocolEvent("  !! video decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    return
                }
                if (VideoPackaging.avccToAnnexBInPlace(packet.payload)) {
                    videoFramesForwarded++
                    if (!reportedVideo) {
                        reportedVideo = true
                        listener.onProtocolEvent("   first video frame (${packet.payload.size}B) decoded path OK")
                    } else if (videoFramesForwarded % FRAME_REPORT_INTERVAL == 0L) {
                        listener.onProtocolEvent("   $videoFramesForwarded video frames forwarded to decoder")
                    }
                    listener.onVideoData(packet.payload)
                } else {
                    log.warn("Dropping malformed video payload ({} bytes)", packet.payload.size)
                    if (!reportedDecryptFailure) {
                        reportedDecryptFailure = true
                        listener.onProtocolEvent("  !! video payload not AVCC after decrypt (${packet.payload.size}B)")
                    }
                }
            }
            MirrorPacket.TYPE_CODEC_DATA -> {
                listener.onVideoFormat(packet.widthSource, packet.heightSource, packet.width, packet.height)
                val parameterSets = VideoPackaging.avccConfigToAnnexB(packet.payload)
                if (parameterSets != null) {
                    if (!reportedConfig) {
                        reportedConfig = true
                        listener.onProtocolEvent(
                            "   codec data OK (SPS/PPS ${parameterSets.size}B, ${packet.widthSource}x${packet.heightSource})"
                        )
                    }
                    listener.onVideoData(parameterSets)
                } else {
                    log.warn("Malformed avcC codec data ({} bytes)", packet.payload.size)
                    listener.onProtocolEvent("  !! malformed codec data (${packet.payload.size}B)")
                }
            }
            MirrorPacket.TYPE_HEARTBEAT -> { /* keep-alive, nothing to do */ }
            else -> {
                log.debug("Ignoring mirror payload type {}", packet.payloadType)
                if (unknownTypes.add(packet.payloadType)) {
                    listener.onProtocolEvent("  !! ignoring unknown mirror payload type ${packet.payloadType}")
                }
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("Mirror stream error", cause)
        ctx.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MirrorPacketHandler::class.java)
        private const val FRAME_REPORT_INTERVAL = 300L
    }
}

/** TCP server for the mirror data stream; bound on demand when a video SETUP arrives. */
internal object MirroringReceiver {

    private val log = LoggerFactory.getLogger(MirroringReceiver::class.java)

    fun start(
        group: EventLoopGroup,
        port: Int,
        session: Session,
        listener: AirPlayListener,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel::class.java)
            .localAddress(InetSocketAddress(port))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(MirrorStreamDecoder(), MirrorPacketHandler(session, listener))
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true)
            // The mirror stream is bursty (whole frames arrive at once); a large
            // receive buffer keeps TCP from stalling the sender and adding latency.
            .childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
            .option(ChannelOption.SO_REUSEADDR, true)
        val channel = bootstrap.bind().sync().channel()
        log.info("Mirror data receiver listening on port {}", port)
        listener.onProtocolEvent("── mirror receiver listening on :$port")
        return channel
    }
}
