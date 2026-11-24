---
status: accepted
---

# 桌面端托盘：后台控制、托盘面板与托盘菜单

[ADR 0013](0013-linux-desktop-control.md) 规定桌面端没有后台控制，关闭窗口即结束控制连接。为了不打开主窗口也能查看电量、切换降噪模式，桌面端引入托盘图标，并由它承载后台控制，取代 0013 中“关闭窗口即结束控制连接”这一条。单实例运行与音频驱动的控制连接生命周期（[ADR 0005](0005-miuix-nav-and-audio-bound-control-lifecycle.md)）不变。

## 决策

- **后台控制**：设置页提供开关，默认开启。开启且托盘可用时，关闭窗口只隐藏界面，控制连接保留；托盘菜单的“退出”结束控制连接并退出进程。系统音频断开仍按 ADR 0005 释放控制连接。托盘不可用（会话总线上没有 `org.kde.StatusNotifierWatcher`，或托盘原生库加载失败）时，无论开关如何，关闭窗口都直接退出，状态页说明原因。托盘图标只要托盘可用就显示，不随该开关变化。
- **托盘实现**：使用 Nucleus 的 ComposeNativeTray（`composenativetray-app` 的 `TrayApp`）。Linux 走 StatusNotifierItem，Windows 走 `Shell_NotifyIcon`；两端呈现给用户的托盘行为一致。托盘可用性检测与面板宿主依平台实现，见下文。
  - （2026-11 补充）**托盘可用性**：Windows 的通知区域属于 shell，始终存在（`ShellTrayMonitor`）。Linux 托盘遵循 StatusNotifierItem：只有当某个已有注册宿主的 watcher 列出本进程的条目时，图标才可见。原生库把条目命名为 `org.kde.StatusNotifierItem-<pid>-1`，watcher 记录的可能是这个名字，也可能是它的唯一总线名，`StatusNotifierTrayMonitor` 两种都认。
  - （2026-11 补充）**tooltip**：Linux 托盘用收到的第一个 tooltip 固定条目的 Title，因此 `DesktopTray` 的首个 tooltip 是应用名“OpenRose”，启动后才换成连接状态与电量。本机未连接而局域网有远端设备持有可流转耳机时，tooltip 第一行展示 `<耳机名> · <音源设备名>`，第二行在存在已知电量读数时复用 `RemoteHeadset.compactBatteryOrNull`，全未知时回退到 `statusLabel`（无则省略）；本机已连接时保持本机优先，多台远端设备持有耳机时只展示首台。
  - （2026-11 补充）**托盘图标与着色**：`TrayIcons` 区分三态——本机已连接（`connected`）、仅远端持有可流转耳机（`remote`，耳机轮廓内嵌双向流转箭头）与未连接（`disconnected`），本机已连接优先于远端。`composenativetray` 2.1.7 的 `isMenuBarInDarkMode()` 在 `LinuxDesktopEnvironment.Unknown`（niri、Hyprland、Sway 等）上写死返回 `true`，导致浅色状态栏下图标始终染白难辨；`DesktopTray` 在 `LinuxDesktopEnvironment.Unknown` 上改为按 `isSystemInDarkTheme()` 显式传 `Color.White`／`Color.Black`，其余桌面与 Windows 沿用库默认。
