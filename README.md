# Knix

Knix is an offline-first Android peer-to-peer file-sharing project.

## Current repository milestone

The Android Kotlin + Jetpack Compose scaffold includes:
- Wi-Fi Direct peer discovery.
- Android NSD/DNS-SD discovery on the local network.
- Optional NSD service publication, enabled only when the TCP listener's active port is supplied.
- Bluetooth LE presence advertising and scanning using a Knix service UUID.
- Runtime permission requests and a basic nearby-device discovery screen.
- Android CI workflow for debug APK builds.

Discovery is not a connection or trust decision. A discovered peer must complete the authenticated KNIX handshake before receiving file data. Bluetooth LE is used for presence discovery, not bulk file transfer. The current screen displays discovered peers; it does not yet connect to them or transfer files.

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
- NSD publication is disabled unless a running TCP listener port is supplied.
- No real two-device test or Android build result is claimed until CI completes and hardware testing is performed.
