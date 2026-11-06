# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](sharedUI/build.gradle.kts)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#getting-started)
[![Status: In development](https://img.shields.io/badge/status-in%20development-orange)](#supported-earphones)

[简体中文](README.md) | **English**

OpenRose is an unofficial control app for ROSE / ROSESELSA (弱水时砂) earphones and a replacement for the official RoseLink app for everyday settings. On HyperOS it can also plug the earphones into the system island, focus notifications, and Fusion Device Center, so they behave much like Xiaomi's own earbuds.

It is built with Kotlin Multiplatform and Compose Multiplatform and ships two interface styles, Miuix and Material 3. Android is the main platform. Linux desktop provides BLE control through BlueZ; Windows desktop currently provides the UI only.

> [!WARNING]
> OpenRose is under active development and most features have not been fully tested on real hardware. See [Supported earphones](#supported-earphones) for details, and please [report problems](#feedback-and-contributing).

## Supported earphones

| Earphones | Protocol / control channel | Tested on hardware |
| --- | --- | --- |
| EarFree i5 (EarFeel i5) | ZT / BLE | Status reads work on a real device; settings writes not yet tested on Android |
| EarFeel i7 | RS Common V2 / BLE or SPP | Protocol and offline tests done; not yet tried on a real device |

One retail name can cover several hardware revisions. OpenRose currently implements only the two model profiles above. Protocol research for other models (BudsFeel MK2, OpenFeel MK2, Swift 2, Jasmine, and a capability list of 43 models) lives in [docs/protocols/](docs/protocols/) but is not wired into the app yet.

## Features

**Earphone controls** (what you see depends on the model and firmware)

- Battery for each earbud and the case; firmware version
- Noise control mode, with separate ANC and transparency levels; choose which modes the touch gesture cycles through
- Game mode, EQ presets, touch bindings, touch on/off, wear detection on/off, prompt volume
- Audio codec preference and multipoint (linked to each other on the i5)
- i7 only: custom 10-band EQ; gain and hearing protection on supported firmware

**Appearance**

- Miuix or Material 3 Expressive styles; switching keeps the earphones connected
- Light / dark, standard or floating bottom bar, background blur, page transitions

**Background control** (no root needed, see [below](#background-control))

- Keeps the control connection after you close the app, with a running notification
- Restores control when the earphones reconnect after the app process was reclaimed or the phone restarted

**HyperOS integration** (requires an Xposed framework, see [below](#hyperos-integration))

- Two control ways: ordinary control, where the OpenRose app connects to the earphones; module control, where the system Bluetooth process connects and OpenRose does not need to be open
- Connection prompt and status notification, shown through the system island / focus notifications
- Earbud battery in Fusion Device Center, plus noise mode switching from there
- Fusion Device Center's "Additional Settings" can open OpenRose directly

## Getting started

Requires Android 8.0 (API 26) or later. The app UI is currently Chinese only, so UI labels below include the Chinese text you will see.

1. Download the APK from [Releases](https://github.com/DOHEX/OpenRose/releases). Development builds are available as artifacts on [GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) (GitHub sign-in required).
2. Pair and connect the earphones in the **system Bluetooth settings** first. OpenRose does not handle audio pairing.
3. Open OpenRose and grant Bluetooth permissions. On Android 8–11, Bluetooth scanning also needs location permission with system location services turned on.
4. Paired earphones show up on the My Earphones (我的耳机) page. When they connect to system audio, OpenRose opens its control connection automatically; you can also tap Connect (连接). To add new earphones, tap **+** in the top bar.

OpenRose's **control connection** is separate from the **audio connection** the system uses for music and calls. "Disconnect control connection" (断开控制连接) in the app only disconnects OpenRose; playback is not affected.

## Background control

Skip this section if you only use OpenRose while it is open. Background control is part of ordinary control and needs neither root nor the module.

The Background control (后台控制) switch on the Settings page is off by default. When on, OpenRose runs as a foreground service (type `connectedDevice`): after you close the app or swipe away its task, the control connection stays open and a running notification is shown, from which you can turn background control off.

- The running notification has its own notification channel, independent of the focus notification and island settings. On Android 13 and later without notification permission, the notification is hidden but the service keeps running, and the Settings page offers an Allow notifications (允许通知) action.
- When the Bluetooth permission is missing, the system restricts background starts, or the foreground service is refused, the Settings page shows the reason and never shows the switch as running.
- Turning the switch off ends background operation and the running notification. It does not disconnect system audio or a control connection you are using. With background control off, the control connection ends when you leave the app.
- Force-stopping OpenRose in system settings ends background control, and OpenRose does not restart itself. HyperOS and some other systems also require allowing autostart and removing battery restrictions by hand; OpenRose cannot detect either.

**Automatic recovery after the process is reclaimed or the phone restarts**

| Android version | How OpenRose is woken |
| --- | --- |
| 8–11 | No extra setup. Earphone audio connection (A2DP / HFP) and boot broadcasts wake OpenRose |
| 12–13 | Tap Associate earphones (关联耳机) on the Settings page; the system companion-device mechanism wakes OpenRose when the earphones appear, and the association also allows starting the foreground service from the background |
| 14 and later | Same as 12–13, and the Bluetooth permission must be granted |

- After a restart nothing is controlled before the first unlock; after it, control resumes from the saved settings, and earphones already connected before the unlock are recognized.
- With background control off, boot, audio connection, and device presence events no longer start the service.
- If the association is cancelled, a permission is missing, or the system refuses, the Settings page shows automatic wake-up (自动唤起) as not ready, and control still works while the app is open. "System conditions configured" (系统条件已配置) only means the checkable conditions are met; it does not guarantee the ROM will wake OpenRose.

**Testing status**: background control and automatic wake-up have only offline tests with simulated events. They have not been verified on a device, and behavior on each Android version is untested.

## HyperOS integration

Skip this section if you only need Bluetooth controls.

**Requirements**

- HyperOS 3 or 4
- A framework supporting libxposed API 102 (for example LSPosed 2.2.0 or later)
- The OpenRose module enabled in the framework, scoped to `com.android.bluetooth`, `com.xiaomi.bluetooth`, and `com.milink.service`

Then check the Status (状态) page in OpenRose to confirm that the module is ready; open diagnostics from there to see the system bridge.

**Control way** (控制方式, on the Settings page; ordinary control by default)

- **Ordinary control** (普通控制): the OpenRose app connects to the earphones itself and needs no module. The module's island, status notification, and Fusion Device Center controls are paused, and anything OpenRose published there is withdrawn.
- **Module control** (模块控制): the module connects to the earphones inside the system Bluetooth process (`com.android.bluetooth`). It works from the first unlock after boot without opening OpenRose, and closing the app, the app being reclaimed, or force-stopping it does not affect it. When you open OpenRose, the app uses the module's connection, with the same full model controls as ordinary control.

A switch closes the old control connection before the new side connects and does not interrupt playback or calls. If OpenRose cannot confirm that the old side has disconnected, the switch does not complete; the Settings page says it is unfinished and the Status page explains why. When the module is temporarily unavailable, the app says so and does not fall back to ordinary control; once you choose ordinary control, the module coming back or reloading does not take control back.

**Settings** (on the Settings (设置) page)

| Setting | Options | What it does |
| --- | --- | --- |
| Island (超级岛) | Off / Official / Module | Official reuses Xiaomi's earphone connection prompt; Module uses OpenRose's own style and triggers. Both are rendered by the system island |
| Focus notification (焦点通知) | On / Off | Keeps an earphone status notification while connected |
| Additional settings destination (附加设置入口) | System details page / OpenRose | With OpenRose selected, the Additional Settings button on the Fusion Device Center card opens OpenRose and connects to those earphones |
| Off uses wind-noise reduction (关闭降噪使用抗风噪) | On / Off (default Off) | Tapping Off in Fusion Device Center or the system headset UI writes wind-noise reduction instead of normal mode, on models that have it; other models keep normal mode. Wind-noise reduction already shows as Off there |

The island, focus notification, and control way do not affect each other: hiding the island or turning off focus notifications only withdraws that display; it does not stop module control or Fusion Device Center and does not reconnect the earphones.

**Known limitations**

- The system only knows Off / ANC / Transparency. When the earphones are in wind-noise reduction, Fusion Device Center shows Off; the real mode is unchanged and visible in OpenRose. Tapping Off writes normal mode unless "Off uses wind-noise reduction" is on.
- When an earbud's battery is known but its charging state is unknown, Fusion Device Center does not show that battery rather than showing it as not charging. The case battery is not shown either, because the system interface cannot represent whether the case is charging.
- The top-right action on the Status page uses Root to restart scoped processes so the module reloads. The dialog lets you tick which processes to restart (all by default). After you confirm, the same dialog stays open: its text shows the current step (requesting Root, terminating processes, then waiting for new processes with a countdown), and each checkbox turns in place into that process's status: waiting, in progress (spinner), and its result; unticked processes stay where they are, marked "not chosen". The chosen processes are terminated together and share one wait, so each result appears as soon as that process is back; failure reasons appear once everything has finished. Only SIGTERM is sent, never a force stop; after sending, OpenRose waits up to 8 seconds and shows "restarted" only once a new process with the same name appears. A process that was terminated but did not come back in time (on-demand processes need not start by themselves) shows "terminated, waiting for the system to start it" and is not a failure; a process that was not running is not started. Bluetooth may drop briefly. "Restarted" only means a new process appeared, not that the module took effect.
- While module control is chosen, the app asks the system Bluetooth host for its status every 5 seconds. After 3 consecutive probes with no answer within 2 seconds, the Status page shows "System integration unresponsive" and withdraws the earbud readings instead of showing the old ones as current. When the host answers again, the readings are fetched afresh. This check has only offline tests and has not been verified on hardware.

**Testing status**: the Fusion Device Center Additional Settings redirect has been verified on an i5 with a Xiaomi 14 (OS4.0.0.27.XNCCNXM, MiLink 18.2.2.0.2609141543, LSPosed 2.2.0-it / API 102). Module control, switching control ways, module autonomy (recovery after boot, force-stop, host reload), the island, and focus notifications have only offline tests, and the i7 and HyperOS 3 have not been fully tested on hardware.

## Platform support

| Platform | Status |
| --- | --- |
| Android 8.0+ | Bluetooth scanning, BLE control, and SPP control for the i7 are implemented |
| HyperOS 3 / 4 | Integration code is complete; awaiting hardware testing |
| Linux x64 | BlueZ BLE scanning and control, paired devices, and control tied to system audio connections; full desktop i5 hardware acceptance is pending |
| Windows | UI and preferences work; Bluetooth control is not implemented |

Linux uses the system BlueZ service and D-Bus, requires no Android-style permission request, and does not switch the adapter on or off. The Status page explains a missing BlueZ service, a powered-off adapter, or D-Bus access denial. An optional Bluetooth settings action probes GNOME, KDE, then Blueman. Only one application instance runs; launching again brings the existing window forward. The system manages audio connections.

The desktop app has a tray icon. Clicking it opens the tray panel, which shows the control connection and battery levels, switches the noise mode, and reconnects when disconnected; the tray menu offers the same quick controls plus Tray panel, Open OpenRose, and Quit. Background control in Settings is on by default: while the tray is available, closing the window only hides it and keeps the control connection, and Quit in the tray ends it. When the tray is unavailable (for example GNOME without the AppIndicator extension), closing the window quits and the Status page explains why. Launch at login is off by default; with background control on and the tray available, a login launch shows only the tray icon. The Linux tray uses StatusNotifierItem and requires `libsystemd`. GNOME and KDE are supported per the library documentation without hardware verification. See [ADR 0014](docs/adr/0014-desktop-tray-and-background-control.md).

On unrecognized desktops such as niri, Hyprland, or Sway, the tray panel is an ordinary window placed by the compositor, titled exactly “OpenRose 托盘面板”; it closes when the pointer leaves it after entering, when focus moves away, or on Esc. A window rule can float it near the tray, for example in niri:

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```

Linux currently supports BLE only, without SPP, OTA, or Android system integration. The i5 uses the shared protocol's 13 initialization reads and setting readback confirmation. When Classic Bluetooth is connected without GATT services, the driver connects the other BLE bearer and waits for services without deleting pairing records. The i7 remains untested on hardware. Offline tests with fake D-Bus objects cover platform events and error mapping. Earlier Linux / Bleak i5 read evidence is documented in the [protocol notes](docs/protocols/models/earfeel-i5-protocol.md) and does not validate this desktop implementation. Maintainer acceptance is still required for i5 scanning through control readiness, comparison of all 13 reads, every setting's reply, audio connection/disconnection, dual-mode recovery, and clean-system DEB/RPM/Pacman installation and launch.

Linux starts automatic control from A2DP audio connections observed through BlueZ `MediaTransport1`. An HFP-only call connection does not start control automatically; connect manually through the add-earphones flow or paired devices.

## Building from source

**Requirements**

- JDK 21 (`.mise.toml` pins a Temurin version you can install with [mise](https://mise.jdx.dev/))
- Android SDK Platform 37 and Build Tools 37.0.0
- `ANDROID_HOME` set, or `sdk.dir=/path/to/android-sdk` in `local.properties`
- On Linux, the desktop app needs a running BlueZ service, access to the system D-Bus, a powered BLE adapter, GTK 3, EGL / OpenGL, and fontconfig. DEB, RPM, and Pacman declare a `bluez` dependency; AppImage also uses the host's BlueZ service

The Gradle Wrapper is included. On Windows, replace `./gradlew` with `.\gradlew.bat`.

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
```

Build the Android APK (output: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`):

```bash
./gradlew :androidApp:assembleDebug
```

Run the desktop app:

```bash
./gradlew :desktopApp:run
```

Run the checks that need no earphone hardware (protocol, control session, BlueZ drivers and event mapping with fake D-Bus objects, shared UI, and Android / desktop preference persistence tests, compilation for both platforms, plus formatting, static analysis, Android Lint, coverage floors and the check that the Chinese and English READMEs keep the same structure):

```bash
./gradlew offlineCheck
```

Run the full check that CI runs:

```bash
./gradlew check offlineCheck
```

`offlineCheck` still needs the Android SDK. Once dependencies are downloaded, add `--offline` to run without network access. If formatting fails, run `./gradlew spotlessApply`; [CONTRIBUTING.md](CONTRIBUTING.md) (Chinese) explains each check.

UI tests run on a virtual clock, with test classes in parallel (half the available cores as forks). Whole-app rendering covers navigation, cross-page flows, style-switch continuity, and main-tab swipes. Other tests render pages or sections with a fake presentation model in both component styles; Android-specific sections are tested in `androidIntegrationUI/`.

Package the desktop app. Linux produces DEB, RPM, Pacman and AppImage (x64) and Windows produces an MSI; each host builds only the formats it supports, into `desktopApp/build/compose/binaries/main/`:

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

CI runs the tests on Linux only. Linux publishes the Android APK and the four Linux packages, Windows the MSI, kept for 14 days. Releases go through release-please: merging the release PR it opens from the commit history tags `vX.Y.Z` and creates a draft release with the signed APK and the installers for each platform.

## Project layout

| Directory | Contents |
| --- | --- |
| `core/` | Model capabilities, protocol codecs, control sessions, the unified control entry (`ControlRuntime`), shared GATT connections, paired-device and audio inference, state, and control settings (force BLE) |
| `androidIntegration/` | Android integration (pure Kotlin, tested on the JVM): control way and owner handoff, module host and client, IPC encoding, system entries, system presentation, Fusion Device Center mapping, background control, and their settings |
| `sharedUI/` | Shared UI, presentation model and platform slots, Miuix / Material 3 components, appearance settings, and UI tests |
| `androidIntegrationUI/` | Android-specific UI sections: control way, background control, integration status and diagnostics, permissions, and scope restart; KMP Android / JVM |
| `androidApp/` | Android entry point, primitive GATT driver and SPP transport, audio profile proxies and broadcasts, libxposed module, and HyperOS system bridge |
| `transportBluez/` | Linux BlueZ D-Bus GATT primitives, scanning, paired devices, and audio facts; reuses shared GATT and audio inference |
| `desktopApp/` | Windows / Linux desktop entry point, windows, and preferences |
| `build-logic/` | Gradle convention plugins shared by the modules: toolchain, JVM bytecode target, Android and desktop packaging setup, static analysis and coverage, and the README structure check |
| `config/` | detekt rules and each module's baseline of existing findings |
| `docs/` | Protocol research & model specifications (`docs/protocols/`), implementation evidence (`docs/protocols.md`), architecture decisions, and specifications |

Each layer owns its settings codec and shares the platform's key-value store: control keys use `core.`, appearance keys use `sharedUI.`, and Android integration keys use `androidIntegration.`. The module host receives only control and integration settings. Old unprefixed keys are ignored without migration; preferences must be selected again when using this version.

Both entry points hold `RuntimeOpenRoseModel`, the control entry, settings store, and host coroutine scope, then pass the model and `PlatformUi` to `OpenRoseApp`. Platforms supply sections and system actions through slots; shared UI never branches on platform names. Desktop creates its runtime only after acquiring single-instance ownership, uses `transportBluez` on Linux, and exposes live transport availability and reasons on the Status page and in the add-earphone flow.

## Documentation

Most documentation is in Chinese.

| Document | Contents |
| --- | --- |
| [Protocol implementation](docs/protocols.md) | How OpenRose implements the i5 / i7 protocols: frame formats, field encodings, setting commands, test coverage |
| [Protocol research](docs/protocols/) | Protocol specs for all models, capability list, and OTA research, reverse-engineered from the official RoseLink app |
| [Architecture decisions](docs/adr/) | Key design decisions and their reasoning |
| [Glossary](GLOSSARY.md) | Domain terms used across the project |

## Feedback and contributing

Report bugs and request features in [GitHub Issues](https://github.com/DOHEX/OpenRose/issues). For device problems, please include:

- Earphone model and firmware version
- Phone model and OS version
- OpenRose version
- Steps to reproduce
- For system integration problems, the Xposed framework version and the bridge status shown in diagnostics (reached from the Status page)

Before contributing, read [CONTRIBUTING.md](CONTRIBUTING.md) (Chinese), the [glossary](GLOSSARY.md) and the [architecture decisions](docs/adr/). When adding a model or system integration, record the protocol evidence, offline test results, and hardware test results separately.

## License

OpenRose is licensed under [GPL-3.0-or-later](LICENSE).

## Acknowledgements

The HyperOS integration draws on ideas from [OppoPods](https://github.com/1812z/OppoPods).
