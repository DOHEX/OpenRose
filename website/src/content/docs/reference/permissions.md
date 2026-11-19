---
title: 权限与隐私
description: OpenRose 申请的每项权限的用途，以及它不做的事。
---

## 概要

- **不联网**：Android 应用没有申请网络权限（`INTERNET`），无法访问网络；代码中也没有网络请求、统计或崩溃上报。
- **不需要账号**：通过本地蓝牙直接与耳机通信。
- **不读取位置**：Android 8–11 申请定位权限只是因为系统扫描 BLE 时要求它，OpenRose 不读取位置。
- **数据留在本地**：设置和诊断记录只保存在设备上；只有你自己复制到 Issue 里时才会离开设备。

## Android 权限

### 蓝牙

首次使用时弹窗申请。拒绝后 OpenRose 无法发现或连接耳机。

- **Android 12 及以上**
  - `BLUETOOTH_SCAN`：添加耳机时扫描附近的 ROSE 耳机。声明了 `neverForLocation`，扫描结果不用于推断位置。
  - `BLUETOOTH_CONNECT`：建立控制连接，读取已配对设备和音频连接状态。
- **Android 8–11**
  - `ACCESS_FINE_LOCATION`：系统要求的 BLE 扫描前提。
  - `BLUETOOTH`、`BLUETOOTH_ADMIN`：使用蓝牙，安装时自动授予。

### 后台控制

只在开启[后台控制](../../guides/background-control/)后使用。

- `POST_NOTIFICATIONS`（Android 13 及以上）：显示运行通知。开启后台控制时弹窗申请一次；拒绝只会隐藏通知，后台控制仍会运行。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`：运行后台控制的前台服务，安装时自动授予。
- `RECEIVE_BOOT_COMPLETED`：手机重启后恢复后台控制，安装时自动授予。
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`、`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`（Android 12 及以上）：点“关联耳机”并在系统弹出的窗口中选择耳机后，耳机出现时由系统唤起后台控制。

## HyperOS 系统集成模块

- 模块只有在你于 Xposed 框架中启用并勾选作用域后才会加载，运行在 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service` 三个系统进程中。
- 模块借用这些系统进程已有的能力连接耳机，并把耳机状态交给超级岛、焦点通知和融合设备中心呈现。
- 只接受 OpenRose 应用的请求；授权被拒绝时，“状态”页会显示“系统蓝牙宿主拒绝了本应用”。
- 只有“重启作用域”操作需要 root，其余功能都不需要。

## Linux 桌面版

- “设置”页的“登录时启动”开启时，会在桌面环境中注册自启动项；关闭即移除。
