package com.eladkay.vibeview.airplay

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.eladkay.vibeview.airplay.internal.InfoResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `/info` is what a sender displays and what it cross-checks against the Bonjour TXT
 * record, so it has to report this receiver's real identity. A static response renamed
 * the device on screen and disagreed with the advertisement.
 */
class InfoResponseTest {

    private val config = AirPlayConfig(
        serverName = "Living Room TV",
        deviceId = "02:11:22:33:44:55",
        pairingId = "11111111-2222-3333-4444-555555555555",
    )
    private val publicKey = "aa".repeat(32)

    private fun parse(): NSDictionary =
        PropertyListParser.parse(InfoResponse.build(config, publicKey, config.pairingId)) as NSDictionary

    @Test
    fun `reports the configured name, not a canned one`() {
        val info = parse()
        assertEquals("Living Room TV", info.objectForKey("name").toJavaObject())
        assertNotEquals("Apple TV", info.objectForKey("name").toJavaObject())
    }

    @Test
    fun `identity matches what bonjour advertises`() {
        val info = parse()
        assertEquals("02:11:22:33:44:55", info.objectForKey("deviceid").toJavaObject())
        assertEquals(publicKey, info.objectForKey("pk").toJavaObject())
        assertEquals(config.pairingId, info.objectForKey("pi").toJavaObject())
        assertEquals(InfoResponse.MODEL, info.objectForKey("model").toJavaObject())

        // The TXT record advertises the feature bits as "<low>,<high>"; /info reports
        // the same value combined into one integer.
        val expected = (InfoResponse.FEATURES_HIGH shl 32) or InfoResponse.FEATURES_LOW
        assertEquals(expected, info.objectForKey("features").toJavaObject())
    }

    @Test
    fun `advertises a display and audio capabilities`() {
        val info = parse()
        assertTrue(info.containsKey("displays"))
        assertTrue(info.containsKey("audioFormats"))
        assertTrue(info.containsKey("audioLatencies"))
        assertEquals(2, info.objectForKey("vv").toJavaObject())
    }

    @Test
    fun `advertises video and mirroring but not FairPlay-protected video`() {
        val features = InfoResponse.FEATURES_LOW
        // Bit 2 claims the video path is FairPlay protected. Setting it makes senders
        // run a FairPlay handshake before POST /play that this receiver cannot answer,
        // so casting is offered as plain video instead.
        assertEquals(0L, features and (1L shl 2), "VideoFairPlay (bit 2) must stay clear")
        assertNotEquals(0L, features and (1L shl 0), "Video (bit 0) must be advertised")
        assertNotEquals(0L, features and (1L shl 4), "VideoHTTPLiveStreams (bit 4) must be advertised")
        assertNotEquals(0L, features and (1L shl 7), "Screen mirroring (bit 7) must be advertised")
    }

    @Test
    fun `txt record and info report the same features`() {
        val info = parse()
        val combined = (InfoResponse.FEATURES_HIGH shl 32) or InfoResponse.FEATURES_LOW
        assertEquals(combined, info.objectForKey("features").toJavaObject())
        // Senders cross-check the TXT record against /info, so the two must agree.
        assertEquals(
            "0x%X,0x%X".format(InfoResponse.FEATURES_LOW, InfoResponse.FEATURES_HIGH),
            InfoResponse.FEATURES_TXT,
        )
    }

    @Test
    fun `is a binary plist`() {
        val bytes = InfoResponse.build(config, publicKey, config.pairingId)
        assertEquals("bplist", String(bytes, 0, 6, Charsets.US_ASCII))
    }
}
