package com.eladkay.vibeview.airplay.internal

import com.github.serezhka.jap2lib.AirPlay
import io.netty.channel.Channel
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client protocol state. One session spans the RTSP control connection and any
 * mirror/audio/cast activity belonging to the same client.
 */
internal class Session(val key: String, deviceKeyPair: KeyPair?) {

    val airPlay = if (deviceKeyPair != null) AirPlay(deviceKeyPair) else AirPlay()

    @Volatile var mirrorChannel: Channel? = null
    @Volatile var audioChannel: Channel? = null
    @Volatile var audioControlChannel: Channel? = null

    /** Reverse-HTTP channel used to push cast events to the client (from POST /reverse). */
    @Volatile var eventChannel: Channel? = null

    /**
     * In-progress PIN pairing. It spans several requests and, since a sender may open a
     * fresh connection between them, has to live on the session rather than the channel.
     */
    @Volatile var pinPairing: SrpPinPairing? = null

    /** Set once a sender has proven the PIN. */
    @Volatile var pinVerified = false

    @Volatile var mirroringActive = false
    @Volatile var castingActive = false

    /**
     * Waits for the listening socket to actually close: a sender may re-SETUP the video
     * stream mid-session, and rebinding the same port before the old socket has released
     * it fails with "address already in use".
     */
    fun stopMirroring() {
        mirrorChannel?.close()?.awaitUninterruptibly(CLOSE_TIMEOUT_MS)
        mirrorChannel = null
        mirroringActive = false
    }

    fun stopAudio() {
        audioChannel?.close()
        audioChannel = null
        audioControlChannel?.close()
        audioControlChannel = null
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 2000L
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
internal class SessionManager(private val deviceKeyPair: KeyPair? = null) {

    private val sessions = ConcurrentHashMap<String, Session>()

    /** The session currently casting media, if any; target for pushed cast events. */
    @Volatile var activeCast: Session? = null

    fun session(key: String?): Session =
        sessions.computeIfAbsent(key ?: "default") { Session(it, deviceKeyPair) }

    fun all(): Collection<Session> = sessions.values

    fun clear() {
        activeCast = null
        sessions.values.forEach { it.teardown() }
        sessions.clear()
    }
}
