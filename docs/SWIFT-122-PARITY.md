# Android 0.2.0 — Swift 122 fusion/calibration port

Android baseline: `2dd6435`. Work branch: `swift-122-fusion-parity`.
Swift production source: `1126441d303150371c80a2b4e7afebde4c37b738`.
Original research evidence: Swift repository commit
`3c9b2a878fcb24988579e5903116873a6cecfb25`.

## Resulting behavior

Fusion is the default instrument source. CALIBRATE ALL captures one set of
session references and reports progress, READY, or a bounded failure. Nintendo
is optional and never substituted for unavailable experimental data. Nintendo
mode itself requires its captured reference. Missing/stale data has waiting
readouts and a neutral dimmed pose, rather than presenting that pose as a
measurement. Horizon, wireframe kart and G meter consume the production engine;
there is no second UI attitude estimator. Android retains its Canvas model and
100 ms UI refresh rather than copying SceneKit geometry/smoothing.

The port replaces arrival-time integration with the validated 1 microsecond
counter/device-clock subsample timing. Duplicates, reverse counters, implausible
sample periods and long clock gaps are rejected; missing sample intervals are
not stretched over received samples. Valid prediction continues when acceleration
is rejected. Native counts/decoding are unchanged. Fusion uses the separate
candidate `[accel.x/16, accel.y/16, accel.z/16 - 9.81]`, with identical precision,
clipping, magnitude, transition and vector-continuity gates in both modes.

Gravity correction uses a world-frame axis and left multiplication, time constant
0.6 seconds, deterministic antiparallel handling, and unconstrained heading.
Session/adaptive bias, accepted-sample stillness windows, the current-sample
0.002 EWMA, gravity-reference acquisition and per-estimator calibration origins
match Swift. Interrupted startup extends bias acquisition until ten samples;
CAL restarts an incomplete window, preserving a valid existing bias. Calibration
requires sixteen stable packets and settled gravity, with a ten-second timeout.
Reconnection/profile changes reset the engine. Stale IMU invalidates poses/G
without silently selecting Nintendo; the next valid packet can recover.

Body linear acceleration subtracts fusion-predicted specific force, rather than
a fixed calibration offset. The G meter uses X-right / -Y-forward, plotted to
±1.25 G; its unavailable state is explicit. Heading can drift, and initial bias
still assumes a stationary kart. Motor, lease, TLS and enrollment behavior are
unchanged; calibration retains the existing explicit disarm behavior.

Android CSV grows from 25 to 46 named columns, preserving original subsamples
and raw packet hex and adding force candidate, calibration phase, source/status,
relative quaternion, pitch/roll, linear acceleration, timing/quality and Swift
fusion version 122. Selected pose fields are packet-level targets repeated for
each recorded subsample, not rendered pixels. This is not Swift's 145-column
research schema. Recording toggles do not reset the engine.

## Direct production-code parity

`tools/generate-swift-golden.py` verifies exact source bytes at the pinned Swift
commit, compiles the actual Swift parser/quality gate/recorder/calibration/math,
and replays native raw packets. It generates the committed expectations under
`app/src/test/resources/swift122/`, with input, source and golden hashes. No
Python attitude filter generates the expected answers.

`tools/fetch-swift-fixtures.py` downloads the original captures at the immutable
research commit and verifies SHA-256 before using them. Raw CSVs stay in
`app/build/swift-fixtures/`; they are not duplicated in Git or included in the
APK. The unit tests fail if fixtures are missing or hashes differ.

| Input capture | Packets | Subsamples | Compared Swift snapshots |
|---|---:|---:|---:|
| Original 117 | 6,024 | 33,431 | 1,207 |
| Original 118 | 4,240 | 23,601 | 851 |
| Physical 122 | 4,331 | 24,241 | 869 |

