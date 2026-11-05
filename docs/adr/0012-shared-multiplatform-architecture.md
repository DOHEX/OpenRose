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

实施（#87）：Android 集成已归入独立的 `:androidIntegration` KMP 模块，生产代码与离线测试分别位于 `commonMain`、`commonTest`。`ControlState`／`PlatformState` 只保留共有控制状态与平台事实，应用同时观察 `SwitchingControlRuntime.integration`；所有者视图从同一次控制状态捕获派生系统呈现，再通过 `HostFrame` 发布，提示过期与领取也会更新订阅，断开或失联后撤销。系统入口在集成层核对设备与会话引用，再调用同一控制入口；`ControlRuntime.set` 的可选 `expectedSessionId` 仅为各平台共用的入队前置条件，在会话锁内原子检查，避免核对后发生的会话替换将设置写到另一会话。设置字段及其类型随后由 #92 按归属层拆分（见下文）；`:sharedUI` 暂时依赖 `:androidIntegration`，由 #89 移出 Android 界面段落。本次不增加 Android 实机验收结论。

实施（#88）：`ControlSession` 只持有权威状态流并实现 `ControlRuntime` 门面；`ConnectionTargeting` 仲裁手动、系统固定目标、添加耳机与音频驱动的连接意图，`DeviceDiscovery` 管理扫描、已配对设备与候选解析。`ControlLink` 集中单次连接尝试的端点选择、打开、订阅、初始化、恢复、关闭与未确认关闭的重试，作为 #90 接入共享 GATT 层的位置；`SettingQueue` 管理校验、队列、写入节流与新鲜读回确认，并与连接生命周期共用锁来原子核对会话及入队。型号能力由纯函数 `deviceCapabilities` 推导。控制状态及其嵌套的设置、能力、协议字段值均使用 `kotlinx.collections.immutable` 持久集合，构造时接纳外部集合，删除会话与系统读取入口对控制状态的防御性拷贝。保留原有 `ControlSession*Test` 的断言语义，只调整集合构造；离线运行另覆盖订阅恢复、设置确认、界面风格切换连续性与失败关闭重试，本次不增加实机验收结论。

添加耳机的连接尝试结果先完成失败清理，再核对该流程是否仍对应当前就绪会话，保持拆分前的顺序。旧添加流程在成功后被同设备的新音频会话取代时，只拒绝旧调用，不清除新会话的音频归属；新增门面回归测试和独立运行冒烟均验证随后音频断开会关闭新控制连接。

实施（#92）：`core.UserSettings` 只保留 `forceBle`；`sharedUI.UiSettings` 持有界面风格、明暗模式、转场动效、背景模糊和底栏样式；`androidIntegration.IntegrationSettings` 持有超级岛、焦点通知、触发条件、融合设备中心偏好、后台控制及控制方式。各层编解码只读取自己的 `core.`、`sharedUI.`、`androidIntegration.` 前缀键，旧键作废且不迁移；显式空触发条件仍表示不自动提示，缺失或未知值回到默认值。`HostSettings` 只组合控制与集成设置，应用从持久化存储在发布锁内读取最新配置，模块宿主与 IPC 共用该编解码。控制入口的设置更新只接收控制设置，系统呈现与融合设备中心从集成层读取偏好，外观变化独立于控制状态。离线测试覆盖三层编解码、应用发布与宿主读回一致性，以及 Android / 桌面存储重新打开后重建运行时；未增加 Android 实机验收结论。

实施（#89）：`RuntimeOpenRoseModel` 在宿主作用域中统一暴露控制状态、外观设置与界面意图；两端经由 `OpenRoseApp(model, platformUi)` 组装界面。`PlatformUi` 提供状态、设置、诊断与耳机页段落，以及权限和蓝牙设置动作；共享界面只判断能力和控制事实。Android 段落与作用域重启迁入 KMP Android / JVM 模块 `:androidIntegrationUI`，`:sharedUI` 和桌面运行时依赖图均不含 Android 集成模块。桌面端使用真实控制入口，无传输时显示不可用原因。整应用渲染限于导航、跨页流程、风格切换连续性与主页面滑动，其余在页面或段落级配假模型保留双风格覆盖；本次不增加 Android 实机验收结论。

