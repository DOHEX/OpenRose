---
status: accepted
---

# 桌面端托盘：后台控制、托盘面板与托盘菜单

[ADR 0013](0013-linux-desktop-control.md) 规定桌面端没有后台控制，关闭窗口即结束控制连接。为了不打开主窗口也能查看电量、切换降噪模式，桌面端引入托盘图标，并由它承载后台控制，取代 0013 中“关闭窗口即结束控制连接”这一条。单实例运行与音频驱动的控制连接生命周期（[ADR 0005](0005-miuix-nav-and-audio-bound-control-lifecycle.md)）不变。

## 决策

- **后台控制**：设置页提供开关，默认开启。开启且托盘可用时，关闭窗口只隐藏界面，控制连接保留；托盘菜单的“退出”结束控制连接并退出进程。系统音频断开仍按 ADR 0005 释放控制连接。托盘不可用（会话总线上没有 `org.kde.StatusNotifierWatcher`，或托盘原生库加载失败）时，无论开关如何，关闭窗口都直接退出，状态页说明原因。托盘图标只要托盘可用就显示，不随该开关变化。
- **托盘实现**：使用 Nucleus 的 ComposeNativeTray（`composenativetray-app` 的 `TrayApp`）。Linux 走 StatusNotifierItem，Windows 走 `Shell_NotifyIcon`；两端行为一致，不按平台分支。
- **托盘面板**：点击托盘图标弹出，内容为控制连接状态、电量与降噪模式；未连接时“重新连接”连接未连接看板展示的主设备，没有已配对设备时引导进入添加耳机流程。降噪档位等配置性项目只在主窗口。面板跟随界面风格，不使用背景模糊；组件位于 `:sharedUI`，与主窗口共用同一模型与控制入口。
- **托盘菜单**：控制连接状态、电量、降噪模式，以及“托盘面板”“打开 OpenRose”“退出”。GNOME 的 AppIndicator 扩展在左键单击时弹出菜单、双击才激活，因此菜单同样承载快捷控制。
- **启动**：“登录时启动”默认关闭，经 `nucleus.autolaunch` 实现；后台控制开启且托盘可用时，登录启动只驻留托盘。再次启动应用唤回主窗口。
- **支持范围**：GNOME（需 AppIndicator 扩展）与 KDE Plasma 依据库文档与扩展源码支持，未经实机验证。未识别的 Linux 桌面（niri、Hyprland、Sway 等平铺式合成器）不使用 `TrayApp` 弹窗，改用普通托盘图标，点击后打开一个由合成器摆放的 Wayland 原生面板窗口（标题固定为“OpenRose 托盘面板”，可用合成器窗口规则让它浮动到托盘附近）。Wayland 下看不到窗口外的点击，面板在鼠标进入后又移出 0.5 秒、或焦点持续离开 1 秒时收起；宽限是因为焦点跟随鼠标会把焦点交给鼠标途经的窗口。

## 考虑过的方案

- **Compose `Tray`（AWT）**：Tao 后端不支持；Linux 上基于 XEmbed，原生 GNOME 不显示。
- **基于 dbus-java 自行实现 StatusNotifierItem**：协议、弹窗与外部点击收起都要自写，Windows 另需一套。
- **只用原生托盘菜单**：完全由托盘宿主绘制与定位，在 Wayland 上最原生，但失去面板的布局与界面风格一致性。
- **所有桌面都用 Wayland 原生普通窗口作面板**：位置由合成器决定（GNOME 居中），不贴近托盘；只在 `TrayApp` 弹窗不可用的桌面采用。
- **layer-shell 或桌面 shell 组件**（GNOME 快捷设置扩展、Plasma 小部件、Noctalia 插件）：Tao 未实现 layer-shell，Mutter 也不支持；shell 组件需要 OpenRose 先开放会话总线 D-Bus 接口并维护独立代码库，本次不做。

## 后果

已识别桌面（GNOME、KDE 等）的 Wayland 会话中，托盘面板是经 XWayland 显示的 override-redirect X11 窗口；Linux 拿不到托盘图标坐标，面板按桌面环境惯例停在屏幕角落；只有 Wayland、没有 XWayland 时退回由合成器摆放的普通窗口。X11 路径只支持整数倍缩放，分数缩放下面板可能与主窗口清晰度不一致。

2026-11-06 在 niri + xwayland-satellite 上实测：`TrayApp` 的 X11 弹窗已映射但从未呈现，弹出一次后界面事件循环卡住，关窗请求不再送达，SIGTERM 也无法结束进程。因此未识别的桌面改走原生面板窗口；换用后关窗驻留、再次启动唤回、托盘退出与 SIGTERM 退出均正常。

托盘原生库 `libLinuxTray.so` 经 sd-bus 与托盘宿主通信，链接 `libsystemd.so.0`，Linux 安装包需声明该依赖。`composenativetray-app` 2.1.7 依赖 Nucleus 2.5.5，应用使用 Nucleus 2.5.18，两者须在同一 2.5.x 线上保持兼容。

Android 行为不变：托盘依赖只在 `:desktopApp`，桌面设置使用独立的 `desktop.` 前缀，平台内容经现有 `PlatformUi` 插槽提供。
