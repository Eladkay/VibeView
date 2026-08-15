package com.eladkay.vibeview.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eladkay.vibeview.airplay.AirPlayAudioFormat
import com.eladkay.vibeview.airplay.AirPlayListener
import com.eladkay.vibeview.airplay.AirPlayServer
import com.eladkay.vibeview.airplay.CastState
import com.eladkay.vibeview.airplay.CastStatus
import com.eladkay.vibeview.dlna.DlnaRendererListener
import com.eladkay.vibeview.dlna.DlnaStatus
import com.eladkay.vibeview.dlna.TransportState
import com.eladkay.vibeview.media.AudioPlayer
import com.eladkay.vibeview.media.CastPlayerController
import com.eladkay.vibeview.media.Diagnostics
import com.eladkay.vibeview.media.VideoDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the TV should currently be showing. */
sealed interface ReceiverState {
    data object Idle : ReceiverState
    data object Mirroring : ReceiverState
    data class Casting(val url: String) : ReceiverState
    data class Photo(val jpeg: ByteArray) : ReceiverState {
        override fun equals(other: Any?) = other is Photo && jpeg.contentEquals(other.jpeg)
        override fun hashCode() = jpeg.contentHashCode()
    }
}

/** Info shown on the idle screen. */
data class ServerInfo(
    val deviceName: String,
    val hostAddress: String?,
    val running: Boolean,
    /** Passcode to display when the receiver requires one; null when open. */
    val passcode: String? = null,
)

/**
 * Process-wide bridge between the AirPlay server (Netty threads), the media pipeline,
 * and the UI. The service populates it; activities observe it.
 */
object ReceiverSessionHub : AirPlayListener, DlnaRendererListener {

    private const val TAG = "ReceiverSessionHub"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _serverInfo = MutableStateFlow(ServerInfo("VibeView", null, running = false))
    val serverInfo: StateFlow<ServerInfo> = _serverInfo.asStateFlow()

    /** Source dimensions of the mirrored video, for aspect-ratio fitting. */
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    @Volatile var videoDecoder: VideoDecoder? = null
        private set
    @Volatile private var audioPlayer: AudioPlayer? = null
    @Volatile private var castController: CastPlayerController? = null
    @Volatile private var server: AirPlayServer? = null
    @Volatile private var appContext: Context? = null
    @Volatile var audioEnabled: Boolean = true

    val castPlayer get() = castController?.player

    fun attach(context: Context, server: AirPlayServer) {
        this.appContext = context.applicationContext
        this.server = server
    }

    fun updateServerInfo(info: ServerInfo) {
        _serverInfo.value = info
    }

    fun shutdown() {
        stopMirrorPipeline()
        mainHandler.post { stopCastPipeline(notify = false) }
        server = null
        _state.value = ReceiverState.Idle
    }

    // ---- Mirroring (called on Netty threads) ----

    override fun onMirroringStarted() {
        Log.i(TAG, "Mirroring started")
        Diagnostics.reset()
        mainHandler.post { stopCastPipeline(notify = false) }
        videoDecoder?.release()
        videoDecoder = VideoDecoder { width, height -> _videoSize.value = width to height }
        _state.value = ReceiverState.Mirroring
        presentUi()
    }

    /**
     * Pulls the receiver UI forward. Only the activity owns a rendering surface, so a
     * session that starts while the app is backgrounded would otherwise decode into
     * nothing.
     */
    private fun presentUi() {
        appContext?.let { SessionLauncher.bringToForeground(it) }
    }

    override fun onVideoData(data: ByteArray) {
        videoDecoder?.enqueue(data)
    }

    override fun onVideoFormat(widthSource: Int, heightSource: Int, width: Int, height: Int) {
        if (widthSource > 0 && heightSource > 0) {
            _videoSize.value = widthSource to heightSource
        }
    }

    override fun onAudioFormat(format: AirPlayAudioFormat) {
        Log.i(TAG, "Audio format: $format")
        Diagnostics.audioFormat =
            "${format.compression} ${format.sampleRate}Hz ${format.channels}ch"
        audioPlayer?.release()
        audioPlayer = if (audioEnabled) AudioPlayer.create(format) else null
    }

    override fun onAudioData(frame: ByteArray) {
        audioPlayer?.enqueue(frame)
    }

    override fun onMirroringStopped() {
        Log.i(TAG, "Mirroring stopped")
        stopMirrorPipeline()
        if (_state.value is ReceiverState.Mirroring) {
            _state.value = ReceiverState.Idle
        }
    }

    private fun stopMirrorPipeline() {
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        _videoSize.value = null
    }

    // ---- Casting (called on Netty threads; ExoPlayer work hops to main) ----

    override fun onCastPlay(url: String, startPosition: Double) {
        Log.i(TAG, "Cast play: $url @ $startPosition")
        val context = appContext ?: return
        mainHandler.post {
            stopMirrorPipeline()
            val controller = castController ?: CastPlayerController(
                context,
                onState = { state -> server?.notifyCastState(state) },
            ).also { castController = it }
            controller.play(url, startPosition)
            _state.value = ReceiverState.Casting(url)
            presentUi()
        }
    }

    override fun onCastRate(rate: Float) {
        mainHandler.post { castController?.setRate(rate) }
    }

    override fun onCastSeek(positionSeconds: Double) {
        mainHandler.post { castController?.seekToSeconds(positionSeconds) }
    }

    override fun onCastStop() {
        Log.i(TAG, "Cast stop")
        mainHandler.post { stopCastPipeline(notify = false) }
    }

    override fun onPhoto(jpeg: ByteArray) {
        _state.value = ReceiverState.Photo(jpeg)
        presentUi()
    }

    override fun castStatus(): CastStatus = castController?.status ?: CastStatus()

    private fun stopCastPipeline(notify: Boolean) {
        castController?.let {
            if (notify) server?.notifyCastState(CastState.STOPPED)
            it.release()
        }
        castController = null
        if (_state.value is ReceiverState.Casting || _state.value is ReceiverState.Photo) {
            _state.value = ReceiverState.Idle
        }
    }

    /** User pressed back/stop on the TV side. */
    fun userDismissedPlayback() {
        mainHandler.post {
            server?.notifyCastState(CastState.STOPPED)
            stopCastPipeline(notify = false)
        }
    }

    // ---- DLNA renderer (called on Netty threads; reuses the cast pipeline) ----

    @Volatile private var castVolume = 100

    override fun onSetUri(uri: String, metadata: String?) = onCastPlay(uri, 0.0)

    override fun onPlay() = onCastRate(1f)

    override fun onPause() = onCastRate(0f)

    override fun onStop() = onCastStop()

    override fun onSeekSeconds(seconds: Double) = onCastSeek(seconds)

    override fun onSetVolume(volume: Int) {
        castVolume = volume.coerceIn(0, 100)
        mainHandler.post { castController?.player?.volume = castVolume / 100f }
    }

    override fun status(): DlnaStatus {
        val controller = castController ?: return DlnaStatus(state = TransportState.NO_MEDIA)
        val s = controller.status
        val url = (_state.value as? ReceiverState.Casting)?.url
        val state = when {
            s.readyToPlay && s.rate > 0f -> TransportState.PLAYING
            s.readyToPlay -> TransportState.PAUSED
            url != null -> TransportState.TRANSITIONING
            else -> TransportState.STOPPED
        }
        return DlnaStatus(
            state = state,
            durationSeconds = s.duration,
            positionSeconds = s.position,
            uri = url,
            volume = castVolume,
        )
    }
}
