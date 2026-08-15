package com.eladkay.vibeview.airplay.internal

/**
 * Helpers converting the mirror stream's video payloads to Annex-B for MediaCodec.
 * Pure functions, unit-tested on the JVM.
 */
internal object VideoPackaging {

    /**
     * Converts a decrypted video payload from AVCC framing (4-byte big-endian NALU
     * length prefixes) to Annex-B (00 00 00 01 start codes), in place.
     *
     * @return true if the whole payload parsed as valid AVCC framing
     */
    fun avccToAnnexBInPlace(payload: ByteArray): Boolean {
        var offset = 0
        while (offset + 4 <= payload.size) {
            val naluLength = ((payload[offset].toInt() and 0xFF) shl 24) or
                    ((payload[offset + 1].toInt() and 0xFF) shl 16) or
                    ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                    (payload[offset + 3].toInt() and 0xFF)
            if (naluLength <= 0 || offset + 4 + naluLength > payload.size) {
                return false
            }
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + naluLength
        }
        return offset == payload.size
    }

    /**
     * Parses an AVCDecoderConfigurationRecord (avcC) and returns the parameter sets
     * as a single Annex-B chunk (SPS and PPS NAL units with start codes), or null
     * if the record is malformed.
     */
    fun avccConfigToAnnexB(record: ByteArray): ByteArray? {
        if (record.size < 7 || record[0].toInt() != 1) return null
        val out = java.io.ByteArrayOutputStream()
        var offset = 5
        val spsCount = record[offset].toInt() and 0x1F
        offset++
        repeat(spsCount) {
            if (offset + 2 > record.size) return null
            val len = ((record[offset].toInt() and 0xFF) shl 8) or (record[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + len > record.size) return null
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(record, offset, len)
            offset += len
        }
        if (offset >= record.size) return null
        val ppsCount = record[offset].toInt() and 0xFF
        offset++
        repeat(ppsCount) {
            if (offset + 2 > record.size) return null
            val len = ((record[offset].toInt() and 0xFF) shl 8) or (record[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + len > record.size) return null
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(record, offset, len)
            offset += len
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
