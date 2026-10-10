# Architecture

Java 17 Android application, no third-party runtime UI/network dependencies.
Native Activity/views keep the application small; work never waits on the UI thread.

| Module | Responsibility |
| --- | --- |
| `MainActivity` | Landscape camera/dashboard, enrollment/document pickers, display controls, lifecycle and physical controller input |
| `JoystickView` | Single captured pointer, signed X/Y, neutral on release/cancel |
| `InstrumentView` | Shared quaternion horizon and projected 3D kart; native axis mapping follows Swift's X-right/Y-rear/Z-down basis |
| `CredentialVault` | AES/GCM encryption with Android Keystore, private public-CA file, explicit local forgetting |
| `GatewayApi` | Bounded HTTPS JSON requests, X-OpenKart-Key, imported CA, default hostname verification, no redirects |
| `KartSession` | Connection generations, UDP readers, subscription renewal/status, independent motor tick and shutdown |
| `VideoDecoder` | Hardware H.264 MediaCodec, bounded input, IDR recovery and rendering into a TextureView surface |
| `LspAssembler` | Native packet sequence tracking, FRAM extraction, terminal metadata exclusion and Annex-B/AUD splitting |
| `Telemetry` | Battery/IMU decoding, precision flags, native quaternion, all subsamples |
| `MotorLease` | Counter validation and unchanged signed 32-byte motor packet layout |
| `Orientation` | Session calibration, gyro integration, conservatively gated experimental relative gravity correction |
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
closes sockets and invalidates pending arm generations. Late arm replies cannot
activate the old connection. Foreground loss disarms and disconnects rather than
leaving a background service. Restoring the app requires Connect and a new explicit
Arm. Saved settings never contain an armed state or calibration/bias.

Android backup is disabled. Client JSON is encrypted in private SharedPreferences
with a non-exportable AES key. The public CA lives in app-private files. No TLS
verification bypass, plaintext transport, admin token, hardcoded credential or
telemetry upload is included. CSV lives in private cache until explicitly exported.
