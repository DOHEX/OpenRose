# Android 控制会话与系统集成生命周期研究

2026-11-01。本文记录架构研究证据及已确认的访谈决策。用户报告：未打开 OpenRose 时，融合设备中心无法读取耳机电量或调节降噪。该现象作为需求与故障事实接受，本次不要求用户重复复现。

## 已有约束

- 普通 Android 客户端无需 root 或模块即可控制耳机；兼容框架中的模块提供 HyperOS 系统集成。见 [README](../../README.md#android-使用)。
- 应用界面和系统入口操作同一权威控制会话；同一设备的控制连接、发送队列与状态须有明确所有者。共享 Kotlin 代码不等于跨进程共享运行中的会话。见 [ADR 0002](../adr/0002-shared-kotlin-core-and-platform-adapters.md)。
- 系统音频连接驱动自动建立和释放控制连接，保留显式手动连接。见 [ADR 0005](../adr/0005-miuix-nav-and-audio-bound-control-lifecycle.md)。
- 官方提示继续由小米蓝牙进程直接调用系统服务，不增加 `android` / `system_server` Hook。见 [ADR 0004](../adr/0004-official-prompts-without-system-server-hooks.md)。

## 调研基线（已被取代的旧实现）

调研时的实现把应用进程当作唯一所有者：

- 控制会话在 OpenRose 自身进程的 `OpenRoseApplication.onCreate()` 创建，不是在 `MainActivity` 创建。
- 应用内的 `ControlBridgeService`（bound Service）向 Hook 宿主提供 AIDL，自身不另建会话；Hook 模块在 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service` 适配系统入口，但不在宿主开启蓝牙控制客户端。
- Hook 客户端以显式组件及 `BIND_AUTO_CREATE` 绑定该 Service，初次绑定失败和 binding died 只有有限重试（`MAX_BINDING_RETRIES = 1`）；绑定后还须取得由应用发布、经框架 RemotePreferences 传递的 HMAC 会话凭据并通过身份、会话和快照校验。
- 融合设备中心的电量与降噪适配只消费经过认证的新鲜快照，命令经桥接送回应用进程的会话。
- Manifest 只有桥接 bound Service，没有前台控制服务、伴生设备服务或开机广播接收器。

这一设计的问题在于：模块系统入口的可用性取决于应用进程是否存在、能否被绑定以及应用是否已发布凭据。该路径（`ControlBridgeService`、`ControlBridgeHost` 与 HMAC 凭据发布和校验）已在 #77 删除，下一节是取代它的当前设计，二者不再并存。当时尚无确定的故障根因：“只有打开 Activity 才能创建会话”与源码不符，也不能把故障归因于应用进程被回收。

## OpenRose 当前实现

完整约束与交接规则见 [ADR 0011](../adr/0011-dual-host-single-control-session.md)；这里只列与本研究相关的代码事实。

1. 同一设备只有一个所有者，由已保存的控制方式选择：普通控制由应用进程内按需创建的 `ControlSession` 承载，模块控制由 `com.android.bluetooth` 内的 `ModuleControlHost` 承载。应用侧入口是 `core` 中的 `SwitchingControlRuntime`，在 [OpenRoseApplication.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/OpenRoseApplication.kt) 创建。见 [SwitchingControlRuntime.kt](../../core/src/commonMain/kotlin/io/github/dohex/openrose/core/SwitchingControlRuntime.kt) 与 [ModuleControlHost.kt](../../core/src/commonMain/kotlin/io/github/dohex/openrose/core/ModuleControlHost.kt)。
2. 模块宿主由 [ModuleHostRuntime.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/module/ModuleHostRuntime.kt) 在蓝牙进程内创建，使用宿主自己的 Context、UID 与权限，从模块持久化配置和用户解锁状态启动并读取现有音频连接；不依赖 OpenRose Activity、`Application`、应用 Service 或应用发布的凭据，因此不存在 HMAC 会话凭据。
3. 宿主经 [HostEndpoint.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/bridge/HostEndpoint.kt) 暴露 Binder 端点：端点由宿主进程内已有服务按 `ACTION_CONTROL_HOST` 交出，找到端点不等于授权；授权是单独的 `open`，角色只来自 Binder 调用者 UID 及其所属包，之后每次调用校验租约、调用者与所有者代际。
4. 应用经 [ModuleHostBinderLink.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/bridge/ModuleHostBinderLink.kt) 绑定并使用宿主，`SwitchingControlRuntime` 通过它读取状态、提交设置并请求释放；仅有释放回执或观察到宿主进程死亡才算释放证明，解绑、超时与失联都不算。
5. 三个系统集成作用域内的消费者由 [OpenRoseBluetoothModule.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/module/OpenRoseBluetoothModule.kt) 经 [AuthenticatedBridgeClient.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/module/AuthenticatedBridgeClient.kt) 使用同一个宿主租约；目标进程由 [BridgeTarget.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/bridge/BridgeTarget.kt) 显式列出，MiLink 与小米蓝牙允许匹配包内子进程。它们不创建传输，也不连接耳机。
6. 融合设备中心的电量与降噪适配只消费宿主经租约交出的状态，无状态时不能提供真实电量，降噪命令经租约进入宿主并以耳机回包确认。见 [MiLinkNativeHeadsetAdapter.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/integration/MiLinkNativeHeadsetAdapter.kt)。
7. 普通控制的后台运行由 [ControlService.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/ControlService.kt)（`connectedDevice` 前台服务）持有会话；进程消失后的唤起使用清单接收器与伴生设备服务（`OrdinaryWakeReceiver`、`OpenRoseCompanionService`），见 [AndroidManifest.xml](../../androidApp/src/main/AndroidManifest.xml)。唤起只请求启动该服务。
8. `ControlSession` 初始化订阅音频连接流；Android 传输注册动态广播，并在初始化时查询现有 A2DP/HFP 连接。控制会话创建、权限可用、识别到已连接耳机以及控制就绪，是不同阶段。见 [ControlSession.kt](../../core/src/commonMain/kotlin/io/github/dohex/openrose/core/ControlSession.kt) 和 [AndroidAudioProfiles.kt](../../androidApp/src/main/kotlin/io/github/dohex/openrose/AndroidAudioProfiles.kt)。

## 基线设备只读观察（旧实现）

以下观察采集于上述旧实现，早于 #77 之后的双承载设计，不描述当前代码。执行了 `adb devices -l`、`adb shell dumpsys activity services io.github.dohex.openrose`、`adb shell dumpsys package io.github.dohex.openrose`，未启动 Activity、强停应用、切换蓝牙或发送耳机控制命令。

- 已连接设备为小米 14（`houji`）。已装 OpenRose 为 `0.1.0-dev`、target SDK 36，蓝牙扫描与连接权限已授予，包状态 `stopped=false`。
- 采样时旧的 `ControlBridgeService` 存在，运行在 OpenRose 应用进程；绑定者包含 `com.android.bluetooth`、`com.milink.service:audio`、`:core`、`:ui`。
- 当时 `startForegroundCount=0`。此观察不等于服务必须作为前台服务运行，也不证明跨进程认证及耳机控制连接已就绪。
- 定向读取旧桥接与 MiLink 日志的命令未返回日志，未据此推断成功或失败。

这些是当时的采样状态，不能重建用户所述故障发生时的时序，也不能用来否定用户报告。

## OppoPods 固定参考实现

以项目已有的固定提交 `c201e251e68b59d46b6bc2758146b2c7f13ca2a1` 为准；以下是源码事实，未在本次设备上运行 OppoPods。

- `HookEntry` 仅在 `com.android.bluetooth` 加载 `HeadsetStateDispatcher`；MiLink 与小米蓝牙加载自己的显示和系统接口 Hook。[模块分发](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/HookEntry.kt)
- `HeadsetStateDispatcher` Hook `AdapterService.onCreate` 及 `A2dpService.handleConnectionStateChanged`。耳机 A2DP 连接后调用 `RfcommController.connectPod`，断开后调用 `disconnectedPod`；控制客户端承载在系统蓝牙宿主，而非主应用。[生命周期入口](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/HeadsetStateDispatcher.kt)
- 耳机控制通道通过 `createRfcommSocketToServiceRecord` 创建 RFCOMM Socket，连接后启动读包及状态查询。此处是 OPPO 控制协议，不代表 ROSE i5 可以改用 SPP；ROSE 的传输仍遵循各自型号证据。[控制客户端](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/pods/RfcommController.kt)
- `sendAppStatusBroadcast` 在主应用界面不活跃时直接返回；`sendExternalPodsStatusBroadcast` 向 MiLink、小米蓝牙等包发送状态不受该界面活跃判断限制。因此系统集成的状态链路不以主应用界面存活为前提。进程内 `object RfcommController` 本身不构成跨进程共享；跨宿主传递实际使用广播。[状态发送实现](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/pods/RfcommController.kt)
- MiLink 接收广播并更新本地状态，Hook 系统耳机电量与降噪读取；`setAncStateBlock` 将降噪命令经广播送往 `com.android.bluetooth` 的控制端。[融合设备中心适配](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/milink/MiLinkServiceHook.kt)
- 上游将自身定位为 HyperOS Xposed 模块，使用步骤要求启用 LSPosed 作用域；其这套控制路径依赖注入系统蓝牙宿主，不能作为 OpenRose 已满足“免 root 独立控制”的证据。[上游要求](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/README.md)

值得借鉴的是“指定一个控制所有者，其他宿主消费状态并提交命令”。不应从此推出所有 Hook 进程都需要连接耳机，也不应据此把 OpenRose 本次故障归因于应用进程被回收；后者尚无故障时序证据。

## Android 官方能力与限制

- `BIND_AUTO_CREATE` 可按需创建目标服务；bound Service 与客户端绑定关系决定其生命周期。Activity 不存在不等于应用进程或 Service 不存在。应用已经使用这种绑定，不能仅因没有前台服务就断言当前桥接必定无法后台运行。[Bound services](https://developer.android.com/develop/background-work/services/bound-services)
- Android 8 的后台服务限制和 Android 12 起的后台前台服务启动限制需要分别考虑，不能把绑定服务一概当成后台 `startService`。前台服务也不是任意时刻都能从后台启动。[后台执行限制](https://developer.android.com/about/versions/oreo/background#services)、[前台服务版本变化](https://developer.android.com/develop/background-work/services/fgs/changes)
- BLE 连接可以在后台建立，但所属进程被杀时连接会关闭。官方为持续通信列出的路径包括 `connectedDevice` 前台服务，以及伴生设备关联与 `CompanionDeviceService` / presence；单纯把协程放进 `Application` 不等于取得持续运行保证。[蓝牙后台通信](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- target SDK 34+ 的 `connectedDevice` 前台服务需要声明服务类型、`FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_CONNECTED_DEVICE`；蓝牙使用场景须满足相应运行时权限条件。前台服务需要提供服务通知，不能把已有“焦点通知开关”等同于前台控制服务的生命周期开关。[服务类型与权限](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、[启动前台服务](https://developer.android.com/develop/background-work/services/fgs/launch)
- 伴生设备关联通过系统用户确认建立；关联本身不创建蓝牙连接。设备存在监听可用于后台唤起与持续通信，但版本、设备识别、ROSE 地址关联及目标 HyperOS 的实际行为仍需验证，不能写成已经可用的兜底保证。[伴生设备关联](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)

本次不采纳“系统蓝牙进程永不死亡”“绑定服务不受任何系统策略影响”“加一个 Intent flag 就必定穿透强停”或“没收到动态广播就是本次根因”等结论。现有代码已经查询初始音频连接；上述绝对结论或故障归因均缺少本次证据。

## 设计树与访谈决策

已确定：免 root 普通控制与可选模块共存；同一设备只有一个权威控制会话；不重新引入 `system_server` Hook。后两项沿用既有 ADR。

第一轮已由用户确认：

1. **模块自治**：完成首次配置并启用模块后，即使本次开机未打开 OpenRose、关闭界面或应用进程被系统回收，融合设备中心仍须能读取真实电量并控制降噪。
2. **免 root 后台控制**：提供用户可启用的持续后台控制，关闭界面后仍保持控制连接，接受前台服务方案所需的运行通知。伴生设备路径的具体使用范围仍须据设备与系统验证确定。

第二轮已由用户逐项确认：

- **Q3，模块承载位置**：由 `com.android.bluetooth` 承载模块控制下唯一的控制会话；OpenRose 界面、MiLink 和小米蓝牙只作为该会话的客户端。免 root 环境由应用承载，两种承载复用同一套协议、型号能力和控制状态机，不新增独立 root 控制进程。
- **Q4，模块失效时的接管策略**：模块短暂不可用时优先恢复模块控制，界面明确显示不可用；切换到普通控制由用户显式选择，且接管前必须确认旧所有者已退出或释放控制连接，不因失联自动建立另一条控制连接。
- **Q5，明确停止的语义**：强行停止 OpenRose 主应用结束应用自身的后台控制，已经启用的自治模块继续提供系统集成；另外提供明确的停用模块控制入口。
- **Q6，免 root 自动恢复范围**：后台控制开启后，在耳机后续音频连接及重启后的连接中也自动恢复，而非只维持当前会话；允许必要的一次性系统关联授权与自启动配置，唤起能力按 Android 版本和目标系统验证。

第三轮已由用户逐项确认：

- **Q7，用户选择持续有效**：用户明确选择普通控制后保留该选择，模块恢复不自动切回模块控制；再次切换仍由用户发起。
- **Q8，普通控制期间的系统集成范围**：普通控制提供应用及可选后台控制，暂停模块提供的融合设备中心和系统耳机提示等增强功能；不保留应用会话向这些系统入口转发的另一条路径。
- **Q9，开机恢复的起点**：模块控制及免 root 后台控制均从本次开机首次解锁后开始自动恢复；首次解锁后无需打开 OpenRose，首次解锁前的自动控制不列入本次目标。

设计树的九项产品与架构取舍及整体共同理解均已由用户确认，无剩余分支。完整架构、交接约束与验收条件见已接受的 [ADR 0011](../adr/0011-dual-host-single-control-session.md)。运行时代码迁移已在代码中完成（上文“OpenRose 当前实现”），但各步骤只有离线契约测试，真机行为尚未验收。


