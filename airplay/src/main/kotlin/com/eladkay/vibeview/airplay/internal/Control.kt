package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.AirPlayAudioFormat
import com.eladkay.vibeview.airplay.AirPlayConfig
import com.eladkay.vibeview.airplay.AirPlayListener
import com.eladkay.vibeview.airplay.internal.AudioReceivers.localPort
import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo
import com.github.serezhka.jap2lib.rtsp.MediaStreamInfo
import com.github.serezhka.jap2lib.rtsp.VideoStreamInfo
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.rtsp.RtspDecoder
import io.netty.handler.codec.rtsp.RtspEncoder
import io.netty.handler.codec.rtsp.RtspMethods
import io.netty.handler.codec.rtsp.RtspResponseStatuses
import io.netty.handler.codec.rtsp.RtspVersions
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * RTSP control connection handler: pairing, FairPlay setup, stream SETUP/TEARDOWN,
 * and session keep-alive. One instance per connection.
 *
 * @param dataGroup event loop group for the mirror/audio data sockets. Must be distinct
 * from the control connection's own loop so their binds can be awaited synchronously.
 */
internal class ControlHandler(
    private val config: AirPlayConfig,
    private val sessions: SessionManager,
    private val dataGroup: EventLoopGroup,
    private val listener: AirPlayListener,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    private var currentSession: Session? = null
    private val digestAuth = DigestAuth(DigestAuth.REALM, config.password)

    /** Sample rate of the negotiated audio stream; progress timestamps are in these units. */
    private var currentAudioSampleRate = 44100

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val sessionKey = request.headers().get(HEADER_ACTIVE_REMOTE)
            ?: (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
        val session = sessions.session(sessionKey)
        currentSession = session

        if (!digestAuth.isAuthorized(request.method().name(), request.headers().get(HttpHeaderNames.AUTHORIZATION))) {
            val challenge = createResponse(request)
            challenge.status = HttpResponseStatus.UNAUTHORIZED
            challenge.headers().add(HttpHeaderNames.WWW_AUTHENTICATE, digestAuth.challenge())
            send(ctx, request, challenge)
            return
        }

        val response = createResponse(request)
        val uri = request.uri().substringBefore('?')
        val method = request.method()

        try {
            when {
                uri == "/pair-setup" -> session.airPlay.pairSetup(ByteBufOutputStream(response.content()))
                uri == "/pair-verify" -> session.airPlay.pairVerify(
                    ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                )
                uri == "/fp-setup" -> session.airPlay.fairPlaySetup(
                    ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                )
                uri.startsWith("/info") -> session.airPlay.info(ByteBufOutputStream(response.content()))
                uri == "/feedback" -> { /* heartbeat, empty 200 */ }
                uri == "/audioMode" -> { /* default mode is fine */ }
                method == RtspMethods.OPTIONS ->
                    response.headers().add(
                        "Public",
                        "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                    )
                method == RtspMethods.SETUP -> handleSetup(session, request, response)
                method == RtspMethods.GET_PARAMETER ->
                    response.content().writeBytes("volume: 1.000000\r\n".toByteArray(Charsets.US_ASCII))
                method == RtspMethods.RECORD -> {
                    response.headers().add("Audio-Latency", "11025")
                    response.headers().add("Audio-Jack-Status", "connected; type=analog")
                }
                method == RtspMethods.SET_PARAMETER -> handleSetParameter(request)
                method.name() == "FLUSH" -> { /* accept */ }
                method == RtspMethods.TEARDOWN -> handleTeardown(session, request)
                else -> {
                    log.info("Unhandled control request {} {}", method, request.uri())
                }
            }
        } catch (e: Exception) {
            log.error("Error handling control request {} {}", method, request.uri(), e)
            response.setStatus(RtspResponseStatuses.INTERNAL_SERVER_ERROR)
        }

        send(ctx, request, response)
    }

    private fun handleSetup(session: Session, request: FullHttpRequest, response: DefaultFullHttpResponse) {
        val streamInfo: MediaStreamInfo? = runCatching {
            session.airPlay.rtspGetMediaStreamInfo(ByteBufInputStream(request.content().duplicate()))
        }.getOrNull()

        when (streamInfo?.streamType) {
            null -> {
                // Initial SETUP: carries the encrypted AES key + IV.
                session.airPlay.rtspSetupEncryption(ByteBufInputStream(request.content()))
            }
            MediaStreamInfo.StreamType.VIDEO -> {
                streamInfo as VideoStreamInfo
                session.stopMirroring()
                session.mirrorChannel = MirroringReceiver.start(dataGroup, config.mirrorDataPort, session, listener)
                session.mirroringActive = true
                session.airPlay.rtspSetupVideo(
                    ByteBufOutputStream(response.content()),
                    config.mirrorDataPort, config.airtunesPort, TIMING_PORT
                )
                listener.onMirroringStarted()
            }
            MediaStreamInfo.StreamType.AUDIO -> {
                streamInfo as AudioStreamInfo
                session.stopAudio()
                val audioFormat = AirPlayAudioFormat.from(streamInfo)
                currentAudioSampleRate = audioFormat.sampleRate
                listener.onAudioFormat(audioFormat)
                val data = AudioReceivers.startData(dataGroup, session, listener)
                val control = AudioReceivers.startControl(dataGroup)
                session.audioChannel = data
                session.audioControlChannel = control
                session.airPlay.rtspSetupAudio(
                    ByteBufOutputStream(response.content()), data.localPort(), control.localPort()
                )
            }
        }
    }

    /**
     * `SET_PARAMETER` carries now-playing information during an audio session: DAAP-tagged
     * track metadata, cover artwork, and a progress line. The content type says which.
     */
    private fun handleSetParameter(request: FullHttpRequest) {
        val contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE)?.lowercase().orEmpty()
        val length = request.content().readableBytes()
        if (length == 0) return
        val body = ByteArray(length)
        request.content().getBytes(request.content().readerIndex(), body)

        when {
            contentType.contains("dmap") || contentType.contains("daap") ->
                DaapMetadata.parse(body)?.let { listener.onNowPlayingMetadata(it) }

            contentType.startsWith("image/") ->
                listener.onNowPlayingArtwork(body)

            contentType.contains("parameters") || contentType.isEmpty() -> {
                val text = String(body, Charsets.UTF_8)
                DaapMetadata.parseProgress(text, currentAudioSampleRate)?.let { (position, duration) ->
                    listener.onNowPlayingProgress(position, duration)
                }
            }
        }
    }

    private fun handleTeardown(session: Session, request: FullHttpRequest) {
        val streamInfo = runCatching {
            session.airPlay.rtspGetMediaStreamInfo(ByteBufInputStream(request.content()))
        }.getOrNull()
        when (streamInfo?.streamType) {
            MediaStreamInfo.StreamType.AUDIO -> stopAudioAndNotify(session)
            MediaStreamInfo.StreamType.VIDEO -> stopMirroringAndNotify(session)
            null -> {
                stopAudioAndNotify(session)
                stopMirroringAndNotify(session)
            }
        }
    }

    private fun stopMirroringAndNotify(session: Session) {
        val wasActive = session.mirroringActive
        session.stopMirroring()
        if (wasActive) listener.onMirroringStopped()
    }

    private fun stopAudioAndNotify(session: Session) {
        val wasActive = session.audioChannel != null
        session.stopAudio()
        if (wasActive) listener.onAudioStopped()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // The control connection dropping means the client is gone: tear its streams down.
        currentSession?.let { session ->
            stopAudioAndNotify(session)
            stopMirroringAndNotify(session)
        }
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("Control connection error", cause)
        ctx.close()
    }

    private fun createResponse(request: FullHttpRequest): DefaultFullHttpResponse {
        val response = DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK)
        response.headers().clear()
        request.headers().get(HEADER_CSEQ)?.let { response.headers().add(HEADER_CSEQ, it) }
        response.headers().add("Server", SERVER_VERSION)
        return response
    }

    private fun send(ctx: ChannelHandlerContext, request: FullHttpRequest, response: FullHttpResponse) {
        HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
        val future = ctx.writeAndFlush(response)
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ControlHandler::class.java)
        private const val HEADER_CSEQ = "CSeq"
        private const val HEADER_ACTIVE_REMOTE = "Active-Remote"
        private const val TIMING_PORT = 7011
        const val SERVER_VERSION = "AirTunes/220.68"
    }
}

/** RTSP control server bound on the `_raop._tcp` port. */
internal object ControlServer {

    private val log = LoggerFactory.getLogger(ControlServer::class.java)

    fun start(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        dataGroup: EventLoopGroup,
        config: AirPlayConfig,
        sessions: SessionManager,
        listener: AirPlayListener,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .localAddress(InetSocketAddress(config.airtunesPort))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        RtspDecoder(),
                        RtspEncoder(),
                        HttpObjectAggregator(64 * 1024),
                        ControlHandler(config, sessions, dataGroup, listener),
                    )
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.SO_REUSEADDR, true)
        val channel = bootstrap.bind().sync().channel()
        log.info("Control server (RTSP) listening on port {}", config.airtunesPort)
        return channel
    }
}
