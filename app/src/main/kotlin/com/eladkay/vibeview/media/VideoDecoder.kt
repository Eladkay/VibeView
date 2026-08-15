package com.eladkay.vibeview.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes the Annex-B H.264 mirror stream into a [Surface] using a hardware MediaCodec.
 *
 * Frames may arrive before the UI provides a surface; they are buffered (bounded) and
 * the last parameter-set chunk is cached so decoding can (re)start at any time. After
 * a codec (re)start, delivery is gated on a keyframe to avoid feeding the decoder
 * mid-GOP garbage.
 */
class VideoDecoder(
    private val onVideoSize: (width: Int, height: Int) -> Unit,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile private var surface: Surface? = null
    @Volatile private var lastConfig: ByteArray? = null
    private var thread: Thread? = null

    fun enqueue(data: ByteArray) {
        if (released.get()) return
        if (isConfigChunk(data)) {
            lastConfig = data
        }
        Diagnostics.onVideoFrameReceived(data.size)
        while (!queue.offer(data)) {
            queue.poll() // drop oldest under pressure; decoder re-syncs on next IDR
            Diagnostics.onVideoFrameDropped()
        }
        Diagnostics.setQueueDepth(queue.size)
    }

    @Synchronized
    fun attachSurface(surface: Surface) {
        if (released.get()) return
        this.surface = surface
        if (running.compareAndSet(false, true)) {
            thread = Thread({ decodeLoop() }, "VideoDecoder").apply { start() }
        }
    }

    @Synchronized
    fun detachSurface() {
        surface = null
        stopThread()
    }

    fun release() {
        released.set(true)
        stopThread()
        queue.clear()
    }

    private fun stopThread() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            val outputSurface = surface ?: return
            codec = MediaCodec.createDecoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, DEFAULT_WIDTH, DEFAULT_HEIGHT)
            // Prioritize latency over throughput: realtime priority, a high operating
            // rate hint, and (API 30+) the decoder's dedicated low-latency mode, which
            // disables frame reordering/buffering so frames surface as soon as decoded.
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, outputSurface, null, 0)
            codec.start()
            Diagnostics.videoCodec = runCatching { codec.name }.getOrDefault("h264")

            var configSent = false
            var sawKeyframe = false
            val pendingConfig = lastConfig

            if (pendingConfig != null) {
                configSent = submit(codec, pendingConfig, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }

            while (running.get() && !released.get()) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    val isConfig = isConfigChunk(chunk)
                    if (isConfig) {
                        configSent = submit(codec, chunk, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) || configSent
                    } else if (configSent) {
                        if (!sawKeyframe && !containsKeyframe(chunk)) {
                            drainOutputs(codec)
                            continue
                        }
                        sawKeyframe = true
                        submit(codec, chunk, 0)
                    }
                }
                drainOutputs(codec)
            }
        } catch (_: InterruptedException) {
            // normal shutdown
        } catch (e: Exception) {
            Log.e(TAG, "Decoder failed", e)
        } finally {
            runCatching {
                codec?.stop()
            }
            runCatching { codec?.release() }
        }
    }

    private fun submit(codec: MediaCodec, data: ByteArray, flags: Int): Boolean {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        buffer.clear()
        if (data.size > buffer.remaining()) {
            Log.w(TAG, "Frame larger than input buffer (${data.size}), dropping")
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            return false
        }
        buffer.put(data)
        codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, flags)
        return true
    }

    private fun drainOutputs(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val width = cropped(format, MediaFormat.KEY_WIDTH, "crop-left", "crop-right")
                    val height = cropped(format, MediaFormat.KEY_HEIGHT, "crop-top", "crop-bottom")
                    if (width > 0 && height > 0) {
                        onVideoSize(width, height)
                        Diagnostics.videoWidth = width
                        Diagnostics.videoHeight = height
                    }
                }
                else -> if (index >= 0) {
                    if (info.size > 0) {
                        // presentationTimeUs was stamped with nanoTime/1000 on input,
                        // so this is the decoder's own input-to-output latency.
                        Diagnostics.onVideoFrameDecoded(System.nanoTime() / 1000 - info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, info.size > 0)
                    Diagnostics.setQueueDepth(queue.size)
                }
            }
        }
    }

    private fun cropped(format: MediaFormat, sizeKey: String, lowKey: String, highKey: String): Int {
        return if (format.containsKey(lowKey) && format.containsKey(highKey)) {
            format.getInteger(highKey) - format.getInteger(lowKey) + 1
        } else {
            runCatching { format.getInteger(sizeKey) }.getOrDefault(0)
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        // Small backlog: in steady state the decoder keeps the queue near empty, so
        // this mainly bounds catch-up lag after a network burst — drop-oldest re-syncs
        // on the next keyframe rather than replaying seconds of stale frames.
        private const val QUEUE_CAPACITY = 12
        private const val INPUT_TIMEOUT_US = 20_000L
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080

        /** True if the chunk starts with an SPS NAL unit (parameter-set/config chunk). */
        private fun isConfigChunk(data: ByteArray): Boolean {
            val offset = startCodeLength(data, 0)
            if (offset <= 0 || offset >= data.size) return false
            return (data[offset].toInt() and 0x1F) == 7
        }

        /** Scans NAL start codes for an IDR slice (type 5). */
        private fun containsKeyframe(data: ByteArray): Boolean {
            var i = 0
            while (i < data.size - 4) {
                val scLen = startCodeLength(data, i)
                if (scLen > 0) {
                    val nalType = data[i + scLen].toInt() and 0x1F
                    if (nalType == 5) return true
                    i += scLen
                } else {
                    i++
                }
            }
            return false
        }

        private fun startCodeLength(data: ByteArray, offset: Int): Int {
            if (offset + 4 <= data.size &&
                data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()
            ) return 4
            if (offset + 3 <= data.size &&
                data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 1.toByte()
            ) return 3
            return 0
        }
    }
}
