---
status: accepted
---

# miuix-nav 导航重构与音频驱动的控制连接生命周期

## 背景

OpenRose 早期版本采用自建 `List<AppPage>` 状态模拟回退栈，在二级页面跳转、全屏展示与手势返回方面缺乏现代移动平台体验：
1. 界面跳转缺乏物理动效与手势滑动返回，二级详情页与常驻底部栏混在一起，无法提供沉浸式调节体验。
2. 当用户已佩戴耳机、系统蓝牙音频（A2DP/HFP）已连接时，App 仍处于未连接扫描页，需要手动搜索并点击连接，缺乏官方耳机的自动直达控制能力。
3. 未连接首页在未连接时同时存在顶栏 `+` 按钮和扫描列表，中央显眼的诊断卡片容易被普通用户误认为设备卡片。
4. “断开控制连接”等工程底层术语暴露在主控制看板上，容易让用户误以为会断开音频播放。

## 架构决策

### 1. miuix-nav 根级路由与全屏转场架构

- 引入 Compose Multiplatform 导航运行时 `top.yukonga.miuix.kmp:miuix-nav`。
- 根级采用 `NavDisplay` 管理全屏路由栈，定义强类型序列化路由层次 `Route : NavKey`。
- 根路由为 `Route.Main(tab: AppPage)`，承载常驻 Scaffold、TopAppBar 以及底部导航栏（宽屏为导航侧边栏 NavigationRail）。
- 二级页面（`Route.Noise`, `Route.Equalizer`, `Route.Touch`, `Route.Audio`, `Route.Details`, `Route.Diagnostics`, `Route.AddDevice`）作为独立全屏路由推入，进入二级页面时自动隐藏底部导航栏，并配置 `swipeDismiss = NavSwipeDirection.LeftToRight` 支持原生边缘手势返回。
- 底部 Tab（耳机、系统、设置）切换在 `Route.Main` 内部原地更新，不向 `NavDisplay` 堆叠多余栈条目；在非耳机 Tab（系统或设置）下触发系统返回操作时，优先回退到主耳机 Tab，在耳机主页返回时则退出应用。
- 核心设置增加独立的「转场动效」（`PageTransition`：`MIUIX_SPRING`、`SLIDE`、`FADE`、`NONE`），使用户能自主定制页面过渡效果，且与界面风格完全解耦。

### 2. 系统音频连接驱动的控制生命周期

- 平台传输层（`ControlTransport`）新增已配对设备（`bondedDevices()`）查询能力，并根据 ROSE 广播/型号特征过滤已配对的 ROSE 耳机。
- 平台适配层持续观察系统音频连接状态（A2DP / HFP 连接状态流 `audioConnectedDevice`）。
- 当检测到已配对的 ROSE 耳机在系统蓝牙中进入音频连接就绪状态时，`ControlSession` 在后台自动静默发起控制连接并进入控制就绪状态；
- 当耳机入盒断开音频连接时，`ControlSession` 自动关闭控制连接通道并恢复离线状态，避免后台无效重试与电池消耗。
- 在无音频连接或离线场景下，保留显式手动连接与断开的能力。
- 遵循 [ADR 0001](0001-preserve-control-session-on-style-switch.md)，界面风格切换时控制会话与路由栈保持连续，不中断连接。

### 3. 未连接首屏精简与控制入口分层透出

- 未连接耳机时，顶栏隐藏冗余的 `+` 添加按钮，移除中央易误导用户的“连接与运行状态”（跳转底层诊断）卡片。
- 未连接首屏优先展示系统已配对的 ROSE 耳机卡片，提供明确的「连接」操作；多副设备时分别展示其名称与型号配置。
- 提供次级「扫描新设备」入口以应对未在系统配对的新设备。
- 主控制看板移除次级的“断开控制连接”按钮，界面聚焦于耳机音频与控制参数；该底层断开能力降级收拢至「设备详情」（`Route.Details`）的高级调试区域以及耳机顶栏菜单中。

## 验证与影响

- 共享 UI 层通过 Compose 语义树和离屏渲染验证手势返回、Tab 切换回退、全屏遮罩及底部栏显隐。
- 核心层通过单元测试验证已配对设备识别、音频连接自动建立控制与音频断开自动释放。
- 既有 Miuix 与 Material 3 界面风格切换测试全部保持通过。