- **托盘面板**：点击托盘图标弹出，固定内容为控制连接状态、电量与降噪模式；未连接时“重新连接”连接未连接看板展示的主设备，没有已配对设备时引导进入添加耳机流程。面板跟随界面风格，不使用背景模糊；组件位于 `:sharedUI`，与主窗口共用同一模型与控制入口。
  - （2026-11 修订）**可扩展控制项与用户自定义**：放宽最初“降噪档位等配置性项目只在主窗口”的限制。托盘面板支持桌面端全局自定义 5 项扩展控制（渲染时与当前耳机 `DeviceCapabilities` 取交集）：降噪／通透档位（合并为一项开关，开启时固定紧跟降噪模式下方按当前模式条件显示），以及游戏模式、EQ 预设、空间音频（含空间模式与条件显示的空间内容）、独立麦克风模式（4 项支持开关与上移／下移排序）。默认开启降噪／通透档位、游戏模式与 EQ 预设；编辑入口位于主窗口设置页的“托盘面板控制项”二级页面（托盘面板底栏在已配对时提供直达入口）。EQ 预设与空间选项采用各界面风格的标准下拉组件：Miuix 通过托盘面板外层的 `Scaffold` overlay 层在同窗口内渲染 `OverlayDropdownPreference`，Material 3 使用 `DropdownMenuPopup`，弹窗展开期间通过 `onPopupOpenChange` 暂停 Wayland 原生窗的失焦／移出自动收起；独立麦克风模式未激活时禁用并提示前提；写入状态与错误复用 `SettingFeedback`；面板默认上限宽 360dp、高 520dp（受 `usableScreenSize` 约束，超出滚动）。原生托盘菜单与 Android 快捷面板边界不变。
  - （2026-11 补充）原生面板窗口 `TrayPanelWindow` 只在打开时组合：第二个实例启动时，每个 `DecoratedWindow` 都会被重新显示，而这次启动应唤回主窗口，不是面板。面板与主窗口的尺寸不超过默认屏幕上窗口可用的区域（不含任务栏和面板，`usableScreenSize`）。Java 报告不出该区域时（没有 X 显示，例如没有 XWayland 的 Wayland 会话）取空值，不设上限。这只是尽力而为：数值采用 Java 报告的单位，只有平台把缩放比例传给 Java 时才与 dp 一致。
- **托盘菜单**：控制连接状态、电量、降噪模式，以及“托盘面板”“打开 OpenRose”“退出”。GNOME 的 AppIndicator 扩展在左键单击时弹出菜单、双击才激活，因此菜单同样承载快捷控制。
  - （2026-11 补充）原生菜单的回调不在界面线程上运行，`TrayActions` 让每个条目绑定构建时所显示的状态。原生实现中再次选择已勾选的降噪模式会将其取消勾选，因此只有选择不同的模式才写入（降噪模式菜单项）。
- Windows 和 Linux 的首个日用目标是在主窗口关闭时完成常用操作。两端分别用 EarFree i5 验收：开启后台控制，系统音频连上耳机后从托盘看到设备上报的电量；在托盘切换降噪模式后收到耳机确认；系统音频断开再连后，托盘恢复读数与控制。
- **启动**：“登录时启动”默认关闭，经 `nucleus.autolaunch` 实现；后台控制开启且托盘可用时，登录启动只驻留托盘。再次启动应用唤回主窗口。
  - （2026-11 补充）登录时启动只对安装版开放（`LoginLaunch.read`）：未打包运行时登记的登录项会是开发用的 JVM 命令。
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

2026-11-24 在 niri 原生 Wayland 上实测：主窗口开启但滚出视口时，niri 暂停派发 `wl_surface.frame`，Nucleus 2.5.18 的 `TaoComposeSceneHostLinux` 对非 popup 窗口固定使用 `swapInterval = 1`，主窗口重绘会使 `TaoSwapThread` 停在 `NativeTaoEglBridge.nativePresent`（`eglSwapBuffers`）；此时打开或关闭 `TrayPanelWindow` 在主线程调用 `nativeAttachWayland` / `nativeDetach` 会与共享 Wayland `EGLDisplay` 互锁，直到主窗口滚回视口。客户端通过 `Main.kt` 按主窗口焦点收敛 `LocalCirculationVisible` 减少视口外轮询重绘，并保持 `TrayPanelWindow` 走原生 Wayland（不启用 `forceX11`，避免 XWayland 依赖与分数缩放退化）；彻底修复依赖上游 [NucleusFramework/Nucleus#958](https://github.com/NucleusFramework/Nucleus/issues/958)。

托盘原生库 `libLinuxTray.so` 经 sd-bus 与托盘宿主通信，链接 `libsystemd.so.0`，Linux 安装包需声明该依赖。`composenativetray-app` 2.1.7 依赖 Nucleus 2.5.5，应用使用 Nucleus 2.5.18，两者须在同一 2.5.x 线上保持兼容。

Android 行为不变：托盘依赖只在 `:desktopApp`，桌面设置使用独立的 `desktop.` 前缀，平台内容经现有 `PlatformUi` 插槽提供。
