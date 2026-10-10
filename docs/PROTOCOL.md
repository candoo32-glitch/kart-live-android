# Gateway protocol and control behavior

The reference is the current [Kart Garden client API](https://github.com/candoo32-glitch/kart-garden/blob/main/main/lan/README.md).
Swift baseline: Iteration 117, commit `c7b47a1` (documentation update on top of `1297519`).

HTTPS uses port 9443 (enrollment `api_url` can supply a different port) and header
`X-OpenKart-Key`. GET `/v1/status` exposes identity, profile mappings and
`connection_health`; POST `/v1/subscribe` registers `{ports:{video:41000,telemetry:41001}}`
and optional `profile`. Renewal is ten seconds; gateway subscriptions last thirty.
Identity from the subscription is the stream source. RSSI is shown only when the
health profile matches, `wifi_stale` is false and the status poll is fresh. It
measures the Pi's reception of the kart. Bars use Swift's thresholds -55/-67/-75/-85
dBm; missing RSSI is shown as unavailable, not zero signal strength.

Only datagrams from the configured hostname’s resolved IPv4 addresses and
subscribed mapped source ports are accepted. This permits Ethernet/Wi-Fi source
addresses advertised by the same Pi; motor traffic uses the first resolved IPv4
address. A literal IP should be the Pi’s preferred LAN interface address. Payloads remain native. Sequence numbers are big-endian bytes
3–4 with wraparound; duplicate/late video datagrams are ignored. FRAM size minus
38 gives H.264 length. Kinds 0xb6/0xba/0xbe exclude five prefix/metadata bytes;
ordinary 0x85 excludes one. Damaged frames are dropped; the decoder waits for IDR.

Battery type 1 uses payload cable flag and HUD value 0–4, never invented percentages.
IMU type 2 has 0x48 bytes after its type byte followed by 1–8 twelve-byte samples.
Flags control acceleration and gyro scales, and the signed LE quaternion is x/y/z/w
Q30. Invalid quaternions are withheld, not labeled as real orientation.

## Movement lifecycle

1. Require a control credential, live matching subscribed kart, foreground app,
   recent status and IMU, and neutral joystick.
2. Show operator confirmation, then bind source UDP 41020 and POST `/v1/arm` with
   `movement_authorized:true` and `source_port:41020`.
3. Validate the mapped motor port and complete unsigned 32-bit counter reservation.
   Send immediate neutral and then native packets every 80 ms. Byte 0 is signed
   throttle, byte 1 signed steering; LE counter is bytes 4–7; other bytes are zero.
4. Release/cancel immediately sets input neutral. STOP/B, focus loss, background,
   disconnect, session loss, IMU age ≥1 second, status age ≥1.8 seconds, frozen
   nonneutral UI input >500 ms, or a motor tick delayed >200 ms disarms locally.
5. Attempt three neutral packets, close sender, cancel ticks and POST `/v1/disarm`.
   Failed HTTPS cleanup does not keep local sending alive. The gateway's independent
   250 ms watchdog remains the final stop when the network/process disappears.
6. A pending or late arm result cannot bypass a cancellation or new connection.
   Counter exhaustion stops sending; automatic lease reacquisition is not allowed.

The LAN motor/telemetry/video payloads are not individually encrypted or authenticated
by this protocol. Use the gateway's permitted trusted LAN. This is a port of the
existing protocol; it does not change the gateway or send live motor commands during
implementation tests.
