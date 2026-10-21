# 系统展示策略与通知：离线实施记录

本页保留 `d19730c` 阶段的历史证据；当时官方路径仍不可用。2026-10-21 新增的官方提示、归属守卫和原生 / 融合入口见 [当前整合记录](system-native.md)，不能沿用本页的旧 APK 或计数描述新提交。

本阶段继续首版 spec #1 的 #19、#20、#22、#23。**这些票尚未完成实机验收，官方连接提示路径仍为部分实现。** i5＋小米 14 / HyperOS 4 暂不可用，按用户决定继续实现和离线验证。HyperOS 3、i7、融合设备中心及桌面真实蓝牙仍需后续工作。

## 实现范围

| 路径 | 当前结果 | 尚缺证据或实现 |
| --- | --- | --- |
| 连接事件策略 | 同一公开控制会话产生规范化展示快照；可信耳电量就绪后只消费一次连接事件 | 真实设备字段与事件时间 |
| 官方连接提示 | 默认设置及去重策略已接入；目标端明确报告不可用，不回退 | OEM 视频资源、URI 授权、限定撤回和原生耳机输入适配尚未实现 / 核实 |
| 模块连接岛 | 独立临时通知附带 V3 焦点模板，由 SystemUI 解释；自有 Bitmap 图标嵌入通知 | HyperOS 接收、实际上岛、摘要持续时间 |
| 耳机状态通知 | 独立焦点开关；稳定设备 tag；真实规范化状态及能力允许的降噪入口；断开撤销 | 蓝牙目标进程发布权限、真机通知和真实协议闭环 |
| 六种模式组合 | 公开会话策略和 Android 通知边界可离线检查 | 目标 ROM 是否将普通通知自动上岛，六组合实际显示 |

持续状态通知在所有模式均使用普通 Android Notification，不附 `miui.focus.param` / `miui.focus.pics`。模块连接提示使用独立通知及 tag，所以普通电量刷新不会通过状态通知再次发送模块岛。`OFF + 焦点开启` 保留状态与控制通知，同时完全不构造焦点扩展；这不能替代对 ROM 自身上岛规则的实机检查。

## 权威状态、事件和命令

`ControlState.presentation` 和 `readSession(reference).presentation` 从与设备状态相同的一次 capture 派生。它们保持 Loading、Unknown、NotProvided、Known 的区别，包含稳定设备标识、电量、降噪、能力、当前策略及短暂连接提示。i5 / i7 原始零电量的既有协议判定仍为 Unknown，`ff` 不转换为百分比；已规范化的 Known(0) 不被展示层当成缺失。没有从检测功能开关、连接状态或电量推导佩戴 / 入盒事件。

提示在 READY 且至少一侧耳电量可信后产生，有效期默认 5 秒。设备 / 会话 / 连接事件去重；原子 `claimConnectionPrompt(reference, id)` 仅允许一个当前读取端领取。重新绑定、热重载、普通电量刷新和切入另一模式均不重播已消费事件。焦点开关只影响持续通知；模块触发显式空集合不显示自动连接提示。模式改变清理旧提示，定时器同时检查提示和会话代际。界面风格、外观及强制 BLE 偏好不触发重连或命令重放。

新增 `set(reference, setting, value)` 在与入队相同的生命周期锁内检查引用。通知降噪仍使用既有协议、串行队列及设备确认流程；旧通知不能向同地址的新连接写入。等待协议结果在独立的有界线程执行，不阻塞展示清理。已发送不被当作已确认，失败明确记录；可见值只来自后续权威快照。

## 认证与生命周期

沿用 [系统桥接](system-bridge.md) 的 UID / PID、HMAC、宿主代际和读取租约边界。AIDL 增加变更唤醒、提示领取、降噪和展示诊断；新增调用也必须通过同样认证，操作另校验设备 / 会话。变更回调只唤醒读取端，不传送另一份权威状态；宿主不持锁调用远端 Binder，保留周期检查以处理凭据变更。

`OpenRoseBluetoothModule` 仍只在已核实的 `com.android.bluetooth` 主进程入口工作。模块没有控制会话、蓝牙连接或协议队列。读取并确认快照后调用展示适配器；无会话、通道失效、模式切换和热重载清理只撤销其自有通知 / 回调，不使用全局取消。降噪操作使用不可变 PendingIntent、每会话随机动作 / token 和精确包名；迟到操作由本地身份和宿主引用双重检查。

目标端报告区分 `CALLED`、`UNAVAILABLE`、`WITHDRAWN`、`ACTION_FAILED`。`CALLED` 仅表示 Android 发布调用返回；`WITHDRAWN` 仅表示撤销调用返回。它们不代表 SystemUI 实际显示、成功上岛、摘要态持续存在或真实设备已执行命令。发布前检查实际目标 Context 的通知权限；缺少 `BLUETOOTH_PRIVILEGED` 时保留查看通知但不创建必然失效的操作按钮，并报告控制入口不可用。API 33 之前没有 `NOT_EXPORTED` 语义，操作接收器仍要求特权发送权限和随机 token。

热重载除关闭展示、监听器与租约外，还检查等待同步 Binder 结果的操作线程是否退出；最多等 1 秒，未退出则报告清理失败，不宣称热重载成功。通知发布处的 Lint 例外仅限一个经过运行时权限检查的调用边界：实际发出者是目标 Bluetooth Context / UID，普通模块 APK 的 Manifest 不能代表其权限；没有因此申请无关的客户端通知权限。

