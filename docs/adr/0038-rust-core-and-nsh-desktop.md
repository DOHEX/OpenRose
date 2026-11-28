---
status: accepted
---

# Rust 耳机核心与 NSH 桌面端

桌面端由 JVM 与 Compose Multiplatform 承载，太笨重：安装包要打入 JDK，托盘常驻占内存，冷启动慢，构建要经过 Gradle、Compose 与 Nucleus。Nucleus／Tao 与 ComposeNativeTray 这一层还有 [ADR 0014](0014-desktop-tray-and-background-control.md) 记录的托盘弹窗卡死、Wayland EGL 互锁，以及 #128 的退出绕路。桌面端为了与 Android 统一界面风格，共用约 1.19 万行共享界面，但桌面端并不需要 Miuix 与 Material 3 Expressive。项目尚无用户，本决策不考虑设置、持久化名称与安装包的兼容。

## 决策

- **平台**：Android、Linux、Windows 与 macOS（arm64、x64）。macOS 纳入范围，取代 [ADR 0013](0013-linux-desktop-control.md) 的“macOS 不在范围内”。niri、Hyprland、Sway 等平铺式 Wayland 合成器是 Linux 的一等目标。
- **核心**：用 Rust 写一份，各平台共用。范围包括型号配置与能力、协议、控制会话、设置队列、共享 GATT 层、音频连接推断，以及耳机流转契约与局域网实现。Android 经 UniFFI 调用核心。所有者交接、模块宿主、IPC 与系统呈现等 Android 集成仍用 Kotlin。协议测试向量（[ADR 0035](0035-protocol-test-vector-evidence.md)）与读回证据（[ADR 0033](0033-model-capability-evidence-and-readback.md)）随核心移植到 Rust。
  - UniFFI 没有流类型，Future 也不能取消。核心以回调接口推送状态，Android 侧把回调包成 Flow；取消由核心接口显式提供。
  - **控制入口**：保留 `ControlRuntime` 的语义：只有一个门面对象；对外推送完整的不可变状态快照；命令是带类型的 async 方法，以耳机读回确认为结果。NSH 适配层把快照转成 signal，Android 转成 `StateFlow`，两端都不另存一份状态。[ADR 0011](0011-dual-host-single-control-session.md) 与 [ADR 0029](0029-control-link-and-setting-queue-invariants.md) 的不变量随核心迁移。
  - **当前耳机**：由核心状态给出，用户在多副耳机之间切换是一条命令，取代 `shared/ui` 的 `PrimaryDeviceSelection`。
  - **显示名称与原因**：型号相关的显示名称（版本名、EQ 预设名、触控槽位名）放在 `ModelSpec`，新增型号只改一处；与型号无关的文案由各界面维护并各自国际化，核心不提供字段名或字段值的界面显示文本。核心只输出原因类别，如 `ConnectionBlockReason`、`ReconnectGuidance`、`TransportUnavailableReason`、`SettingPrerequisite`、`ControlErrorCode` 与 `ValidationErrorKind`；平台报告传输不可用时给原因代码与细节，文字由界面写。在现有共享界面（`shared/ui` 的 `DevicePanel`、`OperationFeedback`、`QuickPanel`、`AddDeviceContent`）仍直接呈现 `ControlError.message` 期间，`ControlError.message`（含 `crates/control` 的连接与设置队列错误，以及 `crates/headset/src/command.rs` 的 `ValidationError.message` 与协议层 `ProtocolError`）保留为随原因代码附带的细节文本，措辞严格遵循 [GLOSSARY](../../GLOSSARY.md)（耳机称“耳机”、“会话”只指控制会话），待各端按错误类别渲染后退化为诊断细节。
  - **序列化**：控制状态快照（`ControlSnapshot`）与模块控制命令（`ControlCommand`）由核心（`crates/control/src/codec.rs`）用 `serde_json` 编码并加上 4 字节帧头（3 字节魔数 `ORC` + 1 字节 `WIRE_FORMAT_VERSION`）。帧头或 JSON 非法返回 `CodecError::Malformed`，格式版本不匹配返回 `CodecError::UnsupportedVersion { expected, actual }`；`openrose-ffi` 导出 `wire_format_version`、`encode_snapshot`、`decode_snapshot`、`encode_command`、`decode_command`，`:platform:android:ffi`（`CoreDecodeResult.kt`）将其包装为 `CoreDecodeResult` 与 `coreWireFormatVersion()` 供握手与 Binder IPC 校验。Android 的 Binder IPC 与局域网流转直接传这些字节，删除 Kotlin 的 `*Wire` 编解码，握手版本重新编号。
  - **传输接口**：从一开始就同时覆盖 GATT 与 SPP。桌面驱动先做 GATT，有 i7 实机后再做 RFCOMM。
  - **沙盒**：`headset::simulator` 是模拟耳机的设备端，按型号回应协议帧，属于 sans-IO；设备端按协议族各写一份（`HeadsetSide`），所有型号都有模拟耳机，初始读数与可接受的档位、取值取自型号配置与固件，模拟器里不另写一套限制。`control` 在 `simulator` feature 下把它包成驱动。沙盒与测试用模拟读数走同一套规则，新桌面端的开发模式接入它，删除 `tools/ui-sandbox`；Android 界面测试仍用假模型，不经过 FFI。不单独建 `simulator` crate：它要依赖 `control`，`control` 的测试又依赖它，会形成开发依赖循环，同一类型被编译两份。
  - **命名**：核心类型按 [GLOSSARY](../../GLOSSARY.md) 命名：耳机一律用 `Headset`（`HeadsetState`、`HeadsetId`、`HeadsetCandidate`、`HeadsetSetting`、`HeadsetField`、`HeadsetCapabilities`、`HeadsetDiscovery`），“会话”只指控制会话，门面沿用控制入口的代码名 `ControlRuntime`，不再有 `ControlSession`。Android 集成的 Kotlin 代码由 #141 完成术语对齐：`ControlWay.ORDINARY` 改为 `APP`，`IslandMode.OFFICIAL`／`MODULE` 改为 `SYSTEM_STYLE`／`OPENROSE_STYLE`，`IslandTrigger` 改为 `IslandTiming`，`focusNotificationEnabled` 改为 `statusNotificationEnabled`，并同步更新持久化键值。
