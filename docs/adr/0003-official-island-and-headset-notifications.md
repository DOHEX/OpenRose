---
status: accepted
---

# 超级岛呈现策略与独立焦点通知

> 术语已更新（[GLOSSARY](../../GLOSSARY.md)）：超级岛模式的“官方／模块”改称“系统样式／OpenRose 样式”，“焦点通知”开关改称“耳机状态通知开关”；焦点通知、摘要态与展开态的定义已按小米官方文档（[业务介绍](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140)、[版本信息](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2141)）校正。下文的模板名、组件名与 `param_v2` 字段已对照《小米超级岛模板库》（2026-01-29 版）、开发指南及展开态、摘要态设计规范核实，并改用官方名称。官方文档未记载的说法逐条注明出处：参考项目、第三方库或实机观察。

OpenRose 首版在 HyperOS 3、4 提供与 OppoPods 类似的超级岛配置和焦点通知开关，并保留融合设备中心集成。官方与模块两种模式均由 SystemUI 呈现，不创建自绘浮层；“官方／模块”表示呈现策略，不是“纯原生接管／主动构造”的技术路线单选。

## 配置与默认值

| 配置 | 选项 | 初始默认值 |
|---|---|---|
| 超级岛 | 不显示／官方／模块，互斥选择 | 官方 |
| 焦点通知 | 开启／关闭，控制耳机状态通知 | 开启 |
| 常驻电量岛 | 模块模式下是否在耳机状态通知中保留电量摘要态 | 开启 |
| 显示时机 | 模块模式下的多选项：连接时、入盒时，按型号事件能力生效 | 连接时 |
| 状态通知点击目标 | OpenRose 耳机主页／系统耳机详情页／RoseLink／快捷面板，焦点通知开启时可设 | OpenRose 耳机主页 |

显示时机开放“连接时”与“入盒时”。入盒提示要求型号同时具有充电盒与可读的耳机入盒标志。当前包括 i7、BudsFeel MK2、OpenFeel MK2 和 OpenFeel 的三个 RS Common 硬件分支。i5 所属的 ZT 协议不上报盒内状态，Jasmine 没有充电盒，SWIFT 2 为单体。入盒尚未实机验证。佩戴、摘下不开放：i5、i7 的佩戴检测只是功能开关，不产生实时事件。清空显示时机会关闭所有事件提示，且保留空选择。模块模式下，焦点通知和常驻电量岛均开启时，连接期间仍可在摘要态查看电量。

焦点通知开关只控制耳机状态通知，不决定系统桥接与超级岛处理器是否启用；关闭时状态卡片及其摘要态一并立即撤销，连接提示与入盒提示不受影响。超级岛选择“不显示”时，停止 OpenRose 管理设备的岛展示，包括连接提示、入盒提示、模块岛及状态通知中的岛内容；状态通知、蓝牙控制和融合设备中心仍按各自设置工作。焦点通知不带 `param_island` 时，SystemUI 仍按 ticker 与标题自行生成摘要态；这是实机观察，开发指南把 `param_island` 列为必选，没有说明缺省时的行为。因此不上岛的状态通知携带 `param_island.dismissIsland=true`（开发指南的岛属性，含义为“摘要态消失”）；2026-11-09 在小米 14（HyperOS 4.0.0.27，SystemUI 17.03.260226）实机确认，这样卡片照常显示且不上岛，其他 ROM 另行验证。

（2026-11 补注：状态通知点击目标增加“快捷面板”，即 OpenRose 自己的底部浮层，见词汇表。原先系统耳机详情页与 RoseLink 打不开时静默改开 OpenRose，系统详情页还不检查目标页面是否存在。现在设置页把当前打不开的目标置为不可选并说明原因；通知发出时目标打不开，仍打开 OpenRose 耳机主页，并在顶部横幅说明原因。）

## 已确认的默认行为

