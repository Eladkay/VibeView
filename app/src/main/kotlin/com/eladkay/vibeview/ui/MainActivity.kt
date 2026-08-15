package com.eladkay.vibeview.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import com.eladkay.vibeview.R
import com.eladkay.vibeview.service.AirPlayService
import com.eladkay.vibeview.service.ReceiverSessionHub
import com.eladkay.vibeview.service.ReceiverState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var idleGroup: View
    private lateinit var surfaceView: SurfaceView
    private lateinit var playerView: PlayerView
    private lateinit var photoView: ImageView
    private lateinit var deviceNameView: TextView
    private lateinit var statusView: TextView
    private lateinit var instructionsView: TextView

    private var surfaceReady = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            ReceiverSessionHub.videoDecoder?.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            }
        }

        AirPlayService.start(this)
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

        when (state) {
            is ReceiverState.Mirroring -> {
                if (surfaceReady) {
                    ReceiverSessionHub.videoDecoder?.attachSurface(surfaceView.holder.surface)
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
        statusView.text = when {
            !info.running -> getString(R.string.status_starting)
            info.hostAddress != null -> getString(R.string.status_ready, info.hostAddress)
            else -> getString(R.string.status_no_network)
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