- **桌面平台层**：桌面平台代码用 Rust 写，与核心同进程运行，按职责分为 `bluetooth`（GATT、SPP、已配对耳机、音频连接事实）、`audio`（流转时让出或接入本机音频、打开声音设置）与 `discovery`（mDNS），每块再按 `target_os` 分为三个平台。蓝牙方面，Linux 用 bluer 与 zbus，Windows 用 windows-rs 调 WinRT，macOS 用 objc2 调 CoreBluetooth 与 IOBluetooth；Windows 不再启动 `openrose-winrt.exe` 子进程。Android 的平台实现仍用 Kotlin，实现核心定义的 trait。
- **Android 系统模块**：系统模块在 `com.android.bluetooth` 内加载 Rust cdylib。ART 不允许另一个 ClassLoader 再次加载同一个 `.so`，libxposed 也不卸载旧代的原生代码，因此系统模块不再支持 LSPosed 热重载，`onHotReloading` 拒绝重载；更新时用 [ADR 0025](0025-integration-scope-root-restart.md) 的作用域重启。绑定先用 UniFFI 默认的 JNA，官方 JNI 绑定正式发布后再评估。Rust 侧保持 `panic = "unwind"`，panic 不能越过 FFI 结束系统蓝牙进程。
  - **切换门槛**：Android 切换前，在小米 14 上由系统模块把 `libopenrose_ffi.so` 与 `libjnidispatch.so` 加载进 `com.android.bluetooth`，完成一次 i5 连接与读数，且 SELinux 与 linker 没有拒绝。失败时控制会话移到应用进程，模块只转发 GATT／SPP 原语，并重开 ADR 0011。**已于 2026-11-27 实测通过（#167，见 [实测结果](../testing/android-rust-core-system-module.md#实测结果)）**：上游 JNA 5.19.1 的 `arm64-v8a/libjnidispatch.so` 因 `libffi` 静态 trampoline 写入保留寄存器 `x18` 触发 Shadow Call Stack 崩溃，构建期已通过 `BuildPatchedJnaArm64Task`（`build-logic/src/main/kotlin/PatchedJna.kt`）编译无 `x18` 引用的 `arm64-v8a/libjnidispatch.so` 覆盖打包，绕路登记为 #179。
  - **系统入口进程也加载核心**：`BridgeBundles.hello` 带上 `coreWireFormatVersion()`，所以 `com.android.bluetooth`、`com.xiaomi.bluetooth` 与 `com.milink.service` 里的 `AuthenticatedBridgeClient` 都会经 JNA 加载 `libopenrose_ffi.so`；#167 的门槛只在 `com.android.bluetooth` 实测过，另外两个进程的加载留给 #171 实测，未验证。加载失败抛出的 `LinkageError`（`UnsatisfiedLinkError`、`NoClassDefFoundError`）由 `binderReply` 当作一次桥接失败处理：记录状态、清空快照、按轮询间隔重试，不能让异常越过轮询线程结束这两个系统进程（`AuthenticatedBridgeClientPollTest.aNativeCoreThatFailsToLoadIsABridgeFailureAndTheClientKeepsPolling`）。
