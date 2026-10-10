# Validation and Swift parity

Implemented from Swift Playgrounds Iteration 117 and the gateway source. This is
an Android implementation of the core client, with the following differences:

| Swift feature | Android implementation / difference |
| --- | --- |
| Enrollment + Keychain | Same JSON aliases plus dashboard api_url; Android Keystore-encrypted storage |
| CA trust in iPad settings | App-scoped public CA import; normal hostname verification |
| UDP camera and telemetry | Same packet format and default receiver ports |
| VideoToolbox | MediaCodec → TextureView, one decoder shared with full-screen |
| Native virtual controller | Android touch joystick; Android joystick/A/B events |
| SceneKit kart model | Canvas projection of a 3D wireframe kart |
| Nintendo instruments | Shared native relative quaternion and still calibration |
| Experimental gyro/fusion | Simpler isolated Android implementation; arrival-based timing, all subsamples, high-precision gravity gates; not exact estimator or CSV-column parity |
| Advanced passive fusion audits | Not ported; do not claim identical experimental results |
| CSV recording/share | All subsamples/native packet hex, 20 MB cap, Android document export; new schema |
| Optional raw TCP video receiver | Not ported; production UDP subscription is implemented |

Unit tests cover terminal-fragment extraction, sequence gaps/duplicates/wrap,
frame abandonment, NAL/AUD splitting, telemetry lengths/scales/subsamples,
battery range, unsigned counters, overflow/exhaustion, nonfinite controls,
background/stale-input gates, enrollment parsing and quaternion operations.
Additional session tests use a fake HTTPS endpoint and real localhost UDP sockets
to verify immediate neutral, stop packets, cancellation of late arm replies, stale
telemetry shutdown, counter exhaustion without rearm, and read-only rejection.
The APK build, 21 unit tests and Android lint are checked by GitHub Actions.
Three Android 15 emulator tests verify visible controls, full-screen return/stop,
and MediaCodec rendering of a generated six-picture H.264 pattern. Test fixtures
contain no physical camera images or credentials; the APK/report artifact also
includes screenshots. These checks do not
prove hardware behavior. No live kart is armed during repository/build testing.

## Preserved camera recording check

The Java LSP assembler processed a private existing physical-kart recording:
6,183 packets, 664 assembled frames, zero gaps/damaged/abandoned frames. After
discarding pictures before the first SPS/IDR, FFmpeg decoded 649 pictures at
1280×800 with zero decode errors. The recording stays outside this repository.
This validates native extraction against real packets; it does not establish
Android hardware decoder performance or live camera-to-screen latency.

## Android acceptance checklist

Use a real Android phone/tablet on the permitted gateway LAN:

- Import read-only JSON + CA; verify bad/missing CA, bad hostname and revoked key
  fail. Verify read-only enrollment cannot arm.
- Receive a powered kart's camera and IMU; compare identity/RSSI/stream freshness
  with the Pi; test portrait rotation lock, landscape phones/tablets and fullscreen.
- Switch profiles; confirm offline profiles never show another kart's signal or
  stale camera. Reconnect after Wi-Fi/router changes and gateway restarts.
- Confirm decoder recovery after real packet loss and repeated camera sessions,
  including the 1280×800-to-720 crop on the device's specific hardware decoder.
- Check calibration and known physical pitch/roll/yaw. Nintendo remains the default;
  gyro drift, gravity sign and timing assumptions need independent validation.
- Record/export CSV and confirm multiple samples/precision transitions survive.
- In a safe physical test with explicit operator approval, verify forward/reverse,
  steering, release-to-neutral, B/STOP, focus loss, background, unplugged controller,
  lost telemetry/network, and control ownership conflicts. No automatic rearm.
- Inspect large-text layouts and touch targets on the actual device.

No physical Android device has been connected to this Pi for these checks.
