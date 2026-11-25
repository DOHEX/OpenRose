---
title: Troubleshooting
description: Find the cause and fix for messages shown in OpenRose.
---

This page is organized by the messages the app shows. The app UI is Chinese only, so each entry quotes the Chinese text you will see, followed by a translation; search for either. If your case is not listed, please [send feedback](../feedback/).

## Cannot find or connect to the earphones

### "授予权限后可检测蓝牙并发现耳机" (grant permission to detect Bluetooth and find earphones)

OpenRose does not have the Bluetooth permission yet. Tap the grant button; if you denied it before, the system opens the app settings page, where you grant it by hand. See [Permissions and privacy](../../reference/permissions/) for what each permission is for.

### "请在系统设置中开启定位服务，以便此 Android 版本扫描蓝牙设备" (turn on location services so this Android version can scan for Bluetooth devices)

On Android 8–11 the system requires location services to be on for BLE scanning. Turn on the system location switch; OpenRose does not read your location.

### "请先在系统设置中打开蓝牙，再回来搜索耳机" (turn on Bluetooth in system settings first)

System Bluetooth is off. Turn it on and return to OpenRose.

### "暂未找到受支持的耳机" when adding earphones (no supported earphones found yet)

- Turn on the earphones, put them in pairing mode, and keep them close to the phone or computer running OpenRose.
- Check that the model is in [Supported earphones](../../reference/supported-earphones/).
- When one retail name covers several hardware branches, OpenRose tells them apart by advertisement prefix; if the name is not enough, it does not pick a branch automatically.

### "未连接 · 耳机开盖后将自动连接" on the earphone page (not connected; connects automatically when the case opens)

Other models may show "未连接 · 系统蓝牙连上耳机后将自动连接" (control will connect after system Bluetooth does). OpenRose's control connection follows the system audio connection: it opens automatically only after the earphones connect to audio in system Bluetooth. Connect the earphones in the system Bluetooth settings first; "无法连接？打开系统蓝牙设置" (can't connect? open system Bluetooth settings) takes you there.

### "连接未成功 · 请确认耳机已开盖并在附近" (connection failed; make sure the case is open and nearby)

The control connection did not open. For models with a charging case, close and reopen the case. For headphones without a case, turn the headphones on again and wait for system audio to reconnect. If it keeps failing, check which stage the control connection stopped at under earphone home → headset info (耳机信息) → diagnostics and records (诊断与记录):

| Reason in diagnostics | Meaning |
| --- | --- |
| 耳机控制服务尚未就绪 (control service not ready) | The earphones do not offer the Bluetooth service needed for control yet, common right after connecting |
| 设备状态订阅未完成 (state subscription incomplete) | Subscribing to earphone state notifications timed out |
| 首次状态读取发送未完成 (first read not sent) | The first read command was not sent |
| 首次读取尚未收到可用状态 (no usable state from first read) | The command was sent but the earphones did not answer |

OpenRose retries these automatically. If they persist, please send feedback with the contents of the diagnostics and records page.

## Settings and readings

### A setting is missing from the UI

Available settings depend on the model and firmware version. Settings without research evidence are neither shown nor written. Models showing "当前提供状态读取，控制设置尚未开放" (state reading only; control settings not available yet) can only display state for now.

### "回读不一致" or "确认超时" after changing a setting (readback mismatch / confirmation timed out)

After every write, OpenRose reads back the earphones' actual state:

- **回读不一致 (readback mismatch)**: the earphones report a different value from the one requested; the setting did not take effect.
- **确认超时 (confirmation timed out)**: no confirmation arrived from the earphones; the setting may not have taken effect.

Both are especially valuable for models not yet verified on hardware. Please send feedback with the setting operation records from the diagnostics and records page.

### A dash on the battery card, or “设备未提供” or “当前状态未知” in readings

“设备未提供” (not provided by device) means the earphones or their firmware do not report that value. The battery card shows a dash (–) for that reading. When the earphones report zero charge, the card shows 0%. “当前状态未知” (current state unknown) means OpenRose has yet to read a usable value.

## Background control

Check the background control card on the Settings page:

| Message | What to do |
| --- | --- |
| 已启用，尚未运行 (enabled, not running yet) | Normal: it starts the next time the earphones connect to audio or you open OpenRose |
| 缺少蓝牙权限，后台控制无法运行 (Bluetooth permission missing) | Tap 授予蓝牙权限 (grant Bluetooth permission) |
| 系统限制了从后台启动，请打开 OpenRose 后重试 (system blocked starting from background) | Open OpenRose once |
| 系统拒绝了前台服务启动，后台控制未运行 (system refused the foreground service) | Check the system's background and battery restrictions for OpenRose |
| 运行通知：系统已隐藏，后台控制仍在运行 (running notification hidden by system) | Tap 允许通知 (allow notifications) to restore it; background control itself is unaffected |
| 自动唤起：尚未关联耳机或关联已被取消 (auto wake: earphones not associated) | On Android 12 and later, tap 关联耳机 (associate earphones) |
| 自动唤起：系统拒绝了设备在线监听 (auto wake: system refused presence observation) | Check app permissions, then tap 重新关联耳机 (associate earphones again) |

Even when it shows 系统条件已配置 (system conditions configured), autostart and battery restrictions cannot be detected. On HyperOS and similar systems, if control does not come back after the process is reclaimed, tap 应用设置（自启动、电量） (app settings: autostart, battery), allow autostart, and remove battery restrictions. See [Background control](../background-control/).

## HyperOS module

The Status page shows why the module is unavailable and what to do next. When a scope restart is needed, tap 重启作用域 (Restart scopes) in the top right of the Status page (requires root), or restart the phone.

In the Restart scopes dialog, select the processes to restart. When the operation finishes, any failure reason appears below the affected package name. In Material 3 Expressive, scroll inside the dialog to see the last result if the window is too short to show them all.

| Status page shows | What to do |
| --- | --- |
| 系统模块未启用 (system module not enabled) | Enable the OpenRose module in the Xposed framework |
| 系统模块暂不可用：尚未收到兼容框架服务 (no compatible framework service yet) | Make sure the Xposed framework is running with the OpenRose module enabled. App control is unaffected |
| 系统模块暂不可用：框架服务 API 低于 102 (framework API below 102) | Update the framework to one supporting libxposed API 102, for example LSPosed 2.2.0 or later |
| Advice to select the system Bluetooth (`com.android.bluetooth`) scope | The module host was not found: make sure that scope is selected, then restart scopes |
| 系统模块已连接但未响应 (module connected but not responding) | The host process is running but not answering; restart scopes |
| 系统蓝牙宿主拒绝了本应用 (system Bluetooth host refused this app) | See diagnostics and records for details |
| 与系统蓝牙宿主的连接已断开，正在自动重试 (link to host lost, retrying) | Wait for the retry; if it keeps happening, restart scopes |

Fusion Device Center showing Off for the noise mode or hiding battery levels are known limitations; see [HyperOS integration](../hyperos-integration/#known-limitations).

## Linux desktop

| Message | What to do |
| --- | --- |
| 未找到蓝牙适配器 (no Bluetooth adapter found) | Make sure the computer has a Bluetooth adapter the system recognizes, for example with `bluetoothctl list` |
| 蓝牙适配器已关闭 (Bluetooth adapter off) | Turn Bluetooth on, for example `bluetoothctl power on` |
| BlueZ 服务不可用 (BlueZ service unavailable) | Start the Bluetooth service, for example `systemctl enable --now bluetooth` |
| D-Bus 拒绝访问蓝牙 (D-Bus denied Bluetooth access) | The current user may not access BlueZ; check your distribution's D-Bus policy for `org.bluez` |
| BlueZ 运行环境缺少依赖 (BlueZ runtime missing dependencies) | Install the missing libraries; check especially when using the AppImage |
| 托盘图标：不可用 (tray icon unavailable) | Install `libsystemd`; GNOME needs the AppIndicator extension |

If the tray panel appears in the wrong place on niri, Hyprland, Sway, or similar, see [Desktop](../desktop/#window-rules-for-tiling-compositors).

## Windows desktop

| Message | What to do |
| --- | --- |
| Windows 蓝牙适配器不支持 BLE (Bluetooth adapter does not support BLE) | Make sure the computer's Bluetooth adapter supports BLE and is working in Device Manager |
| 缺少 OpenRose Windows 蓝牙组件 (missing Windows Bluetooth helper) | Reinstall the Windows `.msi` package; when running from source, build `platform/windows/native` first |
| Windows 蓝牙状态不可用 / Windows 蓝牙组件已退出 (Bluetooth state unavailable / helper exited) | Check that the Windows Bluetooth service is running and reopen OpenRose; allow `openrose-winrt.exe` if security software blocked it |
| Windows 未找到可操作的蓝牙音频端点 (no operable Bluetooth audio endpoint found) | Confirm the earphones are paired in Windows Settings → Bluetooth & devices and not disabled in Sound settings |
