# Knix

Knix is an offline-first Android peer-to-peer file-sharing project.

## Current repository milestone

The Android Kotlin + Jetpack Compose app currently includes:
- Wi-Fi Direct peer discovery.
- Android NSD/DNS-SD discovery on the local network.
- Optional NSD service publication, enabled only when the TCP listener's active port is supplied.
- Bluetooth LE presence advertising and scanning using a Knix service UUID.
- Runtime permission requests and a basic nearby-device discovery screen.
- A versioned `knix://connect` QR ticket with strict validation, short expiry (15–300 seconds), a cryptographically random one-time challenge, offline QR generation, and camera scanning.
- A one-time TCP pairing listener and a P-256 ECDH handshake with mutual HMAC-SHA-256 key confirmation.
- HKDF-SHA-256 session-key derivation bound to the QR challenge and full ephemeral-key transcript. The resulting 256-bit session key remains in memory only; no pairing key is persisted.
- A short peer-key fingerprint shown after pairing. The handshake rejects mismatched QR challenges and bounds all handshake messages.
- AES-256-GCM authenticated record framing with independent directional nonces, strict frame bounds, and monotonically increasing per-direction sequence numbers.
- Encrypted 1 MiB chunked file transfer, source filename preservation, resume from a persisted partial file after reconnect, prefix-digest matching before resume, and end-to-end SHA-256 verification before the receiver commits the file.
- A QR-driven authenticated batch send/receive flow supporting up to 50 files in one session. The sender selects multiple documents; the receiver compares the peer fingerprint and explicitly approves before encrypted data is accepted; every file is SHA-256 verified before commit.
- A foreground notification service for user-started receive sessions and active sends, helping keep local transfer sessions alive while the app is backgrounded. This does not guarantee recovery after Android kills the process or the user force-stops the app.

A QR ticket is an introduction to a live receiver endpoint, not a transfer channel. It must only be generated after a real TCP listener has bound to a reachable address and port. The ticket does not contain a private key; scanning must be followed by an authenticated handshake and explicit peer approval. Expired, oversized, malformed, or unsupported-version tickets are rejected.

Discovery is not a connection or trust decision. Bluetooth LE is used for presence discovery, not bulk file transfer. QR pairing now completes an ephemeral P-256 ECDH exchange and confirms both sides possess the short-lived QR challenge before deriving a shared session key.

**Security boundary:** the QR challenge is a bearer secret. Anyone who copies the QR while it is valid may attempt to pair, so users should show it only to the intended nearby device and close it after pairing. The transfer channel uses a fresh ECDH session key for each QR pairing. AES-GCM authentication rejects altered records, and per-direction sequence numbers prevent record reordering/replay within a session. A failed or interrupted transfer keeps a temporary partial file for a later QR reconnect; the sender must select the same original file so the receiver can verify the saved prefix. Do not treat this initial implementation as a completed independent security audit.

## Offline networking note

Knix does not require cloud services, accounts, analytics, or internet access. Android's INTERNET permission is declared because local TCP sockets use the Android networking stack; it does not mean Knix uploads files to the internet.

## Quick start

1. Connect both phones to the same Wi-Fi network or one phone's hotspot. Mobile data is not used for the transfer.
2. On the receiving phone, open Knix and tap **Receive**. Keep the QR code visible.
3. On the sending phone, tap **Scan QR** and scan the receiver's code.
4. Compare the peer fingerprint on both phones. The receiving phone must tap **Accept** before any file data is received.
5. On the sending phone, tap **Choose multiple files**, select up to 50 files, review the queue, and tap **Send queued files**.
6. The receiving phone verifies SHA-256 before committing the file to its app-specific Downloads/Knix Received folder. The app displays the exact folder path when complete.

A batch uses one fresh QR pairing and one authenticated TCP session. If interrupted, reconnect and select the same files in the same order: partial files resume after prefix verification, and files already committed are safely re-acknowledged only when their existing size and SHA-256 match the sender's authenticated metadata. To cancel an active session, use **Close active connection**. Cancellation closes the socket; a partially received file remains available for a later retry.

## Build and automated checks

The GitHub Actions workflow installs Gradle 8.9 and Android SDK 35, runs JVM unit/protocol tests, builds the debug APK, and uploads it as an Actions artifact:

```bash
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug
```

For a local build, use JDK 17, Android SDK 35, and Gradle 8.9.

## Permissions

Android 13+ requests Nearby Wi-Fi and Nearby Devices permissions. Android 12 and earlier request location permission for Wi-Fi Direct / BLE scanning, with Bluetooth runtime permissions added on Android 12. Discovery starts only after the requested permissions are granted. Device capabilities and OEM restrictions can still affect whether Wi-Fi Direct or BLE discovery is available.

## Current limitations

- The working transfer path is QR + local TCP over the same Wi-Fi/hotspot. Wi-Fi Direct peer discovery, NSD, and Bluetooth LE presence discovery are implemented, but Wi-Fi Direct group negotiation and automatic bulk-transfer routing are not yet integrated.
- Bluetooth is not used to transfer file contents. Transfers over long distances without internet or another network relay are not possible with the current local-network design.
- The foreground service keeps the app process at foreground-service priority during user-started sessions, but the socket/session is still orchestrated by the activity and receiver rendezvous worker. Process-death recovery, boot recovery, a persistent transfer database, and true service-owned socket execution are not complete.
- Pause/resume controls are not implemented. Cancellation closes the current connection; partial receive data can be resumed by reconnecting and reselecting the same batch in the same order.
- Transfers currently use one TCP stream and fixed 1 MiB chunks. Parallel streams, adaptive chunk sizing, Wi-Fi Direct transport integration, and speed benchmarks remain future work.
- Files are stored in Knix's app-specific Downloads directory, not the public shared Downloads directory.
- CI verifies protocol tests and Android compilation/package creation. Real two-device hardware testing is still required for camera scanner behavior, local IP selection, Android/OEM network restrictions, interrupted-transfer recovery, and actual throughput. No claim that Knix is faster than Xender is made until repeatable phone-to-phone benchmarks support it.
- NSD publication remains optional and is only enabled when a real TCP listener port is supplied.
