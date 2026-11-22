---
title: Windows desktop
description: Install the OpenRose MSI, pair your earphones in Windows and start desktop control.
---

## Install and connect

1. Download the Windows x64 `.msi` from [GitHub Releases](https://github.com/DOHEX/OpenRose/releases). For a development build, sign in to GitHub and download the `openrose-<version>-dev-<commit>-windows-x64` artifact from a successful [CI run](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml).
2. Run the MSI. It installs for the current user under `%LOCALAPPDATA%\Programs` without administrator rights; open OpenRose from the Start menu. A newer MSI replaces the installed version.
3. In Windows Settings, open **Bluetooth & devices**, enable Bluetooth, then add and connect your earphones. When disconnected, My Earphones (我的耳机) also offers Open system Bluetooth settings (打开系统蓝牙设置).
4. Open OpenRose. My Earphones (我的耳机) lists supported paired devices. Once system audio connects, the app attempts to establish BLE control. The tray shows battery levels and noise mode.

To pair with a phone for handoff, display the code under Settings → Handoff pairing (设置 → 流转配对) on the computer. Select module control on your phone, then scan the code or enter its six digits. Both devices need to be reachable on the same LAN. See [Platform support](../../reference/platforms/) for verification status.

## Tray panel

Click the tray icon to see each headset with its audio source, battery readings, and noise modes. Select a mode under the headset you want to adjust. If two phones each have a headset connected, the panel lists them separately.

The headset card in My Earphones, the desktop tray, and the phone quick panel shows the connected audio source. While the headset is connected to your phone, you can change noise mode and keep playing from the phone. Choose **改连这台电脑** (Move to this computer) to ask the phone to disconnect the headset. If the audio source is unknown, choose **将耳机连接到这台电脑** (Connect the headset to this computer) to try connecting. The computer keeps its default sound output and shows that device on the headset card. If control recovery fails, choose **恢复耳机控制** (Restore headset controls).
