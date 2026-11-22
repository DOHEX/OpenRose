---
title: Linux desktop
description: Control the earphones on Linux through BlueZ, plus the tray, background control, and window rules.
---

The Linux app provides BLE control through the system BlueZ service and D-Bus, without SPP, OTA, or system integration. The desktop has no control way to choose; the app holds the control connection directly.

:::caution[Verification status]
BLE control on Linux x64 is implemented; full desktop hardware acceptance with the EarFree i5 is pending. The Windows app uses WinRT to read battery levels and change noise modes. Windows hardware acceptance with the i5 is pending.
:::

## Install

Desktop packages are published alongside the Android app; see [Getting started](../getting-started/#get-the-package). Development builds are in the `openrose-<version>-dev-<commit>-linux-x64` artifact:

| File | For |
| --- | --- |
| `*.deb` | Debian, Ubuntu, and derivatives |
| `*.rpm` | Fedora, openSUSE, and similar |
| `*.pacman` | Arch Linux and derivatives |
| `*.AppImage` | Other distributions, no installation |

The packages declare dependencies on `bluez`, GTK 3, and `libsystemd`. The AppImage needs BlueZ installed on the system with the Bluetooth service running.

Pair and connect the earphones in the system Bluetooth settings first. If the Status page shows “D-Bus 拒绝访问蓝牙” during discovery, check whether your desktop account has permission to use the system BlueZ service.

## Pair a phone and desktop

Enable module control on the phone. In the desktop settings, open **流转配对** and show the pairing code. The remaining validity is shown below the code. Open the same setting on the phone, then scan the QR code or select the computer and enter its six digits. Both devices need local network discovery and the phone needs access to TCP port 21871 on the desktop. Either device can remove the pairing in settings after confirming.

Find the i5 card in My Earphones, the desktop tray, or the phone quick panel. **音源设备** (Audio source) shows which phone or computer is connected to this headset. Selecting a noise mode lets the original source keep playing. To use the headset on the other device, choose **改连这台电脑** (Move to this computer) or **改连这部手机** (Move to this phone) on that device. The original source disconnects first. If a call or an active headset microphone prevents the move, the card shows the reason.

## Tray and background control

The tray icon requires `libsystemd`, and GNOME also needs the AppIndicator extension. The tray is not verified on hardware under GNOME or KDE.

- **Tray available**: closing the window only hides it and keeps the control connection; Quit in the tray menu ends it.
- **Tray unavailable**: closing the window quits.

Click the tray icon to see each headset with its audio source, battery readings, and noise modes. Select a mode under the headset you want to adjust. If two phones each have a headset connected, the panel lists them separately.

If the audio source is unknown, choose **将耳机连接到这台电脑** (Connect the headset to this computer) to try connecting. The computer keeps its default sound output and shows that device on the headset card. If battery readings and noise controls fail to return, choose **恢复耳机控制** (Restore headset controls). The audio connection stays on the computer.

To adjust noise levels, open OpenRose from the bottom of the panel. On some desktops a left click opens the tray menu.

## Window rules for tiling compositors

On desktops such as niri, Hyprland, or Sway, the tray panel is an ordinary window placed by the compositor, titled `OpenRose 托盘面板`. A window rule can float it near the tray, for example in niri:

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```

For headset circulation with the AppImage, install the host’s `pactl` command. Debian and Fedora package it in `pulseaudio-utils`, and Arch Linux packages it in `libpulse`.
