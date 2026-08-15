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
    /**
     * When non-null and non-blank, clients must authenticate with this passcode
     * (RTSP/HTTP Digest) before mirroring or casting. The receiver advertises
     * `pw=true` so senders prompt for it. Null disables authentication.
     */
    val password: String? = null,
    /**
     * Stable pairing identity advertised as `pi` in both the Bonjour TXT record and
     * `/info`. Senders compare the two, so it must not change between them.
     */
    val pairingId: String = "2e388006-13ba-4041-9a67-25dd4a43d536",
    /** Stable UUID for the advertised display in `/info`. */
    val displayUuid: String = "e5f7a68d-7b0f-4305-984b-974f677a150b",
)
