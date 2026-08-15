package com.eladkay.vibeview.dlna.internal

import com.eladkay.vibeview.dlna.DlnaRendererListener
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
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.UUID

/** Serves the UPnP description/SCPD documents and the SOAP control + GENA event endpoints. */
internal class UpnpControlHandler(
    private val device: UpnpDevice,
    private val listener: DlnaRendererListener,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val path = QueryStringDecoder(request.uri()).path()
        val method = request.method().name()

        when {
            method == "GET" && path == UpnpDevice.DESCRIPTION_PATH ->
                sendXml(ctx, request, device.deviceDescription())

            method == "GET" && device.scpdFor(path) != null ->
                sendXml(ctx, request, device.scpdFor(path)!!)

            method == "POST" && device.serviceTypeForControl(path) != null ->
                handleControl(ctx, request, device.serviceTypeForControl(path)!!)

            (method == "SUBSCRIBE" || method == "UNSUBSCRIBE") && device.isEventPath(path) ->
                handleSubscription(ctx, request, method)

            else -> sendStatus(ctx, request, HttpResponseStatus.NOT_FOUND)
        }
    }

    private fun handleControl(ctx: ChannelHandlerContext, request: FullHttpRequest, serviceType: String) {
        val body = ByteArray(request.content().readableBytes())
        request.content().readBytes(body)
        val parsed = Soap.parse(body)
        val responseBody = if (parsed == null) {
            Soap.fault()
        } else {
            try {
                UpnpActions.handle(serviceType, parsed, listener)
            } catch (e: Exception) {
                log.warn("Error handling {} on {}", parsed.action, serviceType, e)
                Soap.fault()
            }
        }
        val isFault = parsed == null
        sendXml(
            ctx, request, responseBody,
            status = if (isFault) HttpResponseStatus.INTERNAL_SERVER_ERROR else HttpResponseStatus.OK,
        )
    }

    /**
     * Accepts GENA subscriptions so control points are satisfied. We do not push NOTIFY
     * events; control points poll GetPositionInfo/GetTransportInfo, which we answer live.
     */
    private fun handleSubscription(ctx: ChannelHandlerContext, request: FullHttpRequest, method: String) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        if (method == "SUBSCRIBE") {
            val sid = request.headers().get("SID") ?: "uuid:${UUID.randomUUID()}"
            response.headers().set("SID", sid)
            response.headers().set("TIMEOUT", "Second-1800")
            response.headers().set("Server", SERVER)
        }
        finish(ctx, request, response)
    }

    private fun sendXml(
        ctx: ChannelHandlerContext,
        request: FullHttpRequest,
        xml: String,
        status: HttpResponseStatus = HttpResponseStatus.OK,
    ) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(xml.toByteArray(Charsets.UTF_8))
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/xml; charset=\"utf-8\"")
        finish(ctx, request, response)
    }

    private fun sendXml(
        ctx: ChannelHandlerContext,
        request: FullHttpRequest,
        body: ByteArray,
        status: HttpResponseStatus,
    ) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/xml; charset=\"utf-8\"")
        finish(ctx, request, response)
    }

    private fun sendStatus(ctx: ChannelHandlerContext, request: FullHttpRequest, status: HttpResponseStatus) {
        finish(ctx, request, DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status))
    }

    private fun finish(ctx: ChannelHandlerContext, request: FullHttpRequest, response: FullHttpResponse) {
        response.headers().set("Server", SERVER)
        HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
        val future = ctx.writeAndFlush(response)
        if (!HttpUtil.isKeepAlive(request)) future.addListener(ChannelFutureListener.CLOSE)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("UPnP control connection error", cause)
        ctx.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(UpnpControlHandler::class.java)
        const val SERVER = "Linux/1.0 UPnP/1.0 VibeView/1.0"
    }
}

internal object UpnpHttpServer {

    private val log = LoggerFactory.getLogger(UpnpHttpServer::class.java)

    fun start(
        group: EventLoopGroup,
        port: Int,
        device: UpnpDevice,
        listener: DlnaRendererListener,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel::class.java)
            .localAddress(InetSocketAddress(port))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        HttpServerCodec(),
                        HttpObjectAggregator(1024 * 1024),
                        UpnpControlHandler(device, listener),
                    )
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_REUSEADDR, true)
        val channel = bootstrap.bind().sync().channel()
        log.info("UPnP control server listening on port {}", port)
        return channel
    }
}