- **连接提示**：在超级岛模式允许显示时，新会话取得必要的可信状态后短暂显示连接提示。按设备、会话和事件去重，普通电量变化不重复触发；断开时撤回仍在显示的提示。官方模式每个会话只显示一次，不因单耳或充电盒状态变化重弹，提示视频不区分是否入耳。
- **入盒提示**：模块模式选择“入盒时”且型号能上报盒内状态时，会话期间任一耳机的盒内状态由已知不在盒内变为已知在盒内即显示一次，内容与连接提示相同；连接后首次读取与未知值不算入盒。新提示替换仍在显示的提示；双耳入盒后断开连接时随断开撤回。
- **耳机状态通知**：焦点通知开启时，连接期间保留电量及能力允许的控制入口，按设备更新同一条通知，断开后撤销；关闭该设置时撤销已有状态通知。超级岛模式为模块且开启常驻电量岛时，状态通知携带常驻摘要态，模板与模块提示相同，即摘要态大岛模版“图文组件 1＋图文组件 2”（左右耳图片与各自电量）；电量更新不自动展开。2026-11-09 起不再用“耳机图块＋名称＋整行电量文字”的摘要态：它在大岛中很宽，文字被截断，信息反而少。官方模式不携带摘要态，连接提示是它唯一的岛，与小米原生耳机一致：2026-11-09 在 i5、小米 14（HyperOS 4）官方模式下，连接提示（`ShowOnceBigIsland`）与状态通知摘要态（`BigIsland`）在连接时同时上岛，据此取消官方模式的摘要态。这两个是实机观察到的 SystemUI 内部岛类型名，官方文档中没有。通知同时提供息屏显示文案（`aodTitle`）、“切换降噪”（型号能控制降噪且当前模式已确认时，按全部可选模式循环）与“断开”（断开该耳机的系统蓝牙连接）两个操作，标题使用系统蓝牙别名，只有确认充电时标注充电，锁屏完整可见；点按卡片或其展开态时打开状态通知点击目标。其他型号和 ROM 的持续展示仍待实机验证。
- 通知操作回到同一权威控制会话，由对应型号协议执行，并以设备回包或重读确认后的状态更新。融合设备中心是并行的状态消费者与命令入口；界面风格切换不影响通知与控制会话的生命周期。

## 系统接入与呈现策略

### 共用的系统耳机接入

使 ROSE 设备通过系统认可的型号与能力识别入口，向系统耳机业务提供真实状态，并将系统控制操作桥接回 ROSE 控制器。Hook 系统输入、状态回调或最终 Bundle 来修正数据；这部分与显示模式分开，不能因关闭某一类通知而停止真实设备控制或融合设备中心适配。

需要验证识别、状态回调、通知触发与资源选择的完整链路。不能仅凭上游存在相关 Hook 就认定无需主动补发也能完成全部目标。

### 官方与模块两种模式

- **官方模式**：沿用系统耳机呈现方式，结合原生通知输入修补与必要的主动触发。参考 `showOfficialConnectToast()` 构造 `param`、`island_param` 和 `notifyId=headset_wear_notification`，在小米蓝牙进程通过 `STATUS_BAR_SERVICE.setStatus(1, "strong_toast_action", bundle)` 显示约 5 秒的提示；这些是系统私有接口与字段，来自参考项目，小米公开文档未涉及。不能将此模式描述为完全不主动构造数据。发送及提前撤销直接使用该进程的系统服务，不 Hook `android`，具体取舍见 [ADR 0004](0004-official-prompts-without-system-server-hooks.md)。
- **模块模式**：参考 `FocusIslandUtil.showBatteryIsland()`，由 OpenRose 定义内容与显示时机，构造焦点通知模板交给 SystemUI 呈现；它不是模块自绘悬浮窗。岛模板与参考项目保持一致，不额外补充小岛与展开态内容：不传开发指南列为必选的 `smallIslandArea`，摘要态设计规范说明业务未上传小岛图标时，小岛取大岛 A 区左侧的图标。与参考项目不同，焦点通知和常驻电量岛均开启时，模块提示随状态通知一同展示：状态通知的摘要态本就是提示模板，提示就是状态通知的岛出现一次。连接时岛随通知首次出现；入盒时撤销并重发状态通知，重播岛的出现动画，通知栏卡片随之移除再加回。同一时刻只有一个岛。焦点通知或常驻电量岛关闭时，所选事件的提示单独发送；提示期间关闭焦点通知时，余下时长改为单独发送。2026-11-09 在 i5、小米 14（HyperOS 4）上，分开发送时提示占大岛、摘要态被压成小岛；合并后连接时只有状态通知一个岛，持续显示左右耳电量。入盒重弹尚未实机验证。
- **耳机状态通知**：参考 `MiBluetoothToastHook.createPodsNotification()` 的发布、更新与撤销机制及常驻摘要态、息屏、按钮与点击目标，在模块模式开启常驻电量岛时携带电量摘要态，常规读数更新时保持收起。不同步参考项目在“不显示”与官方模式下仍从状态通知上岛、每次电量更新都展开岛，以及焦点通知开关在 Hook 加载时连带停用全部超级岛的行为。

