package com.eladkay.vibeview.airplay.internal

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.PropertyListParser
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
import java.io.ByteArrayOutputStream
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
    private val publicKeyHex: String,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    private var currentSession: Session? = null

    /** Sample rate of the negotiated audio stream; progress timestamps are in these units. */
    private var currentAudioSampleRate = 44100

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val sessionKey = request.headers().get(HEADER_ACTIVE_REMOTE)
            ?: (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
        val session = sessions.session(sessionKey)
        currentSession = session

        val response = createResponse(request)
        val uri = request.uri().substringBefore('?')
        val method = request.method()
        val localPort = (ctx.channel().localAddress() as? InetSocketAddress)?.port ?: 0
        log.info("Control {} {} ({}) on :{}", method, request.uri(), request.protocolVersion().text(), localPort)
        listener.onProtocolEvent(
            ":$localPort ${method.name()} $uri ${request.protocolVersion().text()}" + bodySummary(uri, request)
        )

        try {
            when {
                uri == "/pair-setup" -> session.airPlay.pairSetup(ByteBufOutputStream(response.content()))
                uri == "/pair-verify" -> session.airPlay.pairVerify(
                    ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                )
                uri == "/fp-setup" -> session.airPlay.fairPlaySetup(
                    ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                )
                uri.startsWith("/info") -> {
                    response.content().writeBytes(InfoResponse.build(config, publicKeyHex, config.pairingId))
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_BINARY_PLIST)
                }
                // A sender asks for the PIN to be displayed, then proves knowledge of it
                // over SRP. This is what password protection actually looks like on the
                // wire — senders never use HTTP authentication for it.
                uri == "/pair-pin-start" -> {
                    listener.onProtocolEvent("   PIN requested; code is shown on the TV")
                }
                uri == "/pair-setup-pin" -> handlePairSetupPin(session, request, response)
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
                    // Not part of the mirroring control protocol: hand it to the casting
                    // handler further down the pipeline instead of answering here.
                    response.release()
                    ctx.fireChannelRead(request.retain())
                    return
                }
            }
        } catch (e: Exception) {
            log.error("Error handling control request {} {}", method, request.uri(), e)
            listener.onProtocolEvent("  !! ${method.name()} failed: ${e.javaClass.simpleName}: ${e.message}")
            response.setStatus(RtspResponseStatuses.INTERNAL_SERVER_ERROR)
        }

        send(ctx, request, response)
    }

    /**
     * For the handshake endpoints, records the body size and first bytes. This
     * distinguishes the pairing protocol in use: legacy pair-setup sends an empty
     * body, whereas the newer SRP/HomeKit flow sends a TLV8 payload that this
     * receiver does not implement.
     */
    private fun bodySummary(uri: String, request: FullHttpRequest): String {
        if (!uri.startsWith("/pair") && !uri.startsWith("/fp")) return ""
        val content = request.content()
        val length = content.readableBytes()
        if (length == 0) return " body=empty"
        val preview = ByteArray(minOf(length, 8))
        content.getBytes(content.readerIndex(), preview)
        return " body=${length}B[${preview.joinToString("") { "%02x".format(it) }}]"
    }

    private fun handleSetup(session: Session, request: FullHttpRequest, response: DefaultFullHttpResponse) {
        val streamInfo: MediaStreamInfo? = runCatching {
            session.airPlay.rtspGetMediaStreamInfo(ByteBufInputStream(request.content().duplicate()))
        }.onFailure {
            listener.onProtocolEvent("  !! SETUP stream-info parse failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        listener.onProtocolEvent("   SETUP ${describeSetupBody(request)} → ${streamInfo?.streamType ?: "encryption"}")

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
                listener.onProtocolEvent("   → video dataPort=${config.mirrorDataPort}, awaiting mirror stream")
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
                listener.onProtocolEvent("   → audio $audioFormat dataPort=${data.localPort()}")
            }
        }
    }

    /**
     * Summarises a SETUP body by its top-level plist keys. An encryption SETUP carries
     * `ekey`/`eiv`; a stream SETUP carries `streams`. Seeing which arrived — and whether
     * it parsed at all — is the difference between "no video sent" and "video request
     * misread".
     */
    private fun describeSetupBody(request: FullHttpRequest): String {
        val content = request.content()
        val length = content.readableBytes()
        if (length == 0) return "empty"
        val bytes = ByteArray(length)
        content.getBytes(content.readerIndex(), bytes)
        return try {
            val dict = PropertyListParser.parse(bytes) as? NSDictionary
                ?: return "not-a-dict(${length}B)"
            val keys = dict.allKeys().joinToString(",")
            val streams = (dict["streams"] as? NSArray)?.array
            val types = streams?.mapNotNull { (it as? NSDictionary)?.get("type")?.toJavaObject() }
            if (types.isNullOrEmpty()) "keys=[$keys]" else "keys=[$keys] streamTypes=$types"
        } catch (e: Exception) {
            "unparsed(${length}B): ${e.javaClass.simpleName}"
        }
    }

    /**
     * The three-step SRP exchange, dispatched by which keys the plist carries:
     * `user` starts it, `pk`+`proof` proves the PIN, `epk`+`authTag` swaps long-term keys.
     */
    private fun handlePairSetupPin(
        session: Session,
        request: FullHttpRequest,
        response: DefaultFullHttpResponse,
    ) {
        val pin = config.password
        if (pin.isNullOrEmpty()) {
            // Nothing to prove: the receiver is open, so a sender should not be here.
            listener.onProtocolEvent("  !! /pair-setup-pin with no passcode configured")
            response.setStatus(RtspResponseStatuses.NOT_IMPLEMENTED)
            return
        }

        val body = ByteArray(request.content().readableBytes())
        request.content().getBytes(request.content().readerIndex(), body)
        val plist = runCatching { PropertyListParser.parse(body) as? NSDictionary }.getOrNull()
        if (plist == null) {
            response.setStatus(RtspResponseStatuses.BAD_REQUEST)
            return
        }

        when {
            plist.containsKey("user") -> {
                val user = plist["user"]?.toJavaObject()?.toString().orEmpty()
                val pairing = SrpPinPairing(pin).also { session.pinPairing = it }
                val challenge = pairing.begin(user)
                respondPlist(response, "pk" to challenge.pk, "salt" to challenge.salt)
                listener.onProtocolEvent("   PIN pairing started (user $user)")
            }

            plist.containsKey("pk") && plist.containsKey("proof") -> {
                val pairing = session.pinPairing
                if (pairing == null) {
                    response.setStatus(RtspResponseStatuses.BAD_REQUEST)
                    return
                }
                try {
                    val proof = pairing.verify(plist.bytes("pk"), plist.bytes("proof"))
                    respondPlist(response, "proof" to proof)
                    listener.onProtocolEvent("   PIN accepted")
                } catch (e: SecurityException) {
                    session.pinPairing = null
                    listener.onProtocolEvent("  !! PIN rejected: ${e.message}")
                    response.setStatus(RtspResponseStatuses.UNAUTHORIZED)
                }
            }

            plist.containsKey("epk") && plist.containsKey("authTag") -> {
                val pairing = session.pinPairing
                if (pairing == null) {
                    response.setStatus(RtspResponseStatuses.BAD_REQUEST)
                    return
                }
                val exchange = pairing.exchangeKeys(
                    plist.bytes("epk"), plist.bytes("authTag"), hexToBytes(publicKeyHex)
                )
                respondPlist(response, "epk" to exchange.epk, "authTag" to exchange.authTag)
                session.pinVerified = true
                listener.onProtocolEvent(
                    "   PIN pairing complete" +
                        if (pairing.clientPublicKey == null) " (sender key not decodable)" else ""
                )
            }

            else -> {
                listener.onProtocolEvent("  !! unexpected /pair-setup-pin body")
                response.setStatus(RtspResponseStatuses.BAD_REQUEST)
            }
        }
    }

    private fun NSDictionary.bytes(key: String): ByteArray =
        (this[key]?.toJavaObject() as? ByteArray) ?: ByteArray(0)

    private fun respondPlist(response: DefaultFullHttpResponse, vararg entries: Pair<String, ByteArray>) {
        val dict = NSDictionary()
        entries.forEach { (key, value) -> dict.put(key, value) }
        val out = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(out, dict)
        response.content().writeBytes(out.toByteArray())
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_BINARY_PLIST)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

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

    override fun channelActive(ctx: ChannelHandlerContext) {
        val localPort = (ctx.channel().localAddress() as? InetSocketAddress)?.port ?: 0
        val remote = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
        log.info("Control connection from {} on :{}", remote, localPort)
        listener.onProtocolEvent("── connected $remote → :$localPort")
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val localPort = (ctx.channel().localAddress() as? InetSocketAddress)?.port ?: 0
        log.info("Control connection closed on :{}", localPort)
        listener.onProtocolEvent("── disconnected :$localPort")
        // The control connection dropping means the client is gone: tear its streams down.
        currentSession?.let { session ->
            stopAudioAndNotify(session)
            stopMirroringAndNotify(session)
        }
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("Control connection error", cause)
        listener.onProtocolEvent("  !! connection error: ${cause.javaClass.simpleName}: ${cause.message}")
        ctx.close()
    }

    private fun createResponse(request: FullHttpRequest): DefaultFullHttpResponse {
        // Answer in the protocol the sender used: RTSP/1.0 for mirroring control,
        // HTTP/1.1 for the plain-HTTP requests that share this port.
        val version = request.protocolVersion().takeIf { it.text().startsWith("RTSP") }
            ?: RtspVersions.RTSP_1_0
        val response = DefaultFullHttpResponse(version, RtspResponseStatuses.OK)
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
        const val CONTENT_TYPE_BINARY_PLIST = "application/x-apple-binary-plist"
    }
}

