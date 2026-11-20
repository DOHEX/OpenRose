---
title: 功能一览
description: OpenRose 提供的耳机控制、外观、后台控制与 HyperOS 系统集成功能。
---

## 耳机控制

可用项随型号和固件版本变化，见[支持的耳机](../supported-earphones/)。

- 左右耳、充电盒电量和固件版本
- 降噪模式切换，分别调节降噪档位与通透档位
- 游戏模式、预设 EQ、触控按键绑定、佩戴检测、提示音音量、多点连接与音频编码偏好
- 部分型号支持自定义 EQ、增益与听力保护、低音增强。FIRST ANGEL 会按音乐或游戏模式切换 EQ 预设。
- Astro X 可设置空间模式、内容模式、耳道自适应与轻拍动作。SPEAKFEEL 的 speakfeel2 分支可控制独立麦克风。
- Android 快捷面板：从控制中心的 OpenRose 磁贴或焦点通知打开，不进主界面即可查看电量，切换降噪模式、EQ 预设与游戏模式

## 外观

- Miuix 与 Material 3 Expressive 两种界面风格，切换时不会断开耳机
- 浅色 / 深色、标准或悬浮底栏、背景模糊

## 后台控制

不需要 root，详见[后台控制](../../guides/background-control/)。

- 关闭界面后保持控制连接，并显示运行通知
- 应用进程被回收或手机重启后，耳机再次连接时自动恢复

## HyperOS 系统集成

需要 Xposed 框架，详见 [HyperOS 系统集成](../../guides/hyperos-integration/)。

- 系统超级岛和焦点通知呈现连接提示与耳机状态；点按焦点通知可打开 OpenRose、系统耳机详情页、RoseLink 或快捷面板
- 融合设备中心显示耳机电量、切换降噪模式，“附加设置”直接跳转到 OpenRose
- 模块控制：由系统蓝牙进程连接耳机，不需要打开 OpenRose
- 耳机流转（实验性）：经融合设备中心，把耳机从一台 HyperOS 音源设备转到另一台

## 隐私

OpenRose 通过本地蓝牙直接与耳机通信，不需要账号，Android 应用也没有网络权限。各项权限的用途见[权限与隐私](../permissions/)。
