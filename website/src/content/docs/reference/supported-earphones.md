---
title: 支持的耳机
description: 已适配的 ROSE / ROSESELSA 耳机型号、控制传输与真机验证状态。
---

| 耳机 | 控制传输 | 状态 |
| --- | --- | --- |
| EarFree i5（EarFeel i5） | BLE | 真机读取已验证，Android 上的设置写入未验证 |
| EarFeel i7 | BLE 或 SPP | 仅有离线测试，未经真机验证 |
| BudsFeel MK2 | BLE 或 SPP | 未经真机验证 |
| OpenFeel MK2 | BLE 或 SPP | 未经真机验证 |
| 踏浪 Swift 2 | BLE 或 SPP | 未经真机验证 |
| Jasmine | BLE 或 SPP | 未经真机验证 |
| EarFree i3 | BLE | 未经真机验证 |
| HeadFeel（HeadFree） | BLE 或 SPP | 单体电量；未经真机验证 |
| AGATE、AGATE X、CERAMICS | BLE | 未经真机验证 |
| CERAMICS-X | BLE | 未经真机验证 |
| CERAMICS-X 7034、EXPLORER | BLE 或 SPP | 未经真机验证 |
| BudsFree i、SWIFT | BLE | 未经真机验证 |
| Cambrian、OpenFun、SportFeel（SportFree） | BLE 或 SPP | 未经真机验证 |
| OpenFeel（OpenFree，LDAC / LHDC / 双编码分支） | BLE | 未经真机验证 |
| CERAMICS-MK2（标准、8912F、5736） | BLE 或 SPP | 未经真机验证 |
| CERAMICS-U（标准、HSC-2710） | BLE 或 SPP | 未经真机验证 |
| FURINA、Zircon、Coral | BLE 或 SPP | 未经真机验证 |
| SpeakFeel（speakfree）、SPEAKFEEL（speakfeel2） | BLE 或 SPP | 未经真机验证 |
| Astro X、OpenFeel L、FIRST ANGEL、MOKA | BLE 或 SPP | 未经真机验证 |
| Hyacinth、Opal、Mirror、KY01 | BLE 或 SPP | 未经真机验证 |
| Luminous Patamon、Tailmon、Gabumon、Agumon | BLE 或 SPP | 未经真机验证 |
| BudsFeel AE Neo、EarFeel iE Neo、EarFeel iE2 | BLE 或 SPP | 未经真机验证 |
| SportFeel 8912F | BLE 或 SPP | 未经真机验证 |
| Cambrian S、OpenLink | BLE 或 SPP | 未经真机验证 |
| Magician | BLE 或 SPP | 未经真机验证 |

OpenRose 已接入 RoseLink 3.8.6 的全部 48 个蓝牙耳机配置，并保留 3.8.0 的 Magician。OpenFeel 的三个编码分支及 OpenFun 的两个触控分支分别适配，共 52 个 OpenRose 型号配置。

## 说明

- **控制传输**是承载控制连接的蓝牙链路。支持两种的型号，每次连接时依型号能力、平台条件和“强制使用 BLE”偏好自动选择；该偏好只影响下一次连接。Linux 版只支持 BLE。
- **同名硬件分支**通过广播前缀识别。名称不足以确定分支时，OpenRose 不会自动选择。
- **可用的设置**随型号和固件版本变化：没有研究证据的设置不会出现在界面上，也不会被写入。

## 依据

- [型号适配证据账本](https://github.com/DOHEX/OpenRose/blob/main/docs/protocols/model-adaptation-ledger.md)：各型号的适配依据与未实现的能力
- [协议研究](https://github.com/DOHEX/OpenRose/tree/main/docs/protocols)：完整的协议资料

你的耳机已适配但表现异常，或想帮助验证某个型号，请[反馈问题](../../guides/feedback/)。
