package com.eladkay.vibeview.airplay.internal

import com.eladkay.vibeview.airplay.NowPlayingMetadata

/**
 * Parses the payloads iOS sends to `SET_PARAMETER` during an AirPlay audio session.
 *
 * Track metadata arrives as DAAP/DMAP-tagged binary: a flat sequence of records, each
 * a 4-character ASCII tag, a 4-byte big-endian length, then the value. Container tags
 * (`mlit` and friends) wrap the interesting leaves, so the parser recurses into any
 * record whose payload itself looks like well-formed tags.
 */
internal object DaapMetadata {

    private const val TAG_SIZE = 4
    private const val HEADER_SIZE = 8

    /** DMAP leaves carrying the fields shown on the now-playing screen. */
    private const val TAG_TITLE = "minm"
    private const val TAG_ARTIST = "asar"
    private const val TAG_ALBUM = "asal"

    fun parse(bytes: ByteArray): NowPlayingMetadata? {
        val fields = HashMap<String, String>()
        collect(bytes, 0, bytes.size, fields, depth = 0)
        val title = fields[TAG_TITLE]
        val artist = fields[TAG_ARTIST]
        val album = fields[TAG_ALBUM]
        if (title == null && artist == null && album == null) return null
        return NowPlayingMetadata(title = title, artist = artist, album = album)
    }

    private fun collect(bytes: ByteArray, from: Int, to: Int, out: MutableMap<String, String>, depth: Int) {
        if (depth > MAX_DEPTH) return
        var offset = from
        while (offset + HEADER_SIZE <= to) {
            val tag = String(bytes, offset, TAG_SIZE, Charsets.US_ASCII)
            if (!tag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return
            val length = readInt(bytes, offset + TAG_SIZE)
            val valueStart = offset + HEADER_SIZE
            if (length < 0 || valueStart + length > to) return

            when (tag) {
                TAG_TITLE, TAG_ARTIST, TAG_ALBUM ->
                    out[tag] = String(bytes, valueStart, length, Charsets.UTF_8)
                else ->
                    if (looksLikeContainer(bytes, valueStart, valueStart + length)) {
                        collect(bytes, valueStart, valueStart + length, out, depth + 1)
                    }
            }
            offset = valueStart + length
        }
    }

    /** A container starts with a plausible tag whose declared length fits inside it. */
    private fun looksLikeContainer(bytes: ByteArray, from: Int, to: Int): Boolean {
        if (from + HEADER_SIZE > to) return false
        for (i in from until from + TAG_SIZE) {
            val c = bytes[i].toInt().toChar()
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')) return false
        }
        val length = readInt(bytes, from + TAG_SIZE)
        return length >= 0 && from + HEADER_SIZE + length <= to
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /**
     * Parses `progress: <start>/<current>/<end>` (RTP timestamps at the stream's sample
     * rate) into elapsed and total seconds.
     *
     * @return position to duration in seconds, or null if absent/unparseable
     */
    fun parseProgress(body: String, sampleRate: Int): Pair<Double, Double>? {
        val line = body.lineSequence()
            .firstOrNull { it.startsWith("progress:", ignoreCase = true) }
            ?: return null
        val parts = line.substringAfter(':').trim().split('/')
        if (parts.size != 3) return null
        val start = parts[0].trim().toLongOrNull() ?: return null
        val current = parts[1].trim().toLongOrNull() ?: return null
        val end = parts[2].trim().toLongOrNull() ?: return null
        val rate = if (sampleRate > 0) sampleRate.toDouble() else 44100.0
        val position = ((current - start).coerceAtLeast(0)) / rate
        val duration = ((end - start).coerceAtLeast(0)) / rate
        return position to duration
    }

    private const val MAX_DEPTH = 4
}
