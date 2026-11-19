---
title: 平台支持
description: OpenRose 在 Android、HyperOS、Linux 与 Windows 上的实现与验证状态。
---

| 平台 | 状态 |
| --- | --- |
| Android 8.0+ | BLE 与 SPP（i7）控制已实现；后台控制只有离线测试 |
| HyperOS 3 / 4 | 附加设置跳转已在 i5 + 小米 14 上验证，其余链路只有离线测试 |
| Linux x64 | 已实现 BLE 控制，桌面端 i5 的完整真机验收待完成 |
| Windows | 界面和偏好设置可用，蓝牙控制尚未实现 |

## 各平台差异

| 能力 | Android | Linux | Windows |
| --- | --- | --- | --- |
| BLE 控制 | ✓ | ✓ | — |
| SPP 控制 | ✓ | — | — |
| 后台控制 | 前台服务与运行通知 | 托盘 | — |
| 控制方式（普通 / 模块） | ✓ | — | — |
| HyperOS 系统集成 | 需要 Xposed 框架 | — | — |

平台说明见 [Linux 桌面版](../../guides/linux-desktop/) 和 [HyperOS 系统集成](../../guides/hyperos-integration/)。
