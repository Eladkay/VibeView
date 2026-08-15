package com.eladkay.vibeview.airplay

import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo

/**
 * Callbacks from the AirPlay receiver. All methods are invoked on Netty I/O threads;
 * implementations must hand work off to their own executors and never block.
 */
interface AirPlayListener {

    /**
     * A protocol-level event (a control request, connection open/close, or error),
     * already formatted for display. Used to trace a handshake that fails partway.
     */
    fun onProtocolEvent(message: String) {}

    /** A mirroring session completed RTSP SETUP for video. */
    fun onMirroringStarted() {}

    /**
     * Decrypted H.264 video in Annex-B format (start-code delimited NAL units).
     * SPS/PPS arrive through this callback too, ahead of the frames that need them.
     */
    fun onVideoData(data: ByteArray)

    /** Source/display dimensions announced in the mirror stream's codec-data packet. */
    fun onVideoFormat(widthSource: Int, heightSource: Int, width: Int, height: Int) {}

    /** Audio stream negotiated. Frames follow via [onAudioData]. */
    fun onAudioFormat(format: AirPlayAudioFormat) {}

    /** One decrypted compressed audio frame (AAC-ELD/AAC-LC/ALAC depending on format). */
    fun onAudioData(frame: ByteArray) {}

    /** Track metadata for the now-playing screen (audio-only AirPlay sessions). */
    fun onNowPlayingMetadata(metadata: NowPlayingMetadata) {}

    /** Cover artwork (JPEG/PNG bytes) for the current track. */
    fun onNowPlayingArtwork(image: ByteArray) {}

    /** Playback progress of the current track, in seconds. */
    fun onNowPlayingProgress(positionSeconds: Double, durationSeconds: Double) {}

    /** The mirroring session ended (TEARDOWN or connection loss). */
    fun onMirroringStopped() {}

    /** The audio stream ended (TEARDOWN or connection loss). */
    fun onAudioStopped() {}

    /**
     * Client asked us to play a media URL (video casting).
     * [startPosition] is a 0..1 fraction of the media duration.
     */
    fun onCastPlay(url: String, startPosition: Double) {}

    /** Rate change: 0 = pause, 1 = play. */
    fun onCastRate(rate: Float) {}

    /** Absolute seek, in seconds. */
    fun onCastSeek(positionSeconds: Double) {}

    /** Casting session stopped by the client. */
    fun onCastStop() {}

    /** A photo was pushed for display (AirPlay photo casting). */
    fun onPhoto(jpeg: ByteArray) {}

    /** Pull-model status used to answer `GET /scrub` and `GET /playback-info`. */
    fun castStatus(): CastStatus = CastStatus()
}

/** Track information shown while playing an audio-only AirPlay stream. */
data class NowPlayingMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/** Negotiated mirroring/streaming audio format. */
data class AirPlayAudioFormat(
    val compression: Compression,
    val sampleRate: Int,
    val channels: Int,
    val samplesPerFrame: Int,
    val bitDepth: Int = 16,
) {
    enum class Compression { PCM, ALAC, AAC_LC, AAC_ELD, OPUS }

    companion object {
        /** Maps the negotiated RTSP stream info to a public format. Module-internal:
         *  keeps the jap2lib [AudioStreamInfo] type out of the public API surface. */
        internal fun from(info: AudioStreamInfo): AirPlayAudioFormat {
            val compression = when (info.compressionType) {
                AudioStreamInfo.CompressionType.LPCM -> Compression.PCM
                AudioStreamInfo.CompressionType.ALAC -> Compression.ALAC
                AudioStreamInfo.CompressionType.AAC -> Compression.AAC_LC
                AudioStreamInfo.CompressionType.AAC_ELD -> Compression.AAC_ELD
                AudioStreamInfo.CompressionType.OPUS -> Compression.OPUS
                else -> Compression.AAC_ELD
            }
            // Format enum names look like AAC_ELD_44100_2 / PCM_48000_16_2 / ALAC_44100_16_2;
            // rate and channel count are always the trailing numeric fields.
            // Format enum names encode rate/bit-depth/channels as trailing numbers,
            // e.g. ALAC_44100_16_2, PCM_48000_24_2, AAC_ELD_44100_2, OPUS_48000_1.
            var sampleRate = 44100
            var channels = 2
            var bitDepth = 16
            info.audioFormat?.let { format ->
                val parts = format.name.split('_').mapNotNull { it.toIntOrNull() }
                parts.firstOrNull { it >= 8000 }?.let { sampleRate = it }
                parts.lastOrNull { it in 1..8 }?.let { channels = it }
                parts.firstOrNull { it == 16 || it == 24 }?.let { bitDepth = it }
            }
            val spf = if (info.samplesPerFrame > 0) info.samplesPerFrame else 480
            return AirPlayAudioFormat(compression, sampleRate, channels, spf, bitDepth)
        }
    }
}

/** Playback status reported back to the casting client. Times in seconds. */
data class CastStatus(
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val rate: Float = 0f,
    val readyToPlay: Boolean = false,
)

/** Coarse cast playback states pushed to the client over the reverse event channel. */
enum class CastState(val wireName: String) {
    LOADING("loading"),
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
}
