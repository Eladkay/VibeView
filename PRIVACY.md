# VibeView Privacy Policy

_Last updated: 2026-08-15_

VibeView is an AirPlay screen-mirroring receiver for Android TV. This policy
describes what the app does and does not do with data.

## Summary

**VibeView collects no personal data, has no accounts, and sends nothing to any
server operated by us or any third party.** There is no analytics, advertising,
tracking, or telemetry in the app.

## What the app handles

- **Screen, audio, video, and photos received over AirPlay.** When you mirror or
  cast from an Apple device, that content is received over your local network,
  decoded, and displayed on the TV in real time. It is held only in memory for
  playback and is never written to storage or transmitted anywhere else.
- **Device name and optional passcode.** The receiver name you set and, if you
  enable it, the connection passcode are stored locally on the TV (in the app's
  private preferences) so the receiver can advertise itself and authenticate
  connections. They never leave the device.
- **Local network discovery.** The app advertises itself on the local network
  using Bonjour/mDNS and accepts incoming AirPlay connections so Apple devices
  can find and connect to it. This traffic stays on your local network.

## What the app does not do

- No data is uploaded to the developer or any third party.
- No advertising or analytics SDKs are included.
- No location, contacts, accounts, or persistent identifiers are collected.
- Received media is not recorded or retained.

## Permissions

- **Network / Wi-Fi / multicast** — to advertise the receiver and accept AirPlay
  connections on the local network.
- **Foreground service** — to keep the receiver discoverable while other apps are
  in the foreground.
- **Run at startup** (optional) — to make the TV discoverable after a reboot when
  you enable "Start on boot".
- **Post notifications** — to show the ongoing "receiver running" notification
  required for the foreground service.

## Children's privacy

VibeView is not directed at children and collects no data from anyone.

## Contact

Questions about this policy can be directed to the project maintainer via the
project's repository.
