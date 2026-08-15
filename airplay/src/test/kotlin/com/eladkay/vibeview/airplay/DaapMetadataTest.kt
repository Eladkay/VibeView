package com.eladkay.vibeview.airplay

import com.eladkay.vibeview.airplay.internal.DaapMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class DaapMetadataTest {

    private fun tag(name: String, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(
            (value.size ushr 24).toByte(),
            (value.size ushr 16).toByte(),
            (value.size ushr 8).toByte(),
            value.size.toByte(),
        ))
        out.write(value)
        return out.toByteArray()
    }

    private fun tag(name: String, value: String) = tag(name, value.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses flat track metadata`() {
        val body = tag("minm", "Blue Monday") + tag("asar", "New Order") + tag("asal", "Power")
        val metadata = DaapMetadata.parse(body)!!
        assertEquals("Blue Monday", metadata.title)
        assertEquals("New Order", metadata.artist)
        assertEquals("Power", metadata.album)
    }

    @Test
    fun `parses metadata nested in a container`() {
        val inner = tag("minm", "Teardrop") + tag("asar", "Massive Attack")
        val body = tag("mlit", inner)
        val metadata = DaapMetadata.parse(body)!!
        assertEquals("Teardrop", metadata.title)
        assertEquals("Massive Attack", metadata.artist)
    }

    @Test
    fun `handles utf8 values`() {
        val metadata = DaapMetadata.parse(tag("minm", "Björk – Jóga"))!!
        assertEquals("Björk – Jóga", metadata.title)
    }

    @Test
    fun `returns null without recognised fields`() {
        assertNull(DaapMetadata.parse(ByteArray(0)))
        assertNull(DaapMetadata.parse(tag("assp", "1234")))
    }

    @Test
    fun `ignores truncated records`() {
        // Declares 100 bytes but supplies far fewer.
        val truncated = "minm".toByteArray() + byteArrayOf(0, 0, 0, 100) + "abc".toByteArray()
        assertNull(DaapMetadata.parse(truncated))
    }

    @Test
    fun `parses progress line into seconds`() {
        // start=0, current=441000 (10s at 44.1kHz), end=4410000 (100s)
        val (position, duration) = DaapMetadata.parseProgress("progress: 0/441000/4410000\r\n", 44100)!!
        assertEquals(10.0, position, 1e-6)
        assertEquals(100.0, duration, 1e-6)
    }

    @Test
    fun `progress respects stream sample rate`() {
        val (position, _) = DaapMetadata.parseProgress("progress: 0/48000/480000", 48000)!!
        assertEquals(1.0, position, 1e-6)
    }

    @Test
    fun `rejects malformed progress`() {
        assertNull(DaapMetadata.parseProgress("volume: 1.0", 44100))
        assertNull(DaapMetadata.parseProgress("progress: 0/441000", 44100))
        assertNull(DaapMetadata.parseProgress("progress: a/b/c", 44100))
    }
}
