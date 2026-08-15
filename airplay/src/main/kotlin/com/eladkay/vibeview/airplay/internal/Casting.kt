package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.AirPlayConfig
import com.eladkay.vibeview.airplay.AirPlayListener
import com.eladkay.vibeview.airplay.CastState
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * HTTP handler for the `_airplay._tcp` port: video casting, photos, and the
 * reverse-HTTP event channel. One instance per connection.
 */
internal class CastHandler(
    private val config: AirPlayConfig,
    private val sessions: SessionManager,
    private val listener: AirPlayListener,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val sessionKey = request.headers().get(HEADER_SESSION_ID)
            ?: request.headers().get(HEADER_ACTIVE_REMOTE)
            ?: (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
        val session = sessions.session(sessionKey)

        val decoder = QueryStringDecoder(request.uri())
        val path = decoder.path()
        val method = request.method()
        log.debug("Cast request {} {}", method, request.uri())

        if (path == "/reverse") {
            handleReverse(ctx, session)
            return
        }

        val response = okResponse()
        try {
            when {
                path == "/server-info" -> respondPlist(response, Plists.serverInfo(config))
                path == "/play" && method == HttpMethod.POST -> {
                    val body = ByteArray(request.content().readableBytes())
                    request.content().readBytes(body)
                    val parsed = Plists.parsePlayBody(body)
                    if (parsed == null) {
                        log.warn("POST /play without Content-Location")
                        response.setStatus(HttpResponseStatus.BAD_REQUEST)
                    } else {
                        val (url, startPosition) = parsed
                        log.info("Cast play request: {} @ {}", url, startPosition)
                        session.castingActive = true
                        sessions.activeCast = session
                        listener.onCastPlay(url, startPosition)
                    }
                }
                path == "/rate" && method == HttpMethod.POST -> {
                    val value = decoder.parameters()["value"]?.firstOrNull()?.toFloatOrNull() ?: 1f
                    listener.onCastRate(value)
                }
                path == "/scrub" && method == HttpMethod.POST -> {
                    decoder.parameters()["position"]?.firstOrNull()?.toDoubleOrNull()?.let {
                        listener.onCastSeek(it)
                    }
                }
                path == "/scrub" && method == HttpMethod.GET -> {
                    val status = listener.castStatus()
                    val text = "duration: %.6f\r\nposition: %.6f\r\n".format(status.duration, status.position)
                    response.content().writeBytes(text.toByteArray(Charsets.US_ASCII))
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, Plists.CONTENT_TYPE_PARAMETERS)
                }
                path == "/playback-info" -> respondPlist(response, Plists.playbackInfo(listener.castStatus()))
                path == "/stop" && method == HttpMethod.POST -> {
                    val wasCasting = session.castingActive
                    session.castingActive = false
                    if (sessions.activeCast === session) sessions.activeCast = null
                    if (wasCasting) listener.onCastStop()
                }
                path == "/photo" && method == HttpMethod.PUT -> {
                    val jpeg = ByteArray(request.content().readableBytes())
                    request.content().readBytes(jpeg)
                    if (jpeg.isNotEmpty()) listener.onPhoto(jpeg)
                }
                path == "/slideshow-features" -> respondPlist(response, Plists.emptyDict())
                path == "/volume" || path == "/authorize" || path == "/setProperty" || path == "/getProperty" -> {
                    // Accepted no-ops: clients treat 200 as success and move on.
                }
                // Some clients run the pairing/FairPlay handshake on this port before casting.
                path == "/pair-setup" -> session.airPlay.pairSetup(ByteBufOutputStream(response.content()))
                path == "/pair-verify" -> session.airPlay.pairVerify(
                    ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                )
                path == "/fp-setup" || path == "/fp-setup2" -> session.airPlay.fairPlaySetup(
                    ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                )
                path.startsWith("/info") -> session.airPlay.info(ByteBufOutputStream(response.content()))
                else -> {
                    log.info("Unhandled cast request {} {}", method, request.uri())
                    response.setStatus(HttpResponseStatus.NOT_FOUND)
                }
            }
        } catch (e: Exception) {
            log.error("Error handling cast request {} {}", method, request.uri(), e)
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }

        HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
        val future = ctx.writeAndFlush(response)
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    /**
     * `POST /reverse` upgrades the connection to reverse HTTP (PTTH/1.0): we keep the
     * socket and later send `POST /event` requests to the client on it.
     */
    private fun handleReverse(ctx: ChannelHandlerContext, session: Session) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS)
        response.headers().set(HttpHeaderNames.UPGRADE, "PTTH/1.0")
        response.headers().set(HttpHeaderNames.CONNECTION, "Upgrade")
        ctx.writeAndFlush(response).addListener { future ->
            if (!future.isSuccess) {
                ctx.close()
                return@addListener
            }
            val pipeline = ctx.pipeline()
            pipeline.remove(NAME_AGGREGATOR)
            pipeline.remove(NAME_CODEC)
            pipeline.addLast(HttpClientCodec(), HttpObjectAggregator(64 * 1024), EventResponseHandler(session, listener))
            pipeline.remove(this)
            session.eventChannel = ctx.channel()
            log.info("Reverse event channel established for session {}", session.key)
        }
    }

    private fun okResponse(): DefaultFullHttpResponse {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set("Server", ControlHandler.SERVER_VERSION)
        return response
    }

    private fun respondPlist(response: DefaultFullHttpResponse, body: ByteArray) {
        response.content().writeBytes(body)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, Plists.CONTENT_TYPE_XML_PLIST)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("Cast connection error", cause)
        ctx.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(CastHandler::class.java)
        private const val HEADER_SESSION_ID = "X-Apple-Session-ID"
        private const val HEADER_ACTIVE_REMOTE = "Active-Remote"
        const val NAME_CODEC = "http-codec"
        const val NAME_AGGREGATOR = "http-aggregator"
    }
}

