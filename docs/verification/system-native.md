# 原生系统入口、官方提示与融合设备中心

日期：2026-10-21。对应 spec #1 的 #18–#30，记录 `d19730c` 后的系统整合。**这是实现与离线验证记录，首版尚未验收完成。** i5、小米 14 HyperOS 4 暂不可用，i7、HyperOS 3 环境也缺少验收证据；不将模拟器或上游声明当作这些组合通过。

## 生产入口与身份

`OpenRoseApplication` 仍持有唯一 `ControlSession`。四类系统消费者通过同一个显式 AIDL Service 读取它，没有新增蓝牙连接或发送队列：

| 作用域 | 入口与职责 |
| --- | --- |
| `com.android.bluetooth` 主进程 | 精确匹配 `AdapterService#onCreate():void`，发布普通状态通知、模块连接提示，并适配原生耳机 Binder |
| `com.xiaomi.bluetooth` 及其子进程 | `Application#attach(Context):void` 后取得真实包 Context；适配原生耳机 Binder，并处理官方提示 |
| `com.milink.service` 及其子进程 | `Application#attach(Context):void` 后安装来源明确的方法适配，消费状态并转交允许的降噪操作 |
| `android` / `system_server` | libxposed `onSystemServerStarting`，观察实际注册的 `statusbar` 服务，安装强提示归属保护 |

作用域和入口匹配只是接入证据，不表示对应 ROM 的功能已兼容。原生类、方法签名、Provider、资源和授权各自探测；诊断分别保留包、实际进程、组件版本、入口与功能结果。`RESOLVED` 表示定位到入口；`SUBMITTED` 表示操作提交；`CONFIRMED` 只在 ROSE 设备确认成功后报告。它们都不能证明岛实际显示。

桥接 wire / Remote Preferences 升为 v2。HMAC 覆盖目标包、实际进程、UID/PID、入口、解析结果和宿主代际；服务核对 Binder 调用身份与 PackageManager 包 UID。签名校验先于包查询，包查询不占用读取端锁，返回后再次核对凭据代际。各角色限制独立：模块提示只由蓝牙消费者领取，官方提示只由小米蓝牙消费者领取，强提示守卫只由 system_server 注册；system_server 不可提交耳机设置。

