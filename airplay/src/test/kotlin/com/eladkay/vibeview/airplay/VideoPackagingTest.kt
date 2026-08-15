package com.eladkay.vibeview.airplay

import com.eladkay.vibeview.airplay.internal.VideoPackaging
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoPackagingTest {

    @Test
    fun `converts single nalu to annex b`() {
        val payload = byteArrayOf(0, 0, 0, 3, 0x65, 0x11, 0x22)
        assertTrue(VideoPackaging.avccToAnnexBInPlace(payload))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22), payload)
    }

    @Test
    fun `converts multiple nalus to annex b`() {
        val payload = byteArrayOf(
            0, 0, 0, 2, 0x09, 0x10,
            0, 0, 0, 3, 0x65, 0x7F, 0x00,
        )
        assertTrue(VideoPackaging.avccToAnnexBInPlace(payload))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x09, 0x10, 0, 0, 0, 1, 0x65, 0x7F, 0x00),
            payload
        )
    }

    @Test
    fun `rejects truncated nalu`() {
        val payload = byteArrayOf(0, 0, 0, 9, 0x65, 0x11)
        assertFalse(VideoPackaging.avccToAnnexBInPlace(payload))
    }

    @Test
    fun `rejects trailing garbage`() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        assertFalse(VideoPackaging.avccToAnnexBInPlace(payload))
    }

    @Test
    fun `parses avcC record into annex b parameter sets`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x28)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val record = byteArrayOf(
            1, 0x42, 0x00, 0x28, 0xFF.toByte(),
            0xE1.toByte(), // 1 SPS
            0, sps.size.toByte(), *sps,
            1, // 1 PPS
            0, pps.size.toByte(), *pps,
        )
        val annexB = VideoPackaging.avccConfigToAnnexB(record)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, *sps, 0, 0, 0, 1, *pps), annexB)
    }

    @Test
    fun `rejects malformed avcC record`() {
        assertNull(VideoPackaging.avccConfigToAnnexB(byteArrayOf(1, 2, 3)))
        assertNull(VideoPackaging.avccConfigToAnnexB(byteArrayOf(2, 0x42, 0, 0x28, 0xFF.toByte(), 0xE1.toByte(), 0, 4)))
    }
}
