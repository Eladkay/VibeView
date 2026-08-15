# Publishing VibeView to Google Play (Android TV)

A checklist for getting VibeView onto the Play Store as an Android TV app,
following <https://developer.android.com/training/tv/publishing/distribute> and
Play's current policies. Items already handled in this repo are checked.

## Build & signing

- [x] Release build type with R8 minification + resource shrinking
      (`app/build.gradle.kts`).
- [x] R8 keep rules for the reflection/JNI-heavy protocol libraries
      (`app/proguard-rules.pro`).
- [x] Signing wired to a git-ignored `keystore.properties`
      (`keystore.properties.template`).
- [ ] Generate an upload keystore and create `keystore.properties`:
      ```sh
      keytool -genkeypair -v -keystore vibeview-release.jks \
        -keyalg RSA -keysize 2048 -validity 10000 -alias vibeview
      cp keystore.properties.template keystore.properties   # then fill in
      ```
- [ ] Build the release bundle and **smoke-test it on a real device** before
      upload — the minified build exercises code paths (Netty, JmDNS, crypto,
      MediaCodec) that debug does not, and CI only builds the debug APK:
      ```sh
      ./gradlew :app:bundleRelease      # app/build/outputs/bundle/release/*.aab
      ./gradlew :app:assembleRelease    # or an installable APK to sideload
      ```
- [ ] Enroll in Play App Signing (recommended) when creating the app in Play
      Console.

## Android TV requirements

- [x] `LEANBACK_LAUNCHER` intent filter on the launcher activity.
- [x] TV banner (`mipmap/banner.png`, 320×180 xhdpi) set via `android:banner`.
- [x] `android.software.leanback` declared; touchscreen declared not required.
- [x] Landscape, D-pad navigable UI (idle screen + settings), no touch needed.
- [ ] Confirm the app meets the
      [TV app quality guidelines](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
      (D-pad focus, back navigation, no reliance on unavailable hardware).

## Play Console listing

- [ ] Create the app and select **Android TV** as a supported form factor; opt
      into TV review so it appears in the TV Play Store.
- [ ] Store listing assets:
  - [ ] TV banner 320×180 (available in-repo).
  - [ ] App icon 512×512 (store listing; generate from `mipmap` art).
  - [ ] Feature graphic 1024×500.
  - [ ] At least one TV screenshot (1920×1080) — capture the idle screen and an
        active mirroring session.
  - [ ] Short + full description (see README for source copy).
- [x] Privacy policy (`PRIVACY.md`) — host it at a public URL and link it in the
      Play Console; the app collects no personal data.
- [ ] Data safety form: declare **no data collected, no data shared** (matches
      `PRIVACY.md`).
- [ ] Content rating questionnaire (utility app; no user-generated content is
      stored or shared by the app).
- [ ] Target API level: `targetSdk` is 35; confirm it still meets Play's current
      minimum target-API requirement at submission time and bump (with a matching
      `compileSdk`/AGP update) if Play has moved to a newer level.

## Policy considerations specific to this app

- **AirPlay trademark**: AirPlay is a trademark of Apple Inc. Describe the app as
  an unofficial, compatible receiver — do not imply Apple affiliation or use
  Apple marks in the icon/branding.
- **No DRM circumvention**: the app does not implement or bypass FairPlay content
  protection; DRM-protected apps intentionally will not cast. State this plainly
  in the listing to avoid policy confusion.
- **Foreground service type**: the receiver uses the `mediaPlayback` FGS type.
  Confirm this remains the appropriate declared type under the Play foreground
  service policy for your target API, and justify it in the Console's FGS
  declaration (real-time media playback of the mirrored/cast stream).
