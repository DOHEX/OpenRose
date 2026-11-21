---
title: Windows 桌面版
description: 安装 OpenRose MSI，通过系统蓝牙配对耳机并启用桌面控制。
---

## 安装与连接

1. 在 [GitHub Releases](https://github.com/DOHEX/OpenRose/releases) 下载 Windows x64 的 `.msi` 安装包。开发版位于 [CI 运行记录](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) 的 `packages-windows-x64` 产物中，下载开发产物需要登录 GitHub。
2. 运行 MSI，按安装程序提示完成安装。
3. 在 Windows「设置 → 蓝牙和设备」打开蓝牙，添加并连接耳机。OpenRose 的「我的耳机」未连接看板也提供「打开系统蓝牙设置」入口。
4. 打开 OpenRose。「我的耳机」显示受支持的已配对设备；系统音频已连接后，应用会尝试建立 BLE 控制连接。可以在托盘里查看电量与降噪模式。

如需与手机流转，在电脑的「设置 → 流转配对」显示配对码，让选择了模块控制的手机扫码或输入六位码。两端须在可互访的局域网内。读数、控制与音频播放的验证情况见[平台支持](../../reference/platforms/)。
