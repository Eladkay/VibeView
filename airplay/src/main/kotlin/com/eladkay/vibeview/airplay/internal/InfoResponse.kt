package com.eladkay.vibeview.airplay.internal

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.eladkay.vibeview.airplay.AirPlayConfig
import java.io.ByteArrayOutputStream

/**
 * Builds the `/info` response.
 *
 * The identity here **must** agree with what Bonjour advertises: senders cross-check
 * `deviceid`, `pi`, `pk`, `features`, and `model` between the TXT record and this
 * response, and display the `name` from here in preference to the service name. A
 * canned response is therefore not usable — it renames the receiver and fails the
 * consistency check.
 */
internal object InfoResponse {

    /** Feature bits, split the way the TXT record advertises them. */
    const val FEATURES_LOW = 0x5A7FFFF7L
    const val FEATURES_HIGH = 0x1EL
    private const val FEATURES_COMBINED = (FEATURES_HIGH shl 32) or FEATURES_LOW

    const val SOURCE_VERSION = "220.68"
    const val MODEL = "AppleTV3,2"

    /** `statusFlags` 0x44: device is available and has no password-protected pairing. */
    private const val STATUS_FLAGS = 68

    fun build(config: AirPlayConfig, publicKeyHex: String, pairingId: String): ByteArray {
        val info = NSDictionary()
        info.put("name", config.serverName)
        info.put("model", MODEL)
        info.put("deviceid", config.deviceId)
        info.put("features", FEATURES_COMBINED)
        info.put("statusFlags", STATUS_FLAGS)
        info.put("pi", pairingId)
        info.put("pk", publicKeyHex)
        info.put("srcvers", SOURCE_VERSION)
        info.put("sourceVersion", SOURCE_VERSION)
        info.put("vv", 2)
        info.put("protovers", "1.0")
        info.put("keepAliveSendStatsAsBody", 1)

        val display = NSDictionary().apply {
            put("width", 1920)
            put("height", 1080)
            put("widthPixels", 1920)
            put("heightPixels", 1080)
            put("refreshRate", 60)
            put("maxFPS", 60)
            put("overscanned", false)
            put("rotation", false)
            put("features", 14)
            put("uuid", config.displayUuid)
        }
        info.put("displays", NSArray(display))

        info.put(
            "audioFormats",
            NSArray(audioFormatEntry(TYPE_MIRRORING_AUDIO), audioFormatEntry(TYPE_STREAM_AUDIO)),
        )
        info.put(
            "audioLatencies",
            NSArray(audioLatencyEntry(TYPE_MIRRORING_AUDIO), audioLatencyEntry(TYPE_STREAM_AUDIO)),
        )

        return ByteArrayOutputStream().also { BinaryPropertyListWriter.write(it, info) }.toByteArray()
    }

    private fun audioFormatEntry(type: Int) = NSDictionary().apply {
        put("type", type)
        put("audioInputFormats", AUDIO_FORMAT_BITS)
        put("audioOutputFormats", AUDIO_FORMAT_BITS)
    }

    private fun audioLatencyEntry(type: Int) = NSDictionary().apply {
        put("type", type)
        put("audioType", "default")
        put("inputLatencyMicros", 0)
        put("outputLatencyMicros", 0)
    }

    // Stream types used by the mirroring and general audio paths.
    private const val TYPE_MIRRORING_AUDIO = 100
    private const val TYPE_STREAM_AUDIO = 101

    /** Advertised PCM/AAC/ALAC/Opus capability bits. */
    private const val AUDIO_FORMAT_BITS = 67108860L
}
