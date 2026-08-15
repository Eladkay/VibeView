package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.AirPlayConfig
import com.eladkay.vibeview.airplay.AirPlayListener
import com.eladkay.vibeview.airplay.CastState
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * Handles the casting endpoints (video, photos, the reverse-HTTP event channel) for
 * requests the mirroring control handler ahead of it in the pipeline declined.
 * One instance per connection.
 */
internal class CastHandler(
    private val config: AirPlayConfig,
    private val sessions: SessionManager,
    private val listener: AirPlayListener,
    private val publicKeyHex: String,
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

        val response = okResponse(request)
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
                path == "/fp-setup" || path == "/fp-setup2" -> {
                    val version = fairPlayVersion(request)
                    if (version == FAIRPLAY_VERSION_SUPPORTED) {
                        session.airPlay.fairPlaySetup(
                            ByteBufInputStream(request.content()), ByteBufOutputStream(response.content())
                        )
                    } else {
                        // Answering 200 with an empty body would tell the sender the
                        // handshake succeeded and leave it to fail confusingly later.
                        listener.onProtocolEvent(
                            "  !! FairPlay v$version ($path) is not implemented — AirPlay video casting needs it"
                        )
                        response.setStatus(HttpResponseStatus.NOT_IMPLEMENTED)
                    }
                }
                path.startsWith("/info") -> {
                    response.content().writeBytes(InfoResponse.build(config, publicKeyHex, config.pairingId))
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, ControlHandler.CONTENT_TYPE_BINARY_PLIST)
                }
                else -> {
                    log.info("Unhandled request {} {}", method, request.uri())
                    listener.onProtocolEvent("  !! 404 ${method.name()} ${request.uri().substringBefore('?')}")
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
            // The connection now runs backwards: we issue requests and the sender
            // answers, so every server-side handler must go, including the control
            // handler ahead of this one, before installing the client codec.
            val pipeline = ctx.pipeline()
            runCatching { pipeline.remove(NAME_AGGREGATOR) }
            runCatching { pipeline.remove(NAME_DECODER) }
            runCatching { pipeline.remove(NAME_ENCODER) }
            runCatching { pipeline.remove(ControlHandler::class.java) }
            pipeline.addLast(HttpClientCodec(), HttpObjectAggregator(64 * 1024), EventResponseHandler(session, listener))
            pipeline.remove(this)
            session.eventChannel = ctx.channel()
            log.info("Reverse event channel established for session {}", session.key)
        }
    }

    /** Replies in the sender's protocol; this port carries both RTSP and HTTP. */
    private fun okResponse(request: FullHttpRequest? = null): DefaultFullHttpResponse {
        val version = request?.protocolVersion() ?: HttpVersion.HTTP_1_1
        val response = DefaultFullHttpResponse(version, HttpResponseStatus.OK)
        response.headers().set("Server", ControlHandler.SERVER_VERSION)
        request?.headers()?.get("CSeq")?.let { response.headers().set("CSeq", it) }
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

    /** Version byte of a FairPlay `FPLY` message; -1 when the body is too short. */
    private fun fairPlayVersion(request: FullHttpRequest): Int {
        val content = request.content()
        if (content.readableBytes() < 5) return -1
        return content.getByte(content.readerIndex() + 4).toInt() and 0xFF
    }

    companion object {
        private val log = LoggerFactory.getLogger(CastHandler::class.java)
        const val FAIRPLAY_VERSION_SUPPORTED = 3
        private const val HEADER_SESSION_ID = "X-Apple-Session-ID"
        private const val HEADER_ACTIVE_REMOTE = "Active-Remote"
        const val NAME_DECODER = "codec-decoder"
        const val NAME_ENCODER = "codec-encoder"
        const val NAME_AGGREGATOR = "aggregator"
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