系统进程中的调用与普通应用公开通知路径不同，不能只填写系统包名就获得该进程的身份或权限。切换模式时清理旧策略自有展示和定时器，按新策略处理后续事件；不重连耳机、不重发控制命令，也不无条件重播连接事件。

参考项目拦截原生 `headset_wear_notification`：不显示与模块模式下吞掉，官方模式下改写电量，且不区分设备。2026-11-09 在 i5、小米 14（HyperOS 4.0.0.27，SystemUI 17.03.260226）官方模式下重连一次，小米蓝牙处理了连接却没有发出原生提示，SystemUI 只收到 OpenRose 的官方提示。因此不安装该拦截；若其他型号或 ROM 观察到原生提示，只拦截 OpenRose 管理设备的提示，无法确认归属时放行。

## 共同约束与验证边界

- 两种模式都消费同一权威控制会话提供的状态；跨进程桥接需校验调用者和会话。i5 盒电量 `FF` 不转换为百分比，佩戴检测功能开关不作为实时佩戴事件，也不以连接状态假造入耳状态。
- 展示、撤销、定时器与去重的本地状态按设备和会话隔离；不能全局拦截其他耳机的提示，也不能用过期回调撤销 OpenRose 新会话的展示。官方提示沿用参考项目的立即隐藏调用，不观察外部强提示覆盖；系统隐藏接口没有设备与会话标识，无法保证不会撤销随后覆盖它的其他提示，此边界按 [ADR 0004](0004-official-prompts-without-system-server-hooks.md) 记录并在目标 ROM 验证。某一模式不可用时明确记录，不静默切换到另一模式。
- OppoPods 快照 `c201e251e68b59d46b6bc2758146b2c7f13ca2a1` 同时包含这两类路径。其 `BluetoothUpstreamHeadsetHook` 处理识别、能力、状态回调及原生提示 Bundle；主动构造路径则在其他通知工具与 Hook 中。不能将参考项目笼统描述为纯粹的任一种实现。
- 上游设置中的“超级岛”是“不显示／官方／模块”三选一，默认“官方”；另有默认开启的“焦点通知”开关。官方模式仍调用 `showOfficialConnectToast()` 主动构造并发送数据，模块模式调用 `FocusIslandUtil.showBatteryIsland()`，因此这组模式不等价于“纯原生接管／主动构造”的技术路线单选。两项设置在界面上分开呈现，但关闭焦点通知会影响通知 Hook 的安装，不能假定运行时完全互不影响。
- 参考项目的“模块超级岛”分支也使用通知扩展并由 SystemUI 渲染，不是自绘悬浮窗。它的命名不能用来判断通知是否由系统原生耳机业务生成。
- OpenRose 已有官方提示、模块提示与耳机状态通知的调用实现，已在 i5 和小米 14（HyperOS 4）上观察到本文记录的通知与岛展示。入盒重弹、通知控制闭环和断开撤销仍需单独验收。i7 与 HyperOS 3 的结果另行记录。
- 系统类与方法、资源、Provider 和 URI 授权需按目标组件版本验证，不将上游的混淆类名和资源名视为稳定接口。

## 系统接口与呈现的实现约束（2026-11 补充）

### 融合设备中心与系统耳机 Binder 的固定接口事实

- （2026-11 补充）`MiLinkNativeHeadsetAdapter` 对 `getDeviceId` 回答固定的融合设备中心兼容标识 `NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID`（`01010607`），取自锁定版本的适配器源码。它与 ROSE 的型号配置及能力快照刻意分开。
- （2026-11 补充）-1 是锁定的 MiLink 源码中明确表示“隐藏／不支持”的状态值，`MiLinkNativeHeadsetAdapter` 用它回答不支持的状态查询。
- （2026-11 补充）在锁定的源码中，降噪设置器（`setAncStateBlock`）返回一个状态码。适配器返回最近一次已确认的模式；耳机回包后，新模式经下一次发布的快照更新。
- （2026-11 补充）`NativeHeadsetBinderAdapter.callbackClassOrNull` 加载的回调类型 `com.android.bluetooth.ble.app.IMiuiHeadsetCallback` 由 HyperOS 蓝牙宿主定义，公开 SDK 中没有对应类型。`readCallback` 用宿主厂商 AIDL 的 `Stub.asInterface`，把回调 Binder 转成与直接注册时相同的接口。

### 焦点通知模板、岛与状态通知的更新

