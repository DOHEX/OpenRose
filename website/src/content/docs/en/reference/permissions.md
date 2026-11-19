---
title: Permissions and privacy
description: What each permission OpenRose requests is for, and what it does not do.
---

## Summary

- **No network**: the Android app does not request the network permission (`INTERNET`) and cannot reach the network; the code has no network requests, analytics, or crash reporting.
- **No account**: OpenRose talks to the earphones directly over local Bluetooth.
- **No location reads**: on Android 8–11 the location permission is requested only because the system requires it for BLE scanning; OpenRose does not read your location.
- **Data stays on the device**: settings and diagnostic records are kept on the device, and only leave it if you copy them into an issue yourself.

## Android permissions

### Bluetooth

Requested by a prompt on first use. Without it, OpenRose cannot find or connect to the earphones.

- **Android 12 and later**
  - `BLUETOOTH_SCAN`: scanning for nearby ROSE earphones when adding them. Declared `neverForLocation`, so scan results are not used to derive location.
  - `BLUETOOTH_CONNECT`: opening the control connection, reading paired devices and audio connection state.
- **Android 8–11**
  - `ACCESS_FINE_LOCATION`: system prerequisite for BLE scanning.
  - `BLUETOOTH`, `BLUETOOTH_ADMIN`: using Bluetooth; granted automatically at install.

### Background control

Used only after you turn on [background control](../../guides/background-control/).

- `POST_NOTIFICATIONS` (Android 13 and later): showing the running notification. Requested by a prompt once when you turn on background control; denying it only hides the notification, and background control keeps running.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`: running the background control foreground service; granted automatically at install.
- `RECEIVE_BOOT_COMPLETED`: restoring background control after the phone restarts; granted automatically at install.
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (Android 12 and later): after you tap Associate earphones (关联耳机) and choose the earphones in the system dialog, the system wakes background control when the earphones appear.

## HyperOS integration module

- The module loads only after you enable it in the Xposed framework and select its scopes. It runs inside three system processes: `com.android.bluetooth`, `com.xiaomi.bluetooth`, and `com.milink.service`.
- It uses capabilities those system processes already have to connect to the earphones, and hands earphone state to the system island, focus notifications, and Fusion Device Center.
- It only accepts requests from the OpenRose app; when authorization is refused, the Status page shows "系统蓝牙宿主拒绝了本应用" (the system Bluetooth host refused this app).
- Only Restart scopes (重启作用域) needs root; nothing else does.

## Linux desktop

- Scans for and connects to the earphones through BlueZ on the system D-Bus; no root needed.
- When Launch at login (登录时启动) on the Settings page is on, an autostart entry is registered with the desktop environment; turning it off removes it.
