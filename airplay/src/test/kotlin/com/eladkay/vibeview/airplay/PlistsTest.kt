package com.eladkay.vibeview.airplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.eladkay.vibeview.airplay.internal.Plists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PlistsTest {

    @Test
    fun `parses binary plist play body`() {
        val dict = NSDictionary()
        dict.put("Content-Location", "https://example.com/stream.m3u8")
        dict.put("Start-Position", 0.25)
        val out = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(out, dict)

        val (url, start) = Plists.parsePlayBody(out.toByteArray())!!
        assertEquals("https://example.com/stream.m3u8", url)
        assertEquals(0.25, start, 1e-9)
    }

    @Test
    fun `parses text play body`() {
        val body = "Content-Location: http://example.com/video.mp4\r\nStart-Position: 0.5\r\n"
        val (url, start) = Plists.parsePlayBody(body.toByteArray())!!
        assertEquals("http://example.com/video.mp4", url)
        assertEquals(0.5, start, 1e-9)
    }

    @Test
    fun `rejects body without url`() {
        assertNull(Plists.parsePlayBody("Start-Position: 0.5\r\n".toByteArray()))
        assertNull(Plists.parsePlayBody(ByteArray(0)))
    }

    @Test
    fun `playback info contains status fields`() {
        val bytes = Plists.playbackInfo(CastStatus(duration = 120.0, position = 30.0, rate = 1f, readyToPlay = true))
        val dict = PropertyListParser.parse(bytes) as NSDictionary
        assertEquals(120.0, dict.objectForKey("duration").toJavaObject())
        assertEquals(30.0, dict.objectForKey("position").toJavaObject())
        assertEquals(1.0, dict.objectForKey("rate").toJavaObject())
        assertEquals(true, dict.objectForKey("readyToPlay").toJavaObject())
        assertTrue(dict.containsKey("loadedTimeRanges"))
        assertTrue(dict.containsKey("seekableTimeRanges"))
    }

    @Test
    fun `server info advertises device`() {
        val bytes = Plists.serverInfo(AirPlayConfig(serverName = "Test", deviceId = "AA:BB:CC:DD:EE:FF"))
        val dict = PropertyListParser.parse(bytes) as NSDictionary
        assertEquals("AA:BB:CC:DD:EE:FF", dict.objectForKey("deviceid").toJavaObject())
        assertEquals("AppleTV3,2", dict.objectForKey("model").toJavaObject())
    }

    @Test
    fun `event info carries state`() {
        val dict = PropertyListParser.parse(Plists.eventInfo(CastState.PLAYING)) as NSDictionary
        assertEquals("playing", dict.objectForKey("state").toJavaObject())
        assertEquals("video", dict.objectForKey("category").toJavaObject())
    }
}
