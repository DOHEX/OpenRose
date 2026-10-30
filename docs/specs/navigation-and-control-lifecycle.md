# Spec: miuix-nav 导航重构与音频驱动的控制连接生命周期

## Problem Statement

作为 ROSE / ROSESELSA 耳机用户，在日常使用 OpenRose 管理和调控耳机时面临若干不符合直觉和繁琐的体验：
1. **未连接时的认知困惑与入口冗余**：当耳机未连接时，打开 App 的首页本身就是一个扫描列表，但顶栏右上角依然存在一个内容完全相同的 `+` 按钮；屏幕正中显眼的“连接与运行状态”卡片外观形似设备卡片，点击后却跳转到底层系统诊断，造成用户误导。
2. **缺乏系统已配对耳机的自动感知与重连**：当用户已经戴上耳机、手机正在播放音乐（蓝牙音频通道已连接）时，打开 OpenRose 却显示“还没有发现耳机”，要求用户手动点击“扫描耳机”进行广播搜索，无法像主流耳机官方 App 一样自动直达控制看板。
3. **页面导航生硬且缺乏手势返回**：在主界面与二级功能页（均衡器、触控手势、降噪设置、设备详情等）之间切换时，页面跳转生硬无过渡动效，无法通过边缘滑动返回，且底部 Tab 切换容易产生不符合直觉的回退栈。
4. **工程术语直接暴露于主交互面**：“断开控制连接”（普通用户常误解为断开音频）等底层技术概念被直接作为显眼的按钮展示在控制面板中，增加了非必要的认知负担。

## Solution

1. 引入 Compose Multiplatform 导航运行时 `miuix-nav`，建立根级 `NavDisplay` 架构。主界面承载底部常驻 Tab（耳机、系统、设置），二级详情与设置页面作为独立路由全屏推入并隐藏底部栏，支持自然的边缘手势滑动返回（`NavSwipeDirection.LeftToRight`）。
2. 在应用设置中提供独立的「转场动效」配置项（Miuix 弹性滑动、标准平移、淡入淡出、关闭动效），使用户能够自主定制页面过渡体验，且不与界面风格强绑定。
3. 重构未连接时的首屏体验：移除未连接时顶栏冗余的 `+` 按钮与中心误导性诊断卡片；优先显示系统已配对（Bonded）的 ROSE 设备卡片，提供明确的「连接」按钮与次级「扫描新设备」入口。
4. 建立由“系统音频连接驱动”的控制生命周期：当检测到已配对的 ROSE 耳机在系统蓝牙中已处于音频连接状态时，App 在后台自动静默建立控制连接并直接展现设备控制面板；耳机入盒断开音频后，自动释放控制连接并退回离线状态。
5. 实施高级信息分层透出：将“断开控制连接 / 释放连接”降级收拢至「设备详情」的高级调试项中，主界面专注于直观、高效的设备控制。

## User Stories

1. As a regular headphone user, I want the app to immediately show my headphone controls when my headphones are already connected to my phone, so that I don't have to manually scan and pair every time I open the app.
2. As a regular headphone user, I want to see my paired ROSE headphones listed clearly on the home screen when they are not currently connected, so that I can initiate a connection with a single tap.
3. As a regular headphone user, I want the home screen to have clean and non-redundant controls when disconnected, so that I am not confused by duplicate "+" buttons that lead to the same scan view.
4. As a regular headphone user, I want clicking the status card not to unexpectedly dump me into technical diagnostics, so that my navigation flow stays predictable.
5. As an earphone user navigating into the Equalizer page, I want the page to smoothly slide in over the full screen with spring physics, so that the experience matches native HyperOS and modern mobile standards.
6. As a user on any secondary settings page, I want to swipe from the left edge of the screen to pop back to the previous screen, so that I can comfortably navigate one-handed.
7. As a user browsing secondary pages, I want the bottom tab bar to hide while I am inside a detail page, so that the entire screen is dedicated to the settings I am modifying.
8. As a user switching between the bottom tabs (Headphones, System, Settings), I want tab switching to replace the active view without piling onto the back stack, so that pressing back from another tab returns me directly to the Headphones home.
9. As a user on the Headphones home screen, I want pressing the system back button to exit or background the app, so that I am not trapped in an endless loop of back presses.
10. As a user who prefers minimal animations, I want to change the page transition to fade or none in Settings, so that navigation is instantaneous and suits my preference.
11. As a user who prefers fluid visual motion, I want to select Miuix spring physics for transitions in Settings, so that page changes have smooth parallax and bounce effects.
12. As a user switching between Miuix and Material 3 interface styles, I want my active navigation stack and headphone control session to remain completely uninterrupted, so that my music and controls are not dropped during restyling.
13. As a user who puts their headphones back into the charging case, I want the app to automatically detect that audio disconnected and smoothly release the control session, so that the app does not waste battery retrying connection in the background.
14. As an everyday user, I want not to see confusing "Disconnect Control Connection" buttons on the primary dashboard, so that I am not worried about interrupting my audio playback accidentally.
15. As an advanced user or developer, I want to still access low-level control session disconnection and reconnection in the Device Details or System page, so that I can troubleshoot hardware communication issues when needed.
16. As a user configuring HyperOS Super Island, I want to find island presentation toggles inside Settings without having them scattered across disjointed tabs, so that my setup workflow is cohesive.
17. As an advanced user inspecting Xposed integration, I want the System tab to clearly show Bluetooth permissions, module hooking availability, and IPC bridge diagnostics, so that I can verify my system environment.
18. As a user with multiple ROSE devices, I want the unconnected home screen to display each paired model with its distinct name, so that I can choose which headset to manage.

