# Publishing VibeView to Google Play (Android TV)

Everything needed to get VibeView onto the Play Store, in the order it has to
happen. Items already handled in this repo are checked; the rest need you.

Requirements verified against Play policy in August 2026 — re-check the linked
pages at submission time, since Google moves these dates.

---

## 0. Decide whether to publish publicly

Read this before spending time on the rest. Two issues could get the listing
rejected or pulled after the fact, and both are decisions rather than tasks.

**Reverse-engineered FairPlay code.** `airplay/src/main/java/com/github/serezhka/jap2lib`
implements Apple's FairPlay handshake, derived from reverse engineering
(the `OmgHax`/playfair work). It negotiates AirPlay *session* keys — it does not
decrypt protected commercial content, and the app cannot play DRM-protected
media. Even so:

- Apple could file an intellectual-property complaint under Play's IP policy,
  which can remove a listing without warning.
- In the US, circumventing an access-control measure carries DMCA §1201
  exposure independent of Play's policies.
- Comparable AirPlay receivers *are* published on Play (AirScreen, AirServer
  and others), so this is plainly not an automatic bar — but those are
  commercial products whose publishers presumably took legal advice.

If VibeView is going to be a public, indexed listing rather than a sideloaded
app for your own TV, getting a lawyer's read on this is the cheap step.

**The AirPlay trademark.** "AirPlay" is Apple's. Keep it out of the app title,
icon and branding; describe compatibility factually in the listing body
("works with AirPlay-enabled iPhones, iPads and Macs") and state plainly that
the app is unofficial and not affiliated with Apple. Note also that for
protocol compatibility the receiver advertises itself as Apple hardware
(`model = AppleTV3,2` in `/info` and the Bonjour record) — necessary to work,
but worth knowing it is there.

**Alternative:** distribute the APK from GitHub Releases for sideloading. No
review, no policy exposure, and Android TV sideloading is routine. You lose
discoverability and auto-updates.

---

## 1. Developer account

- [ ] Create a Play Console account — **$25 one-time** fee.
- [ ] **Check which account type you have.** Personal accounts created after
      13 November 2023 must run a **closed test with at least 12 testers who
      stay opted in for 14 continuous days** before you can apply for
      production access. "Opted in" means they accepted the invite *and*
      installed the app; invited-but-not-installed does not count. Organization
      accounts, and personal accounts older than that date, publish straight to
      production.
      → [App testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)

      This is the longest lead time in the whole process: budget **two weeks
      minimum** and line up 12 real people with Google accounts before you
      start, ideally people who own an Android TV device.

---

## 2. Signing

- [x] Release build type with R8 minification + resource shrinking.
- [x] R8 keep rules for the reflection/JNI-heavy protocol libraries
      (`app/proguard-rules.pro`).
- [x] Signing wired to a git-ignored `keystore.properties`.
- [ ] Generate an upload keystore and fill in the properties file:
      ```sh
      keytool -genkeypair -v -keystore vibeview-release.jks \
        -keyalg RSA -keysize 2048 -validity 10000 -alias vibeview
      cp keystore.properties.template keystore.properties   # then edit
      ```
      **Back the keystore up somewhere durable.** With Play App Signing you can
      recover a lost *upload* key, but losing it is still a support round trip.
- [ ] Enrol in **Play App Signing** when creating the app (recommended;
      Google holds the app signing key, you hold the upload key).

---

## 3. Build and smoke-test

- [ ] Build the **production** flavor — never `home`, which carries a personal
      subtitle:
      ```sh
      ./gradlew :app:bundleProductionRelease
      # → app/build/outputs/bundle/productionRelease/app-production-release.aab
      ```
- [ ] **Install the minified build on a real TV and exercise it.** This is the
      single most likely thing to bite you: CI only ever builds *debug* APKs, so
      R8 minification and resource shrinking have never run against the
      reflection-heavy protocol stack (Netty, JmDNS, dd-plist, the crypto
      providers) on a device. A missing keep rule shows up as a crash or a
      receiver that never appears on the network, not as a build failure.
      ```sh
      ./gradlew :app:assembleProductionRelease
      adb install -r app/build/outputs/apk/production/release/app-production-release.apk
      ```
      Check: the TV appears in Screen Mirroring, mirroring runs, audio plays,
      and the app survives a reboot with "Start on boot" enabled.
