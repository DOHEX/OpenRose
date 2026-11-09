# RS Common V2 协议族

RoseLink 3.8.0（versionCode 5310）中 28 个耳机配置使用 RS Common V2。本文是整理这一族任何型号时的入口：说明哪些部分全族共用、哪些按型号变化、各自的证据在哪。共用部分的细节和逐条证据仍在 [EarFeel i7 文档](../models/earfeel-i7-protocol.md) 中，本文只指向对应章节，不重复内容。

本文的结论都来自静态分析，没有任何一款 RS Common V2 耳机做过实机验证。

## 全族共用

28 个耳机配置的构造函数都实例化 `RsCommonV2Sender` 和 `RsCommonV2Receiver`（例：BudsFeel MK2 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:47`）），所以帧格式、命令编码和接收拆包属于协议族，型号只决定开放哪些控件、取哪些值。接收字段的处理由 ReceiveHandler 负责：26 个配置用 `RsCommonV2ReceiveHandler`，FIRST ANGEL 用 `FirstAngleReceiveHandler`，SpeakFeel [speakfree] 用 `SpeakfreeReceiveHandler`，这两款的字段处理需要单独核对。

| 环节 | 结论 | 细节与证据 |
|---|---|---|
| 广播识别 | manufacturer 数据先拼所有 company ID（小端）再拼 payload；按前 12 → 8 → 6 位查前缀表 | [i7 §2.1](../models/earfeel-i7-protocol.md#21-广播识别) |
| 广播字段 | `getInfoFromRawData` 的 rs_common_v2 分支：设备类型、BR/EDR 标志、地址偏移 | [i7 §2.1](../models/earfeel-i7-protocol.md#21-广播识别) |
| 控制通道选择 | `ConnectManager._shouldUseSppControl`：SPP 可用、型号允许、未强制 BLE、非 LE Audio 时走 SPP | [i7 §2.2](../models/earfeel-i7-protocol.md#22-传输选择) |
| BLE 控制特征 | `BaseDeviceConfig` 默认 `00fe` / `00f1` / `00f2`；SPP UUID 由型号的 `customConfigEarObject` 写入 | [i7 §2.3](../models/earfeel-i7-protocol.md#23-通道表和连接步骤)（表中 BES OTA 两行只属于 i7） |
| 发送帧 | `FF SS LL BODY CC AA`，`CC` 为前缀字节和的低 8 位 | [i7 §3](../models/earfeel-i7-protocol.md#3-正常发送帧) |
| 初始化 | `GlobalController` 经 GDT+0xd8ba 按配置 cid 分派；已核对的共享目标 `0x8e2444` 只调用 `getInfo` | [i7 §3.1](../models/earfeel-i7-protocol.md#31-初始化与查询)、分派补核（`rose_reverse/analysis/i7_dispatch_findings.md`） |
| 命令编码 | `RsCommonV2Sender` 各方法 | [i7 §4](../models/earfeel-i7-protocol.md#4-设置命令与参数)；机器目录 `rose_reverse/analysis/i7_sender_commands.json` |
| 接收解析 | `DD HH [L T V...] CS AA`、两种校验、分包重组、各 Tag | [i7 §5](../models/earfeel-i7-protocol.md#5-接收帧分包与字段)；机器布局 `rose_reverse/analysis/i7_receiver_layouts.json` |
| 客户端状态建议 | 写入完成与设备确认分开、会话级序号、电量缓存与新鲜度 | [i7 §6](../models/earfeel-i7-protocol.md#6-客户端状态和异常处理) |

`i7_sender_commands.json` 和 `i7_receiver_layouts.json` 虽然以 i7 命名，内容是整个族的 Sender 与 Receiver。前者用 `applicability` 区分 `i7_configured`（i7 界面实际调用）和 `shared_sender_only_not_proven_i7`（只在 Sender 中存在）。目录没有收录 Sender 的全部方法（例如 `setEqBassPlus`、`setLeftTapTwo`）；目录里查不到的方法，以 `rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart` 为准。

## 按型号变化

| 项 | 去哪里查 |
|---|---|
| 识别前缀、SPP 是否允许、控制与 OTA 的 SPP UUID、CMIIT | `rose_reverse/analysis/model_registry.json` |
| 配置类 cid、覆写的方法 | Blutter 配置类文件头部的 `// class id:` 与方法列表 |
| 初始化目标 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv`） 第 `0x1000 + 0xd8ba + cid` 项 |
| 开放的控件（型号能力） | 配置类中 `Allocate*WidgetModel` 的调用；`rose_reverse/analysis/v2_capabilities.json` 的 `capabilities` 已按控件汇总并附行号 |
| 控件取值 | `v2_capabilities.json`：`NoiseType`、`DeviceAudioType`、`DeviceEQTypeModel`、`TouchCtlType`、`IntValueModel`（触控动作）、`BatteryType` / `battery_types`、`noise_levels`（ANC / 通透档位） |
| 固件门槛 | 自定义 EQ、OTA 的门槛已收入 `v2_capabilities.json`；其他控件的门槛写在控件构造参数里，是 Dart Smi，需右移 1 位（见 `rose_reverse/analysis/v2_capabilities.md`） |

`rose_reverse/analysis/model_brief.py` 以型号名为参数，把上表各项和下面的控件对应关系汇总成一份简报，并列出索引回答不了的缺口。

## 控件与命令

控件模型类定义在 `roselink/rose_bluetooth/config/widget_model.dart`，对应界面在 `roselink/modules/device_control/widget`，全族共用，经 `RCommander` 调用当前配置的 Sender；取值由该型号的枚举决定。`model_brief.py` 读取本表：第三列只写方法名；写“结构”表示容器或展示控件，写“范围外”表示 OTA 或云端业务入口，写“—”表示尚未找到对应的 Sender 方法。

| 控件类 | 功能 | Sender 方法 | 说明 |
|---|---|---|---|
| `RDeviceComponentNoiseBarWidgetModel` | 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind` | 可选模式取 `NoiseType` |
| `RDeviceComponentOnlyNoiseBarWidgetModel` | 降噪开关 | `setNoiseModeAnc`、`setNoiseModeNormal` | 按方法名推断，i7 没有该控件 |
| `DeviceAutoNoiseAncPanelItemWidgetModel` | 自动 ANC | `setAncAutoOn` | i7 上关闭时发送 `09 04`（抗风），见 [i7 §4.5](../models/earfeel-i7-protocol.md#45-自定义曲线工作流门槛和自动模式关闭) |
| `DeviceAutoNoiseAncDynamicPanelItemWidgetModel` | 动态 ANC | `setAncAutoDynamicOn` | 同上 |
| `DeviceLimitNoiseAncPanelItemWidgetModel` | 深度 ANC | `setDeepAncOn` | 接收 `09 06` 已由界面互证 |
| `DeviceAncLinePanelItemWidgetModel` | 降噪轮换 | `setAncLine` | 线序 ANC / 抗风 / 普通 / 通透，与界面顺序不同 |
| `RDeviceTouchSettingItemWidgetModel` | 触控 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong`、`setTouchOn`、`setTouchOff` | 槽位取 `TouchCtlType`，动作取 `IntValueModel`；目录中合并记为 `touch_key` |
| `DeviceGamePanelItemWidgetModel` | 游戏模式 | `setGameModeOn`、`setGameModeOff` | `01` 开 |
| `DeviceMulDevicePanelItemWidgetModel` | 多点连接 | `setConnectMulOn`、`setConnectMulOff` | 独立命令，不与音频模式耦合 |
| `DeviceAudioPanelItemWidgetModel` | 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLc3`、`setAudioAptx`、`setAudioAptxHd`、`setAudioAptxAdaptive` | 可选项取 `DeviceAudioType`，其线值即 `2B` 参数 |
| `DeviceDingLevelPanelItemWidgetModel` | 提示音 | `setDingLevel` | 0（静音）在 i7 上有固件门槛 304 |
| `DeviceDetectionSwitchPanelItemWidgetModel` | 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | `00` 开 |
| `RDeviceFindItemWidgetModel` | 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | 接收是事件，不是完整状态 |
| `DeviceEqPanelItemWidgetModel` | EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight`、`setEqEap`、`setEqGame1`、`setEqGame2`、`setEqGame3`、`setEqGame4`、`setEqGame5` | 预设取 `DeviceEQTypeModel`；`hasCustom` 时另有自定义曲线（`setEqCustom`、`setEqCustomData`、`getCustomEq`） |
| `RDeviceEqBassPlusItemWidgetModel` | 低音增强 | `setEqBassPlus` | Sender 接受 0–4 |
| `RDeviceGainLevelItemWidgetModel` | 增益 | `setGainLevel`、`getGainLevel` | i7 门槛 213 |
| `RDeviceHearingProtectionItemWidgetModel` | 听力保护 | `setHearingProtection` | i7 门槛 213 |
| `RDeviceSpatialAudioBarWidgetModel` | 空间音频 | `setSpatialAudioSwitchOn`、`setSpatialAudioSwitchOff`、`setSpatialAudioModeHead`、`setSpatialAudioModeFixed`、`setSpatialAudioModeOff`、`setSpatialAudioMusicModeMusic`、`setSpatialAudioMusicModeMovie`、`setSpatialAudioMusicModeGame`、`setSpatialAudioMusicModeTV`、`setHeadMoveModeOn`、`setHeadMoveModeOff` | 按方法名推断，控件实际调用哪些未核对 |
| `DeviceEarAdaptivePanelItemWidgetModel` | 耳道自适应 | `setEarAdaptiveOn`、`setEarAdaptiveOff`、`setEarTipFitTest` | 按方法名推断 |
| `RDeviceTapControlItemWidgetModel` | 轻拍控制 | `setTapOn`、`setTapOff`、`setLeftTapTwo`、`setLeftTapThree`、`setRightTapTwo`、`setRightTapThree` | 按方法名推断 |
| `RDeviceLangItemWidgetModel` | 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish`、`getEarLanguage` | |
| `DeviceIndependentMicModePanelItemWidgetModel` | 独立麦克风 | `setIndependentMicModeOn`、`setIndependentMicModeOff`、`setIndependentMicModeActivateOn`、`setIndependentMicModeActivateOff` | 按方法名推断 |
| `RDeviceRecordItemWidgetModel` | 录音 | — | Sender 中没有录音方法 |
| `RDevicePersonalizationItemWidgetModel` | 个性化 | — | 尚未分析 |
| `DeviceOtaPanelItemWidgetModel` | OTA | 范围外 | OTA 是独立协议，路由见 `v2_capabilities.json` 的 `ota` |
| `RDeviceStarburstBusinessEntryItemWidgetModel` | 星芒业务入口 | 范围外 | 云端业务，不是耳机控制协议 |
| `RDeviceComponentDisplayBarWidgetModel` | 电量展示 | 结构 | 电量槽取 `battery_types` |
| `DeviceOptionPanelWidgetModel` | 面板容器 | 结构 | |
| `RDeviceMoreItemWidgetModel` | 更多设置入口 | 结构 | |

“按方法名推断”的行只说明 Sender 有名称相符的方法，控件是否调用、调用哪些、参数如何取值，都要在控件类中核对后才能写进型号文档。

## 全族已知的坑

- `getInfo` 整帧 35 字节，超过默认 ATT MTU 23 的单次写入容量（[i7 §2.3](../models/earfeel-i7-protocol.md#23-通道表和连接步骤)）。
- 接收 `2A` 为单字节时 `00` / `01` 与控制器值互换，双字节时不换；显示名称取该型号的 `DeviceEQTypeModel`（[i7 §5.3](../models/earfeel-i7-protocol.md#53-字段表)）。
- 接收 `2B`–`2E` 把十六进制文本按十进制解析，`0A` 及以上会解析失败（[i7 §5.3](../models/earfeel-i7-protocol.md#53-字段表)）。
- 极性不统一：`07`、`08` 是 `00` 开；`0E`、`32` 是 `01` 开（[i7 §4](../models/earfeel-i7-protocol.md#4-设置命令与参数)）。
- Sender 收到越界参数时返回空列表，不会生成帧（[i7 §4.1](../models/earfeel-i7-protocol.md#41-动态参数命令)）。
- `0C` 中的仓电量只有在至少一只入仓耳机读数有效时才是当前值，否则 App 显示缓存值（[i7 §5.3](../models/earfeel-i7-protocol.md#53-字段表)）。
- `getInfo` 查询包含 29 个 ID，只能说明 Sender 会问，不能据此给型号增加能力。

## 维护

整理型号时发现的新的全族事实，写回本文对应的表格或列表，并在“细节与证据”中注明出处。控件与命令的对应关系只在本文维护，`model_brief.py` 从这里读取。
