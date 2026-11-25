# V21 - connection / reconnect fixes (items 1-5 of the review)

1. Discovery no longer restarts in-flight connections: `CameraSessionActor.connect()` is idempotent
   (no-op while connecting/live/reconnecting for the same address; `force = true` only for the manual IP dialog).
2. Slot is claimed synchronously and discovery is serialised (`MultiCamViewModel.onCameraDiscovered`);
   after the handshake `claimCameraIdentity` guarantees one camera never occupies two slots.
3. Time-based supervision (`ReconnectPolicy`): stale after 1.5 s, reconnect after 4 s, exponential backoff with
   jitter, 120 s reconnect window (USB: 2 attempts, first connect: 5). Replaces "40 errors = permanent disconnect".
   UI shows CONNECTING / SIGNAL LOST - RECONNECTING (n). Reconnects retry only the known protocol first.
4. Previous driver is always closed before a new handshake (and on every reconnect), and a handshake that
   finishes after being superseded closes itself instead of leaking a session.
5. Discovery: SSDP bound to the Wi-Fi network; subnet sweep only while no slot owns an address, with
   10 s -> 60 s backoff, TCP pre-check, includes .1 (camera AP mode) and PTP/IP port 15740, pauses in background
   and when Wi-Fi is down. `NetworkBinder.init` is idempotent, sockets are bound before connect, and traffic
   fails fast instead of silently using mobile data.

New: `engine/ReconnectPolicy.kt`, `ReconnectPolicyTest`. Not compiled or run here - build with
`./gradlew detekt testDebugUnitTest assembleDebug` before field use.
