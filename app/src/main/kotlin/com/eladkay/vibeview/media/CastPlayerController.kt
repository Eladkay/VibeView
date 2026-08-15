package com.eladkay.vibeview.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.eladkay.vibeview.airplay.CastState
import com.eladkay.vibeview.airplay.CastStatus

/**
 * Wraps ExoPlayer for AirPlay video casting. All methods must run on the main thread.
 * [status] is safe to read from any thread; it is refreshed periodically so the
 * receiver can answer `/scrub` and `/playback-info` without touching the player.
 */
class CastPlayerController(
    context: Context,
    private val onState: (CastState) -> Unit,
) {
    // Small playback buffer so casting starts quickly after /play instead of
    // pre-buffering several seconds first.
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 30_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .build()

    @Volatile var status: CastStatus = CastStatus()
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStartFraction = 0.0
    private var startPositionApplied = true
    private var released = false

    private val statusUpdater = object : Runnable {
        override fun run() {
            if (released) return
            refreshStatus()
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        applyPendingStartPosition()
                        onState(if (player.playWhenReady) CastState.PLAYING else CastState.PAUSED)
                    }
                    Player.STATE_BUFFERING -> onState(CastState.LOADING)
                    Player.STATE_ENDED -> onState(CastState.STOPPED)
                    Player.STATE_IDLE -> Unit
                }
                refreshStatus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_READY) {
                    onState(if (isPlaying) CastState.PLAYING else CastState.PAUSED)
                }
                refreshStatus()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Cast playback error", error)
                onState(CastState.STOPPED)
            }
        })
        handler.post(statusUpdater)
    }

    @JvmOverloads
    fun play(url: String, startFraction: Double, subtitleUrls: List<String> = emptyList()) {
        pendingStartFraction = startFraction
        startPositionApplied = startFraction <= 0.0
        onState(CastState.LOADING)
        player.setMediaItem(buildMediaItem(url, subtitleUrls))
        player.playWhenReady = true
        player.prepare()
    }

    /** Queues the item to play when the current one finishes; null clears the queue. */
    fun setNext(url: String?, subtitleUrls: List<String> = emptyList()) {
        // Index 0 is the current item, so anything beyond it is the pending "next".
        while (player.mediaItemCount > 1) {
            player.removeMediaItem(player.mediaItemCount - 1)
        }
        if (url != null) player.addMediaItem(buildMediaItem(url, subtitleUrls))
    }

    private fun buildMediaItem(url: String, subtitleUrls: List<String>): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (subtitleUrls.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitleUrls.map { subtitleUrl ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setMimeType(mimeTypeFor(subtitleUrl))
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
            )
        }
        return builder.build()
    }

    private fun mimeTypeFor(url: String): String =
        when (url.substringAfterLast('.', "").substringBefore('?').lowercase()) {
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ssa", "ass" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

    fun setRate(rate: Float) {
        player.playWhenReady = rate > 0f
    }

    fun seekToSeconds(seconds: Double) {
        player.seekTo((seconds * 1000).toLong())
    }

    fun release() {
        released = true
        handler.removeCallbacks(statusUpdater)
        player.release()
        status = CastStatus()
    }

    private fun applyPendingStartPosition() {
        if (startPositionApplied) return
        val duration = player.duration
        if (duration > 0) {
            player.seekTo((pendingStartFraction * duration).toLong())
        }
        startPositionApplied = true
    }

    private fun refreshStatus() {
        val duration = player.duration
        status = CastStatus(
            duration = if (duration > 0) duration / 1000.0 else 0.0,
            position = player.currentPosition / 1000.0,
            rate = if (player.isPlaying) 1f else 0f,
            readyToPlay = player.playbackState == Player.STATE_READY,
        )
    }

    companion object {
        private const val TAG = "CastPlayerController"
        private const val STATUS_INTERVAL_MS = 500L
    }
}
