package com.eladkay.vibeview.dlna

import com.eladkay.vibeview.dlna.internal.Soap
import com.eladkay.vibeview.dlna.internal.UpnpActions
import com.eladkay.vibeview.dlna.internal.UpnpDevice
import com.eladkay.vibeview.dlna.internal.UpnpTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlnaTest {

    private class FakeListener : DlnaRendererListener {
        var uri: String? = null
        var played = false
        var paused = false
        var stopped = false
        var seekTo = -1.0
        var volume = -1
        var status = DlnaStatus()
        override fun onSetUri(uri: String, metadata: String?) { this.uri = uri }
        override fun onPlay() { played = true }
        override fun onPause() { paused = true }
        override fun onStop() { stopped = true }
        override fun onSeekSeconds(seconds: Double) { seekTo = seconds }
        override fun onSetVolume(volume: Int) { this.volume = volume }
        override fun status(): DlnaStatus = status
    }

    @Test
    fun `formats and parses upnp time`() {
        assertEquals("0:00:00", UpnpTime.format(0.0))
        assertEquals("1:02:03", UpnpTime.format(3723.0))
        assertEquals(90.0, UpnpTime.parse("0:01:30"), 1e-9)
        assertEquals(3723.5, UpnpTime.parse("1:02:03.5"), 1e-9)
        assertEquals(0.0, UpnpTime.parse("garbage"), 1e-9)
    }

    @Test
    fun `parses SetAVTransportURI soap body`() {
        val body = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>http://example.com/video.mp4</CurrentURI>
            <CurrentURIMetaData></CurrentURIMetaData>
            </u:SetAVTransportURI></s:Body></s:Envelope>""".toByteArray()
        val request = Soap.parse(body)!!
        assertEquals("SetAVTransportURI", request.action)
        assertEquals("http://example.com/video.mp4", request.args["CurrentURI"])
        assertEquals("0", request.args["InstanceID"])
    }

    @Test
    fun `dispatches SetAVTransportURI to listener`() {
        val listener = FakeListener()
        val request = Soap.Request("SetAVTransportURI", mapOf("CurrentURI" to "http://x/v.mp4"))
        val response = UpnpActions.handle(UpnpDevice.SERVICE_AVT, request, listener)
        assertEquals("http://x/v.mp4", listener.uri)
        assertTrue(String(response).contains("SetAVTransportURIResponse"))
    }

    @Test
    fun `dispatches transport controls`() {
        val listener = FakeListener()
        UpnpActions.handle(UpnpDevice.SERVICE_AVT, Soap.Request("Play", emptyMap()), listener)
        UpnpActions.handle(UpnpDevice.SERVICE_AVT, Soap.Request("Pause", emptyMap()), listener)
        UpnpActions.handle(UpnpDevice.SERVICE_AVT, Soap.Request("Stop", emptyMap()), listener)
        UpnpActions.handle(
            UpnpDevice.SERVICE_AVT,
            Soap.Request("Seek", mapOf("Unit" to "REL_TIME", "Target" to "0:00:45")),
            listener,
        )
        assertTrue(listener.played)
        assertTrue(listener.paused)
        assertTrue(listener.stopped)
        assertEquals(45.0, listener.seekTo, 1e-9)
    }

    @Test
    fun `GetTransportInfo reflects listener state`() {
        val listener = FakeListener().apply {
            status = DlnaStatus(state = TransportState.PLAYING, durationSeconds = 120.0, positionSeconds = 30.0, uri = "http://x")
        }
        val response = String(UpnpActions.handle(UpnpDevice.SERVICE_AVT, Soap.Request("GetTransportInfo", emptyMap()), listener))
        assertTrue(response.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))

        val posInfo = String(UpnpActions.handle(UpnpDevice.SERVICE_AVT, Soap.Request("GetPositionInfo", emptyMap()), listener))
        assertTrue(posInfo.contains("<TrackDuration>0:02:00</TrackDuration>"))
        assertTrue(posInfo.contains("<RelTime>0:00:30</RelTime>"))
    }

    @Test
    fun `sets volume via rendering control`() {
        val listener = FakeListener()
        UpnpActions.handle(UpnpDevice.SERVICE_RC, Soap.Request("SetVolume", mapOf("DesiredVolume" to "42")), listener)
        assertEquals(42, listener.volume)
    }

    @Test
    fun `connection manager advertises sink protocol info`() {
        val listener = FakeListener()
        val response = String(UpnpActions.handle(UpnpDevice.SERVICE_CM, Soap.Request("GetProtocolInfo", emptyMap()), listener))
        assertTrue(response.contains("video/mp4"))
        assertTrue(response.contains("Sink"))
    }

    @Test
    fun `unknown action returns fault`() {
        val listener = FakeListener()
        val response = String(UpnpActions.handle(UpnpDevice.SERVICE_AVT, Soap.Request("Bogus", emptyMap()), listener))
        assertTrue(response.contains("UPnPError"))
        assertNull(Soap.parse(ByteArray(0)))
    }
}
