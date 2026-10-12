# Architecture

Java 17 Android application, no third-party runtime UI/network dependencies.
Native Activity/views keep the application small; work never waits on the UI thread.

| Module | Responsibility |
| --- | --- |
| `MainActivity` | Landscape camera/dashboard, enrollment/document pickers, display controls, lifecycle and physical controller input |
| `JoystickView` | Single captured pointer, signed X/Y, neutral on release/cancel |
| `InstrumentView` | Shared selected-quaternion horizon, projected kart and fusion-predicted linear-acceleration G meter; Swift basis/angles |
| `CredentialVault` | AES/GCM encryption with Android Keystore, private public-CA file, explicit local forgetting |
| `GatewayApi` | Bounded HTTPS JSON requests, X-OpenKart-Key, imported CA, default hostname verification, no redirects |
| `KartSession` | Connection generations, UDP readers, subscription renewal/status, independent motor tick and shutdown |
| `VideoDecoder` | Hardware H.264 MediaCodec, bounded input, IDR recovery and rendering into a TextureView surface |
| `LspAssembler` | Native packet sequence tracking, FRAM extraction, terminal metadata exclusion and Annex-B/AUD splitting |
| `Telemetry` | Battery/IMU decoding, precision flags, native quaternion, all subsamples |
| `MotorLease` | Counter validation and unchanged signed 32-byte motor packet layout |
| `Orientation`, `AccelerationQuality`, `InstrumentCalibration` | Swift 122 device-clock fusion, adaptive bias, force-quality gates and one packet-time calibration state |
| `CsvRecorder` | Opt-in private 20 MB CSV with all subsamples and original telemetry packet hex |

One serial HTTPS worker renews subscriptions every ten seconds and polls status
once per second, with bounded timeouts and no unbounded repeating job queue. Video
and telemetry have separate socket reader threads. MediaCodec operations are
serialized; complete frames are submitted without an accumulating decode queue.
If a decoder input buffer is unavailable, the frame is dropped and recovery waits
for IDR. SPS/PPS changes rebuild the decoder; the Swift-observed parameter sets are
fallbacks only when the stream omits them. Source resolution is 1280×800; display
transformation shows the top 1280×720 at 16:9.

Motor transmission has its own scheduled worker and connection lock. Disconnect
closes sockets and invalidates pending arm generations. Changing the subscribed profile resets decoder/assembly, instruments, battery,
telemetry freshness and capture state; calibration cannot leak to another kart.
Late arm replies cannot
activate the old connection. Foreground loss disarms and disconnects rather than
leaving a background service. Restoring the app requires Connect and a new explicit
Arm. Saved settings never contain an armed state or calibration/bias.

Android backup is disabled. Client JSON is encrypted in private SharedPreferences
with a non-exportable AES key. The public CA lives in app-private files. No TLS
verification bypass, plaintext transport, admin token, hardcoded credential or
telemetry upload is included. CSV lives in private cache until explicitly exported.

Instrument snapshots are read under the connection's orientation lock by both
the 100 ms Activity refresh and CSV writer. UDP input updates the engine even
when recording is off. The Activity ticks calibration timeout/staleness without
waiting for another packet. The view never runs a separate filter or substitutes
Nintendo for missing data. See [Swift 122 parity](SWIFT-122-PARITY.md).