/** Consumes responses on the reverse event channel; notices when the client goes away. */
internal class EventResponseHandler(
    private val session: Session,
    private val listener: AirPlayListener,
) : SimpleChannelInboundHandler<FullHttpResponse>() {

    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
        log.debug("Event response: {}", msg.status())
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (session.eventChannel === ctx.channel()) {
            session.eventChannel = null
            if (session.castingActive) {
                session.castingActive = false
                listener.onCastStop()
            }
        }
        super.channelInactive(ctx)
    }

    companion object {
        private val log = LoggerFactory.getLogger(EventResponseHandler::class.java)
    }
}

internal object EventChannel {

    private val log = LoggerFactory.getLogger(EventChannel::class.java)

    fun sendState(session: Session, state: CastState) {
        val channel = session.eventChannel ?: return
        if (!channel.isActive) return
        val body = Plists.eventInfo(state)
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/event", Unpooled.wrappedBuffer(body)
        )
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, Plists.CONTENT_TYPE_XML_PLIST)
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.size)
        request.headers().set(HEADER_SESSION_ID, session.key)
        channel.writeAndFlush(request)
        log.debug("Sent cast event {}", state.wireName)
    }

    private const val HEADER_SESSION_ID = "X-Apple-Session-ID"
}

/** Plain-HTTP server bound on the `_airplay._tcp` port. */
internal object CastServer {

    private val log = LoggerFactory.getLogger(CastServer::class.java)

    fun start(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        config: AirPlayConfig,
        sessions: SessionManager,
        listener: AirPlayListener,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .localAddress(InetSocketAddress(config.airplayPort))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(CastHandler.NAME_CODEC, HttpServerCodec())
                    // Photos arrive as a single PUT body; 32 MB headroom.
                    ch.pipeline().addLast(CastHandler.NAME_AGGREGATOR, HttpObjectAggregator(32 * 1024 * 1024))
                    ch.pipeline().addLast(CastHandler(config, sessions, listener))
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.SO_REUSEADDR, true)
        val channel = bootstrap.bind().sync().channel()
        log.info("Cast server (HTTP) listening on port {}", config.airplayPort)
        return channel
    }
}