- （2026-11 补充）`SystemPresentationRenderer` 在 `com.android.bluetooth` 宿主进程内渲染模块所有者的可信快照。状态通知是焦点通知卡片，使用 OS3 新增的展开态模版“新图文组件＋识别图形组件 1＋按钮组件 4”（模板库 PDF 序号 17，展开态设计规范模版 18）：新图文组件即 `iconTextInfo`，按钮组件 4 即 `textButton`，支持 1–2 个文字按钮。识别图形组件 1（`picInfo`）不传；官方说明它默认取桌面图标，省略时的呈现以实机为准。原先写的“新图文＋按钮组件 1”不对：按钮组件 1 是 `actions`，其中的文字按钮只支持 1 个，且不能与其他按钮同时使用。只有快照要求时（模块模式且开启常驻电量岛），卡片才携带摘要态岛；更新时从不悬浮（`enableFloat=false`，官方含义为通知更新时不自动展开为展开态），所以电量刷新不会把它重新展开。这个摘要态就是模块提示的电量模板，即摘要态大岛模版“图文组件 1＋图文组件 2”（`bigIslandArea` 中的 `imageTextInfoLeft` 与 `imageTextInfoRight`）：卡片显示期间，模块提示就是岛的一次出现，靠重发卡片重播；没有卡片时，提示是一条只为该提示发出的单独通知。JSON 另外对照了第三方库 HyperNotification 1.4 公开的 V3 模型 schema（xzakota/HyperNotification，修订 `c704a4de3e1d069e213508363303f62b5e057526`），它不是小米官方文档。无论符合官方字段表还是该 schema，都不能证明 SystemUI 会接受或真正显示。
- （2026-11 补充）`FocusNotificationTemplate.statusExtras`：焦点通知卡片包含 `iconTextInfo`（新图文组件）、至多两个 `textButton` 按钮（按钮组件 4），需要时再加电量岛。`expandOnEntrance` 写入官方属性 `islandFirstFloat`，即通知第一次出现时是否自动显示展开态：为真时卡片首次出现即进入展开态，之后合并的提示回到摘要态。按钮用 `action` 键引用 `miui.focus.actions` 中的 `Notification.Action`，即模板库所列的“方式一”。SystemUI 会把每个 JSON `actionTitle` 传给 `Html.fromHtml`，缺少标题会让 SystemUI 崩溃；这一点官方文档没有记载，出自本项目对 SystemUI 的观察。因此模板里的每个按钮都带非空标题。
- （2026-11 补充）`HeadsetStatusNotification`：不带岛数据的更新是否会移除已显示的摘要态，目前没有证据；带岛数据的更新会原地改岛，用户察觉不到。因此要移除岛或重播岛的出现时，先撤销通知再重发。

### 状态通知点击目标的系统查询

- （2026-11 补充）蓝牙应用持有 `QUERY_ALL_PACKAGES`，所以 `PresentationNotificationContent` 能在宿主进程内解析 RoseLink。
- （2026-11 补充）`PresentationNotificationContent.systemHeadsetLaunch` 打开 HyperOS 自己的耳机页，附带的 extras 与参考项目 OppoPods 为其兼容设备传入的相同。
- （2026-11 补充）`SYSTEM_HEADSET_SUPPORT_FLAGS`（`000000000000000010000000`）是 OppoPods 随兼容设备 ID 一起发送的特性标志，各位的含义未经验证。

## 来源

- [OppoPods 原生耳机识别、状态回调与通知修补](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/BluetoothUpstreamHeadsetHook.kt)
- [OppoPods 主动构造连接提示与隐藏](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt)
- [OppoPods 主动构造耳机通知、更新与撤销](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/MiBluetoothToastHook.kt)
- [OppoPods 模块超级岛模板](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/utils/FocusIslandUtil.kt)
- [OppoPods 通知与超级岛设置](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/ui/pages/SettingsPage.kt)
- [OppoPods 配置默认值与模式定义](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/config/ConfigManager.kt)
- [小米面向普通应用的超级岛开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
- [小米超级岛模板库（2026-01-29 版）](https://mag-admin.cnbj1.mi-fds.com/mag-admin/island/%E5%B0%8F%E7%B1%B3%E8%B6%85%E7%BA%A7%E5%B2%9B%E6%A8%A1%E6%9D%BF%E5%BA%9320260129.pdf)
- [小米超级岛展开态设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2142)
- [小米超级岛摘要态设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143)
- [HyperNotification V3 模型（第三方库，非小米官方）](https://github.com/xzakota/HyperNotification/tree/c704a4de3e1d069e213508363303f62b5e057526)
