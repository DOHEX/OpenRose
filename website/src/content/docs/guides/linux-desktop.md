---
title: Linux 桌面版
description: 在 Linux 上通过 BlueZ 控制耳机，以及托盘、后台控制和窗口规则。
---

Linux 版通过系统 BlueZ 和 D-Bus 提供 BLE 控制，不含 SPP、OTA 和系统集成。桌面端没有控制方式可选，应用直接持有控制连接。

:::caution[验证状态]
Linux x64 的 BLE 控制已实现，EarFree i5 在桌面端的完整真机验收尚未完成。Windows 桌面版使用 WinRT 读取电量和切换降噪，i5 真机验证待完成。
:::

## 安装

安装包与 Android 版发布在同一处，见[快速开始](../getting-started/#获取安装包)。开发版在 `packages-linux-x64` 产物里：

| 文件 | 适用 |
| --- | --- |
| `*.deb` | Debian、Ubuntu 及其衍生发行版 |
| `*.rpm` | Fedora、openSUSE 等 |
| `*.pacman` | Arch Linux 及其衍生发行版 |
| `*.AppImage` | 其他发行版，免安装 |

软件包会声明对 `bluez`、GTK 3 和 `libsystemd` 的依赖。AppImage 需要系统已装好 BlueZ 并运行蓝牙服务。

使用前先在系统蓝牙设置里配对并连接耳机。如果扫描时状态页显示「D-Bus 拒绝访问蓝牙」，请检查当前桌面用户访问系统 BlueZ 服务的权限。

## 流转配对

手机启用模块并选择「模块控制」。在电脑的「设置 → 流转配对」显示配对码，再在手机的同一位置扫码，或选择电脑并输入六位码。配对码下方显示剩余有效时间。两端通过局域网发现彼此，主机防火墙需允许手机访问桌面的 TCP 21871 端口。在任一端的配对列表中可解除配对，解除前需确认。

用手机快捷面板或电脑托盘切换降噪时，声音留在原音源。需要移动声音时，在目标设备选择「接到这台电脑」或「接回手机」。

## 托盘与后台控制

托盘图标需要 `libsystemd`；GNOME 还需要启用 AppIndicator 扩展。GNOME 与 KDE 上的托盘尚未实机验证。

- **托盘可用**：关闭窗口只隐藏界面、保留控制连接；从托盘菜单“退出”才结束。
- **托盘不可用**：关闭窗口即退出。

点击托盘图标弹出托盘面板，显示控制连接状态、电量和降噪模式，未连接时可以手动重连；降噪档位等设置只在主窗口。部分桌面环境左键单击会弹出托盘菜单而不是托盘面板。

## 平铺式合成器的窗口规则

在 niri、Hyprland、Sway 等桌面上，托盘面板是由合成器摆放的普通窗口，窗口标题为 `OpenRose 托盘面板`。可以用窗口规则让它浮动到托盘附近，例如 niri：

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```

使用 AppImage 时，耳机流转需要系统提供 `pactl`。Debian 和 Fedora 的对应软件包是 `pulseaudio-utils`，Arch Linux 的对应软件包是 `libpulse`。
