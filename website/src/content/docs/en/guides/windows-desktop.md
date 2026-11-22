---
title: Windows desktop
description: Install the OpenRose MSI, pair your earphones in Windows and start desktop control.
---

## Install and connect

1. Download the Windows x64 `.msi` from [GitHub Releases](https://github.com/DOHEX/OpenRose/releases). For a development build, sign in to GitHub and download the `openrose-<version>-dev-<commit>-windows-x64` artifact from a successful [CI run](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml).
2. Run the MSI and follow the installer.
3. In Windows Settings, open **Bluetooth & devices**, enable Bluetooth, then add and connect your earphones. When disconnected, My Earphones (我的耳机) also offers Open system Bluetooth settings (打开系统蓝牙设置).
4. Open OpenRose. My Earphones (我的耳机) lists supported paired devices. Once system audio connects, the app attempts to establish BLE control. The tray shows battery levels and noise mode.

To pair with a phone for handoff, display the code under Settings → Handoff pairing (设置 → 流转配对) on the computer. Select module control on your phone, then scan the code or enter its six digits. Both devices need to be reachable on the same LAN. See [Platform support](../../reference/platforms/) for verification status.
