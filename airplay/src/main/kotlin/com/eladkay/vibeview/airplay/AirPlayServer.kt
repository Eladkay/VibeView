package com.eladkay.vibeview.airplay

import com.eladkay.vibeview.airplay.internal.AirPlayAdvertiser
import com.eladkay.vibeview.airplay.internal.CastServer
import com.eladkay.vibeview.airplay.internal.ControlServer
import com.eladkay.vibeview.airplay.internal.EventChannel
import com.eladkay.vibeview.airplay.internal.SessionManager
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import org.slf4j.LoggerFactory
import java.net.InetAddress

/**
 * AirPlay receiver: advertises the service over Bonjour and serves screen mirroring
 * (H.264 + AAC-ELD via [AirPlayListener.onVideoData]/[AirPlayListener.onAudioData]),
 * video casting, and photo casting.
 *
 * Lifecycle: [start] binds all sockets and begins advertising; [stop] tears everything
 * down. A stopped server cannot be restarted; create a new instance.
 */
class AirPlayServer(
    private val config: AirPlayConfig,
    private val listener: AirPlayListener,
) {

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup(2)
    private val dataGroup = NioEventLoopGroup(2)
    private val sessions = SessionManager()
    private val advertiser = AirPlayAdvertiser(config)

    private var controlChannel: Channel? = null
    private var castChannel: Channel? = null

    @Volatile private var started = false

    /**
     * Binds the control and cast servers and starts Bonjour advertising.
     * Blocks briefly; do not call from a UI thread.
     *
     * @param advertiseAddress LAN address to advertise on, or null to auto-pick
     */
    @JvmOverloads
    @Throws(Exception::class)
    fun start(advertiseAddress: InetAddress? = null) {
        check(!started) { "AirPlayServer already started" }
        started = true
        try {
            controlChannel = ControlServer.start(bossGroup, workerGroup, dataGroup, config, sessions, listener)
            castChannel = CastServer.start(bossGroup, workerGroup, config, sessions, listener)
            advertiser.start(advertiseAddress)
        } catch (e: Exception) {
            stop()
            throw e
        }
        log.info("AirPlay receiver '{}' started", config.serverName)
    }

    /** Pushes a cast playback state change to the connected client's event channel. */
    fun notifyCastState(state: CastState) {
        sessions.activeCast?.let { EventChannel.sendState(it, state) }
    }

    /** Stops advertising, closes all sockets, and releases event loops. */
    fun stop() {
        advertiser.stop()
        sessions.clear()
        controlChannel?.close()
        controlChannel = null
        castChannel?.close()
        castChannel = null
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
        dataGroup.shutdownGracefully()
        log.info("AirPlay receiver stopped")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AirPlayServer::class.java)
    }
}
