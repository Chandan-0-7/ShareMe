# ShareMe — Kotlin + Compose Multiplatform

Native local-network file sharing. No browser, Python server, account, or cloud relay is used by the native apps.

## Current build

| Platform | Status |
| --- | --- |
| Android 8+ | Native app with file/folder picker, QR scanner, send/receive, and export through Android’s document picker |
| macOS | Native desktop app, built and runnable |
| Windows / Linux | Same JVM desktop source; installer definitions included, not yet tested on those operating systems |
| iPhone / iPad | Shared Compose UI and framework targets compile; native transport and document integration are **not implemented** |

This is a functional Android/desktop engineering preview, not a production release covering every platform.

## Open in Android Studio

Open this directory as a Gradle project and let it sync. Select the `androidApp` configuration and an Android device/emulator, then Run. Use JDK 17 or newer for Gradle, and install Android SDK 36. `local.properties` contains the SDK location for this machine and is ignored by Git; on another machine Android Studio creates it.

Pinned versions: Kotlin 2.4.10, Compose Multiplatform 1.11.1, Android Gradle Plugin 9.3.1, Gradle 9.5.0. The official Android KMP library plugin is used; Android has a separate application module.

```sh
# Run the desktop app (macOS/Linux; use gradlew.bat on Windows)
./gradlew :desktopApp:run

# Build an installable debug APK
./gradlew :androidApp:assembleDebug

# Tests, desktop compilation, and iOS shared-UI compilation
./gradlew :shared:desktopTest :desktopApp:classes :shared:compileKotlinIosSimulatorArm64

# Native Android TLS smoke test (requires a running emulator or connected device)
./gradlew :androidApp:connectedDebugAndroidTest

# Package a desktop app with a bundled Java runtime on the current OS
./gradlew :desktopApp:createDistributable
```

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Verified locally: eight desktop/common tests, one Android emulator TLS/transfer test on API 36.1, Android debug build and lint, iOS simulator shared-source compilation, and macOS application packaging/startup. Android camera scanning and cross-device Wi-Fi transfers still need physical-device testing. The macOS bundle uses package version 1.0.0 because Apple packaging disallows a leading zero; the project remains a 0.1 engineering preview.

## Transfer files

1. Open ShareMe on both devices while connected to the same Wi-Fi.
2. On Android, tap **Scan other device’s QR** and scan the desktop app’s QR. Alternatively, copy a connection link and paste it into the other app.
3. Pairing enables sending in both directions. Choose **Send files** or **Send folder** on either device.
4. Desktop receives into `~/Downloads/ShareMe`. Android receives into its app-specific `Received` folder; choose **Open / export received files** to copy them to a document folder you control. Export Android files before uninstalling the app.

Keep both apps open and devices awake during transfers. QR/link credentials change when the app process restarts. If the computer has multiple network adapters and its QR selects the wrong address, substitute the correct LAN IP in the link; the other fields must stay intact. Firewalls must allow local inbound connections. Guest Wi-Fi can isolate devices.

## Transfer guarantees implemented

- TLS 1.2/1.3 with an ephemeral self-signed certificate pinned by SHA-256 from the connection link. A separate random 128-bit bearer secret authorizes the receiver. Share QR codes/links only with devices you intend to pair.
- Bounded 256 KiB stream buffers; files are never loaded entirely into memory. The sender hashes before transfer; the receiver hashes before completion.
- Folder hierarchy and empty folders are preserved. Unsafe paths, traversal, symlinks on desktop selection, and nonportable names are rejected.
- Interrupted files stay in private partial storage. Network I/O failures retry up to three attempts and resume from the receiver’s byte offset. Cancel and re-send the unchanged file to resume manually. Pause/resume controls and persistent queues are not implemented.
- Completed files are moved out of partial storage after validation. Existing different files receive a numbered name; matching verified retry receipts avoid duplicate files after a lost acknowledgement.
- One incoming file at a time, four concurrent inbound connections, socket timeouts, and an available-space check.

## Structure

- `shared/src/commonMain`: Compose screens, state, and portable path rules.
- `shared/src/jvmSharedMain`: Android/desktop TLS identity, protocol, file streaming, retry, resume, and receipt handling.
- `shared/src/desktopTest`: real-socket integration tests.
- `androidApp`: document-provider adapters, Android scanner, launcher, and app lifecycle.
- `desktopApp`: native desktop window, file chooser, and packaging.
- `shared/src/iosMain`: explicitly disabled iOS UI preview and framework entry point.
- `prototypes/browser`: archived initial experiment, excluded from the native Gradle build.

## Remaining production work

Native iOS transport/file-provider implementation and Xcode launcher; Android foreground-service lifecycle; network-change handling and discovery; persistent queue/history; explicit pause/resume; partial-file expiry/storage controls; release signing; real-device testing across Android OEMs, iOS, Windows, macOS and Linux; accessibility and security review. The current test suite does not establish production readiness.
