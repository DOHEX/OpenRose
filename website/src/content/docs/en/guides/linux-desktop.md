---
title: Linux desktop
description: Control the earphones on Linux through BlueZ, plus the tray, background control, and window rules.
---

The Linux app provides BLE control through the system BlueZ service and D-Bus, without SPP, OTA, or system integration. The desktop has no control way to choose; the app holds the control connection directly.

:::caution[Verification status]
BLE control on Linux x64 is implemented; full desktop hardware acceptance with the EarFree i5 is pending. The Windows app currently has the UI and preferences only; Bluetooth control is not implemented.
:::

## Install

Desktop packages are published alongside the Android app; see [Getting started](../getting-started/#get-the-package). Development builds are in the `packages-linux-x64` artifact:

| File | For |
| --- | --- |
| `*.deb` | Debian, Ubuntu, and derivatives |
| `*.rpm` | Fedora, openSUSE, and similar |
| `*.pacman` | Arch Linux and derivatives |
| `*.AppImage` | Other distributions, no installation |

The packages declare dependencies on `bluez`, GTK 3, and `libsystemd`. The AppImage needs BlueZ installed on the system with the Bluetooth service running.

As on Android, pair and connect the earphones in the system Bluetooth settings first.

## Tray and background control

The tray icon requires `libsystemd`, and GNOME also needs the AppIndicator extension. The tray is not verified on hardware under GNOME or KDE.

- **Tray available**: closing the window only hides it and keeps the control connection; Quit in the tray menu ends it.
- **Tray unavailable**: closing the window quits.

Clicking the tray icon opens the tray panel with the control connection status, battery, and noise mode, plus a manual reconnect when disconnected; settings such as noise levels are only in the main window. On some desktop environments a left click opens the tray menu instead of the tray panel.

## Window rules for tiling compositors

On desktops such as niri, Hyprland, or Sway, the tray panel is an ordinary window placed by the compositor, titled `OpenRose 托盘面板`. A window rule can float it near the tray, for example in niri:

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```
