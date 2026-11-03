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

Fusion Device Center displays earbud and case battery levels. When an earbud has a known battery level but an unknown charging state, the whole battery readout is withheld until real readings arrive, rather than being shown as not charging; a known case battery level is withheld as well, because the system interface cannot represent the case's charging state. The system offers only off, noise cancellation, and transparency: wind reduction appears as off without changing the actual earphone mode, which remains visible in OpenRose.

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

### System presentation (module control)

During module control, the official connection prompt (in the Xiaomi Bluetooth process), the module system island, and the earphone status notification (in the Bluetooth process) read state directly from the single control session inside the Bluetooth host. Noise-control actions in the notification go back to the same owner, and the display updates only after the earphones reply or a re-read confirms the change. Presentation and actions do not depend on the OpenRose app process; the presenting side opens no control connection and has no command queue of its own.

- The system island (Off / Official / Module), the focus-notification switch, and the control way (ordinary / module) are three independent settings: hiding the island or turning off focus notifications only withdraws that display and does not stop module control or Fusion Device Center. Changing presentation settings neither reconnects the earphones nor sends them any command.
- Only the system entry that owns the current island mode may claim the connection prompt (the Bluetooth process in Module mode, the Xiaomi Bluetooth process in Official mode, no entry when Off), and each connection event is claimed once. Routine battery refreshes, mode changes, and re-selecting module control do not replay a prompt that was already claimed.
- Switching to ordinary control, disabling module control, disconnecting, losing the module, or restarting the host withdraws the prompts, status notification, timers, and notification action references that OpenRose published. Callbacks and actions from an older owner generation are rejected, without affecting a new session or other earphones. Withdrawing an official prompt early still uses `hide_strong_toast`; the system interface carries no device identifier, a boundary recorded in ADR 0004.
- Switching back to module control resumes with the saved presentation preferences, and a new earphone session produces a new connection event.
- This behavior has only offline contract tests. Card appearance, notification updates, the noise-control loop, user dismissal, withdrawal on disconnect, and isolation from other earphones have **not yet been exercised on a device** (i5 with Xiaomi 14 on HyperOS 4).

### Control way (ordinary or module)

The System Integration page lets you choose between ordinary control (default) and module control, and shows three separate facts: the chosen way, whether its owner is ready (ready, starting, unavailable with the failing stage, or switch blocked), and the earphone control connection. Module control runs the shared control session inside `com.android.bluetooth`; the OpenRose interface is an authenticated client of it with the full model controls, and opening or closing the interface does not change that session. A switch releases the previous owner first and, if that release cannot be confirmed, does not take over and explains why. “Disable module control” only stops the module and never turns ordinary control on: the disable choice is saved, and if the module's release of the control connection is not yet confirmed, the result is a blocked switch at the release-confirmation stage; the runtime keeps retrying until the release is proven and never starts ordinary control meanwhile. During a module hot reload, if the old instance's release is not confirmed, the reload is refused and the old instance keeps serving and keeps the connection. This path has not been validated on a device yet.

### Background control (ordinary control)

The “Background control” switch on the settings page is off by default and needs neither root nor the module. When enabled, OpenRose keeps the same control runtime alive in a `connectedDevice` foreground service: after you close the interface or remove the task, an established control connection stays open and a required running notification is shown (you can disable background control directly from it).

- The required running notification has its own notification channel, independent of the focus-notification and system-island settings. On Android 13 and later, if the notification permission is not granted, the notification is hidden while the service keeps running; the settings page says so and offers an “allow notifications” entry.
- When the Bluetooth permission is missing, the system restricts background starts, or the foreground service is refused, the settings page shows the specific reason and never displays the saved switch as “running”.
- The foreground service is started when you open OpenRose (the switch, launching the interface, or returning from a permission grant). It can also be started with no process present by the boot, earphone audio-connection, and device-presence events described under “Automatic wake-up after the process is gone or the phone restarts” below. A wake-up only asks to start that service; whether it starts still depends on the saved switch, the first unlock, and permissions. Background starts are restricted on Android 12 and later, so on those versions the wake-up relies on the exemption that a companion-device association grants.
- Turning the switch off ends background operation and removes the running notification. It does not disconnect system audio and does not disconnect a control connection you are using.
- “Force stop” in system settings, or stopping OpenRose in the task manager, ends background control and OpenRose does not try to bypass this. Some systems (such as HyperOS) also require allowing OpenRose to autostart and removing battery restrictions in system settings; OpenRose cannot detect these settings.
- Device-side observation (i5 reads and settings, closing the interface and removing the task, the running notification, user disable) has not yet been performed on a device, and Android version-specific behavior still needs testing.

#### Automatic wake-up after the process is gone or the phone restarts

- Android 8–11: there is no device-presence API and no association is needed. Earphone audio-connection (A2DP/HFP) broadcasts and the boot broadcast wake OpenRose through a manifest receiver, which then starts the background service.
- Android 12–13: use “Associate earphones” on the settings page. The system's companion-device association and device-presence observation wake OpenRose after the process is gone, and the association also grants the exemption for starting a foreground service from the background.
- Android 14 and later: the same, and the `connectedDevice` foreground service type plus a granted Bluetooth permission are required; when they are missing the settings page gives the reason and does not show wake-up as ready.
- After a restart nothing is controlled before the first unlock; after the first unlock control resumes from the saved settings, and earphones that were already audio-connected before that are recognized. Once background control is disabled, boot, audio-connection, and device-presence events no longer start the service, and the device-presence observation is withdrawn.
- If the association is cancelled, a permission is missing, or the system refuses, the settings page shows automatic wake-up as not ready; ordinary foreground control while the app is open still works, and you can finish the missing conditions and enable background recovery afterwards. “System conditions configured” only means the verifiable conditions are met, not that the ROM will definitely wake OpenRose.
- The wake-up behavior (per-Android-version bands, HyperOS autostart policy, recovery at first unlock after a restart, cancelled associations, and permission failures) has not yet been verified on a device; only contract tests with simulated events exist.

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
