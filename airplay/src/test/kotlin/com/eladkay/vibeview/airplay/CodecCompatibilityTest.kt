package com.eladkay.vibeview.airplay

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.rtsp.RtspDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The AirPlay port carries plain HTTP (casting, pairing) *and* RTSP-style mirroring
 * requests over the same connection, so one decoder has to cope with both.
 *
 * These tests pin down why the receiver uses [RtspDecoder] everywhere: it accepts both
 * request styles, while [HttpServerCodec] rejects the RTSP version and silently yields
 * a bad-request GET — which looks to a sender like a receiver that accepts the
 * connection and then refuses to mirror.
 */
class CodecCompatibilityTest {

    private val rtspRequest =
        "SETUP rtsp://192.168.1.5/stream RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 0\r\n\r\n"
    private val httpRequest =
        "POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: 0\r\n\r\n"

    private fun decodeWithRtsp(raw: String): FullHttpRequest? {
        val channel = EmbeddedChannel(RtspDecoder(), HttpObjectAggregator(64 * 1024))
        channel.writeInbound(Unpooled.copiedBuffer(raw, Charsets.US_ASCII))
        return channel.readInbound()
    }

    @Test
    fun `rtsp decoder handles mirroring requests`() {
        val request = decodeWithRtsp(rtspRequest)
        assertNotNull(request)
        assertEquals("SETUP", request!!.method().name())
        assertEquals("RTSP/1.0", request.protocolVersion().text())
        assertEquals("3", request.headers().get("CSeq"))
        assertTrue(request.decoderResult().isSuccess)
    }

    @Test
    fun `rtsp decoder also handles plain http casting requests`() {
        val request = decodeWithRtsp(httpRequest)
        assertNotNull(request)
        assertEquals("POST", request!!.method().name())
        assertEquals("HTTP/1.1", request.protocolVersion().text())
        assertEquals("/play", request.uri())
        assertTrue(request.decoderResult().isSuccess)
    }

    @Test
    fun `rtsp decoder handles every verb mirroring uses`() {
        for (verb in listOf("RECORD", "TEARDOWN", "FLUSH", "SET_PARAMETER", "GET_PARAMETER", "OPTIONS")) {
            val request = decodeWithRtsp("$verb rtsp://tv RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n")
            assertNotNull(request, "$verb should decode")
            assertEquals(verb, request!!.method().name())
            assertTrue(request.decoderResult().isSuccess, "$verb should decode cleanly")
        }
    }

    @Test
    fun `http codec cannot parse RTSP, which is why it is not used`() {
        val channel = EmbeddedChannel(HttpServerCodec(), HttpObjectAggregator(64 * 1024))
        channel.writeInbound(Unpooled.copiedBuffer(rtspRequest, Charsets.US_ASCII))
        val request = channel.readInbound<FullHttpRequest>()
        assertNotNull(request)
        // Netty reports the failure by substituting a bad-request GET rather than throwing.
        assertTrue(request!!.decoderResult().isFailure)
        assertEquals("GET", request.method().name())
    }
}
