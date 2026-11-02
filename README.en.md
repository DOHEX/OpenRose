# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](composeApp/build.gradle.kts)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#using-openrose-on-android)
[![Status: In development](https://img.shields.io/badge/status-in%20development-orange)](#current-status)

[简体中文](README.md) | **English**

A control client for ROSE / ROSESELSA earphones, built with Kotlin Multiplatform and Compose Multiplatform.

OpenRose separates device protocols, control sessions, and the application UI. It offers Miuix and Material 3 interface styles and integrates with HyperOS system earphone features on Android. Android is the primary development platform; Windows and Linux have desktop UI entry points.

## Features

- View earphone battery levels, firmware versions, and device state.
- Switch noise control modes using home-screen icons: four in one row for i5, six in two rows for i7. Adjust ANC and transparency levels independently and configure touch-cycle participants through a dedicated multi-select picker.
- Toggle game mode on the home screen. Change EQ presets, touch bindings, touch and detection switches, and prompt volume on their respective feature pages.
- Select audio configurations and multipoint connectivity according to model capabilities. The i7 also offers a custom ten-band EQ and firmware-dependent settings such as gain and hearing protection.
- Switch between Miuix and Material 3, with light and dark appearance options. Changing interface style preserves the control connection and device state.
- Android system integration includes the HyperOS system island (超级岛), focus notifications, and Fusion Device Center (融合设备中心), targeting HyperOS 3 and 4.

Available controls depend on the specific model profile and the firmware reported by the device. See the [protocol documentation directory](docs/protocols/) for model protocol specifications and capability research, and [protocol implementation & evidence](docs/protocols.md) for current i5/i7 client details.

Fusion Device Center displays available earbud and case battery levels. Unknown charging states appear as not charging. The system offers only off, noise cancellation, and transparency: wind reduction appears as off without changing the actual earphone mode, which remains visible in OpenRose.

On applicable HyperOS devices, choose the system earphone details page (default) or the OpenRose headphones home under Settings → Fusion Device Center → Additional settings destination. Choosing OpenRose makes the device card's Additional Settings open the headphones home and attempt a control connection to that card's earphones. Connection failures retain the target and reason for troubleshooting or retry. Preferences can be saved before the module is ready and apply to subsequent clicks once it is ready, without restarting the integration scopes. The entry requires the corresponding system component and a loaded module.

This entry has been exercised with an i5 and Xiaomi 14 running OS4.0.0.27.XNCCNXM, MiLink 18.2.2.0.2609141543, and LSPosed 2.2.0-it / API 102: OpenRose redirection, the original system destination, and applying a changed preference on the next click were verified. This entry has not yet been exercised with an i7 or HyperOS 3.

## Current status

The project is under active development and hardware validation.

| Platform | Current implementation |
| --- | --- |
| Android 8.0+ (API 26+) | Bluetooth scanning, BLE control, and conditional i7 SPP control are implemented. The regular client runs independently. |
| HyperOS 3 / 4 | System bridge and presentation adapters are included. They require a framework supporting modern libxposed API 102; the complete system integration still needs hardware validation. |
| Windows / Linux | Shared UI, windows, and preferences are implemented. Bluetooth control transports are not yet implemented. |

| Model profile | Protocol and transport | Hardware validation |
| --- | --- | --- |
| EarFree i5 | ZT / BLE | Records cover 13 initialization reads from one device on Linux. Android control and settings writes await validation. |
| EarFeel i7 | RS Common V2 / BLE, SPP | Protocol implementation and hardware-independent tests are available; no hardware validation yet. |

The same retail name may refer to different hardware variants. Only the model profiles listed above are currently implemented. The first HyperOS integration validation target is an i5 with a Xiaomi 14 running HyperOS 4.

In addition to i5 and i7, reverse engineering has documented protocol specifications for BudsFeel MK2, OpenFeel MK2, Swift 2, Jasmine, and a 43-model capability matrix; see the [protocol documentation directory](docs/protocols/).

## Using OpenRose on Android

1. Install the APK, enable Bluetooth, and pair and connect your earphones for audio in the system Bluetooth settings.
2. Open OpenRose and grant the requested Bluetooth permissions. Android 8–11 also requires location permission and enabled system location services for Bluetooth scanning.
3. Scan for devices in the app, select a recognized pair of earphones, and wait for device state to load before using the controls.
4. For HyperOS system integration, enable the OpenRose module in a compatible framework, then check the module and system bridge status in the app settings.

Island presentation offers Off, Official, and Module modes. Focus notifications are configured separately. Both island presentation modes are rendered by the system UI. Regular Bluetooth control works without enabling the module.

**Control way (ordinary or module).** The System Integration page lets you choose between ordinary control (default) and module control, and shows three separate facts: the chosen way, whether its owner is ready (ready, starting, unavailable with the failing stage, or switch blocked), and the earphone control connection. Module control runs the shared control session inside `com.android.bluetooth`; the OpenRose interface is an authenticated client of it, and opening or closing the interface does not change that session. A switch releases the previous owner first and, if that release cannot be confirmed, does not take over and explains why. “Disable module control” only stops the module and never turns ordinary control on. This path has not been validated on a device yet.

On Android, the System Integration page offers a restart action for the three integration scopes. After confirmation it requests Root; Bluetooth may disconnect, and stopped targets are not started. “Executed” reports the command, not that the process recovered or the module loaded. Root and process behavior still require validation on the target device.

OpenRose manages its **control connection** separately from the system audio connection used for playback and calls. “Disconnect control connection” in the app ends only the OpenRose control connection.

## Building from source

### Prerequisites

- JDK 21. The repository's `.mise.toml` includes a Temurin version configuration.
- Android SDK Platform 37 and Build Tools 37.0.0.
- Set `ANDROID_HOME`, or add `sdk.dir=/path/to/android-sdk` to an untracked `local.properties` file.
- The Linux desktop app also needs GTK 3, EGL / OpenGL, and fontconfig. See the [CI configuration](.github/workflows/ci.yml) for an installation example.

The repository includes the Gradle Wrapper, so a separate Gradle installation is unnecessary. The first build needs internet access to download dependencies. On Windows, replace `./gradlew` in the commands below with `.\gradlew.bat`.

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
```

### Build the Android APK

```bash
./gradlew :androidApp:assembleDebug
```

The APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

### Run the desktop app

```bash
./gradlew :desktopApp:run
```

The desktop app currently lets you explore the UI and change application preferences. Earphone control requires the Android app.

### Checks and tests

```bash
# Protocol and control session tests, shared UI tests, and Android / desktop compilation
./gradlew offlineCheck

# Full checks, including Android unit tests and Lint
./gradlew check offlineCheck
```

`offlineCheck` runs checks that do not require earphone hardware. It still requires the Android SDK. Once dependencies are cached, add `--offline` to prevent Gradle from accessing the network.

[GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) is configured to build and check the project on Windows and Linux. Successful builds upload an Android APK, a Windows ZIP, and a Linux tar.gz. Development build artifacts are retained for 14 days.

## Project structure

| Directory | Responsibility |
| --- | --- |
| `core/` | Model capabilities, protocol codecs, control sessions, the unified control entry (`ControlRuntime`), state, and user settings |
| `composeApp/` | Shared UI, Miuix / Material 3 components, and UI tests |
| `androidApp/` | Android entry point, BLE / SPP transports, libxposed module, and HyperOS system bridge |
| `desktopApp/` | Windows / Linux desktop entry point, windows, and preferences |
| `docs/` | Protocol research & model specifications (`docs/protocols/`), implementation evidence (`docs/protocols.md`), architecture decisions, and specifications |

## Contributing and feedback

Feature requests, bugs, and compatibility reports are tracked in [GitHub Issues](https://github.com/DOHEX/OpenRose/issues). For device issues, include the exact earphone model and firmware, phone model and system version, OpenRose version, and reproduction steps. For system integration issues, also include the framework version and bridge status.

Before contributing, read the [domain glossary](GLOSSARY.md), [architecture decisions](docs/adr/), [protocol documentation](docs/protocols/), and [implementation evidence](docs/protocols.md). When adding a model or system adapter, record protocol evidence, hardware-independent test results, and hardware validation results separately.
