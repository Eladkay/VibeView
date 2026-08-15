package com.eladkay.vibeview.airplay.internal

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.eladkay.vibeview.airplay.AirPlayConfig
import com.eladkay.vibeview.airplay.CastState
import com.eladkay.vibeview.airplay.CastStatus

/** Builders/parsers for the plist bodies used by the casting HTTP protocol. */
internal object Plists {

    const val CONTENT_TYPE_XML_PLIST = "text/x-apple-plist+xml"
    const val CONTENT_TYPE_BINARY_PLIST = "application/x-apple-binary-plist"
    const val CONTENT_TYPE_PARAMETERS = "text/parameters"

    // Same feature bits the Bonjour TXT record advertises (video, photo, mirroring, audio, ...).
    const val FEATURES = 0x5A7FFFF7L

    fun serverInfo(config: AirPlayConfig): ByteArray {
        val dict = NSDictionary()
        dict.put("deviceid", config.deviceId)
        dict.put("features", FEATURES)
        dict.put("model", "AppleTV3,2")
        dict.put("protovers", "1.0")
        dict.put("srcvers", "220.68")
        return dict.toXMLPropertyList().toByteArray(Charsets.UTF_8)
    }

    fun playbackInfo(status: CastStatus): ByteArray {
        val dict = NSDictionary()
        dict.put("duration", status.duration)
        dict.put("position", status.position)
        dict.put("rate", status.rate.toDouble())
        dict.put("readyToPlay", status.readyToPlay)
        dict.put("playbackBufferEmpty", !status.readyToPlay)
        dict.put("playbackBufferFull", false)
        dict.put("playbackLikelyToKeepUp", status.readyToPlay)
        val range = NSDictionary()
        range.put("start", 0.0)
        range.put("duration", status.duration)
        val ranges = NSArray(range)
        dict.put("loadedTimeRanges", ranges)
        dict.put("seekableTimeRanges", ranges)
        return dict.toXMLPropertyList().toByteArray(Charsets.UTF_8)
    }

    fun eventInfo(state: CastState): ByteArray {
        val dict = NSDictionary()
        dict.put("category", "video")
        dict.put("state", state.wireName)
        return dict.toXMLPropertyList().toByteArray(Charsets.UTF_8)
    }

    fun emptyDict(): ByteArray = NSDictionary().toXMLPropertyList().toByteArray(Charsets.UTF_8)

    /**
     * Parses a `POST /play` body. Binary plists carry `Content-Location` and
     * `Start-Position`; the legacy text form is `Header: value` lines.
     *
     * @return url to start position (0..1 fraction), or null if no URL found
     */
    fun parsePlayBody(bytes: ByteArray): Pair<String, Double>? {
        if (bytes.isEmpty()) return null
        val binary = runCatching {
            val dict = PropertyListParser.parse(bytes) as? NSDictionary ?: return@runCatching null
            val url = dict.objectForKey("Content-Location")?.toString() ?: return@runCatching null
            val start = dict.objectForKey("Start-Position")?.toString()?.toDoubleOrNull() ?: 0.0
            url to start
        }.getOrNull()
        if (binary != null) return binary

        var url: String? = null
        var start = 0.0
        for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when {
                key.equals("Content-Location", ignoreCase = true) -> url = value
                key.equals("Start-Position", ignoreCase = true) -> start = value.toDoubleOrNull() ?: 0.0
            }
        }
        return url?.let { it to start }
    }
}
