# Knix

Knix is an offline-first Android peer-to-peer file-sharing project.

## Current repository milestone

This initial repository scaffold includes:
- Android Kotlin + Jetpack Compose app module.
- Wi-Fi Direct peer discovery.
- Android NSD/DNS-SD discovery and service publication on the local network.
- Bluetooth LE presence advertising and scanning using a Knix service UUID.

Discovery is not a connection or trust decision. A discovered peer must complete the authenticated KNIX handshake before receiving file data. Bluetooth LE is used for presence discovery, not bulk file transfer.

## Offline networking note

The app does not require cloud services, accounts, analytics, or internet access. Android's INTERNET permission is declared because local TCP sockets use the Android networking stack; it does not mean Knix uploads files to the internet.

## Build

Open this project in Android Studio with JDK 17 and Android SDK 35 installed, then run:

```bash
./gradlew :app:assembleDebug
```

The Gradle wrapper is not included yet. Generate it with a locally installed Gradle 8.9 installation before using the command above.

## Permissions

The app requests Nearby Wi-Fi / Nearby Devices permissions at runtime before starting discovery. Android versions up to 12 require location permission for Wi-Fi Direct and BLE scanning. The current UI is a scaffold; runtime permission prompts and discovery controls are the next integration task.
