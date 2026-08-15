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
    fun `is a binary plist`() {
        val bytes = InfoResponse.build(config, publicKey, config.pairingId)
        assertEquals("bplist", String(bytes, 0, 6, Charsets.US_ASCII))
    }
}