- **Android 驱动适配层的约定**（`:platform:android:ffi`、`:platform:android:system-module`）：
  - **包边界**：`io.github.dohex.openrose.ffi.drivers` 把 Android 的 Kotlin 驱动适配成核心的 trait（`ControlTransportAdapter`、`GattDriverAdapter`、`ScanDriverAdapter`、`SppDriverAdapter`、`AudioFactsDriverAdapter`，驱动集合接口为 `PlatformDrivers`），Kotlin 核心删除后仍保留；`io.github.dohex.openrose.ffi.kotlincore` 是 `ControlState` 投影（`CoreControlRuntime`、`ControlStateProjection`、`CoreInputs` 等），随 #172 删除。投影包依赖驱动包与 `EnumNames`，反向不依赖。
  - **枚举名称契约**：Kotlin 与 Rust 的枚举一律经 `EnumNames` 按名称（忽略 `_`）一一对应，包内不手写 `when`：`GattFailureKind`、`GattSubscription`、`AudioProfile`、`AudioConnectionState`、`ModelProfile`、`DeviceField`、`DeviceSetting`、`ControlErrorCode` 等。任一侧改名、增删枚举项，`EnumNames.of` 在映射该值时抛 `NoSuchElementException`；驱动方向的全部枚举项由 `ControlTransportAdapterTest.everyPlatformEnumEntryCrossesTheBoundaryUnderItsOwnName` 覆盖。
  - **`REGISTRATION_TIMEOUT_MILLIS` 与 `runBlocking`**：UniFFI 的 `AudioFactsDriver.subscribe` 是同步的外部 trait 方法（异步工厂方法触发 rustc HRTB 缺陷），而核心要求它返回时接收器已经注册。`AudioFactsDriverAdapter.subscribe` 因此在 `runBlocking` 内最多等 `REGISTRATION_TIMEOUT_MILLIS`（5 秒）驱动发出 `RawAudioEvent.Ready`；超时不报错，订阅照常继续，只是不再保证先注册后返回。
  - **`ENDS_BEFORE_CLOSE`**：`CoreControlRuntime` 要等两件事各发生一次才关闭 `RustControlRuntime` 与传输：所有者作用域结束，以及 `release()` 成功释放连接，所以 `ClosingBarrier.ENDS_BEFORE_CLOSE = 2`；两件事谁先谁后都可以，`release()` 只计一次。
  - **驱动的等待调用不必可取消**：外部实现的 `AudioEvents.next_event`、`GattDriver.next_event`、`SppDriver.read` 与 `ScanDriver.next_advertisement` 被 Rust 丢弃时，Kotlin 一侧的协程不会随之取消，它仍会取走下一个事件，结果被丢掉。核心因此在订阅或连接存续期间不丢弃已发出的等待：`Core::track_audio_facts` 为每次订阅只保留一个进行中的 `next_event`，取到结果后才发出下一个；GATT、SPP 与扫描的读取循环只在连接或扫描结束时放弃等待。测试用的 `ScriptedAudio`（`crates/control/tests/support/scripted.rs`）按同样的方式实现：被丢弃的等待仍按先后顺序取走事件。
  - **集成测试中等核心时不挂起**：`OwnerRig` 的所有者代码跑在 `runTest` 的虚拟时间上，Rust 核心跑在真实时间的线程上。测试协程挂起等核心时，`runTest` 见调度器空闲，会把虚拟时间直接跳到下一个计时器，例如连接提示 5 秒后的过期（`SystemEntry.expireLater`），核心一返回提示已被清掉。`RustAppHeadset.CoreAwaitedInRealTime` 因此把 `ControlRuntime` 的每个挂起方法改为在调用方协程下启动，再用 `eventually` 阻塞线程等结果：等待期间只执行当前时刻到期的任务，虚拟时间不动；调用方被取消时这次调用随之取消。
  - **`@Suppress` 的理由**：
    - `TooGenericExceptionCaught`：`pumpInto`（`FlowPump.kt`，GATT、扫描、SPP 与音频事实四个驱动共用）把平台 Flow 中抛出的任何 `Exception` 变成通道关闭或断开事件，`CancellationException` 原样重抛，驱动协程的异常因此不会越过 FFI。
    - `CyclomaticComplexMethod`：`HeadsetCommand.toRust`（`CoreInputs.kt`）为每个 Kotlin `HeadsetCommand` 子类型写一个分支。

    `CoreInputs` 与 `ControlStateProjection` 随 #172 删除，`CyclomaticComplexMethod` 一条随之失效；`pumpInto` 的理由不受影响。
