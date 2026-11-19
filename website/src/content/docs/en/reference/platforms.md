---
title: Platform support
description: Implementation and verification status of OpenRose on Android, HyperOS, Linux, and Windows.
---

| Platform | Status |
| --- | --- |
| Android 8.0+ | BLE and SPP (i7) control implemented; background control has only offline tests |
| HyperOS 3 / 4 | Additional Settings redirect verified on an i5 with a Xiaomi 14; the rest has only offline tests |
| Linux x64 | BLE control implemented; full desktop i5 hardware acceptance pending |
| Windows | UI and preferences work; Bluetooth control is not implemented |

## Differences between platforms

| Capability | Android | Linux | Windows |
| --- | --- | --- | --- |
| BLE control | ✓ | ✓ | — |
| SPP control | ✓ | — | — |
| Background control | Foreground service with running notification | Tray | — |
| Control way (ordinary / module) | ✓ | — | — |
| HyperOS integration | Requires an Xposed framework | — | — |

See [Linux desktop](../../guides/linux-desktop/) and [HyperOS integration](../../guides/hyperos-integration/) for details.