- [ ] Bump `versionCode` for **every** upload (currently `1`). Play rejects a
      re-used code. `versionName` (`0.1.0`) is cosmetic.

---

## 4. Target API level

- [x] `targetSdk = 35`, `compileSdk = 35`, `minSdk = 26`.
- [ ] Confirm still current at submission. As of August 2026 Play requires
      **API 34+ for Android TV apps** — a lower bar than the API 36 required of
      phone apps, because TV form factors lag. `targetSdk = 35` clears it with
      room to spare, so **no upgrade is needed**; going to 36 would force an
      AGP upgrade (8.7.3 does not support `compileSdk = 36`) for no benefit.
      → [Target API level requirements](https://developer.android.com/google/play/requirements/target-sdk)

      This applies because the app is TV-only: `android.software.leanback` is
      declared `required="true"`, so it will not install on phones.

---

## 5. Android TV form factor

- [x] `LEANBACK_LAUNCHER` intent filter on the launcher activity.
- [x] `android:banner` set; `android.software.leanback` required; touchscreen
      declared not required.
- [x] Landscape, D-pad navigable UI, no touch needed anywhere.
- [ ] In Play Console, add **Android TV** as a supported form factor and opt
      into TV review, or the app will not surface in the TV Play Store.
- [ ] Self-check against the
      [TV app quality guidelines](https://developer.android.com/docs/quality-guidelines/tv-app-quality):
      D-pad focus is always visible, Back never traps the user, no reliance on
      hardware a TV lacks.

---

## 6. Store listing assets

None of these exist yet. The in-repo banner is 320×180 for the *launcher* and
is **not** the store asset.

| Asset | Spec | Status |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | ❌ generate |
| TV banner | 1280×720 PNG/JPEG | ❌ generate |
| Feature graphic | 1024×500 PNG/JPEG | ❌ generate |
| TV screenshots | 1920×1080 (16:9), 1–8 of them | ❌ capture on device |
| Short description | ≤ 80 characters | ❌ write |
| Full description | ≤ 4000 characters | ❌ write (README is source copy) |

Screenshots are best captured from the real TV: the idle screen with
connection instructions, an active mirroring session, and the settings screen.

---

## 7. Console declarations

- [x] Privacy policy written (`PRIVACY.md`) — the app collects nothing.
- [ ] **Host it at a public URL** and paste that into the listing. GitHub Pages
      on this repo is the least-effort option; a `raw.githubusercontent.com`
      link works but looks unpolished.
- [ ] **Data safety form**: declare *no data collected, no data shared*. This
      must match `PRIVACY.md` — mismatches are a common rejection cause.
- [ ] **Content rating** questionnaire (utility app; it stores and shares
      nothing user-generated).
- [ ] **Foreground service declaration**: the receiver runs an FGS of type
      `mediaPlayback`. Play asks you to justify it — the honest answer is that
      the service decodes and renders a live mirrored/cast media stream and
      must keep running while other apps are foregrounded.
- [ ] **Permissions review**: be ready to justify `RECEIVE_BOOT_COMPLETED`
      (optional "start on boot" so the TV is discoverable after a reboot) and
      `USE_FULL_SCREEN_INTENT` (bringing the receiver UI forward when a device
      connects, since only the activity owns the rendering surface).

---

## 8. Release

1. **Internal testing** — instant, up to 100 testers. Verify the signed bundle
   installs and runs from Play rather than from `adb`.
2. **Closed testing** — required if §1 applies to you: 12 testers, 14
   continuous days.
3. **Apply for production access** on the Play Console dashboard.
4. **Production** — expect review to take days rather than hours for a first
   submission, and longer for TV review.

---

## Known limitations to state in the listing

Setting expectations here heads off one-star reviews and reduces the chance a
reviewer thinks the app is broken:

- DRM-protected apps (Netflix, Disney+, Apple TV+) will not cast or mirror
  their video.
- AirPlay video casting from inside iOS apps is unreliable; screen mirroring
  and DLNA casting are the supported paths.
- Requires the TV and the sending device to be on the same network, with
  multicast/mDNS not blocked by the router.
