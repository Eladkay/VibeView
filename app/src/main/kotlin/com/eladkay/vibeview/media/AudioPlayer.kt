package com.eladkay.vibeview.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.eladkay.vibeview.airplay.AirPlayAudioFormat
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the mirroring audio stream: decodes AAC-ELD/AAC-LC frames with MediaCodec and
 * writes PCM to an [AudioTrack]. PCM input plays directly. Unsupported codecs (ALAC,
 * Opus without config) are silently dropped — video keeps playing without audio.
 */
class AudioPlayer private constructor(
    private val format: AirPlayAudioFormat,
    private val decodeWithCodec: Boolean,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(true)
    private val thread = Thread({ playLoop() }, "AudioPlayer").apply { start() }

    fun enqueue(frame: ByteArray) {
        if (!running.get()) return
        while (!queue.offer(frame)) {
            queue.poll()
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
        try {
            track = createTrack()
            track.play()

            if (decodeWithCodec) {
                codec = createCodec()
                codec.start()
                decodeLoop(codec, track)
            } else {
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

    private fun decodeLoop(codec: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
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
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(pcm)
                        track.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun pcmLoop(track: AudioTrack) {
        while (running.get()) {
            val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            track.write(frame, 0, frame.size)
        }
    }

    private fun createTrack(): AudioTrack {
        val channelMask =
            if (format.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
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
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun createCodec(): MediaCodec {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val mediaFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels
        )
        val csd = when (format.compression) {
            AirPlayAudioFormat.Compression.AAC_ELD -> eldAudioSpecificConfig()
            else -> aacLcAudioSpecificConfig()
        }
        mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        mediaFormat.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        codec.configure(mediaFormat, null, null, 0)
        return codec
    }

    /**
     * AudioSpecificConfig for AAC-ELD: AOT 39 (5-bit escape 31 + 6-bit 7),
     * 4-bit sampling frequency index, 4-bit channel config, then ELDSpecificConfig
     * (frameLengthFlag=1 for 480 samples per frame, all resilience/SBR flags 0).
     */
    private fun eldAudioSpecificConfig(): ByteArray {
        val frameLengthFlag = if (format.samplesPerFrame == 480) 1 else 0
        val bits = BitWriter()
        bits.write(31, 5)
        bits.write(39 - 32, 6)
        bits.write(frequencyIndex(format.sampleRate), 4)
        bits.write(format.channels, 4)
        bits.write(frameLengthFlag, 1)
        bits.write(0, 3) // section/scalefactor/spectral data resilience flags
        bits.write(0, 1) // ldSbrPresentFlag
        bits.write(0, 4) // ELDEXT_TERM
        return bits.toByteArray()
    }

    private fun aacLcAudioSpecificConfig(): ByteArray {
        val config = (2 shl 11) or (frequencyIndex(format.sampleRate) shl 7) or (format.channels shl 3)
        return byteArrayOf((config shr 8).toByte(), (config and 0xFF).toByte())
    }

    private fun frequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
        24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
        else -> 4
    }

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
            if (bitCount > 0) {
                out.add((current shl (8 - bitCount)).toByte())
            }
            return out.toByteArray()
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val QUEUE_CAPACITY = 256
        private const val INPUT_TIMEOUT_US = 20_000L

        /** Creates a player for the negotiated format, or null when it can't be played. */
        fun create(format: AirPlayAudioFormat): AudioPlayer? = when (format.compression) {
            AirPlayAudioFormat.Compression.AAC_ELD,
            AirPlayAudioFormat.Compression.AAC_LC ->
                AudioPlayer(format, decodeWithCodec = true)
            AirPlayAudioFormat.Compression.PCM ->
                AudioPlayer(format, decodeWithCodec = false)
            else -> {
                Log.w(TAG, "Unsupported audio codec ${format.compression}; playing without audio")
                null
            }
        }
    }
}