All three local comparisons pass: calibration phase, each selected source's
availability, sign-equivalent relative quaternion components within 1e-9,
pitch/roll within 1e-7 degree, and residual acceleration magnitude within 1e-9 G.
These are algorithm-parity tolerances, not claims of physical measurement accuracy.
Every original packet/subsample is processed; the Swift runner emits every fifth
packet and phase transition for comparison. Both engines start fresh and request
CAL after three seconds. The 122 recording starts after prior session/calibration
history, so this restart comparison does not reconstruct the recorded iPad state
or assert identical angles to that historical calibration.

Local Java 17-compatible compilation passes for all production classes against
Android API 35. All 34 local tests pass: 29 protocol/orientation/CSV tests and
five existing session safety tests using real localhost UDP and a fake API.
They cover startup interruption and movement after READY, optional Nintendo,
identical samples, both precision modes, recalibration, reset, invalid timing,
unsigned wraps, rejected acceleration with continued prediction, large-angle
correction, pure-yaw preservation, unavailable-source rejection and CSV blanks.

## Live gateway test

A passive 30-second capture received 900 native IMU packets from the connected
`original` kart. It sends no motor packets or subscription requests. The production
Java runner acquired references and reached READY at 3.497 seconds. Across 160
READY comparisons, maximum Android/Swift angle difference was 2.65e-17 degree,
quaternion-component difference 1.12e-16, and residual-G difference 5.36e-16.
The stationary kart ended at pitch -0.001668 degree and roll -0.009421 degree;
maximum READY tilt was 0.02349 degree, with median residual acceleration
0.001417 G. This verifies live decoding and fusion while stationary; the saved
117/118/122 recordings exercise movement. Native captures and comparison outputs
stay outside Git in `/home/candoo/kart-android-lab/`.

## Build and emulator verification

The initial hosted build [run 38197777739](https://github.com/candoo32-glitch/kart-live-android/actions/runs/38197777739)
was rejected before any steps ran because GitHub reported failed account payments
or a spending limit. This is not an Android test failure. Emulator tests have
not run; a local API 35 production-source compilation and the 34 local tests
pass. The local Gradle tasks `testDebugUnitTest`, `lintDebug`, and
`assembleDebug`, and `assembleDebugAndroidTest` all pass (72 tasks; build
successful). Lint reports zero errors and 24 warnings. The Android instrumentation
test APK builds successfully; execution remains unverified.

The installable debug APK is saved locally as
`/home/candoo/kart-android-lab/artifacts/kart-live-android-0.2.0-swift122.apk`.
SHA-256: `eb56761c78a37e23a8f7835381cf6a80e8b70f6789c4508dc884b254ed23230c`.
It is version 0.2.0/code 2, built from implementation commit `9c5fb75`, and its
APK v2 signature verifies. The companion instrumentation APK is saved alongside
it as `kart-live-android-0.2.0-tests.apk`. It uses this server's local debug signing key; that
may differ from a previously installed CI debug APK.

This server is ARM64. The local build used SDK platform/build-tools 35 and
Google's AAPT2 2.19-11948202 through `qemu-x86_64` with extracted Debian
amd64 libc/libgcc. The compiler override is local and not committed:
`-Pandroid.aapt2FromMavenOverride=/home/candoo/kart-android-lab/google-tools/aapt2`.
The older Debian ARM AAPT2 cannot read API 35's resource table. No source or
SDK target was changed to bypass that limitation.

Run from the repository root:

```sh
python3 tools/fetch-swift-fixtures.py
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Android 15 emulator test feeds native synthetic packets through real localhost
UDP and the actual `KartSession` receiver, then activates CALIBRATE ALL through
the Activity's confirmation dialog. Nintendo quaternion is absent deliberately.
It requires READY, a moving instrument bitmap (excluding labels), a 20 degree
nose-up target and return, with no arm or motor socket. Held/returned screenshots
are collected alongside existing dashboard/MediaCodec tests. This tests the data
path that the earlier Swift core-only validation did not cover.

No physical Android device is attached for this task. Build/emulator and Java
live-input tests do not prove physical Android sensor-to-display behavior or
decoder latency. Install the test APK, keep the kart stationary through initial
bias acquisition, CALIBRATE ALL once, and verify isolated pitch/roll holds and
returns, G settling, and reconnect/background behavior on the actual device.