## Implementation Decisions

- **Navigation Runtime Replacement**:
  - The ad-hoc mutable list backstack in the application root is replaced by `top.yukonga.miuix.kmp:miuix-nav`.
  - A root `NavDisplay` manages all full-screen entries.
  - The root entry is `Route.Main(tab: AppPage)`, containing the persistent scaffold, app bar, and bottom navigation bar (or navigation rail in wide mode).
  - Secondary pages (`Route.Equalizer`, `Route.Touch`, `Route.Audio`, `Route.Details`, `Route.Diagnostics`, `Route.AddDevice`) are declared as serializable `NavKey` implementations and pushed onto the root stack.
  - Secondary entries configure `swipeDismiss = NavSwipeDirection.LeftToRight` to enable native predictive back and swipe dismissal.

- **Homepage Noise and Game Controls (2026-10-30)**:
  - The shared homepage renders noise modes from model capabilities, using icons above labels: four in one row for i5 and six in two rows of three for i7.
  - ANC and transparency levels remain on the homepage and follow the confirmed current mode. Automatic and dynamic modes do not display either manual level control.
  - The former `Route.Noise` page is removed. The low-frequency noise-cycle row sits near the bottom of the homepage feature list, after equalizer, touch, and audio settings and before device details. It opens a multi-select picker directly; Miuix uses `OverlayDropdownPreference`, and Material 3 uses a multi-select dialog.
  - Cycle participants come from the separate cycle capability; both current models offer four regular modes, excluding i7 automatic and dynamic modes. Each change submits immediately, permits an empty selection, and waits for device confirmation without closing the picker or changing the current noise mode.
  - Game mode has its own homepage switch when writable and is removed from the equalizer page. A game-only device capability does not create an equalizer entry.
  - Open cycle pickers survive interface-style replacement, and dismissal does not resend or undo submitted settings. Missing cycle state and pending operations prevent edits.

- **Configurable Transitions**:
  - The core user settings schema is expanded to include a `PageTransition` enumeration with options: `MIUIX_SPRING`, `SLIDE`, `FADE`, `NONE`.
  - The `NavDisplay` reads the active transition preference and applies the corresponding `NavTransition` preset or motion curve, independent of the `InterfaceStyle`.

- **Tab Switching Semantics**:
  - Tapping bottom navigation tabs within `Route.Main` updates the active tab state in-place without pushing additional entries onto the `miuix-nav` stack.
  - A back gesture or back key press from non-home tabs (`SYSTEM` or `SETTINGS`) resets the active tab to `HEADPHONES`. A back press from `HEADPHONES` exits the host activity.

- **Audio-Bound Control Lifecycle**:
  - The platform transport layer exposes bonded Bluetooth devices matching ROSE model signatures.
  - The platform adapter observes system audio connection events (A2DP / HFP connection status).
  - When an authorized bonded device is actively connected to system audio, the control session automatically triggers connection without user intervention.
  - When system audio disconnects, the control session drops the GATT/SPP channel and transitions to the disconnected state.

- **UI De-cluttering and Tiered Disclosure**:
  - When disconnected, the top bar `+` action is hidden.
  - The home screen displays a dedicated card for paired devices with explicit connect actions, followed by a secondary scanning action.
  - The primary control screen removes the secondary "Disconnect Control Connection" button; this capability is moved to the advanced section of the Device Details screen.
  - All domain rules follow `GLOSSARY.md` and ADR 0001, ADR 0005.

## Testing Decisions

- **What makes a good test**:
  - Tests must verify externally observable user interactions, navigation flows, and state transitions.
  - Tests must never assert against internal implementation details such as private transition animation ticks or local state variable names.

- **Tested Modules**:
  1. **UI Layer (`composeApp`)**:
     - Navigation stack pushes, pops, and back-handler intercepts using Compose semantics tree nodes.
     - Tab switching and back-button routing to the home tab.
     - Presence and absence of top-bar actions depending on connection state.
     - Settings preference dispatching when transition animation is changed.
  2. **Core Session & Transport (`core`)**:
     - Auto-connect invocation when a bonded device is marked as audio-connected.
     - Safe fallback to manual connection when no audio channel is connected.
     - Session survival across interface style switches.

- **Prior Art**:
  - `OpenRoseNavigationUiTest.kt`: Uses headless `ImageComposeScene` with `NavigationHarness` to drive clicks, test tabs, and verify visible semantic text.
  - `OpenRoseConnectedHomeUiTest.kt`: Tests home screen card visibility across connected and disconnected states.
  - `ControlSessionBehaviorTest.kt`: Tests transport state transitions and lifecycle responses.

## Out of Scope

- Reverse-engineering new or unreleased ROSE headphone models not currently covered by `ModelProfile`.
- Direct audio stream processing or custom equalizer DSP on the Android OS audio pipeline (handled strictly by hardware on-chip DSP via protocol commands).
- Managing non-ROSE Bluetooth audio devices.
- Refactoring the internal Xposed hook implementation in the system bridge service.

## Further Notes

- Decision recorded in architectural decision record `docs/adr/0005-miuix-nav-and-audio-bound-control-lifecycle.md`.
- Domain terms updated in `GLOSSARY.md` (added `转场动效`).
