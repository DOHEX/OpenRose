---
title: Platform support
description: Implementation and verification status of OpenRose on Android, HyperOS, Linux, and Windows.
---

| Platform | Status |
| --- | --- |
| Android 8.0+ | BLE control, with SPP available on compatible models. Background control has offline test coverage |
| HyperOS 3 / 4 | i5 + Xiaomi 14 verified the Additional Settings redirect. HyperOS 4.0.0.27 also verified hiding the island while keeping the status card, and merging the OpenRose style connection prompt into one battery island. HyperOS 3 awaits verification. |
| Linux x64 | BLE control implemented; full desktop i5 hardware acceptance pending |
| Windows | WinRT BLE control and tray actions implemented. Windows hardware acceptance with the i5 is pending |

## Differences between platforms

| Capability | Android | Linux | Windows |
| --- | --- | --- | --- |
| BLE control | ✓ | ✓ | ✓ |
| SPP control | ✓ | — | — |
| Background control | Foreground service with running notification | Tray | Tray |
| Control way (app / module) | ✓ | — | — |
| HyperOS integration | Requires an Xposed framework | — | — |

See [Desktop](../../guides/desktop/) and [HyperOS integration](../../guides/hyperos-integration/) for details.
