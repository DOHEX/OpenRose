---
title: Permissions and privacy
description: What each permission OpenRose requests is for, and what it does not do.
---

## Summary

The Android app process controls the earphones over local Bluetooth. The APK does not request `INTERNET`. When module control is selected, the system Bluetooth host uses its own network permissions to communicate with the desktop app over the LAN. OpenRose requires no account and stores preferences and diagnostic history on the device.

### Handoff pairing and data exchanged

- The phone module host and desktop app publish their device name, discovery ID and service port using mDNS. Devices on the same LAN can discover the service.
- Display a pairing code on the computer, then scan it or enter its six digits on the phone. The code is valid for two minutes and is consumed after one successful pairing. Both devices save the peer identity and authenticate subsequent connections using TLS.
- Paired peers can read the supported earphones’ model, battery levels, audio source, control state and noise mode. Noise changes and manual handoffs also exchange a target device identifier, a command and its result. The operating system plays audio over Bluetooth.
- Remove the peer under Settings → Handoff pairing (设置 → 流转配对) on either device to revoke its local authorization.

## Android permissions

### Bluetooth

Adding earphones requires scanning permission. Connecting to a paired device uses the Bluetooth connection permission for your Android version.

- **Android 12 and later**
  - `BLUETOOTH_SCAN`: scanning for nearby ROSE earphones when adding them. Declared `neverForLocation`, so scan results are not used to derive location.
  - `BLUETOOTH_CONNECT`: opening the control connection, reading paired devices and audio connection state.
- **Android 8–11**
  - `ACCESS_FINE_LOCATION`: system prerequisite for BLE scanning.
  - `BLUETOOTH`, `BLUETOOTH_ADMIN`: using Bluetooth; granted automatically at install.

### QR scanning

`CAMERA` is used when you choose to scan the computer’s pairing QR code. You can also select a nearby computer and enter its six-digit code.

### Background control

Used only after you turn on [background control](../../guides/background-control/).

- `POST_NOTIFICATIONS` (Android 13 and later): showing the running notification. Requested by a prompt once when you turn on background control; denying it only hides the notification, and background control keeps running.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`: running the background control foreground service; granted automatically at install.
- `RECEIVE_BOOT_COMPLETED`: restoring background control after the phone restarts; granted automatically at install.
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (Android 12 and later): after you tap Associate earphones (关联耳机) and choose the earphones in the system dialog, the system wakes background control when the earphones appear.

## HyperOS integration module

- The module loads only after you enable it in the Xposed framework and select its scopes. It runs inside three system processes: `com.android.bluetooth`, `com.xiaomi.bluetooth`, and `com.milink.service`.
- It uses capabilities those system processes already have to connect to the earphones, and hands earphone state to the system island, focus notifications, and Fusion Device Center.
- Local app and system integration clients use an authenticated IPC endpoint. If the host refuses app authorization, the Status page shows "系统蓝牙宿主拒绝了本应用" (the system Bluetooth host refused this app).
- Only Restart scopes (重启作用域) needs root; nothing else does.

## Linux desktop

- Scans for and connects to the earphones through BlueZ on the system D-Bus; no root needed.
- When Launch at login (登录时启动) on the Settings page is on, an autostart entry is registered with the desktop environment; turning it off removes it.
