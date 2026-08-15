# VibeView

An Android TV app that turns your TV into an **AirPlay receiver**: full-screen
mirroring from iPhones, iPads, and Macs — with audio — plus AirPlay video
casting and photo sharing.

<p align="center"><img src="app/src/main/res/mipmap-xhdpi/banner.png" alt="VibeView banner" width="320"></p>

## Features

- **Screen mirroring** — mirror the entire screen of an iPhone/iPad (Control
  Center → Screen Mirroring) or a Mac (Displays → Screen Mirroring), not just
  videos. Hardware H.264 decoding via MediaCodec.
- **Mirroring audio** — the AAC-ELD audio stream that accompanies mirroring is
  decoded and played in sync.
- **Video casting** — tap the AirPlay icon in an app that shares plain video
  URLs (HLS or progressive) and VibeView plays the stream natively with
  ExoPlayer, honoring play/pause/seek from the sender. (DRM-protected apps
  like Netflix will not work — see limitations.)
- **Photo casting** — share a photo from the iOS Photos app and it appears on
  the TV.
- **TV-friendly UI** — an idle screen with connection instructions, and a
  D-pad settings screen (device name, audio toggle, start-on-boot).
- Runs as a foreground service, so the TV stays discoverable while you use
  other apps, and (optionally) from boot.

## Requirements

- Android TV / Google TV device on Android 8.0 (API 26) or newer
- The TV and the Apple device on the same network (multicast/mDNS must not be
  blocked by the router — most home networks are fine)

## Install

**From source:** with an Android SDK installed (Android Studio, or
`ANDROID_HOME`/`ANDROID_SDK_ROOT` set):

```sh
./gradlew :app:assembleDebug
adb connect <tv-ip>
adb install app/build/outputs/apk/debug/app-debug.apk
```

The protocol module is pure JVM and can be built and tested with nothing but
a JDK: `./gradlew :airplay:test`.

**CI:** a ready-to-use GitHub Actions workflow lives at
[`ci/build.yml`](ci/build.yml) — it runs the protocol tests, assembles the
debug APK, and uploads it as an artifact. Copy it into `.github/workflows/`
to enable it (it isn't committed there directly because pushing workflow
files requires a token with the `workflow` scope):

```sh
mkdir -p .github/workflows && cp ci/build.yml .github/workflows/
```

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
- **ALAC / Opus audio** (used by some audio-only senders) is not decoded yet —
  such sessions play silently.
- **No PIN/onscreen-code pairing** yet; any device on your network can
  connect. Planned once the protocol core supports SRP pairing.
- One sender at a time; multi-room audio (AirPlay 2 group playback) is out of
  scope.
- The AirPlay protocol is unofficial and reverse-engineered; new iOS/macOS
  releases can break compatibility until the protocol core is updated.

## Development

- `./gradlew :airplay:test` — protocol unit tests (framing, plists, FairPlay
  vectors) run on any JDK 17+, no Android SDK needed.
- `./gradlew :app:assembleDebug` — needs an Android SDK (CI does this on
  every push).

## Legal

This project is licensed under the [MIT License](LICENSE). It vendors and
builds on MIT-licensed work by Sergei Fedorov — see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

AirPlay is a trademark of Apple Inc. This is an unofficial, reverse-engineered
receiver implementation, not affiliated with or endorsed by Apple, and it does
not implement or circumvent FairPlay DRM content protection.
