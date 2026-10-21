# 系统会话桥接：实现与验证边界

本页保留初始桥接阶段的历史证据。2026-10-21 的多目标桥接、官方守卫、原生及 MiLink 入口见 [当前整合记录](system-native.md)。

日期：2026-10-20，对应 [#18](https://github.com/DOHEX/OpenRose/issues/18)，审查基线 `0fc0522`。当前没有兼容框架及 HyperOS 真机，不能声明模块已经加载、目标进程读取成功或首版系统集成已验收。

## 版本与初始目标

实施时核对的现代 libxposed API / Service 正式版本均为 `102.0.0`，分别使用 `compileOnly` / `implementation` 锁定；模块元数据的初始框架 API 为 102，不混入旧版 Xposed 入口。官方 API 102 的最低 Android 版本为 26，与普通客户端现有下限一致。

依据为官方 [API 102 源码](https://github.com/libxposed/api/tree/102.0.0)、[Service 102 源码](https://github.com/libxposed/service/tree/102.0.0) 和 [模块生命周期定义](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java)。Service `getRunningTargets()` 的 PID / UID / 进程名是诊断数据，不作为授权凭据。

初始静态作用域仅为 `com.android.bluetooth`，模块还要求框架报告的主进程名相同。精确解析 `com.android.bluetooth.btservice.AdapterService#onCreate():void` 后才安装读取入口。它仅是蓝牙进程的初始接入点，不代表 HyperOS 3 / 4 的 SystemUI、岛或融合设备中心兼容性已得到证明。后续展示和控制入口由相应实施票推进。

## 唯一宿主与读取契约

`OpenRoseApplication` 继续创建唯一 `ControlSession`，显式绑定的 `ControlBridgeService` 位于同一应用进程。模块不创建会话、传输、发送队列或蓝牙连接；Activity 停止只结束前台发现，不替换控制宿主。宿主进程被系统结束后，不承诺内存会话继续存在，新宿主会使用新的代际。

公开 `ControlSession.readSession(SessionReference)` 只捕获一次状态，要求连接、设备快照及引用的设备 / 会话一致；不就绪、错误设备和旧会话返回明确失败。返回独立集合快照，保留 Loading / Unknown / NotProvided / Known 及真实型号能力，不将 i5 盒电量 `ff` 转为百分比，也不产生佩戴等新事件。

此处 #18 验证的初始 AIDL 包含认证、取得当前引用、读取、确认收到和关闭读取端。后续展示阶段增加受同样认证约束的变更唤醒、提示领取、降噪操作和展示诊断，见 [系统展示验证](system-presentation.md)；仍不提供第二条连接或发送队列。设备状态以带版本的 primitive / Bundle 契约传递，保留字段种类、能力范围、字段错误及原始值。快照带随机 `host_epoch`、设备 / 会话、递增 `read_sequence` 与采集时间；采集时间是宿主生成快照的时间，不是每个字段最后一次设备回包的时间。

## 身份、代际和撤销

[Service 102 官方 Provider 的 `call()`](https://github.com/libxposed/service/blob/102.0.0/service/src/main/java/io/github/libxposed/service/XposedProvider.java) 没有验证发送者；本应用替换其外部入口，只接受 OS 认定的 root / system 分发者，再委托原库初始化和接收框架 Binder。普通 UID 不能伪造框架服务来取得随机凭据。该限制是当前框架兼容条件，其他 UID 分发方式不会静默放行。

宿主通过该框架 Service 通道，将每次框架服务绑定生成的随机凭据发布到模块私有 Remote Preferences。目标代码经 API 只读取得凭据；它只在框架生命周期给出的目标包 / 进程与真实入口检查一致后生成 HMAC。签名覆盖协议版本、宿主代际、目标包 / 进程、OS UID / PID、入口及解析结果。

宿主将证明中的 UID / PID 与每次 Binder 调用的 OS 身份比对，并校验已安装目标包 UID、签名和宿主代际。共享 UID 中某个包的名字、自报进程名或 `HookedTarget` 的诊断 PID 都不能单独授权。此信任链将 root / system 分发身份、现代框架隔离的模块凭据与 OpenRose 注入代码视为受信任部分，不承诺对抗已取得 root / system 身份或能任意修改框架 / 目标进程代码的环境。

成功握手返回绑定调用者的短期读取凭据；每次读取和关闭都再次检查 UID / PID。框架断开、凭据轮换、读取端死亡或解绑会撤销读取凭据，不能作用于之后的新读取端。读取还要求当前宿主代际、设备和会话一致；旧宿主、旧会话或旧读取序号不能确认新快照。

宿主生成回复与目标实际收到回复分别记录。目标检查快照身份后回传读取序号，宿主确认该序号仍属于当前设备会话，才显示读取确认。这个确认表示 IPC 快照收发，不表示系统岛或通知已经呈现。

## 生命周期与诊断

Provider 和桥接 Service 支持 Direct Boot，可能在解锁前初始化 Application。普通偏好在解锁前不读取，解锁后加载到原有会话；框架凭据在解锁后发布。目标进程可先保持绑定等待凭据，避免错过解锁前唯一一次蓝牙服务启动回调。模块工作线程、Hook、偏好监听和绑定独立于 Activity，热重载需要先完成旧代清理再交接框架 / 目标类加载器拥有的 Context。

应用诊断分别显示框架版本、已校验目标、组件版本、匹配依据、IPC 与会话读取结果。类缺失、方法缺失或 Hook 失败的受认证报告不发放读取凭据。若诊断入口本身也不能安装，只记录 Xposed 日志，应用保持未确认状态，不凭一个固定标志报告成功。目标凭据会周期性重新读取，不能假定 Remote Preferences 的变化监听在所有框架实现中都可靠。

## 当前验证

公开会话新增四项定向测试，在 API 尚不存在时先失败，实施后通过：两型号读取、错误设备 / 旧会话、断开后手工新会话、在途设置切换风格期间保持确认状态且没有额外 TX，以及修改返回集合不影响权威会话。

`--offline check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable` 完整通过，共 124 项任务；JVM 和 Android host JVM 各 167 项测试，零失败 / 错误。日志为 `.scratch/bridge-full-check-final.log`，Android host JVM 不是手机端测试。APK 包含现代 `META-INF/xposed` 入口 / API 102 声明 / 静态作用域；合并清单的桥接 Service、受保护 Provider 与 AndroidX Provider 均未新增独立进程。

最终调试 APK SHA-256 为 `d4215a263b209280e4c6df853ae758c5f795cc8115254a3f908a685f0749bb86`。API 26 专用模拟器安装、启动成功，崩溃日志为空，界面如实显示没有 BLE 硬件。独立签名测试应用的真实 UID 为 10066，实际跨进程调用 Provider 注入、空 / 伪造握手、当前引用、读取、确认和关闭共七项均被拒绝；最终证据为 `.scratch/bridge-probe/final-asserted-result.txt` 和 `.scratch/emulator/logs/bridge-final-*.txt`。测试应用仅用于验证，没有在产品中加入测试身份或绕过认证的入口。

临时测试应用随后卸载，普通客户端重新启动如实显示没有框架与 BLE 硬件；清理后证据为 `.scratch/emulator/bridge-clean.xml` / `bridge-clean.png` 和 `logs/bridge-clean-*.txt`。

这里的六项 AIDL 拒绝都发生在框架凭据缺失的情况下，只证明无框架时失败关闭；Provider 拒绝直接覆盖普通 UID 的分发注入入口。它们没有验证有效框架凭据下 UID / PID / HMAC 各分支、有效 lease 跨身份盗用或正向系统读取，不能扩大结论。

## 独立复审

Standards 轴的两项发现已修复并复核通过：README 的实现状态与实际代码一致，共享 wire 头 / 状态 / 解析结果和目标入口常量由 `BridgeWire` 统一持有。Spec 轴的一项 Direct Boot 发现已修复并复核通过：桥接 Service 可在解锁前保持绑定，偏好与凭据仍等解锁后加载，避免错过一次性入口。两轴最终均无未解决的可执行发现。另由实现者核对官方源码后补上框架 Provider 的 OS 身份防护，并隔离已撤销读取端的迟到清理，未授权解绑不会发布虚假的目标诊断。

兼容框架中的正向 Binder 读取、真实模块加载 / 热重载、页面关闭后的设备状态、身份校验及 i5＋小米 14 HyperOS 4 仍待独立实机验收；i7 和 HyperOS 3 同样保留在首版验收范围。
