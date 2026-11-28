---
title: Contributing
description: Build OpenRose from source, plus developer docs, license, and acknowledgements.
---

## Building from source

Requires JDK 21, the stable Rust toolchain, [nsh](https://nsh.dev/) 0.7+, and the Android SDK (Platform 37, Build Tools 37.0.0; set `ANDROID_HOME` or `sdk.dir` in `local.properties`). Building the Android APK also requires the `aarch64-linux-android`, `armv7-linux-androideabi` and `x86_64-linux-android` targets and `cargo-ndk`; building and packaging the Linux NSH desktop app also requires `nfpm`, `appimagetool`, and the GTK 4.14+, GStreamer, and D-Bus development libraries (`libgtk-4-dev`, `libgstreamer1.0-dev`, `libgstreamer-plugins-base1.0-dev`, `libdbus-1-dev`, `pkg-config`). On Windows, replace `./gradlew` with `.\gradlew.bat`.

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
./gradlew :androidApp:assembleDebug   # Android debug APK
(cd desktop && nsh run)               # run the NSH desktop app
cargo xtask package-linux             # build Linux AppImage / deb / rpm / pacman
cargo xtask check                     # all checks that need no earphone hardware (Rust, NSH, and Gradle)
```

Pull requests must pass `cargo xtask check`. It runs the version check, Rust tests and clippy, the no-comments check, NSH check and tests, and Gradle `check offlineCheck` (formatting with Spotless, detekt and Android lint without baselines, and coverage verification). For the development environment, module layout, adding models, the UI sandbox, and commit conventions, see [CONTRIBUTING.md](https://github.com/DOHEX/OpenRose/blob/main/CONTRIBUTING.md) (Chinese).

Run `./gradlew :tools:ui-sandbox:run` to check when settings become available. The initializing scenario represents a headset that has yet to report its readings. Ordinary settings become writable once the control connection is ready. The sandbox processes writes in submission order. On FirstAngel, an EQ request queued after a game mode change is checked against the new preset list once the headset confirms the change. To simulate reconnecting, return the console to the initializing phase. Pending results belong to the previous session.

## Developer docs

These documents are mostly in Chinese.

| Document | Contents |
| --- | --- |
| [Protocol implementation](https://github.com/DOHEX/OpenRose/blob/main/docs/protocols.md) | How OpenRose implements each protocol family |
| [Protocol research](https://github.com/DOHEX/OpenRose/tree/main/docs/protocols) | Research material per model and protocol family |
| [Model adaptation evidence ledger](https://github.com/DOHEX/OpenRose/blob/main/docs/protocols/model-adaptation-ledger.md) | Each model's sources and omitted controls |
| [Architecture decision records](https://github.com/DOHEX/OpenRose/tree/main/docs/adr) | Key architecture and interaction decisions |
| [Glossary](https://github.com/DOHEX/OpenRose/blob/main/GLOSSARY.md) | Terms shared by code, docs, and UI |

## License

OpenRose is licensed under [GPL-3.0-or-later](https://github.com/DOHEX/OpenRose/blob/main/LICENSE).

## Acknowledgements

- [OppoPods](https://github.com/1812z/OppoPods): the HyperOS integration draws on its approach
- [Miuix](https://github.com/compose-miuix-ui/miuix): UI library behind the Miuix style
- [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass): liquid glass effect library behind the Miuix floating bottom bar
- [LSPosed](https://github.com/LSPosed/LSPosed): Xposed framework that runs the system-integration module
- [libxposed API](https://github.com/libxposed/api): Xposed API used by the module
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform): cross-platform UI framework