- **macOS 设备身份**：CoreBluetooth 只提供本机 UUID，不提供地址。不使用私有接口 `retrieveAddressForPeripheral:`，改用广播前缀与名称规则（[ADR 0032](0032-roselink-firmware-advertisement-and-name-rules.md)）把 BLE 外设对应到已配对的音频设备；协议没有可读出的唯一标识。同一台 Mac 配对了多副同型号同名的耳机时，连接前无法区分：优先有系统音频连接的一副；仍不能确定时由用户选择，不猜。
- **桌面界面**：使用 NSH 混合模式。界面用 TSX 写，Rust nshm 适配层把核心状态转成 signal；核心 crate 不依赖 NSH。
  - 桌面端用自己固定的一套设计，不提供界面风格，明暗跟随系统。
  - 主窗口按桌面重新设计信息架构，功能覆盖与 Android 一致。
  - 保留托盘面板、托盘菜单、后台控制、单实例与登录时启动。
  - **采用前提**：一次性原型须通过以下验证：
    - niri 原生 Wayland：主窗口、托盘图标与菜单、托盘面板、关闭窗口后驻留、单实例与退出。
    - macOS x64：菜单栏图标与面板。
    - Windows：托盘与单实例。

    niri 上任一项卡死或无法退出，就不采用 NSH，重新选择界面方案。

    **2026-11-27 结论**：niri 原生 Wayland 各项已通过（[#144 记录](https://github.com/DOHEX/OpenRose/issues/144#issuecomment-7215960176)），采用 NSH，Linux 桌面端从 #152 起开工。macOS x64、Windows 两项与这两个平台的音频连接观察探针移到 #160、#161 开工前验收；某一平台不通过时，只为该平台另选界面方案，Linux 桌面端不受影响。Linux 托盘面板不用原生 `closeOnBlur`：`focus-follows-mouse` 下指针从状态栏移向面板时会掠过底层窗口，面板须等指针首次移入后才响应失焦（原型 `db3f73f`）。
- **打包**：
  - Linux：NSH 产出 AppImage，CI 再用 nfpm 打 deb、rpm 与 pacman 包。
  - Windows：Inno Setup 安装程序。
  - macOS：dmg，暂不签名与公证。Info.plist 声明 `NSBluetoothAlwaysUsageDescription`。

  NSH 要求 Linux 有 GTK 4.14 以上、macOS 14 以上、Windows 10 1809 以上，各平台的安装包须在对应系统上构建。
- **仓库结构**：仍是同一个仓库，顶层按角色划分，取代 `shared/`、`platform/`、`tools/`：
  - 根目录只放 Cargo workspace，成员为 `crates/*` 与 `xtask/`，排除 `desktop/ext/*`（NSH 的 `ext/` crate 自带空 `[workspace]`，经路径依赖引用 `crates/*`）。
  - crate 依赖单向：`headset` ← `control` ← `circulation` ← `ffi`／`desktop-platform` ← `desktop/ext/openrose-native`。目录用短名，包名加 `openrose-` 前缀。
    - `crates/headset`：型号配置、型号能力推导、协议帧与编解码、按型号限制的设置校验、模拟耳机。不依赖 tokio，sans-IO 由编译器保证。
    - `crates/control`：控制入口、控制会话、连接目标仲裁、耳机发现、控制连接与 GATT 层、控制传输选择、设置队列编排（节流、等待读回）、音频连接推断、当前耳机、驱动 trait、状态的 serde 序列化与控制设置。
    - `crates/circulation`：流转契约与局域网实现，包括流转配对、对端身份与信任、加密、线上协议与耳机端点；对端发现与本机音频的让出、接入只定义 trait。不再拆出局域网 crate：Kotlin 拆分只因 bcprov、jmdns 限于 JVM。
    - `crates/desktop-platform`：见“桌面平台层”。平台依赖写在 `[target.'cfg(...)'.dependencies]`，任何主机都能 `cargo check --workspace`。名称带 `desktop`，因为 Android 的平台实现在 `android/`。
    - `crates/ffi`：UniFFI 门面、回调接口与 Android 唯一的 cdylib（`libopenrose_ffi.so`）。桌面端经 nshm 直接调用 Rust，不经过它。
    - `crates/ffi-simulator`：把 `headset::simulator` 导出给 Kotlin JVM 测试的 UniFFI 门面，只依赖 `headset`，命名空间为 `openrose_ffi_simulator`，生成的 Kotlin 绑定只编入 `:platform:android:ffi` 的 `jvmMain`。`crates/ffi` 在 `simulator` feature 下链接它：`BuildHostRustFfiTask` 开启该 feature，Android 的 cargo-ndk 构建不开启，所以 `libopenrose_ffi.so` 不导出模拟耳机。它不能放进 `openrose_ffi` 命名空间：Kotlin 绑定在加载时对每个导出符号做 `uniffiCheckApiChecksums`，含模拟耳机的绑定遇到缺少这些符号的 Android 库会加载失败。
  - 核心类型经可选的 `uniffi` feature 派生 FFI 类型，核心本身不依赖 UniFFI；不为每个类型写镜像。生成的 Kotlin 绑定不进版本库。
    - **例外：平台驱动的错误**：`TransportError`（`crates/control/src/transport.rs`）、`GattError`、`SppError` 不派生 `uniffi::Error`；`crates/ffi/src/drivers.rs` 的 `DriverError` 是唯一的 FFI 错误类型，承载 `ControlTransport`、`ScanDriver`、`AudioFactsDriver`、`GattDriver`、`SppDriver` 的 Kotlin 实现抛出的错误，并经 `From<DriverError>` 转成这三个核心类型。以 UniFFI 0.32.2 的 Kotlin 绑定实测，派生在核心类型上有两处失败：
      - 在 `TransportError` 上派生 `uniffi::Error` 后，`uniffi.openrose_ffi` 的外部 trait 错误降级处 `{ e: TransportException -> FfiConverterTypeTransportError.lower(e) }` 无法编译：`Return type mismatch: expected 'uniffi.openrose_ffi.RustBuffer.ByValue', actual 'uniffi.openrose_control.RustBuffer.ByValue'`（`openrose_ffi.kt` 中 `ControlTransport.open_gatt`、`start_scan`、`open_spp`，`ScanDriver.next_advertisement`、`AudioFactsDriver.bonded_devices` 五处）。上游原因：`uniffi_bindgen` 的 `CallbackInterfaceImpl.kt` 模板对抛出的错误直接调用定义该错误的 crate 的 `FfiConverter.lower`，其返回值属于那个 crate 的包内 `RustBuffer`；`ExternalTypeTemplate.kt` 只为 `lift` 方向生成换缓冲区的 `…ExternalErrorHandler`，`lower` 方向没有。
      - `TransportError::Recoverable` 的字段名 `message` 与 Kotlin `Exception.message` 冲突：`Conflicting declarations: val message: String`、`Overload resolution ambiguity`（`openrose_control.kt`）。
      `GattError`、`SppError` 是结构体，而 `uniffi::Error` 只能派生在枚举上，且 `GattError` 已作为 `uniffi::Record` 出现在 `GattEvent::Disconnected`。`DriverError` 定义在 `crates/ffi`，与回调接口同属 `openrose_ffi` 命名空间，不触发缓冲区包不一致；不含 `Recoverable`，因为可恢复失败只由 `control` 自己依据 `GattFailureKind::Transient` 产生。UniFFI 在降级处补上缓冲区转换后，改为派生在核心类型上并删除 `DriverError`。
  - `android/`：Gradle 根目录。`desktop/`：NSH 应用，`desktop/ext/openrose-native` 是组合核心与本机驱动的 nshm 适配层。
- **核心分层**：`headset` 是 sans-IO 纯逻辑；`control` 与 `circulation` 是 async，经 trait 注入 IO，运行时为 tokio，UniFFI 以 `async_runtime = "tokio"` 导出。跨 crate 使用的项须为 `pub`，这是用编译器保证分层的代价。
  - **流转协议与信任存储（#164）**：`crates/circulation` 重新设计局域网协议，不兼容旧 Kotlin 线协议：
    - **错误与术语**：对端之间只传 `RemoteError` 原因代码，不传界面显示文本，文案由界面映射；另一台已配对音源设备在界面与词汇表称“对端”（代码 `Peer` / `PeerId`）。
    - **传输与认证**：使用 `rustls`（`ring` provider）与 `rcgen` 生成的自签名 P-256 证书，对端证书 SHA-256 指纹即为 `PeerId`，在 TLS 握手期间通过自定义 `ServerCertVerifier` / `ClientCertVerifier` 直接钉扎（pin）校验，而不是握手后再校验。
    - **流转配对**：采用 SPAKE2（`spake2` crate，6 位数字配对码，120 秒有效期与 5 次尝试上限），并将双方 TLS 证书指纹编入 SPAKE2 身份与密钥确认摘要，防止中间人替换证书。QR 载荷包含配对码、响应方指纹、地址与端口；手动配对由用户从发现列表选取对端后输入配对码。响应方先把待提交授权挂起，收到发起方确认回执后才写入信任存储并消耗配对码；最后一条确认丢包不留下半配对状态。
    - **撤销**：主动解除配对时立即移除本地信任并拒绝该对端，同时尽力发送 `Forget` 通知；未配对或已撤销对端的任何请求均返回 `NotPaired`，接收方据此自动清理本地记录。
    - **静态密钥**：本机私钥/证书与已配对对端授权存放在应用数据目录中，Unix 下使用 `0600` 原子替换写入；暂不接入系统钥匙串。
    - **线协议、端口与发现**：4 字节大端长度前缀的 `serde_json` 帧，单帧上限 64 KiB，每帧携带 `version`；版本不匹配时回复 `UnsupportedVersion` 并关闭连接。桌面端监听 `21871` 端口，占用时回退临时端口并由发现层通告实际端口。桌面端经 `crates/desktop-platform/src/discovery.rs` 使用纯 Rust 的 `mdns-sd` 实现 `PeerDiscovery`（服务类型 `_openrose._tcp.local.`，TXT 记录含 `id`、`name` ≤ 60 字符与 `v` 线协议版本）；Android 发现层在 #170 前保留 Kotlin，`PeerDiscovery` 仅传递套接字地址。
- **构建入口**：`cargo xtask check` 取代 `./gradlew offlineCheck`，运行 Rust 测试、clippy、Kotlin 测试、`nsh check` 与 `nsh test`。不用 justfile，因为它的命令依赖 shell，在 Windows 上不便。
- **版本**：版本号仍只来自 `version.txt`（[ADR 0015](0015-single-version-source.md)）。`xtask/src/version.rs` 提供 `sync_workspace_version`（`cargo xtask sync-version`）从 `version.txt` 推导根 `Cargo.toml` 的 `[workspace.package].version` 与 `Cargo.lock` 中所有继承工作区版本的成员包版本，并提供 `check_workspace_version`（`cargo xtask check-version`，由 `cargo xtask check` 首位执行）在漂移时使检查失败；发布流中 `release-please-config.json` 通过 `extra-files` 更新 `Cargo.toml`，`.github/workflows/ci.yml` 的 `release-please` 任务在创建或更新发布 PR 后于该分支运行 `cargo xtask sync-version` 自动保持 `Cargo.toml` 与 `Cargo.lock` 与 `version.txt` 一致。桌面端迁入 NSH 后，`nsh.json` 版本与 Android `versionCode` 亦由 xtask 推导并取代 `build-logic` 的 `AppVersion`；安装包版本只用 `X.Y.Z`。
- **不写注释**：[ADR 0021](0021-no-code-comments.md) 扩展到 Rust 与 TSX，由 xtask 检查，取代 detekt 的 `openrose:NoComments`。Rust 的 `///` 同样禁止，只有 `#[native_module]` 导出的 API 例外，因为 `nsh build` 会把缺文档的导出报为警告。
- **迁移**：按阶段进行，Android 最后切换：
  0. 原型门槛：NSH 原型（见“桌面界面”），并验证 Windows 与 macOS 能否按设备观察音频连接。
  1. `crates/headset` 移植到 Rust，协议测试向量与读回证据测试全部通过。
  2. Linux：bluer 驱动与 NSH 桌面端，在 niri 上用 i5 验收（读到电量，切换降噪后收到耳机确认）。功能对等后删除 `desktopApp`、`shared/ui` 中只供桌面端使用的代码、`platform/linux` 与 `platform/windows`。
  3. Windows 与 macOS 驱动，各自用 i5 完成同样的验收。
  4. `crates/circulation` 移植或重新设计。
  5. Android 切换：先应用进程，再系统模块（见“切换门槛”）。
  6. 删除剩余的 Kotlin 核心与旧的构建结构。

  现有 JVM 桌面端即日起不再发布。局域网流转协议可以重新设计；Android 切到 Rust 核心之前，Android 与新桌面端之间的流转不保证可用。

## 考虑过的方案

- **保留 CMP，推动上游修复**：解决不了 JVM 的体积与常驻成本。
- **JVM 作常驻控制宿主，原生外壳经 IPC 调用**：桌面端仍然带着 JVM。
- **Kotlin/Native 核心导出 C 接口**：Android 可以不动，但导出的 C 接口是函数指针结构加 StablePointer，`suspend` 函数不能导出；`mingwX64` 只是 Tier 3，`macosX64` 已经弃用。
- **桌面端用 Rust 重写规则，Android 保留 Kotlin**：同一条规则要在两处实现。
- **btleplug 作全平台驱动**：不支持经典蓝牙、已配对设备列表与音频连接观察，与 ADR 0013 否决 Kable 的理由相同。
- **Tauri**：打包格式更全，但界面跑在系统 WebView 里，Linux 上是 WebKitGTK。
- **NSH 纯 Rust 模式**：每次改界面都要重新编译。

## 后果

本决策取代以下内容：[ADR 0002](0002-shared-kotlin-core-and-platform-adapters.md) 的 Kotlin 共享核心与共享界面；[ADR 0012](0012-shared-multiplatform-architecture.md) 的模块划分与“界面”一条；[ADR 0020](0020-client-and-platform-modules.md) 的模块布局与沙盒；ADR 0013 的平台范围与 dbus-java 蓝牙栈；ADR 0014 的托盘实现，以及“面板跟随界面风格、组件位于 `:sharedUI`”；[ADR 0026](0026-module-host-loading-and-hot-reload.md) 中为热重载设计的转发器与跨代传值；ADR 0015 中由 `build-logic` 推导版本的做法；[ADR 0027](0027-desktop-packages-and-windows-helper.md) 的全部内容。在新结构接管之前，这些 ADR 仍然描述现有代码。

UniFFI 与 NSH 都还在 0.x，升级可能带来破坏性变更。Windows 与 macOS 都没有找到公开的、按协议观察 A2DP／HFP 连接的接口，这两个平台的音频连接推断驱动须在原型中验证。
