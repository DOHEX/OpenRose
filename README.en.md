# OpenRose

[![CI](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml/badge.svg)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/DOHEX/OpenRose?include_prereleases)](https://github.com/DOHEX/OpenRose/releases)
[![License](https://img.shields.io/github/license/DOHEX/OpenRose)](LICENSE)

[简体中文](README.md) | **English**

OpenRose is an unofficial control app for ROSE / ROSESELSA (弱水时砂) earphones and a replacement for the official RoseLink app for everyday settings. On HyperOS it can also plug the earphones into the system island, focus notifications, and Fusion Device Center. The UI comes in two styles, Miuix and Material 3, and runs on Android and on Linux and Windows desktops (see [Platform support](#platform-support)).

> [!WARNING]
> OpenRose is under active development and most features have not been fully tested on real hardware. See [Supported earphones](#supported-earphones) for details, and please [report problems](#feedback).

## Supported earphones

| Earphones | Connection | Status |
| --- | --- | --- |
| EarFree i5 (EarFeel i5) | BLE | Reads verified on a real device; settings writes on Android not verified |
| EarFeel i7 | BLE or SPP | Offline tests only; not tested on a real device |
| BudsFeel MK2 | BLE or SPP | Not tested on a real device |
| OpenFeel MK2 | BLE or SPP | Not tested on a real device |
| Swift 2 | BLE or SPP | Not tested on a real device |
| Jasmine | BLE or SPP | Not tested on a real device |
| EarFree i3 | BLE | Not tested on a real device |
| HeadFeel (HeadFree) | BLE or SPP | Single-unit battery; not tested on a real device |
| AGATE, AGATE X, CERAMICS | BLE | Not tested on a real device |
| CERAMICS-X | BLE | Not tested on a real device |
| CERAMICS-X 7034, EXPLORER | BLE or SPP | Not tested on a real device |
| BudsFree i, SWIFT | BLE | Not tested on a real device |
| Cambrian, OpenFun, SportFeel (SportFree) | BLE or SPP | Not tested on a real device |
| OpenFeel (OpenFree, LDAC / LHDC / dual-codec branches) | BLE | Not tested on a real device |

Hardware branches with the same retail name are identified by advertisement prefixes; an ambiguous name does not select a branch automatically. Other models are not adapted yet. See the [model adaptation evidence ledger](docs/protocols/model-adaptation-ledger.md) for each model's sources and omitted controls, and [docs/protocols/](docs/protocols/) for the full protocol research.

## Features

**Earphone controls** (what you see depends on the model and firmware)

- Battery for each earbud and the case, firmware version; noise control mode with separate ANC and transparency levels
- Game mode, EQ presets, touch bindings, wear detection, prompt volume, multipoint, and audio codec preference
- Custom EQ, gain and hearing protection, and bass boost on some models

**Appearance**

- Miuix or Material 3 Expressive styles; switching keeps the earphones connected
- Light / dark, standard or floating bottom bar, background blur

**Background control** (no root needed, see [Background control](#background-control))

- Keeps the control connection after you close the app, with a running notification
- Restores control when the earphones reconnect after the app process was reclaimed or the phone restarted

**HyperOS integration** (requires an Xposed framework, see [HyperOS integration](#hyperos-integration))

- Connection prompts and earphone status shown through the system island and focus notifications
- Earbud battery and noise mode switching in Fusion Device Center; its "Additional Settings" can open OpenRose directly
- Module control: the system Bluetooth process connects to the earphones, and OpenRose does not need to be open

## Getting started

Requires Android 8.0 (API 26) or later. The app UI is currently Chinese only, so UI labels below include the Chinese text you will see.

1. Download the APK from [Releases](https://github.com/DOHEX/OpenRose/releases); development builds are available as artifacts on [GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) (GitHub sign-in required).
2. Pair and connect the earphones in the **system Bluetooth settings** first. OpenRose does not handle audio pairing.
3. Open OpenRose and grant the Bluetooth permission (on Android 8–11 also location permission, with system location services turned on). Paired earphones show up on the My Earphones (我的耳机) page, and the control connection opens automatically once they connect to system audio; add new earphones with **+** in the top right.

OpenRose's **control connection** is separate from the **audio connection** the system uses for music and calls. "Disconnect control connection" (断开控制连接) in the app only disconnects OpenRose; playback is not affected.

## Background control

The Background control (后台控制) switch on the Settings page is off by default. When on, the control connection stays open after you close the app or swipe away its task, and a running notification is shown, from which you can turn it off.

- Android 8–11: after the process is reclaimed or the phone restarts, no extra setup is needed; the earphones' audio connection wakes OpenRose.
- Android 12 and later: tap Associate earphones (关联耳机) on the Settings page; Android 14 and later also need the Bluetooth permission granted.
- HyperOS and similar systems need autostart allowed and battery restrictions removed for OpenRose by hand; OpenRose cannot detect either.
- Force-stopping OpenRose in system settings ends background control, and it does not restart itself.

## HyperOS integration

**Requirements**

- HyperOS 3 or 4
- A framework supporting libxposed API 102 (for example LSPosed 2.2.0 or later)
- The OpenRose module enabled in the framework, scoped to `com.android.bluetooth`, `com.xiaomi.bluetooth`, and `com.milink.service`

Then check the Status (状态) page in OpenRose to confirm that the module is ready.

Choose the control way (控制方式) on the Settings page: ordinary control (普通控制, the default) has the OpenRose app connect to the earphones itself; module control (模块控制) has the system Bluetooth process connect, works from the first unlock after boot, and does not need OpenRose to be open.

**Known limitations**

- The system only knows Off / ANC / Transparency. When the earphones are in wind-noise reduction, Fusion Device Center shows Off and the real mode is unchanged; turn on "Off uses wind-noise reduction" (关闭降噪使用抗风噪) on the Settings page to make tapping Off write wind-noise reduction instead of normal mode.
- When an earbud's battery is known but its charging state is unknown, Fusion Device Center does not show that battery; the case battery is not shown either.

## Platform support

| Platform | Status |
| --- | --- |
| Android 8.0+ | BLE and SPP (i7) control implemented; background control has only offline tests |
| HyperOS 3 / 4 | Additional Settings redirect verified on an i5 with a Xiaomi 14; the rest has only offline tests |
| Linux x64 | BLE control implemented; full desktop i5 hardware acceptance pending |
| Windows | UI and preferences work; Bluetooth control is not implemented |

The Linux app provides BLE control through the system BlueZ service and D-Bus, without SPP, OTA, or system integration. The tray icon requires `libsystemd`, and GNOME also needs the AppIndicator extension (the tray is not verified on hardware under GNOME or KDE). While the tray is available, closing the window only hides it and keeps the control connection, and Quit in the tray ends it; when the tray is unavailable, closing the window quits.

On desktops such as niri, Hyprland, or Sway, the tray panel is an ordinary window placed by the compositor. A window rule can float it near the tray, for example in niri:

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```

## Building from source

Requires JDK 21 and the Android SDK (Platform 37, Build Tools 37.0.0; set `ANDROID_HOME` or `sdk.dir` in `local.properties`). On Windows, replace `./gradlew` with `.\gradlew.bat`. For the development environment, checks, and commit conventions, see [CONTRIBUTING.md](CONTRIBUTING.md) (Chinese).

```bash
./gradlew :androidApp:assembleDebug   # Android debug APK
./gradlew :desktopApp:run             # run the desktop app
./gradlew offlineCheck                # all checks that need no earphone hardware
```

## Feedback

Report bugs and request features in [GitHub Issues](https://github.com/DOHEX/OpenRose/issues), and include:

- Earphone model and firmware version
- Phone model and OS version
- OpenRose version (shown in Settings (设置) → About (关于))
- Steps to reproduce
- For system integration problems, the Xposed framework version and the bridge status shown in diagnostics (reached from the Status page)

To contribute, see [CONTRIBUTING.md](CONTRIBUTING.md) (Chinese). Docs (mostly Chinese): [protocol implementation](docs/protocols.md), [protocol research](docs/protocols/), [architecture decisions](docs/adr/), [glossary](GLOSSARY.md).

## License

OpenRose is licensed under [GPL-3.0-or-later](LICENSE).

## Acknowledgements

- [OppoPods](https://github.com/1812z/OppoPods) — the HyperOS integration draws on its approach
- [Miuix](https://github.com/compose-miuix-ui/miuix) — UI library behind the Miuix style
- [LSPosed](https://github.com/LSPosed/LSPosed) — Xposed framework that runs the system-integration module
- [libxposed API](https://github.com/libxposed/api) — Xposed API used by the module
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — cross-platform UI framework
