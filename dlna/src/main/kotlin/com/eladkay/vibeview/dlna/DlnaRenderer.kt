package com.eladkay.vibeview.dlna

import com.eladkay.vibeview.dlna.internal.GenaSubscriptions
import com.eladkay.vibeview.dlna.internal.SsdpResponder
import com.eladkay.vibeview.dlna.internal.UpnpDevice
import com.eladkay.vibeview.dlna.internal.UpnpHttpServer
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import org.slf4j.LoggerFactory
import java.net.InetAddress

/**
 * A UPnP/DLNA MediaRenderer (DMR). Advertises itself over SSDP and serves the UPnP
 * device/service descriptions and SOAP control endpoints, so any control point that
 * can "Play to" / "Cast to TV" — Android apps, VLC, Windows, Plex — can push a media
 * URL that the host app plays.
 *
 * This handles media-URL casting only, not live screen mirroring (Android's Cast and
 * Miracast receiver stacks are not available to a third-party app).
 */
class DlnaRenderer(
    private val config: DlnaConfig,
    private val listener: DlnaRendererListener,
) {
    private val eventLoopGroup = NioEventLoopGroup(2)
    private val subscriptions = GenaSubscriptions()
    private var httpChannel: Channel? = null
    private var ssdp: SsdpResponder? = null

    @Volatile private var started = false

    /**
     * Binds the HTTP control server and starts SSDP advertising on [address].
     * Blocks briefly; do not call from a UI thread.
     */
    @Throws(Exception::class)
    fun start(address: InetAddress) {
        check(!started) { "DlnaRenderer already started" }
        started = true
        try {
            val device = UpnpDevice(config, address)
            httpChannel = UpnpHttpServer.start(eventLoopGroup, config.httpPort, device, listener, subscriptions)
            ssdp = SsdpResponder(config, address).also { it.start() }
            log.info("DLNA renderer '{}' started at http://{}:{}/", config.friendlyName, address.hostAddress, config.httpPort)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /**
     * Pushes the current playback state to subscribed control points. Call whenever
     * playback starts, stops, pauses, or the track changes.
     */
    fun notifyStateChanged(status: DlnaStatus) {
        subscriptions.notifyTransportState(status, System.currentTimeMillis())
    }

    fun stop() {
        subscriptions.clear()
        ssdp?.stop()
        ssdp = null
        httpChannel?.close()
        httpChannel = null
        eventLoopGroup.shutdownGracefully()
        log.info("DLNA renderer stopped")
    }

    companion object {
        private val log = LoggerFactory.getLogger(DlnaRenderer::class.java)
    }
}

/**
 * @param friendlyName name shown to control points
 * @param uuid stable device UUID (without the "uuid:" prefix); must persist across restarts
 * @param httpPort port for the UPnP description/control HTTP server
 */
data class DlnaConfig(
    val friendlyName: String = "VibeView",
    val uuid: String,
    val httpPort: Int = 8873,
)

/**
 * Callbacks from the renderer. Control methods are invoked on Netty I/O threads;
 * implementations must not block. [status] is polled to answer position/state queries.
 */
interface DlnaRendererListener {
    /** SetAVTransportURI: a control point set the media URL (with optional DIDL-Lite metadata). */
    fun onSetUri(uri: String, metadata: String?)

    /** SetNextAVTransportURI: the item to play when the current one ends; null clears it. */
    fun onSetNextUri(uri: String?, metadata: String?) {}

    /** Play. */
    fun onPlay()

    /** Pause. */
    fun onPause()

    /** Stop. */
    fun onStop()

    /** Seek to an absolute position, in seconds. */
    fun onSeekSeconds(seconds: Double)

    /** SetVolume, 0..100. */
    fun onSetVolume(volume: Int) {}

    /** Current playback status, for GetPositionInfo / GetTransportInfo. */
    fun status(): DlnaStatus = DlnaStatus()
}

/** Renderer playback status. Times in seconds. */
data class DlnaStatus(
    val state: TransportState = TransportState.NO_MEDIA,
    val durationSeconds: Double = 0.0,
    val positionSeconds: Double = 0.0,
    val uri: String? = null,
    val volume: Int = 100,
)

/** UPnP AVTransport transport states. */
enum class TransportState(val upnpName: String) {
    NO_MEDIA("NO_MEDIA_PRESENT"),
    STOPPED("STOPPED"),
    PLAYING("PLAYING"),
    PAUSED("PAUSED_PLAYBACK"),
    TRANSITIONING("TRANSITIONING"),
}
