---
title: 权限与隐私
description: OpenRose 申请的每项权限的用途，以及它不做的事。
---

## 概要

Android 应用进程通过本机蓝牙控制耳机，APK 没有申请 `INTERNET`。选择模块控制后，系统蓝牙宿主会使用自身的网络权限与桌面端在局域网互联。OpenRose 无需账号，偏好与诊断记录保存在本机。

### 流转配对与数据交换

- 手机模块宿主和桌面端通过 mDNS 发布设备名称、发现标识与服务端口。同一局域网中的设备可以发现这项服务。
- 在电脑显示配对码，在手机扫码或输入六位码。配对码两分钟内有效，成功使用一次后失效。两端保存设备身份，用 TLS 验证后续连接。
- 已配对对端可以读取受支持耳机的型号、电量、音源、控制状态和降噪模式。切换降噪或手动流转时，两端还会交换目标设备标识、命令及执行结果。音频由系统蓝牙播放。
- 在任一端的「设置 → 流转配对」解除配对，即撤销本机对该对端的授权。

## Android 权限

### 蓝牙

「添加耳机」需要扫描权限；连接已配对设备使用对应系统版本的蓝牙连接权限。

- **Android 12 及以上**
  - `BLUETOOTH_SCAN`：添加耳机时扫描附近的 ROSE 耳机。声明了 `neverForLocation`，扫描结果不用于推断位置。
  - `BLUETOOTH_CONNECT`：建立控制连接，读取已配对设备和音频连接状态。
- **Android 8–11**
  - `ACCESS_FINE_LOCATION`：系统要求的 BLE 扫描前提。
  - `BLUETOOTH`、`BLUETOOTH_ADMIN`：使用蓝牙，安装时自动授予。

### 扫码

`CAMERA` 用于在流转配对页扫描电脑的二维码。也可选择附近的电脑并输入六位配对码。

### 后台控制

只在开启[后台控制](../../guides/background-control/)后使用。

- `POST_NOTIFICATIONS`（Android 13 及以上）：显示运行通知。开启后台控制时弹窗申请一次；拒绝只会隐藏通知，后台控制仍会运行。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`：运行后台控制的前台服务，安装时自动授予。
- `RECEIVE_BOOT_COMPLETED`：手机重启后恢复后台控制，安装时自动授予。
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`、`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`（Android 12 及以上）：点“关联耳机”并在系统弹出的窗口中选择耳机后，耳机出现时由系统唤起后台控制。

## HyperOS 系统集成模块

- 模块只有在你于 Xposed 框架中启用并勾选作用域后才会加载，运行在 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service` 三个系统进程中。
- 模块借用这些系统进程已有的能力连接耳机，并把耳机状态交给超级岛、焦点通知和融合设备中心呈现。
- 本机控制与系统入口通过认证的跨进程接口访问宿主。应用授权被拒绝时，“状态”页会显示“系统蓝牙宿主拒绝了本应用”。
- 只有“重启作用域”操作需要 root，其余功能都不需要。

## Linux 桌面版

- “设置”页的“登录时启动”开启时，会在桌面环境中注册自启动项；关闭即移除。
