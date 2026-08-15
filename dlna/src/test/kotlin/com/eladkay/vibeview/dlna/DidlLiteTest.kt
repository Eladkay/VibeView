package com.eladkay.vibeview.dlna

import com.eladkay.vibeview.dlna.internal.GenaSubscriptions
import com.eladkay.vibeview.dlna.internal.Soap
import com.eladkay.vibeview.dlna.internal.UpnpActions
import com.eladkay.vibeview.dlna.internal.UpnpDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DidlLiteTest {

    @Test
    fun `extracts title and subtitle from res protocolInfo`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/">
            <item><dc:title>Big Buck Bunny</dc:title>
            <res protocolInfo="http-get:*:video/mp4:*">http://example.com/v.mp4</res>
            <res protocolInfo="http-get:*:text/srt:*">http://example.com/v.srt</res>
            </item></DIDL-Lite>"""
        val metadata = DidlLite.parse(didl)!!
        assertEquals("Big Buck Bunny", metadata.title)
        assertEquals(listOf("http://example.com/v.srt"), metadata.subtitleUrls)
    }

    @Test
    fun `extracts samsung captioninfo subtitles`() {
        val didl = """<DIDL-Lite xmlns:sec="http://www.sec.co.kr/">
            <item><sec:CaptionInfoEx sec:type="srt">http://example.com/s.srt</sec:CaptionInfoEx>
            </item></DIDL-Lite>"""
        val metadata = DidlLite.parse(didl)!!
        assertEquals(listOf("http://example.com/s.srt"), metadata.subtitleUrls)
    }

    @Test
    fun `ignores non subtitle resources`() {
        val didl = """<DIDL-Lite><item>
            <res protocolInfo="http-get:*:video/mp4:*">http://example.com/v.mp4</res>
            </item></DIDL-Lite>"""
        assertNull(DidlLite.parse(didl))
    }

    @Test
    fun `handles absent or malformed metadata`() {
        assertNull(DidlLite.parse(null))
        assertNull(DidlLite.parse(""))
        assertNull(DidlLite.parse("<not-xml"))
    }

    @Test
    fun `dispatches SetNextAVTransportURI`() {
        var next: String? = "unset"
        val listener = object : DlnaRendererListener {
            override fun onSetUri(uri: String, metadata: String?) {}
            override fun onSetNextUri(uri: String?, metadata: String?) { next = uri }
            override fun onPlay() {}
            override fun onPause() {}
            override fun onStop() {}
            override fun onSeekSeconds(seconds: Double) {}
        }
        UpnpActions.handle(
            UpnpDevice.SERVICE_AVT,
            Soap.Request("SetNextAVTransportURI", mapOf("NextURI" to "http://x/next.mp4")),
            listener,
        )
        assertEquals("http://x/next.mp4", next)

        UpnpActions.handle(
            UpnpDevice.SERVICE_AVT,
            Soap.Request("SetNextAVTransportURI", mapOf("NextURI" to "")),
            listener,
        )
        assertNull(next)
    }

    @Test
    fun `gena subscribe renew and unsubscribe`() {
        val subscriptions = GenaSubscriptions()
        val now = 1_000_000L
        assertNull(subscriptions.subscribe(null, UpnpDevice.SERVICE_AVT, 1800, now))
        assertNull(subscriptions.subscribe("<ftp://nope>", UpnpDevice.SERVICE_AVT, 1800, now))

        val sid = subscriptions.subscribe("<http://127.0.0.1:9/cb>", UpnpDevice.SERVICE_AVT, 1800, now)
        assertNotNull(sid)
        assertTrue(subscriptions.renew(sid, 1800, now))
        assertFalse(subscriptions.renew("uuid:bogus", 1800, now))

        subscriptions.unsubscribe(sid)
        assertFalse(subscriptions.renew(sid, 1800, now))
        subscriptions.clear()
    }

    @Test
    fun `expired subscriptions are dropped on notify`() {
        val subscriptions = GenaSubscriptions()
        val start = 1_000_000L
        val sid = subscriptions.subscribe("<http://127.0.0.1:9/cb>", UpnpDevice.SERVICE_AVT, 60, start)!!
        // Well past the 60s timeout: the notify pass should evict it.
        subscriptions.notifyTransportState(DlnaStatus(state = TransportState.PLAYING), start + 120_000)
        assertFalse(subscriptions.renew(sid, 60, start + 120_000))
        subscriptions.clear()
    }
}