/**
 * Binds a receiver port serving the *whole* protocol: mirroring control (pairing,
 * FairPlay, RTSP SETUP/RECORD/TEARDOWN) and, for anything the control handler declines,
 * the casting endpoints.
 *
 * Both advertised services get this same pipeline, because a sender may run the session
 * over either the `_airplay._tcp` or the `_raop._tcp` port, and the two protocols share
 * one connection. [RtspDecoder] is used rather than an HTTP codec because it is the only
 * one that decodes both RTSP and HTTP request lines (see CodecCompatibilityTest).
 */
internal object ControlServer {

    private val log = LoggerFactory.getLogger(ControlServer::class.java)

    fun start(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        dataGroup: EventLoopGroup,
        config: AirPlayConfig,
        port: Int,
        sessions: SessionManager,
        listener: AirPlayListener,
        publicKeyHex: String,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .localAddress(InetSocketAddress(port))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(CastHandler.NAME_DECODER, RtspDecoder())
                    ch.pipeline().addLast(CastHandler.NAME_ENCODER, RtspEncoder())
                    // Photos arrive as a single large body.
                    ch.pipeline().addLast(CastHandler.NAME_AGGREGATOR, HttpObjectAggregator(32 * 1024 * 1024))
                    ch.pipeline().addLast(ControlHandler(config, sessions, dataGroup, listener, publicKeyHex))
                    ch.pipeline().addLast(CastHandler(config, sessions, listener, publicKeyHex))
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.SO_REUSEADDR, true)
        val channel = bootstrap.bind().sync().channel()
        log.info("Receiver control server listening on port {}", port)
        return channel
    }
}
