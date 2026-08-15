package com.eladkay.vibeview.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import com.eladkay.vibeview.Prefs
import com.eladkay.vibeview.R
import com.eladkay.vibeview.media.Diagnostics
import com.eladkay.vibeview.service.AirPlayService
import com.eladkay.vibeview.service.NowPlaying
import com.eladkay.vibeview.service.ReceiverSessionHub
import com.eladkay.vibeview.service.ReceiverState
import com.eladkay.vibeview.service.SessionLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var idleGroup: View
    private lateinit var surfaceView: SurfaceView
    private lateinit var playerView: PlayerView
    private lateinit var photoView: ImageView
    private lateinit var deviceNameView: TextView
    private lateinit var statusView: TextView
    private lateinit var instructionsView: TextView
    private lateinit var passcodeView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var nowPlayingGroup: View
    private lateinit var nowPlayingArtwork: ImageView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var nowPlayingAlbum: TextView
    private lateinit var nowPlayingProgress: ProgressBar
    private lateinit var nowPlayingTime: TextView

    private var surfaceReady = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            val decoder = ReceiverSessionHub.videoDecoder
            ReceiverSessionHub.onProtocolEvent(
                if (decoder != null) "   surface ready → decoder attached" else "   surface ready (no decoder yet)"
            )
            decoder?.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            // Worth seeing in the trace: this stops the decoder, and restarting it means
            // waiting for the sender's next keyframe before the picture returns.
            ReceiverSessionHub.onProtocolEvent("── surface destroyed, decoder detached")
            ReceiverSessionHub.videoDecoder?.detachSurface()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        idleGroup = findViewById(R.id.idle_group)
        surfaceView = findViewById(R.id.mirror_surface)
        playerView = findViewById(R.id.cast_player)
        photoView = findViewById(R.id.photo_view)
        deviceNameView = findViewById(R.id.device_name)
        statusView = findViewById(R.id.status_line)
        instructionsView = findViewById(R.id.instructions)
        passcodeView = findViewById(R.id.passcode_line)
        diagnosticsView = findViewById(R.id.diagnostics_overlay)
        nowPlayingGroup = findViewById(R.id.now_playing_group)
        nowPlayingArtwork = findViewById(R.id.now_playing_artwork)
        nowPlayingTitle = findViewById(R.id.now_playing_title)
        nowPlayingArtist = findViewById(R.id.now_playing_artist)
        nowPlayingAlbum = findViewById(R.id.now_playing_album)
        nowPlayingProgress = findViewById(R.id.now_playing_progress)
        nowPlayingTime = findViewById(R.id.now_playing_time)

        // A session can arrive while the TV is asleep or showing another app, so the
        // receiver screen must be able to wake the display and come forward itself.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        surfaceView.holder.addCallback(surfaceCallback)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ReceiverSessionHub.state.collect(::render) }
                launch { ReceiverSessionHub.serverInfo.collect { renderServerInfo() } }
                launch { ReceiverSessionHub.videoSize.collect { it?.let(::fitSurface) } }
                launch { ReceiverSessionHub.nowPlaying.collect(::renderNowPlaying) }
                launch { runDiagnosticsLoop() }
            }
        }

        AirPlayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // The UI is up, so the full-screen-intent fallback has served its purpose.
        SessionLauncher.clear(this)
    }

    override fun onDestroy() {
        surfaceView.holder.removeCallback(surfaceCallback)
        super.onDestroy()
    }

    private fun render(state: ReceiverState) {
        idleGroup.visibility = if (state is ReceiverState.Idle) View.VISIBLE else View.GONE
        surfaceView.visibility = if (state is ReceiverState.Mirroring) View.VISIBLE else View.GONE
        playerView.visibility = if (state is ReceiverState.Casting) View.VISIBLE else View.GONE
        photoView.visibility = if (state is ReceiverState.Photo) View.VISIBLE else View.GONE
        nowPlayingGroup.visibility = if (state is ReceiverState.AudioOnly) View.VISIBLE else View.GONE

        when (state) {
            is ReceiverState.AudioOnly -> Unit // populated by the nowPlaying collector
            is ReceiverState.Mirroring -> {
                if (surfaceReady) {
                    ReceiverSessionHub.onProtocolEvent("   mirroring UI shown, attaching surface")
                    ReceiverSessionHub.videoDecoder?.attachSurface(surfaceView.holder.surface)
                } else {
                    // Surface is created once the view becomes visible; the callback attaches it.
                    ReceiverSessionHub.onProtocolEvent("   mirroring UI shown, waiting for surface")
                }
            }
            is ReceiverState.Casting -> playerView.player = ReceiverSessionHub.castPlayer
            is ReceiverState.Photo -> {
                val bitmap = BitmapFactory.decodeByteArray(state.jpeg, 0, state.jpeg.size)
                if (bitmap != null) photoView.setImageBitmap(bitmap)
            }
            is ReceiverState.Idle -> {
                playerView.player = null
                photoView.setImageDrawable(null)
            }
        }
    }

    private fun renderServerInfo() {
        val info = ReceiverSessionHub.serverInfo.value
        deviceNameView.text = info.deviceName
        instructionsView.text = getString(R.string.idle_instructions, info.deviceName)
        val passcode = info.passcode
        if (passcode != null) {
            passcodeView.visibility = View.VISIBLE
            passcodeView.text = getString(R.string.passcode_prompt, passcode)
        } else {
            passcodeView.visibility = View.GONE
        }
        statusView.text = when {
            !info.running -> getString(R.string.status_starting)
            info.hostAddress != null -> getString(R.string.status_ready, info.hostAddress)
            else -> getString(R.string.status_no_network)
        }
    }

    private fun renderNowPlaying(nowPlaying: NowPlaying) {
        nowPlayingTitle.text = nowPlaying.title ?: getString(R.string.now_playing_unknown_track)
        nowPlayingArtist.text = nowPlaying.artist.orEmpty()
        nowPlayingArtist.visibility = if (nowPlaying.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        nowPlayingAlbum.text = nowPlaying.album.orEmpty()
        nowPlayingAlbum.visibility = if (nowPlaying.album.isNullOrBlank()) View.GONE else View.VISIBLE

        val artwork = nowPlaying.artwork
        if (artwork != null) {
            val bitmap = BitmapFactory.decodeByteArray(artwork, 0, artwork.size)
            if (bitmap != null) nowPlayingArtwork.setImageBitmap(bitmap)
        } else {
            nowPlayingArtwork.setImageDrawable(null)
        }

        val duration = nowPlaying.durationSeconds
        val hasProgress = duration > 0
        nowPlayingProgress.visibility = if (hasProgress) View.VISIBLE else View.INVISIBLE
        nowPlayingTime.visibility = if (hasProgress) View.VISIBLE else View.INVISIBLE
        if (hasProgress) {
            val fraction = (nowPlaying.positionSeconds / duration).coerceIn(0.0, 1.0)
            nowPlayingProgress.progress = (fraction * nowPlayingProgress.max).toInt()
            nowPlayingTime.text = getString(
                R.string.now_playing_time,
                formatClock(nowPlaying.positionSeconds),
                formatClock(duration),
            )
        }
    }

    private fun formatClock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /**
     * Refreshes the diagnostics HUD once a second while it's enabled. Runs only while
     * the activity is STARTED, so it costs nothing in the background.
     */
    private suspend fun runDiagnosticsLoop() {
        while (true) {
            if (Prefs.showDiagnostics(this)) {
                // Shown while idle too: the pairing handshake happens before any
                // session starts, and that trace is the point of the overlay.
                diagnosticsView.visibility = View.VISIBLE
                val active = ReceiverSessionHub.state.value !is ReceiverState.Idle
                val trace = Diagnostics.traceLines()
                diagnosticsView.text = buildString {
                    if (active) append(Diagnostics.sample()).append('\n')
                    if (trace.isEmpty()) {
                        append(getString(R.string.diagnostics_waiting))
                    } else {
                        append(trace.joinToString("\n"))
                    }
                }
            } else if (diagnosticsView.visibility != View.GONE) {
                diagnosticsView.visibility = View.GONE
            }
            delay(1000)
        }
    }

    /** Sizes the mirror surface to the video's aspect ratio, letterboxed on the screen. */
    private fun fitSurface(size: Pair<Int, Int>) {
        val (videoWidth, videoHeight) = size
        if (videoWidth <= 0 || videoHeight <= 0) return
        val parent = surfaceView.parent as? ViewGroup ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth == 0 || parentHeight == 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight
        val parentAspect = parentWidth.toFloat() / parentHeight
        val (w, h) = if (videoAspect > parentAspect) {
            parentWidth to (parentWidth / videoAspect).toInt()
        } else {
            (parentHeight * videoAspect).toInt() to parentHeight
        }
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (params.width != w || params.height != h) {
            params.width = w
            params.height = h
            surfaceView.layoutParams = params
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val state = ReceiverSessionHub.state.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                if (state is ReceiverState.Idle) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (state is ReceiverState.Casting || state is ReceiverState.Photo) {
                    ReceiverSessionHub.userDismissedPlayback()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (state is ReceiverState.Casting) {
                    ReceiverSessionHub.castPlayer?.let { it.playWhenReady = !it.playWhenReady }
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
