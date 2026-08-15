package com.eladkay.vibeview.media

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight counters for the on-screen diagnostics HUD.
 *
 * The media pipeline updates these from its own threads; the UI samples once a
 * second and turns cumulative counters into rates. Everything is plain atomics so
 * recording stays cheap enough to leave on in a live session.
 */
object Diagnostics {

    private val framesReceived = AtomicLong()
    private val framesDecoded = AtomicLong()
    private val framesDropped = AtomicLong()
    private val renderSkips = AtomicLong()
    private val videoBytes = AtomicLong()
    private val audioFramesReceived = AtomicLong()
    private val audioFramesDecoded = AtomicLong()
    private val audioFramesDropped = AtomicLong()

    private val queueDepth = AtomicInteger()

    @Volatile private var decoderLatencyMs = 0.0
    @Volatile var videoCodec: String = "—"
    @Volatile var videoWidth: Int = 0
    @Volatile var videoHeight: Int = 0
    @Volatile var audioFormat: String = "—"

    /** Recent protocol events, newest last, for the on-screen handshake trace. */
    private val trace = ArrayDeque<String>()
    private const val TRACE_LIMIT = 14

    @Synchronized
    fun addTrace(message: String) {
        trace.addLast(message)
        while (trace.size > TRACE_LIMIT) trace.removeFirst()
    }

    @Synchronized
    fun traceLines(): List<String> = trace.toList()

    private var lastSampleNanos = 0L
    private var lastFramesReceived = 0L
    private var lastFramesDecoded = 0L
    private var lastFramesDropped = 0L
    private var lastRenderSkips = 0L
    private var lastVideoBytes = 0L

    fun onVideoFrameReceived(bytes: Int) {
        framesReceived.incrementAndGet()
        videoBytes.addAndGet(bytes.toLong())
    }

    /** Frames thrown away before the decoder saw them: the picture breaks until the next IDR. */
    fun onVideoFramesDropped(count: Int) {
        if (count > 0) framesDropped.addAndGet(count.toLong())
    }

    /**
     * A decoded frame released without presenting it. Unlike a drop this is free —
     * references stay intact and only the display rate dips while catching up.
     */
    fun onVideoRenderSkipped() {
        renderSkips.incrementAndGet()
    }

    /** @param latencyMicros time from feeding the decoder to the frame being ready */
    fun onVideoFrameDecoded(latencyMicros: Long) {
        framesDecoded.incrementAndGet()
        if (latencyMicros in 0..2_000_000) {
            // Exponential moving average keeps the readout stable but responsive.
            val ms = latencyMicros / 1000.0
            decoderLatencyMs = if (decoderLatencyMs == 0.0) ms else decoderLatencyMs * 0.9 + ms * 0.1
        }
    }

    fun onAudioFrameReceived() = audioFramesReceived.incrementAndGet()

    fun onAudioFrameDecoded() = audioFramesDecoded.incrementAndGet()

    /** Audio discarded to stop the backlog — and with it the lag — from growing. */
    fun onAudioFrameDropped() = audioFramesDropped.incrementAndGet()

    fun setQueueDepth(depth: Int) = queueDepth.set(depth)

    /** Clears media counters for a new session; the protocol trace is kept. */
    fun reset() {
        framesReceived.set(0)
        framesDecoded.set(0)
        framesDropped.set(0)
        renderSkips.set(0)
        videoBytes.set(0)
        audioFramesReceived.set(0)
        audioFramesDecoded.set(0)
        audioFramesDropped.set(0)
        queueDepth.set(0)
        decoderLatencyMs = 0.0
        videoCodec = "—"
        videoWidth = 0
        videoHeight = 0
        audioFormat = "—"
        lastSampleNanos = 0
        lastFramesReceived = 0
        lastFramesDecoded = 0
        lastFramesDropped = 0
        lastRenderSkips = 0
        lastVideoBytes = 0
    }

    /**
     * Rates since the previous call, formatted for the HUD. Call about once a second
     * from a single thread (the UI).
     */
    fun sample(): String {
        val now = System.nanoTime()
        val elapsed = if (lastSampleNanos == 0L) 1.0 else (now - lastSampleNanos) / 1e9
        lastSampleNanos = now

        val received = framesReceived.get()
        val decoded = framesDecoded.get()
        val dropped = framesDropped.get()
        val skipped = renderSkips.get()
        val bytes = videoBytes.get()

        val inFps = ((received - lastFramesReceived) / elapsed).coerceAtLeast(0.0)
        val decodeFps = ((decoded - lastFramesDecoded) / elapsed).coerceAtLeast(0.0)
        val skipRate = ((skipped - lastRenderSkips) / elapsed).coerceAtLeast(0.0)
        val dropRate = ((dropped - lastFramesDropped) / elapsed).coerceAtLeast(0.0)
        val kbps = ((bytes - lastVideoBytes) * 8 / 1000.0 / elapsed).coerceAtLeast(0.0)

        lastFramesReceived = received
        lastFramesDecoded = decoded
        lastFramesDropped = dropped
        lastRenderSkips = skipped
        lastVideoBytes = bytes

        val resolution = if (videoWidth > 0) "${videoWidth}x$videoHeight" else "—"
        val audioDropped = audioFramesDropped.get()
        return buildString {
            append("video  ").append(videoCodec).append("  ").append(resolution).append('\n')
            // "shown" is what reaches the panel; the gap to "decoded" is catch-up.
            append("fps    in %.1f / shown %.1f".format(inFps, (decodeFps - skipRate).coerceAtLeast(0.0)))
            if (skipRate > 0) append("  skipped %.1f/s".format(skipRate))
            if (dropRate > 0) append("  dropped %.1f/s".format(dropRate))
            append('\n')
            append("rate   %.0f kbps\n".format(kbps))
            append("lat    %.1f ms decode   queue %d\n".format(decoderLatencyMs, queueDepth.get()))
            append("audio  ").append(audioFormat)
            append("  frames ").append(audioFramesDecoded.get())
            append('/').append(audioFramesReceived.get())
            if (audioDropped > 0) append(" (-").append(audioDropped).append(')')
            if (dropped > 0 || skipped > 0) {
                append("\ntotal  dropped ").append(dropped).append(", skipped ").append(skipped)
            }
        }
    }
}
