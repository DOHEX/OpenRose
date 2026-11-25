---
title: Background control
description: Keep the control connection after closing OpenRose, and restore it after the process is reclaimed or the phone restarts.
---

Background control keeps OpenRose's control connection open after the UI is closed. No root is needed.

## Turn it on

The Background control (后台控制) switch on the Settings page is off by default. When on:

- The control connection stays open after you close the app or swipe away its task.
- A running notification is shown, from which you can turn background control off.

## After the process is reclaimed or the phone restarts

OpenRose restores control when the earphones connect to audio again. What you need to set up depends on the Android version:

| System | Setup |
| --- | --- |
| Android 8–11 | Nothing; the earphones' audio connection wakes OpenRose |
| Android 12–13 | Tap Associate earphones (关联耳机) on the Settings page |
| Android 14 and later | Tap Associate earphones (关联耳机) on the Settings page, with the Bluetooth permission granted |

:::note[HyperOS and similar systems]
Allow autostart for OpenRose and remove its battery restrictions by hand. OpenRose cannot detect either setting.
:::

Force-stopping OpenRose in system settings ends background control, and it does not restart itself.

## Relation to module control

Background control belongs to app control and runs in the OpenRose app's foreground service. With the HyperOS module enabled and [module control](../hyperos-integration/#control-way) selected, the system Bluetooth process holds the control connection, and OpenRose does not need to run in the background.

On the desktop, background control runs in the tray; see [Desktop](../desktop/#tray-and-background-control).