## 固定参考与兼容限制

OppoPods 固定提交为 `c201e251e68b59d46b6bc2758146b2c7f13ca2a1`。本阶段取得固定 tree，并核对保存源码的 Git blob SHA；没有把本地另一 HEAD 当作固定来源。

- [官方连接提示及隐藏](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt)：反射 `setStatus(int, String, Bundle)`，动作 `strong_toast_action`，官方提示带 `param` / `island_param`、`notifyId=headset_wear_notification`、5 秒时长。视频依赖 OEM Provider 和 raw 资源；固定 tree 未包含相应 MP4。隐藏载荷仅有包名与 hide 标志，没有设备 / 会话或 notifyId，不能据此证明只撤销 OpenRose 提示。因此当前官方分支不发出未经核实的全局展示 / 隐藏调用。
- [模块连接岛](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/utils/FocusIslandUtil.kt)：参考其系统模板路径，使用自有程序生成图标，未复制上游图片、媒体或整个库。
- [状态通知](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/MiBluetoothToastHook.kt)：固定源在焦点关闭时不安装相关处理器，且状态通知仍可能附岛字段，不能用于证明六种组合独立。本实现将处理器、连接事件与持续通知分别控制。
- [HyperNotification 发布者源码](https://github.com/xzakota/HyperNotification/tree/c704a4de3e1d069e213508363303f62b5e057526)：声明 focus-api 1.4，最低 SDK 27 / JVM 21。本项目保持普通客户端最低 API 26，没有引入该库；根据其公开字段结构独立构造 `miui.focus.param` 内的 `param_v2.param_island` 及 `miui.focus.pics`。序列化结构有源码依据，SystemUI 是否接受仍待实机验证。

## 验证记录

2026-10-20 完成以下离线验证；构建与探针日志保留于未纳入版本库的 `.scratch/`。

- 新增 6 个公开 `ControlSession` 测试，涵盖六组合、触发空集合、可信状态就绪、领取去重、期限和旧引用。扩展既有快照测试，检查 i5 / i7 规范化状态、防御性集合，以及在途降噪期间切换风格 / 岛模式 / 焦点开关仍保持同一会话和写入次数，设备确认前不改状态。
- 最终 `check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable` 全部通过，共 124 个任务；JVM、Android 宿主各 173 项测试，均无失败 / 错误。Android 宿主测试不是手机测试。最终日志 `.scratch/presentation-full-check-final.log`。
- 独立签名的 API 26 通知探针使用生产 renderer 和真实 NotificationManager，在 fixture 自己的普通 UID 下验证：38 项通过、0 失败、2 项跳过。观察六组合通知数量 / 稳定 tag、无焦点扩展的普通通知、V3 JSON / 内嵌 Icon、未知电量、提示去重、旧定时器隔离、模式切换和断开清理。最终证据 `.scratch/presentation-probe/final-asserted-result.txt`。未授予特权或借用目标进程身份，因此 PendingIntent 正向执行、其旧引用拒绝与 privileged receiver 注销的广播验证仍不能通过此 fixture 验收。
- 新增提示领取回归先红后绿：模拟提交前 RPC 异常，后续仍未领取的有效快照可重试并产生通知；模拟宿主已领取但回执丢失，后续 `claimed=true` 不重播。日志 `.scratch/presentation-probe/regression-red.log` / `regression-green.log`。服务端原子领取仍负责实际跨进程去重。
- 不同签名 / UID 的桥接探针得到 11 次 SecurityException，包含 Provider 注入、原有读取入口以及新增 watch / claim / setNoise / report 入口。证据 `.scratch/bridge-probe/presentation-asserted-result.txt`。由于框架不存在，这些拒绝在有效凭据分支之前发生，不能据此宣称有效 HMAC、正向桥接或真实 Bluetooth 控制已通过。
- 最终 APK 在任务专用 API 26 模拟器安装、启动，页面如实显示“没有 BLE 硬件 / 尚未收到兼容框架服务”，crash buffer 为空。两个临时探针均已卸载。页面证据 `.scratch/emulator/presentation-clean.xml` / `presentation-clean.png`。
- 两位独立 Luna Max 审查分别检查 Standards / Spec。修复旧通知拒绝污染当前 `lastError`、未消费的门控、重复清理及临时领取失败后永久丢失提示等问题，相关复审通过。Spec 审查明确保留 #19 官方路径 partial；本阶段没有关闭任何票，也未宣称首版完成。

最终调试 APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`，SHA-256 `d9977c9f75fd6e2161e2812ca324e353b88c2334c3b780e67337f265407cf336`。Linux 发行目录构建通过；这不代表桌面真实蓝牙或 Windows 验收通过。

## Standards

独立审查发现 3 项：旧引用拒绝污染新会话错误、门控尚无实际消费者、重复的热重载清理。前者以公开回归先红后绿修复；后两项接入真实门控并提取清理函数。复审确认三项已解决，未发现新的文档规则冲突。额外复核确认未退出的同步操作线程会使热重载清理失败。

## Spec

独立审查列出 2 项：官方路径仍只有明确不可用结果（#19 尚缺，P1），临时领取异常被永久去重而丢失提示（P2）。后者经独立 Android 探针先红后绿修复并复审；前者继续保留为明确的未完成工作。模块实际展示、真实降噪控制和 ROM 六组合仍待实机，未发现范围外功能。

审查合计：Standards 3 项均已修复；Spec 2 项中 1 项已修复，最高严重度仍为官方路径尚缺（P1）。
