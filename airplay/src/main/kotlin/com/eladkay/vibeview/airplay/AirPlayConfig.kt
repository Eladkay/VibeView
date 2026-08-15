package com.eladkay.vibeview.airplay

/**
 * Configuration for the AirPlay receiver.
 *
 * Three server sockets are involved:
 *  - [airplayPort]: advertised as `_airplay._tcp`. Speaks plain HTTP and carries the
 *    casting protocol (`/play`, `/rate`, `/scrub`, `/playback-info`, `/photo`, `/reverse`, ...).
 *  - [airtunesPort]: advertised as `_raop._tcp`. Speaks RTSP and carries the mirroring
 *    control session (pairing, FairPlay setup, stream SETUP/TEARDOWN).
 *  - [mirrorDataPort]: not advertised; communicated to the client in the RTSP SETUP
 *    response. Receives the raw encrypted H.264 mirror stream over TCP.
 *
 * Audio data/control sockets are UDP and bound to ephemeral ports per session.
 */
data class AirPlayConfig(
    val serverName: String = "VibeView",
    val airplayPort: Int = 7000,
    val airtunesPort: Int = 7100,
    val mirrorDataPort: Int = 7102,
    /** Colon-separated pseudo-MAC identifying this receiver. Must stay stable across restarts. */
    val deviceId: String = "4A:56:56:42:56:57",
)