离线验证（2026-11-05，24 核 Linux）：`./gradlew :sharedUI:jvmTest --rerun :androidIntegrationUI:jvmTest --rerun --console=plain` 强制执行全部 113 个 UI 测试，零失败、零跳过，Gradle 调用墙钟 22.6 s，低于 #86 的 44.2 s 基线。`check offlineCheck :androidApp:assembleDebug` 通过；桌面运行时依赖查询不含 Android 集成模块。

桌面实际窗口冒烟覆盖状态页与添加耳机流程的传输不可用原因、设置对话框和两种界面风格间的切换；构建产物固定后切换正常。Android 未连接设备或模拟器，状态与设置内容由双风格 Compose 页面测试覆盖，不据此宣称 Android 实机通过。

实施（#90）：`:core` 的 `GattDriver` 只描述单次原生连接、服务表、协商出的 MTU、本地通知路由、远端订阅、带／不带应答写入、事件与释放；`GattControlConnection` 统一执行首次发现后最多两次重试、MTU 1.5 s 等待与 20 字节回退、`min(MTU - 3, 512)` 容量限制、完整报文校验、订阅与写入串行化、订阅 5 s 超时及远端／本地回滚。原生错误经 `GattException` 分类，只有明确的暂时性错误可恢复；权限、参数、不支持、断开与未知错误保持终止语义。发现中的服务变更使该次服务表失效，发现完成后的变更结束旧连接并交由控制会话重建。Android 仅保留原生句柄、回调关联、CCCD 操作及状态码映射；取消中的操作保留原生回调槽，防止迟到回调完成后续操作，未成功释放的句柄保留以便显式关闭重试。

`AudioFactsDriver` 报告原始配对名称与地址、音频协议快照及广播；`AudioFacts` 统一执行型号匹配、地址规范化与保守的 LE Audio／Classic 推断。先注册事件再查询既有连接，查询完成前按“协议＋地址”合并期间事件；蓝牙关闭使旧查询失效，重新开启后重查。Android 的 `AndroidAudioProfiles` 只持有协议代理租约与广播接收，不再包含这些规则。

审查补充：特征按发现得到的实例标识传递，操作与通知都保留该实例，避免不同服务复用 UUID 时串到另一特征。协议代理失效只使该协议快照不可用，不丢弃仍有效的 A2DP／HFP 连接事实；不可读的初始查询与明确的蓝牙关闭事件分别处理，随后可读的音频广播仍可恢复自动连接，查询期间的广播也不被不可读结果覆盖。

WinRT 原语可实现：[`GattDeviceService`](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattdeviceservice) 提供服务及特征对象并支持释放；本地注册 `ValueChanged` 后调用 [`WriteClientCharacteristicConfigurationDescriptorAsync`](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic.writeclientcharacteristicconfigurationdescriptorasync) 设置 Notify／Indicate／None，描述符无需暴露给共享层；[`WriteValueAsync(IBuffer, GattWriteOption)`](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattcharacteristic.writevalueasync) 支持有／无应答写入；[`GattServicesChanged`](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothledevice.gattserviceschanged) 对应服务变更事件。Windows 自行协商 MTU，驱动报告 [`GattSession.MaxPduSize`](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maxpdusize)，不要求能主动请求指定 MTU。此为文档核对，未实现或实测 Windows 驱动。

可执行离线冒烟使用真实 `ControlSession`、协议编解码、设置队列与共享 GATT，假驱动完成 i5 默认 MTU 下的 13 项初始化读取、修改降噪模式并经合成回读确认、断开释放；冒烟脚本运行后删除。`adb devices -l` 没有设备，小米 14 + i5 普通控制的连接、读取、设置及耳机确认仍需维护者实机验收，不能以本次离线结果替代。