现代 libxposed API / Service 在 2026-10-21 重新核对，最新正式发布仍均为 `102.0.0`（发布于 2026-06-14），依赖锁定不变。来源为官方 [API 发布](https://github.com/libxposed/api/releases/tag/102.0.0) / [Service 发布](https://github.com/libxposed/service/releases/tag/102.0.0)。

模块生命周期独立于 Activity。当前使用前置条件是用户先在普通客户端选择并连接设备；系统消费者只读取已有就绪会话，不自动扫描、配对或建立另一个会话。关闭页面不会替换 Application 会话；进程被系统结束后，内存会话不承诺继续存在。无就绪会话时保持组件通道，呈现消费者清理自己的输出。

## 官方连接提示

`OfficialConnectionPresenter` 使用固定 OppoPods 源码的 `strong_toast_action` / `param` / `island_param` / `notifyId=headset_wear_notification` 结构。实际调用经宿主 relay 到 system_server 中的真实 `setStatus(int,String,Bundle)`；找不到签名、服务或守卫时返回不可用，不切换模式。

首选 OEM 中性 `earphone_left` / `earphone_right` 资源；缺失时使用项目自有中性耳机视频，通过真实小米蓝牙 Context 复制到其 filesDir，再验证实际 FileProvider、可读取的视频、尺寸及 SystemUI URI 授权。自有 MP4 的矢量源与重建脚本为 `tools/generate-official-media.py`；不复制上游媒体，也不使用带入耳语义的资源模拟佩戴。没有可用 Provider / 授权时不提交展示。

电量仅取同一快照的 Known 值，缺失不变成百分比。已知充电为 true 时按固定参考使用绿色，其他电量文字保持中性或低电量色；没有补造充电、佩戴或入盒字段。快充载荷字段保持参考默认，不拿耳机充电开关冒充快充状态。

参考系统 hide 动作没有设备 ID，不能直接用于安全撤回。新增归属守卫在真正转发显示时记录签名 ticket、宿主代际、设备、会话、提示 ID 与载荷摘要，并串行处理强提示变更。任何外部强提示都会使旧归属失效；只有仍拥有同一 ticket 的撤回才能转发 hide。旧会话、旧 ticket、已被其他提示覆盖或守卫已撤销时拒绝，不调用全局 hide。私有签名字段转发给 SystemUI 前移除。

官方提示由核心原子领取，普通电量刷新不重放。定时器、模式切换、断开及热重载只处理自己的 ticket；资源按唯一 URI / 文件登记，正常撤回保留有限读取宽限，终止时撤销授权、删除文件并取消延迟任务，避免 reader 退出后遗留清理任务。

## 原生耳机与 MiLink 状态、动作

固定参考仍为 OppoPods `c201e251e68b59d46b6bc2758146b2c7f13ca2a1`。MiLink 额外核对同一提交的 `MiLinkServiceHook.kt`，Git blob 为 `76c45d34eed794cc14a706e7714a785a94e7e9b9`；来源为 [固定源码](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/MiLinkServiceHook.kt)。

原生适配只匹配当前受校验快照里的真实蓝牙地址。i7 广播独立识别出的 BR/EDR 地址随公开会话快照传递，不拿 BLE 地址假充传统蓝牙地址，也不按零售名称认定设备。快照有期限，回调带设备 / 会话归属，排队后再次核对；已识别地址在本次模块代际保留到卸载 Hook，避免期限或容量驱逐重新放行旧动作；其他耳机继续原链。

`NativeHeadsetBinderAdapter` 根据实际方法 / Binder 签名适配注册与状态回调。`MiLinkNativeHeadsetAdapter` 按固定来源定位 `MxBluetoothManager`、`MxBluetoothService`、`ProfileContext`、`AncBatteryController` 与 `HeadsetInfo` 方法，真实地址匹配后提供可确认状态。普通降噪 / ANC / 通透分别映射到来源中的 0 / 1 / 2，额外的 ROSE 模式不强映射成另一种模式。

动作提交携带原设备 / 会话，经相同桥接和公开 `set(reference,...)` 入队；适配器返回的本地接受不更新已确认值。真实回包 / 重读确认后，后续快照及回调才更新。通知、原生控制、融合入口不依赖岛或焦点通知设置。

以下仍是**部分适配和外部证据缺口**，不能称完整融合卡片已实现或 OS3/4 已兼容：

- 固定参考的能力 mask 没有逐位语义，未复制完整 OPPO 能力，也未将兼容 device ID 等同于 ROSE 型号能力。目标组件的能力协商仍须核实。
- MiLink 六元素电量列表要求盒充电状态，但 ROSE 当前没有该可信字段，也没有目标组件对未知充电的值约定。不会用 0 或猜测的 -1 伪装；该列表暂不替换，已有独立可信耳电量查询仍可映射。完整卡片的未知值语义待真实组件证据。
- 名为 `getBatteryLevel(BluetoothDevice):Int` 的来源返回常数 1，但没有可证明的数值语义，不照搬成 1% 或“支持电量”。
- 原生 ANC 编码的普通 / ANC 区分证据不充分，不填假值；MiLink 的明确 0 / 1 / 2 映射与其分开记录。
- OS3 / OS4 的目标 APK、实际类与资源版本尚无样本。动态找到同名方法不等于目标组件验证通过。实际卡片、选项和 SystemUI 呈现仍须在对应版本验收。

## 本次验证

最终完整检查为 `check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable`，124 个任务通过；JVM 与 Android host JVM 各 173 项测试，失败 / 错误 / 跳过均为 0。日志 `.scratch/native-full-check-final.log`；host JVM 测试不是手机测试。公开会话断言还验证 i7 在 SPP 与 BLE 回退路径保留独立识别的传统蓝牙地址。

三套独立签名的 API 26 探针仅在任务模拟器 `emulator-5560` / ADB 5038 执行：

| 探针 | 实际结果 | 能证明的范围 |
| --- | --- | --- |
| 公开会话 → 生产 bridge codec → 真实 Parcel 字节往返 → 原生消费者 | i5 / i7 共 50 断言通过，fixture 跳过 0 项；旧 wire=1 parser 副本对两个 wire=2 快照均按预期失败，当前生产 parser 通过 | 受控传输及参考 RX 下，公开会话、地址、能力、Known / Unknown / NotProvided、旧引用隔离与 Android Bundle 契约连通。未使用真实耳机或 OEM 组件 |
| 生产通知 renderer + 真实 NotificationManager，普通 UID 10066 | 39 项通过、0 失败、2 项跳过 | 六组合、字段 / 模板、稳定 tag、去重、旧定时器、断开及 MODULE→OFFICIAL 清理；蓝牙 renderer 不领取官方提示。缺少 Bluetooth privileged 权限，PendingIntent 正向执行和接收器广播验证未覆盖 |
| 独立 UID 10067 → 实际导出 Provider / AIDL Service | Provider 注入及 14 次 AIDL 请求共 15 次拒绝 | 无框架凭据时拒绝普通应用；没有覆盖有效 HMAC 下的分支、合法角色、正向读取、系统守卫或设备控制 |

证据分别为 `.scratch/native-probe/asserted-result.txt`、`.scratch/presentation-probe/native-asserted-result.txt`、`.scratch/bridge-probe/native-asserted-result.txt`。运行 fixture 时没有增加产品测试身份、借用目标进程、授予特权或制造正向框架认证。三个临时探针随后均已卸载。

公开会话 / Parcel 探针的可重建绿版源码与入口保存于 [tools/android-native-probe](../../tools/android-native-probe/README.md)。新增入口已完成本机构建与签名验证；它的输出没有再次在模拟器运行，50 项运行结果属于上述原始 scratch fixture。构建只输出独立 APK，不自动连接或选择设备，手工运行必须显式给出 ADB 端口和设备序号。

最终 Android 调试包为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，SHA-256 `b62a3beff3386bc83972a6c620ade491de45f3c77782eacc01897f6ce593ec50`。Linux 发行目录为 `desktopApp/build/compose/binaries/main/app`；生成成功不代表桌面蓝牙或 Windows 运行通过。最终 APK 安装、启动及清理后的页面记录位于 `.scratch/emulator/native-final.xml` / `native-final.png`，崩溃缓冲区记录为 `logs/native-final-crash.txt`。真实 HyperOS / Xposed / 蓝牙和 SystemUI 验收仍未执行。

## Standards

独立 Luna Max 复审的 6 个可执行问题均已修复：Host 锁内包查询、过期动作落入 OEM、切换官方模式未清旧模块提示、退出时丢失资源清理、MiLink 延迟回调缺少会话复核，以及归属记录过期 / 驱逐重新放行动作。最终复核确认 0 个待修硬规则问题。重复反射查找 / 诊断转换保留为低优先级判断，没有为此扩展整合范围。

## Spec

独立 Luna Max 审查的 wire 版本不一致与已知充电状态丢失已修复。MiLink 六元素电量列表经复核归为目标组件未知值语义缺口，不能靠填假值修复；原生能力和 OS3/4 目标样本、实际卡片 / 系统展示仍未验证，明确保留部分适配。README 与逐票矩阵已同步，不沿用旧基线的“完全无实现”描述，也不宣称全部兼容。

审查合计：Standards 6 项可执行发现全部修复；Spec 2 项代码问题已修复，1 项目标组件语义缺口仍阻塞完整融合适配。

## 完成条件

完整 spec 仍需目标系统组件样本来补齐能力与未知值映射，并完成 i5 + 小米 14 OS4、i7 + OS4、OS3 上 i5 / i7、i7 真实 BLE / 专用 SPP 等验收。用户已明确设备暂不可用，继续离线工作而不重复索取设备；#1 及相关实测票保持未完成。逐票状态见 [spec 验收矩阵](spec-acceptance.md)。
