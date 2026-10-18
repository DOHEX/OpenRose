---
status: accepted
---

# 超级岛呈现策略与独立焦点通知

OpenRose 首版在 HyperOS 3、4 提供与 OppoPods 类似的超级岛配置和焦点通知开关，并保留融合设备中心集成。官方与模块两种模式均由 SystemUI 呈现，不创建自绘浮层；“官方／模块”表示呈现策略，不是“纯原生接管／主动构造”的技术路线单选。

## 配置与默认值

| 配置 | 选项 | 初始默认值 |
|---|---|---|
| 超级岛 | 不显示／官方／模块，互斥选择 | 官方 |
| 焦点通知 | 开启／关闭，控制耳机状态通知 | 开启 |
| 超级岛显示时机 | 模块模式下的多选项，按型号事件能力开放 | 连接时 |

显示时机可扩展为连接、佩戴、摘下、入盒，但只有取得相应型号协议与状态证据后才开放对应选项。i5 的佩戴检测功能开关不能作为佩戴、摘下或入盒事件；显式清空显示时机表示不自动触发，不能自动恢复默认选择。

焦点通知开关只控制耳机状态通知，不决定系统桥接与超级岛处理器是否启用。超级岛选择“不显示”时，停止 OpenRose 管理设备的岛展示，包括连接提示、模块岛及状态通知中可能触发上岛的数据；状态通知、蓝牙控制和融合设备中心仍按各自设置工作。目标系统上不触发上岛的通知呈现方式需独立验证。

## 已确认的默认行为

- **连接提示**：在超级岛模式允许显示时，新会话取得必要的可信状态后短暂显示连接提示。按设备、会话和事件去重，普通电量变化不重复触发；断开时撤回仍在显示的提示。
- **耳机状态通知**：焦点通知开启时，连接期间保留电量及能力允许的控制入口，按设备更新同一条通知，断开后撤销；关闭该设置时撤销已有状态通知。通知存在不等同于超级岛摘要态始终可见，摘要态的持续展示仍需实机验证。
- 通知操作回到同一权威控制会话，由对应型号协议执行，并以设备回包或重读确认后的状态更新。融合设备中心是并行的状态消费者与命令入口；界面风格切换不影响通知与控制会话的生命周期。

## 系统接入与呈现策略

### 共用的系统耳机接入

使 ROSE 设备通过系统认可的型号与能力识别入口，向系统耳机业务提供真实状态，并将系统控制操作桥接回 ROSE 控制器。Hook 系统输入、状态回调或最终 Bundle 来修正数据；这部分与显示模式分开，不能因关闭某一类通知而停止真实设备控制或融合设备中心适配。

需要验证识别、状态回调、通知触发与资源选择的完整链路。不能仅凭上游存在相关 Hook 就认定无需主动补发也能完成全部目标。

### 官方与模块两种模式

- **官方模式**：沿用系统耳机呈现方式，结合原生通知输入修补与必要的主动触发。参考 `showOfficialConnectToast()` 构造 `param`、`island_param` 和 `notifyId=headset_wear_notification`，通过 `STATUS_BAR_SERVICE.setStatus(1, "strong_toast_action", bundle)` 显示约 5 秒的提示；不能将此模式描述为完全不主动构造数据。
- **模块模式**：参考 `FocusIslandUtil.showBatteryIsland()`，由 OpenRose 定义内容与触发时机，构造焦点通知模板交给 SystemUI 呈现；它不是模块自绘悬浮窗。
- **耳机状态通知**：单独参考 `MiBluetoothToastHook.createPodsNotification()` 的发布、更新与撤销机制，根据焦点通知开关与超级岛模式组合决定输出，避免关闭超级岛后仍从状态通知意外触发岛展示。

系统进程中的调用与普通应用公开通知路径不同，不能只填写系统包名就获得该进程的身份或权限。切换模式时清理旧策略自有展示和定时器，按新策略处理后续事件；不重连耳机、不重发控制命令，也不无条件重播连接事件。

## 共同约束与验证边界

- 两种模式都消费同一权威控制会话提供的状态；跨进程桥接需校验调用者和会话。i5 盒电量 `FF` 不转换为百分比，佩戴检测功能开关不作为实时佩戴事件，也不以连接状态假造入耳状态。
- 所有展示、撤销、定时器与去重按设备和会话隔离，仅作用于 OpenRose 管理的设备；不能全局拦截其他耳机的提示，也不能用过期回调撤销新会话的展示。某一模式不可用时明确记录，不静默切换到另一模式。
- OppoPods 快照 `c201e251e68b59d46b6bc2758146b2c7f13ca2a1` 同时包含这两类路径。其 `BluetoothUpstreamHeadsetHook` 处理识别、能力、状态回调及原生提示 Bundle；主动构造路径则在其他通知工具与 Hook 中。不能将参考项目笼统描述为纯粹的任一种实现。
- 上游设置中的“超级岛”是“不显示／官方／模块”三选一，默认“官方”；另有默认开启的“焦点通知”开关。官方模式仍调用 `showOfficialConnectToast()` 主动构造并发送数据，模块模式调用 `FocusIslandUtil.showBatteryIsland()`，因此这组模式不等价于“纯原生接管／主动构造”的技术路线单选。两项设置在界面上分开呈现，但关闭焦点通知会影响通知 Hook 的安装，不能假定运行时完全互不影响。
- 参考项目的“模块超级岛”分支也使用通知扩展并由 SystemUI 渲染，不是自绘悬浮窗。它的命名不能用来判断通知是否由系统原生耳机业务生成。
- 当前尚未为 OpenRose 实现或实测上述调用。首验环境为 i5、小米 14（HyperOS 4）；需验证识别、真实状态、岛展示、通知更新、控制闭环和断开撤销。i7 与 HyperOS 3 的结果另行记录。
- 系统类与方法、资源、Provider 和 URI 授权需按目标组件版本验证，不将上游的混淆类名和资源名视为稳定接口。

## 来源

- [OppoPods 原生耳机识别、状态回调与通知修补](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/BluetoothUpstreamHeadsetHook.kt)
- [OppoPods 主动构造连接提示与隐藏](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt)
- [OppoPods 主动构造耳机通知、更新与撤销](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/hook/MiBluetoothToastHook.kt)
- [OppoPods 模块超级岛模板](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/utils/FocusIslandUtil.kt)
- [OppoPods 通知与超级岛设置](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/ui/pages/SettingsPage.kt)
- [OppoPods 配置默认值与模式定义](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/app/src/main/java/moe/chenxy/oppopods/config/ConfigManager.kt)
- [小米面向普通应用的超级岛开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
