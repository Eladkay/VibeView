package com.eladkay.vibeview.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
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
 *
 * Dropping a frame is expensive here: H.264 inter-frames depend on their predecessors,
 * and a mirroring sender may not emit another keyframe for many seconds, so a single
 * discarded frame can freeze the picture until it does. The input path therefore works
 * hard to hand every frame to the decoder rather than giving up after one attempt.
 *
 * Catching up is done on the *output* side instead. Presenting a frame is paced by the
 * display, so a 60 fps sender on a 60 Hz panel has no headroom: any backlog the pipeline
 * picks up — a network burst, a busy moment on the sender — would otherwise stay for the
 * rest of the session and keep growing until the queue overflowed. Releasing a decoded
 * frame without presenting it costs nothing (references stay intact) and is what lets the
 * stream return to live, so while the queue is deep only the newest frames are shown.
 *
 * @param trace receives protocol-level notes for the diagnostics overlay and logcat
 */
class VideoDecoder(
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val trace: (String) -> Unit = {},
) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val resyncRequested = AtomicBoolean(false)

    @Volatile private var surface: Surface? = null
    @Volatile private var lastConfig: ByteArray? = null
    private var thread: Thread? = null

    private var droppedInput = 0L
    private var decodedFrames = 0L
    private var skippedRenders = 0L
    private var resyncs = 0L

    fun enqueue(data: ByteArray) {
        if (released.get()) return
        if (isConfigChunk(data)) {
            lastConfig = data
        }
        Diagnostics.onVideoFrameReceived(data.size)
        if (!queue.offer(data)) {
            // The decoder is a full queue behind and skipping presentation has not been
            // enough to catch up. Handing it a stream with a hole in it produces a smear
            // of broken references for as long as the hole is referenced, so drop the
            // whole backlog and re-enter cleanly at the sender's next keyframe.
            val discarded = queue.size
            queue.clear()
            Diagnostics.onVideoFramesDropped(discarded)
            resyncRequested.set(true)
            queue.offer(data)
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
        droppedInput = 0
        decodedFrames = 0
        skippedRenders = 0
        resyncs = 0
        // Feeding and presenting frames is latency-critical; without this the decode
        // thread competes with the network and UI threads at plain default priority.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        try {
            val outputSurface = surface ?: return
            codec = MediaCodec.createDecoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, DEFAULT_WIDTH, DEFAULT_HEIGHT)
            // Prioritize latency over throughput: realtime priority, a realistic
            // operating-rate hint, and (API 30+) the decoder's low-latency mode, which
            // disables frame reordering so frames surface as soon as they are decoded.
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, TARGET_FRAME_RATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, outputSurface, null, 0)
            codec.start()
            val codecName = runCatching { codec.name }.getOrDefault("h264")
            Diagnostics.videoCodec = codecName
            trace("   decoder started ($codecName)")

            var configSent = false
            var sawKeyframe = false
            var waitingLogged = false
            val pendingConfig = lastConfig

            if (pendingConfig != null) {
                configSent = submit(codec, pendingConfig, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }

            while (running.get() && !released.get()) {
                if (resyncRequested.compareAndSet(true, false)) {
                    resyncs++
                    sawKeyframe = false
                    waitingLogged = false
                    if (resyncs == 1L) {
                        trace("  !! input backlog overflowed; resyncing at the next keyframe")
                    }
                }
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    val isConfig = isConfigChunk(chunk)
                    if (isConfig) {
                        val sent = submit(codec, chunk, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        if (sent && !configSent) trace("   SPS/PPS accepted by decoder")
                        configSent = sent || configSent
                    } else if (configSent) {
                        if (!sawKeyframe && !containsKeyframe(chunk)) {
                            if (!waitingLogged) {
                                waitingLogged = true
                                trace("   waiting for a keyframe…")
                            }
                            drainOutputs(codec)
                            continue
                        }
                        if (!sawKeyframe) trace("   keyframe found, decoding")
                        sawKeyframe = true
                        if (!submit(codec, chunk, 0)) {
                            // The frame never reached the decoder, so everything that
                            // references it would decode into a smear. Wait it out.
                            sawKeyframe = false
                            waitingLogged = false
                        }
                    }
                }
                drainOutputs(codec)
            }
        } catch (_: InterruptedException) {
            // normal shutdown
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder failed", e)
            trace(
                "  !! decoder CodecException: ${e.message} " +
                    "(recoverable=${e.isRecoverable} transient=${e.isTransient}) ${e.diagnosticInfo}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Decoder failed", e)
            trace("  !! decoder error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            trace(
                "── decoder stopped after $decodedFrames frames " +
                    "($droppedInput input drops, $skippedRenders skipped, $resyncs resyncs)"
            )
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /**
     * Hands a chunk to the decoder, draining outputs while waiting for an input buffer.
     * Returns false only if the decoder stayed full for [SUBMIT_BUDGET_US], which costs
     * a frame and, with it, everything up to the next keyframe.
     */
    private fun submit(codec: MediaCodec, data: ByteArray, flags: Int): Boolean {
        var waited = 0L
        while (waited < SUBMIT_BUDGET_US && running.get() && !released.get()) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    return false
                }
                buffer.clear()
                if (data.size > buffer.remaining()) {
                    Log.w(TAG, "Frame larger than input buffer (${data.size})")
                    trace("  !! frame ${data.size}B exceeds decoder input buffer")
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    return false
                }
                buffer.put(data)
                codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, flags)
                return true
            }
            // No input buffer yet: releasing decoded frames is what frees them up.
            drainOutputs(codec)
            waited += INPUT_TIMEOUT_US
        }
        droppedInput++
        if (droppedInput == 1L) {
            trace("  !! decoder input starved; frames will drop until the next keyframe")
        }
        return false
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
                        trace("   decoder output format ${width}x$height")
                        onVideoSize(width, height)
                        Diagnostics.videoWidth = width
                        Diagnostics.videoHeight = height
                    }
                }
                else -> if (index >= 0) {
                    // Presenting is vsync-paced, so while frames are stacking up behind
                    // this one, showing it would only hold the backlog in place. Skip
                    // straight to the newest picture instead — the decoder has already
                    // done the work and the reference chain is unaffected.
                    val render = info.size > 0 && queue.size < CATCHUP_DEPTH
                    if (info.size > 0) {
                        // presentationTimeUs was stamped with nanoTime/1000 on input,
                        // so this is the decoder's own input-to-output latency.
                        Diagnostics.onVideoFrameDecoded(System.nanoTime() / 1000 - info.presentationTimeUs)
                        decodedFrames++
                        if (!render) {
                            skippedRenders++
                            Diagnostics.onVideoRenderSkipped()
                            if (skippedRenders == 1L) trace("   behind: showing only the newest frames")
                        }
                        if (decodedFrames == 1L) trace("   first frame rendered")
                        else if (decodedFrames % FRAME_REPORT_INTERVAL == 0L) {
                            trace("   $decodedFrames frames ($skippedRenders skipped, $resyncs resyncs)")
                        }
                    }
                    codec.releaseOutputBuffer(index, render)
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

        // Headroom to ride out a network burst without discarding frames, which would
        // break decoding until the sender's next keyframe — but no more than that, since
        // a queued frame the decoder has not reached yet is latency the viewer feels.
        // At 60 fps this bounds the input backlog at ~0.4 s.
        private const val QUEUE_CAPACITY = 24

        /** Backlog at which frames are decoded but no longer presented, to catch up. */
        private const val CATCHUP_DEPTH = 3

        private const val INPUT_TIMEOUT_US = 5_000L

        /**
         * Total time to wait for a decoder input buffer before giving up on a frame.
         * Long enough to outlast a stall, short enough that the wait itself does not
         * fill the queue behind it.
         */
        private const val SUBMIT_BUDGET_US = 150_000L
        private const val TARGET_FRAME_RATE = 60
        private const val FRAME_REPORT_INTERVAL = 300L
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
