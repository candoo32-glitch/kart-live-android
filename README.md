# Kart Live – Android

Native Android client for [Kart Garden](https://github.com/candoo32-glitch/kart-garden),
based on the [Swift Playgrounds client](https://github.com/candoo32-glitch/kart-live-swift-playgrounds)
Iteration 117. Android 8.0 (API 26) or newer; landscape phones and tablets.

## What it does

- Authenticated HTTPS enrollment import, encrypted local credential storage and
  gateway CA import with normal hostname verification.
- Native UDP H.264/LSP video, hardware MediaCodec decoding, full-screen view and
  display-only brightness, contrast and saturation controls.
- Saved-kart selection, live identity/serial, measured RSSI with four bars,
  battery HUD bars/cable state, stream freshness and diagnostic statistics.
- Touch joystick and A/B controls; Android gamepad left stick, A and B.
- Explicit movement confirmation, reserved native motor counters, neutral on
  release, disarm on background/focus loss, and local stale-session/input checks.
- Shared Nintendo-quaternion horizon and projected 3D kart, per-session still
  calibration, optional experimental gyro/fusion displays.
- Opt-in bounded IMU CSV recording of all subsamples and export through Android's
  document picker. No Android storage permission or embedded client key needed.

## Install and connect

1. Open this repository in Android Studio, or download the debug APK from a
   successful [Actions run](https://github.com/candoo32-glitch/kart-live-android/actions).
   In its `kart-live-android-debug` artifact, use `app-debug.apk`.
   Debug APKs are for development; updates from different machines/runs may need
   uninstalling because debug signing keys can differ (this deletes local enrollment).
2. In the Pi dashboard's **Client access**, create a separate read credential for
   this device; download its JSON and public `openkart-ca.crt`. Use control permission
   only if you intend to drive. Administrator credentials are not client credentials.
3. Transfer those two files to Android. Open **Setup → Import client JSON**, then
   **Import gateway CA**. The key is encrypted with Android Keystore; it is never
   displayed in diagnostics or committed to source control.
4. Check the hostname/IP under Setup and tap **Connect**. The host must appear in
   the gateway certificate's subject alternative names. If `.local` resolution
   does not work on your Android version/network, use a certificate-covered IPv4
   address. There is no insecure TLS switch or automatic certificate download.
5. Power on the selected kart. **Setup → Choose saved kart** loads gateway profile
   names once connected; blank profile ID follows the active profile at subscription
   renewal. Selection receives a saved profile; it does not switch the Pi radio.
6. For instruments, keep the kart level and still and tap **Calibrate**. For driving,
   release the joystick and confirm **Arm**. A requests that confirmation; B/STOP
   disarms. No connection, launch, calibration or credential import arms automatically.

The gateway requires a permitted LAN source address and has one active kart radio
network. Multiple Android/iPad receivers need distinct client credentials, since
one credential has one subscription destination. This app receives on UDP 41000
(video), 41001 (telemetry), and sends armed motor packets from UDP 41020.

## Build and verification

Use JDK 17, Android SDK platform 35 / build-tools 35.0.0, and the checked-in
Gradle 8.11.1 wrapper (distribution SHA-256 verified). Android Gradle Plugin 8.9.2:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions performs these checks, runs Android 15 emulator UI/video tests,
and uploads the debug APK, reports and screenshots (retained for 14 days).
See [architecture](docs/ARCHITECTURE.md), [protocol and safety](docs/PROTOCOL.md),
and [validation and differences from Swift](docs/VALIDATION.md).

The Android port is a new implementation, not a device-validated copy of the iPad
app. Physical video decoding, gamepad behavior, orientation signs and live driving
must be checked on Android hardware. Do not interpret experimental fusion as a
validated attitude estimate or battery bars as a calibrated percentage.
