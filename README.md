# Knix

Knix is an offline-first Android peer-to-peer file-sharing project.

## Current repository milestone

The Android Kotlin + Jetpack Compose scaffold includes:
- Wi-Fi Direct peer discovery.
- Android NSD/DNS-SD discovery on the local network.
- Optional NSD service publication, enabled only when the TCP listener's active port is supplied.
- Bluetooth LE presence advertising and scanning using a Knix service UUID.
- Runtime permission requests and a basic nearby-device discovery screen.
- A versioned `knix://connect` QR ticket model with strict field validation, short expiry (15–300 seconds), a cryptographically random one-time challenge, and offline QR bitmap generation using ZXing.

A QR ticket is an introduction to a live receiver endpoint, not a transfer channel. It must only be generated after a real TCP listener has bound to a reachable address and port. The ticket does not contain a private key; scanning must be followed by an authenticated handshake and explicit peer approval. Expired, oversized, malformed, or unsupported-version tickets are rejected.

Discovery is not a connection or trust decision. A discovered peer must complete the authenticated KNIX handshake before receiving file data. Bluetooth LE is used for presence discovery, not bulk file transfer. The current screen displays discovered peers; QR scanning/display is not yet wired into the screen, and there is not yet a TCP listener or file transfer flow.

## Offline networking note

Knix does not require cloud services, accounts, analytics, or internet access. Android's INTERNET permission is declared because local TCP sockets use the Android networking stack; it does not mean Knix uploads files to the internet.

## Build

The GitHub Actions workflow installs Gradle 8.9 and Android SDK 35, then runs:

```bash
gradle --no-daemon :app:assembleDebug
```

For a local build, use JDK 17, Android SDK 35, and Gradle 8.9.

## Permissions

Android 13+ requests Nearby Wi-Fi and Nearby Devices permissions. Android 12 and earlier request location permission for Wi-Fi Direct / BLE scanning, with Bluetooth runtime permissions added on Android 12. Discovery starts only after the requested permissions are granted. Device capabilities and OEM restrictions can still affect whether Wi-Fi Direct or BLE discovery is available.

## Current limitations

- Discovery is implemented, but peer connection and transfer orchestration are not yet wired to the screen.
- QR ticket parsing and generation primitives are implemented; camera scanning and the display flow await a real listening endpoint.
- NSD publication is disabled unless a running TCP listener port is supplied.
- No real two-device test or Android build result is claimed until CI completes and hardware testing is performed.
