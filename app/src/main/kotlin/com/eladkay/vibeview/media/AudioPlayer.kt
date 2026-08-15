package com.eladkay.vibeview.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import com.eladkay.vibeview.airplay.AirPlayAudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the AirPlay audio stream. Decodes AAC-ELD / AAC-LC / ALAC / Opus frames with
 * MediaCodec and writes PCM to a low-latency [AudioTrack]; PCM streams play directly.
 * The AudioTrack is (re)created from the decoder's actual output format, so codecs that
 * resample (Opus always outputs 48 kHz) are handled correctly.
 *
 * A codec with no decoder on the device (some TVs lack ALAC/Opus) yields a null player
 * from [create] — video keeps playing without audio rather than crashing.
 */
class AudioPlayer private constructor(
    private val format: AirPlayAudioFormat,
    private val decodeWithCodec: Boolean,
    private val mime: String,
    private val codecSpecificData: List<ByteArray>,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity(format))
    private val running = AtomicBoolean(true)
    private val thread = Thread({ playLoop() }, "AudioPlayer").apply { start() }

    fun enqueue(frame: ByteArray) {
        if (!running.get()) return
        Diagnostics.onAudioFrameReceived()
        while (!queue.offer(frame)) {
            queue.poll() // drop oldest: fresher audio matters more than completeness
            Diagnostics.onAudioFrameDropped()
        }
    }

    fun release() {
        running.set(false)
        thread.interrupt()
        queue.clear()
    }

    private fun playLoop() {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        // A small AudioTrack buffer only stays underrun-free if this thread is scheduled
        // promptly; at default priority it competes with the network and decode threads.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            if (decodeWithCodec) {
                codec = createCodec()
                codec.start()
                track = decodeLoop(codec)
            } else {
                track = createTrack(format.sampleRate, format.channels).also { it.play() }
                pcmLoop(track)
            }
        } catch (_: InterruptedException) {
            // normal shutdown
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback failed", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { track?.release() }
        }
    }

    /** Returns the AudioTrack it created (so the caller can release it). */
    private fun decodeLoop(codec: MediaCodec): AudioTrack? {
        val info = MediaCodec.BufferInfo()
        var track: AudioTrack? = null
        while (running.get()) {
            val frame = queue.poll(100, TimeUnit.MILLISECONDS)
            if (frame != null) {
                val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    if (buffer != null && frame.size <= buffer.remaining()) {
                        buffer.clear()
                        buffer.put(frame)
                        codec.queueInputBuffer(index, 0, frame.size, 0, 0)
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, 0)
                    }
                }
            }
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track?.release()
                    track = createTrackFrom(codec.outputFormat).also { it.play() }
                    continue
                }
                if (outIndex >= 0) {
                    if (track == null) {
                        track = createTrack(format.sampleRate, format.channels).also { it.play() }
                    }
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(pcm)
                        track.write(pcm, 0, pcm.size)
                        Diagnostics.onAudioFrameDecoded()
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
        return track
    }

    private fun pcmLoop(track: AudioTrack) {
        while (running.get()) {
            val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            track.write(frame, 0, frame.size)
            Diagnostics.onAudioFrameDecoded()
        }
    }

    private fun createTrackFrom(outputFormat: MediaFormat): AudioTrack {
        val sampleRate = runCatching { outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
            .getOrDefault(format.sampleRate)
        val channels = runCatching { outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
            .getOrDefault(format.channels)
        return createTrack(sampleRate, channels)
    }

    private fun createTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        // Everything in this buffer is latency. Doubling it bought jitter tolerance that
        // the input queue already provides, and an oversized buffer also makes the
        // low-latency performance mode below a no-op on most devices.
        val bufferSize = if (minBuffer > 0) minBuffer else DEFAULT_BUFFER_BYTES
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun createCodec(): MediaCodec {
        val codec = MediaCodec.createDecoderByType(mime)
        val mediaFormat = MediaFormat.createAudioFormat(mime, format.sampleRate, format.channels)
        codecSpecificData.forEachIndexed { i, csd ->
            mediaFormat.setByteBuffer("csd-$i", ByteBuffer.wrap(csd))
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            mediaFormat.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        codec.configure(mediaFormat, null, null, 0)
        return codec
    }

    // ---- Codec-specific configuration builders ----

    private class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var current = 0
        private var bitCount = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                current = (current shl 1) or ((value shr i) and 1)
                bitCount++
                if (bitCount == 8) {
                    bytes.add(current.toByte())
                    current = 0
                    bitCount = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            val out = bytes.toMutableList()
            if (bitCount > 0) out.add((current shl (8 - bitCount)).toByte())
            return out.toByteArray()
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val INPUT_TIMEOUT_US = 20_000L
        private const val MIME_ALAC = "audio/alac"

        /** How much audio may sit queued ahead of the decoder. */
        private const val TARGET_BUFFER_MS = 120.0
        private const val MIN_QUEUE_FRAMES = 4
        private const val MAX_QUEUE_FRAMES = 24
        private const val DEFAULT_SAMPLES_PER_FRAME = 480
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_BUFFER_BYTES = 4096

        /**
         * Audio arrives at exactly the rate it plays out, so the queue never drains on
         * its own: whatever depth it reaches while the codec and track are starting up
         * is lag the listener hears for the rest of the session. Bounding it in
         * milliseconds rather than frames keeps that budget the same across codecs —
         * a flat 256-frame cap meant 2.8 s for AAC-ELD's 480-sample frames but only
         * 1.2 s for AAC-LC's 1024-sample ones.
         */
        private fun queueCapacity(format: AirPlayAudioFormat): Int {
            val samples =
                if (format.samplesPerFrame > 0) format.samplesPerFrame else DEFAULT_SAMPLES_PER_FRAME
            val rate = if (format.sampleRate > 0) format.sampleRate else DEFAULT_SAMPLE_RATE
            val frameMs = samples * 1000.0 / rate
            return (TARGET_BUFFER_MS / frameMs).toInt().coerceIn(MIN_QUEUE_FRAMES, MAX_QUEUE_FRAMES)
        }
        private const val OPUS_PRE_SKIP_SAMPLES = 3840L // 80 ms at 48 kHz, Opus default
        private const val OPUS_SEEK_PREROLL_NS = 80_000_000L

        /** Creates a player for the negotiated format, or null when it can't be played. */
        fun create(format: AirPlayAudioFormat): AudioPlayer? = when (format.compression) {
            AirPlayAudioFormat.Compression.AAC_ELD ->
                codecPlayer(format, MediaFormat.MIMETYPE_AUDIO_AAC, listOf(eldConfig(format)))
            AirPlayAudioFormat.Compression.AAC_LC ->
                codecPlayer(format, MediaFormat.MIMETYPE_AUDIO_AAC, listOf(aacLcConfig(format)))
            AirPlayAudioFormat.Compression.ALAC ->
                requireDecoder(MIME_ALAC, "ALAC") {
                    codecPlayer(format, MIME_ALAC, listOf(alacMagicCookie(format)))
                }
            AirPlayAudioFormat.Compression.OPUS ->
                requireDecoder(MediaFormat.MIMETYPE_AUDIO_OPUS, "Opus") {
                    codecPlayer(format, MediaFormat.MIMETYPE_AUDIO_OPUS, opusConfig(format))
                }
            AirPlayAudioFormat.Compression.PCM ->
                AudioPlayer(format, decodeWithCodec = false, mime = "", codecSpecificData = emptyList())
        }

        private fun codecPlayer(format: AirPlayAudioFormat, mime: String, csd: List<ByteArray>) =
            AudioPlayer(format, decodeWithCodec = true, mime = mime, codecSpecificData = csd)

        private inline fun requireDecoder(mime: String, label: String, build: () -> AudioPlayer): AudioPlayer? =
            if (hasDecoderFor(mime)) build() else {
                Log.w(TAG, "No $label decoder on this device; playing without audio")
                null
            }

        private fun hasDecoderFor(mime: String): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)

        /** AAC-ELD AudioSpecificConfig (AOT 39, 480-sample frames when negotiated). */
        private fun eldConfig(format: AirPlayAudioFormat): ByteArray {
            val frameLengthFlag = if (format.samplesPerFrame == 480) 1 else 0
            val bits = BitWriter()
            bits.write(31, 5)          // AOT escape
            bits.write(39 - 32, 6)     // AOT 39 (ER AAC ELD)
            bits.write(frequencyIndex(format.sampleRate), 4)
            bits.write(format.channels, 4)
            bits.write(frameLengthFlag, 1)
            bits.write(0, 3)           // resilience flags
            bits.write(0, 1)           // ldSbrPresentFlag
            bits.write(0, 4)           // ELDEXT_TERM
            return bits.toByteArray()
        }

        private fun aacLcConfig(format: AirPlayAudioFormat): ByteArray {
            val config = (2 shl 11) or (frequencyIndex(format.sampleRate) shl 7) or (format.channels shl 3)
            return byteArrayOf((config shr 8).toByte(), (config and 0xFF).toByte())
        }

        /** Apple ALACSpecificConfig "magic cookie" (24 bytes, big-endian). */
        private fun alacMagicCookie(format: AirPlayAudioFormat): ByteArray {
            val frameLength = if (format.samplesPerFrame > 0) format.samplesPerFrame else 352
            return ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(frameLength)                    // frameLength
                put(0.toByte())                        // compatibleVersion
                put(format.bitDepth.toByte())          // bitDepth
                put(40.toByte())                       // pb (rice history mult)
                put(10.toByte())                       // mb (initial history)
                put(14.toByte())                       // kb (rice param limit)
                put(format.channels.toByte())          // numChannels
                putShort(255.toShort())                // maxRun
                putInt(0)                              // maxFrameBytes (unknown)
                putInt(0)                              // avgBitRate (unknown)
                putInt(format.sampleRate)              // sampleRate
            }.array()
        }

        /** Opus csd: OpusHead, then codec-delay and seek-preroll as 8-byte LE nanos. */
        private fun opusConfig(format: AirPlayAudioFormat): List<ByteArray> {
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1.toByte())                        // version
                put(format.channels.toByte())          // channel count
                putShort(OPUS_PRE_SKIP_SAMPLES.toShort()) // pre-skip
                putInt(format.sampleRate)              // input sample rate
                putShort(0.toShort())                  // output gain
                put(0.toByte())                        // channel mapping family
            }.array()
            val codecDelayNs = OPUS_PRE_SKIP_SAMPLES * 1_000_000_000L / 48_000L
            val delay = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(codecDelayNs).array()
            val preRoll = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(OPUS_SEEK_PREROLL_NS).array()
            return listOf(head, delay, preRoll)
        }

        private fun frequencyIndex(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
    }
}
