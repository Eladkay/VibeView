package com.eladkay.vibeview.airplay.internal

import com.github.serezhka.jap2lib.AirPlay
import io.netty.channel.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client protocol state. One session spans the RTSP control connection and any
 * mirror/audio/cast activity belonging to the same client.
 */
internal class Session(val key: String) {

    val airPlay = AirPlay()

    @Volatile var mirrorChannel: Channel? = null
    @Volatile var audioChannel: Channel? = null
    @Volatile var audioControlChannel: Channel? = null

    /** Reverse-HTTP channel used to push cast events to the client (from POST /reverse). */
    @Volatile var eventChannel: Channel? = null

    @Volatile var mirroringActive = false
    @Volatile var castingActive = false

    fun stopMirroring() {
        mirrorChannel?.close()
        mirrorChannel = null
        mirroringActive = false
    }

    fun stopAudio() {
        audioChannel?.close()
        audioChannel = null
        audioControlChannel?.close()
        audioControlChannel = null
    }

    fun teardown() {
        stopMirroring()
        stopAudio()
        eventChannel?.close()
        eventChannel = null
        castingActive = false
    }
}

/**
 * Sessions are keyed by the client's `Active-Remote` header (RTSP) or
 * `X-Apple-Session-ID` header (casting HTTP), falling back to the remote address.
 */
internal class SessionManager {

    private val sessions = ConcurrentHashMap<String, Session>()

    /** The session currently casting media, if any; target for pushed cast events. */
    @Volatile var activeCast: Session? = null

    fun session(key: String?): Session =
        sessions.computeIfAbsent(key ?: "default") { Session(it) }

    fun all(): Collection<Session> = sessions.values

    fun clear() {
        activeCast = null
        sessions.values.forEach { it.teardown() }
        sessions.clear()
    }
}
