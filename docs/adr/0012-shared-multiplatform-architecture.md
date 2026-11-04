---
status: accepted
---

# 多端共享架构：控制领域核心、Android 集成分离与共享 GATT 层

[ADR 0002](0002-shared-kotlin-core-and-platform-adapters.md) 确立了 Kotlin 共享核心，但桌面端此前只有界面：没有平台传输，界面回调由各入口各自转发，桌面端多数为空实现。`core` 同时承载 Android 的所有者交接、模块宿主与 IPC、系统呈现与连接提示、融合设备中心映射及后台控制，共享界面也按 `HostPlatform.ANDROID` 分支。为让各平台接入同一控制闭环、不按平台重复逻辑，按领域重新划分模块。应用尚未发布，设置键与 IPC 不保留兼容。

## 决策

- **模块**：对齐 JetBrains 2026 年的默认 KMP 结构，入口独立成模块。
  - `:core`：型号与能力、协议、控制会话、控制入口、传输契约与共享 GATT 层、已配对设备与音频连接推断规则、控制相关设置，不含 Android 概念。
  - `:androidIntegration`：控制方式与所有者交接、模块宿主与客户端、IPC 编码、系统入口、系统呈现（超级岛、焦点通知、连接提示）、融合设备中心映射、后台控制状态与设置。纯 Kotlin，在 JVM 上测试。
  - `:sharedUI`：共享界面、表现层、平台插槽与外观设置。`:androidIntegrationUI`：Android 专属界面段落。
  - `:transportBluez`：Linux 平台驱动。`:androidApp`：全部 Android API 胶水，含 AIDL（Android-KMP 库插件不支持 AIDL）。`:desktopApp`：组装。
- **状态**：`ControlState` 只含控制会话与平台蓝牙事实，Android 集成状态由 `:androidIntegration` 单独暴露。
- **控制入口**：`ControlRuntime` 只含各平台共有的操作。系统入口的操作（来自系统的连接、按会话引用读取与设置、领取连接提示）由集成层核对后再调用同一控制入口；连接提示的判定与计时移出 `ControlSession`。`ControlSession` 只保留门面，组合连接目标仲裁、发现、单次连接、设置队列等部件，状态使用不可变集合。
- **设置**：按归属层拆分，各层自带编解码，键名加层前缀。
- **界面**：`OpenRoseApp(model, platformUi)`。平台通过插槽提供状态页、设置页段落与平台动作，共享界面不按平台名称分支。
- **传输**：平台只实现 GATT 原语驱动（连接、取得服务、协商出的 MTU、开关通知、写入、关闭、事件流），并把原生错误映射为共享失败类型。共享连接层负责写入串行化、服务发现重试、MTU 回退与截断、订阅顺序与超时回滚、可恢复失败分类、服务变更处理。平台只报告原始的已配对设备与音频协议事件；型号匹配、地址规范化、音频连接推断，以及“先查询现有连接再监听、事件优先”的规则都放在 `:core`。Android 迁到同一层。
- **构建与测试**：`build-logic` 约定插件与配置缓存。UI 测试使用官方 `runComposeUiTest` 与虚拟时间；整应用渲染只用于导航、跨页流程与风格切换连续性，双风格矩阵在页面和段落级覆盖。

## 考虑过的方案

- **窄拆**（只移出宿主与 IPC 机制）：连接提示仍在 `ControlSession`，所有者、呈现与后台字段仍在 `ControlState`，桌面端继续依赖 Android 概念。
- **各平台直接实现 `ControlTransport`**：Android 中约 620 行与平台无关的 GATT 与音频连接推断规则，要在 Linux、Windows 各复制一份。
- **`jvmAndAndroid` 中间源集**：Kotlin 不支持 JVM 与 Android 共用的源集，Android 集成逻辑因此放进独立 KMP 模块的 `commonMain`。

## 后果

[ADR 0011](0011-dual-host-single-control-session.md) 的决策不变，其实施记录中 `SwitchingControlRuntime`、`ModuleControlHost` 与连接提示判定的位置改为 `:androidIntegration`。Android 的 BLE 路径迁到共享 GATT 层后，须在 Android 实机冒烟。

实施（#87）：Android 集成已归入独立的 `:androidIntegration` KMP 模块，生产代码与离线测试分别位于 `commonMain`、`commonTest`。`ControlState`／`PlatformState` 只保留共有控制状态与平台事实，应用同时观察 `SwitchingControlRuntime.integration`；所有者视图从同一次控制状态捕获派生系统呈现，再通过 `HostFrame` 发布，提示过期与领取也会更新订阅，断开或失联后撤销。系统入口在集成层核对设备与会话引用，再调用同一控制入口；`ControlRuntime.set` 的可选 `expectedSessionId` 仅为各平台共用的入队前置条件，在会话锁内原子检查，避免核对后发生的会话替换将设置写到另一会话。设置字段及其类型暂留 `:core`，由 #92 拆分；`:sharedUI` 暂时依赖 `:androidIntegration`，由 #89 移出 Android 界面段落。本次不增加 Android 实机验收结论。
