# VibeView

An Android TV app that turns your TV into an **AirPlay receiver**: full-screen
mirroring from iPhones, iPads, and Macs — with audio — plus AirPlay video
casting and photo sharing.

<p align="center"><img src="app/src/main/res/mipmap-xhdpi/banner.png" alt="VibeView banner" width="320"></p>

## Features

- **Screen mirroring** — mirror the entire screen of an iPhone/iPad (Control
  Center → Screen Mirroring) or a Mac (Displays → Screen Mirroring), not just
  videos. Hardware H.264 decoding via MediaCodec.
- **Mirroring audio** — the audio accompanying mirroring is decoded and played
  in sync. AAC-ELD (mirroring's usual codec), AAC-LC, ALAC, and raw PCM are all
  handled; ALAC and Opus use the device's platform decoders where present.
- **Low latency** — the video decoder runs in MediaCodec low-latency mode with a
  small, drop-oldest frame backlog, and audio uses a low-latency AudioTrack, so
  the mirrored image tracks the source closely and recovers fast after network
  hiccups.
- **Video casting** — tap the AirPlay icon in an app that shares plain video
  URLs (HLS or progressive) and VibeView plays the stream natively with
  ExoPlayer, honoring play/pause/seek from the sender. (DRM-protected apps
  like Netflix will not work — see limitations.)
- **Photo casting** — share a photo from the iOS Photos app and it appears on
  the TV.
- **Optional passcode** — require a code (shown on the TV) before a device can
  mirror or cast, enforced with RTSP/HTTP Digest authentication.
- **TV-friendly UI** — an idle screen with connection instructions, and a
  D-pad settings screen (device name, audio toggle, passcode, start-on-boot).
- Runs as a foreground service, so the TV stays discoverable while you use
  other apps, and (optionally) from boot.

## Requirements

- Android TV / Google TV device on Android 8.0 (API 26) or newer
- The TV and the Apple device on the same network (multicast/mDNS must not be
  blocked by the router — most home networks are fine)

## Install

**From CI:** every push runs the
[Build workflow](.github/workflows/build.yml), which assembles a debug APK
and uploads it as the `vibeview-debug-apk` artifact. Download it from the
Actions run and sideload it:

```sh
adb connect <tv-ip>
adb install app-debug.apk
```

**From source:** with an Android SDK installed (Android Studio, or
`ANDROID_HOME`/`ANDROID_SDK_ROOT` set):

```sh
./gradlew :app:assembleDebug
adb connect <tv-ip>
adb install app/build/outputs/apk/debug/app-debug.apk
```

The protocol module is pure JVM and can be built and tested with nothing but
a JDK: `./gradlew :airplay:test`.

## Usage

1. Open VibeView on the TV (or enable *Start on boot* in settings once).
2. On your iPhone/iPad: Control Center → **Screen Mirroring** → pick the
   device name shown on the TV (default *VibeView*).
3. On a Mac: System Settings → Displays → **Screen Mirroring**, or the
   Screen Mirroring icon in Control Center.
4. Stop mirroring from the sender, or just walk away — the TV returns to the
   idle screen when the session ends.

Press **OK** on the idle screen for settings. While casting, **play/pause**
and **back** on the remote work as expected.

## Architecture

Two Gradle modules:

```
airplay/   Pure-JVM AirPlay receiver library (no Android dependencies)
  ├─ com.github.serezhka.jap2lib   vendored MIT protocol core:
  │     pairing (Curve25519/Ed25519), FairPlay handshake, AES stream decryption
  └─ com.eladkay.vibeview.airplay  Kotlin network layer (Netty):
        Bonjour advertising (JmDNS), RTSP control server, mirror-stream TCP
        receiver, RTP audio receiver, casting HTTP server + reverse-HTTP events

app/       Android TV app
  ├─ service/   foreground service + session hub bridging network ↔ UI
  ├─ media/     MediaCodec H.264 renderer, AAC-ELD audio player, ExoPlayer cast
  └─ ui/        idle/mirror/cast/photo screens, settings
```

Port layout (all on the TV):

| Port | Protocol | Advertised as | Purpose |
|------|----------|---------------|---------|
| 7000 | HTTP     | `_airplay._tcp` | casting: `/play`, `/scrub`, `/playback-info`, `/photo`, `/reverse` |
| 7100 | RTSP     | `_raop._tcp`    | mirroring control: pairing, `fp-setup`, stream `SETUP` |
| 7102 | TCP      | (in SETUP reply) | encrypted H.264 mirror stream |
| ephemeral | UDP | (in SETUP reply) | RTP audio data + control |

The mirroring session works like this: the sender discovers the receiver via
Bonjour, runs pair-setup/pair-verify and the FairPlay handshake over RTSP,
sends an encrypted AES key, then streams length-prefixed H.264 over TCP and
AAC-ELD over RTP/UDP. The `airplay` module decrypts and re-frames everything
and hands Annex-B video / raw audio frames to the app, which feeds them to
`MediaCodec` decoders rendering into a `SurfaceView`/`AudioTrack`.

## Limitations & roadmap

- **No DRM**: apps that protect their streams with FairPlay DRM (Netflix,
  Disney+, Apple TV+…) will refuse to cast or show a black screen. Mirroring
  the screen still works for everything that isn't HDCP-protected on the
  sender side.
- **ALAC / Opus** playback relies on the device's platform decoders. Most modern
  Android TV devices ship them; where a decoder is missing, that audio path is
  skipped (video keeps playing) rather than crashing.
- The **passcode** uses AirPlay's password/Digest mechanism (advertised as
  `pw=true`), not the AirPlay 2 SRP on-screen-code flow. The sender prompts for
  the code shown on the TV; some senders cache it after the first entry.
- One sender at a time; multi-room audio (AirPlay 2 group playback) is out of
  scope.
- The AirPlay protocol is unofficial and reverse-engineered; new iOS/macOS
  releases can break compatibility until the protocol core is updated.

## Development

- `./gradlew :airplay:test` — protocol unit tests (framing, plists, digest auth,
  FairPlay vectors) run on any JDK 17+, no Android SDK needed.
- `./gradlew :app:assembleDebug` — needs an Android SDK. CI
  ([`.github/workflows/build.yml`](.github/workflows/build.yml)) runs this
  on every push and publishes the APK as an artifact.
- `./gradlew :app:bundleRelease` — minified, signed release bundle. See
  [`docs/PUBLISHING.md`](docs/PUBLISHING.md) for signing setup and the Play
  Store / Android TV publishing checklist. **Smoke-test the release build on a
  device** — R8 minification exercises the reverse-engineered libraries that CI's
  debug build does not.

## Privacy

VibeView collects no personal data and sends nothing off the device; received
media is held only in memory for playback. See [PRIVACY.md](PRIVACY.md).

## Legal

This project is licensed under the [MIT License](LICENSE). It vendors and
builds on MIT-licensed work by Sergei Fedorov — see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

AirPlay is a trademark of Apple Inc. This is an unofficial, reverse-engineered
receiver implementation, not affiliated with or endorsed by Apple, and it does
not implement or circumvent FairPlay DRM content protection.
