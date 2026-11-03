# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](composeApp/build.gradle.kts)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#getting-started)
[![Status: In development](https://img.shields.io/badge/status-in%20development-orange)](#supported-earphones)

[简体中文](README.md) | **English**

OpenRose is an unofficial control app for ROSE / ROSESELSA (弱水时砂) earphones and a replacement for the official RoseLink app for everyday settings. On HyperOS it can also plug the earphones into the system island, focus notifications, and Fusion Device Center, so they behave much like Xiaomi's own earbuds.

It is built with Kotlin Multiplatform and Compose Multiplatform and ships two interface styles, Miuix and Material 3. Android is the main platform. The Windows / Linux desktop builds have the UI only and cannot control earphones yet.

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

**HyperOS integration** (requires an Xposed framework, see [below](#hyperos-integration))

- Connection prompt and status notification, shown through the system island / focus notifications
- Earbud and case battery in Fusion Device Center, plus noise mode switching from there
- Fusion Device Center's "Additional Settings" can open OpenRose directly

## Getting started

Requires Android 8.0 (API 26) or later. The app UI is currently Chinese only, so UI labels below include the Chinese text you will see.

1. Download the APK from [Releases](https://github.com/DOHEX/OpenRose/releases). Development builds are available as artifacts on [GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) (GitHub sign-in required).
2. Pair and connect the earphones in the **system Bluetooth settings** first. OpenRose does not handle audio pairing.
3. Open OpenRose and grant Bluetooth permissions. On Android 8–11, Bluetooth scanning also needs location permission with system location services turned on.
4. Paired earphones show up on the My Earphones (我的耳机) page. When they connect to system audio, OpenRose opens its control connection automatically; you can also tap Connect (连接). To add new earphones, tap **+** in the top bar.

OpenRose's **control connection** is separate from the **audio connection** the system uses for music and calls. "Disconnect control connection" (断开控制连接) in the app only disconnects OpenRose; playback is not affected.

## HyperOS integration

Skip this section if you only need Bluetooth controls.

**Requirements**

- HyperOS 3 or 4
- A framework supporting libxposed API 102 (for example LSPosed 2.2.0 or later)
- The OpenRose module enabled in the framework, scoped to `com.android.bluetooth`, `com.xiaomi.bluetooth`, and `com.milink.service`

Then check the System Integration (系统集成) page in OpenRose to confirm that the module and system bridge are ready.

**Settings** (on the Settings (设置) page)

| Setting | Options | What it does |
| --- | --- | --- |
| Island (超级岛) | Off / Official / Module | Official reuses Xiaomi's earphone connection prompt; Module uses OpenRose's own style and triggers. Both are rendered by the system island |
| Focus notification (焦点通知) | On / Off | Keeps an earphone status notification while connected |
| Additional settings destination (附加设置入口) | System details page / OpenRose | With OpenRose selected, the Additional Settings button on the Fusion Device Center card opens OpenRose and connects to those earphones |

**Known limitations**

- The system only knows Off / ANC / Transparency. When the earphones are in wind-noise reduction, Fusion Device Center shows Off; the real mode is unchanged and visible in OpenRose.
- If the earphones do not report charging state, the system shows them as not charging.
- The top-right action on the System Integration page uses Root to restart the three scoped processes so the module reloads. Bluetooth may drop briefly; processes that were not running are not started. "Executed" only means the command was sent.

**Testing status**: the Fusion Device Center Additional Settings redirect has been verified on an i5 with a Xiaomi 14 (OS4.0.0.27.XNCCNXM, MiLink 18.2.2.0.2609141543, LSPosed 2.2.0-it / API 102). The island, focus notifications, the i7, and HyperOS 3 have not been fully tested on hardware.

## Platform support

| Platform | Status |
| --- | --- |
| Android 8.0+ | Bluetooth scanning, BLE control, and SPP control for the i7 are implemented |
| HyperOS 3 / 4 | Integration code is complete; awaiting hardware testing |
| Windows / Linux | UI and preferences work; Bluetooth control is not implemented |

## Building from source

**Requirements**

- JDK 21 (`.mise.toml` pins a Temurin version you can install with [mise](https://mise.jdx.dev/))
- Android SDK Platform 37 and Build Tools 37.0.0
- `ANDROID_HOME` set, or `sdk.dir=/path/to/android-sdk` in `local.properties`
- On Linux, the desktop app also needs GTK 3, EGL / OpenGL, and fontconfig; see the [CI configuration](.github/workflows/ci.yml) for the install command

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

Run the tests that need no earphone hardware (protocol, control session, and shared UI tests, plus Android / desktop compilation):

```bash
./gradlew offlineCheck
```

Run the full check, which adds Android unit tests and Lint:

```bash
./gradlew check offlineCheck
```

`offlineCheck` still needs the Android SDK. Once dependencies are downloaded, add `--offline` to run without network access.

CI builds and tests on Linux and Windows and publishes an Android APK, a Windows ZIP, and a Linux tar.gz, kept for 14 days. Pushing a `vX.Y.Z` tag creates a draft release with a signed APK.

## Project layout

| Directory | Contents |
| --- | --- |
| `core/` | Platform-independent core: model capabilities, protocol codecs, control session, user settings |
| `composeApp/` | Shared UI (Miuix / Material 3) and UI tests |
| `androidApp/` | Android entry point, BLE / SPP transports, Xposed module, HyperOS system bridge |
| `desktopApp/` | Windows / Linux entry point |
| `docs/` | Protocol documentation and architecture decision records |

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
- For system integration problems, the Xposed framework version and the bridge status shown on the System Integration page

Before contributing, read the [glossary](GLOSSARY.md) and [architecture decisions](docs/adr/). When adding a model or system integration, record the protocol evidence, offline test results, and hardware test results separately.

## Acknowledgements

The HyperOS integration draws on ideas from [OppoPods](https://github.com/1812z/OppoPods).
