# RS Common 协议族

RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）中有 7 个配置使用 RS Common（旧版，非 V2）：通用配置 RS-Common（前缀 `0a0b0a0b`）和 BudsFree i、Cambrian、OpenFeel、OpenFun、SportFeel、SWIFT 六个零售型号。7 个构造函数都实例化 `RsCommonSender` 和 `RsCommonReceiver`；BudsFree、Cambrian、OpenFree、SWIFT 另有型号 ReceiveHandler，其余三个用 `RsCommonReceiveHandler`。本文的结论全部来自静态分析，7 个配置都没有实机验证。

## 全族共用

| 配置 | 实现类（构造行） | ReceiveHandler |
|---|---|---|
| RS-Common | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:43`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:54`） | `RsCommonReceiveHandler`（L91（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:91`）） |
| BudsFree i | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:43`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:54`） | `BudsfreeReceiveHandler`（L91（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:91`）），只重写电量 |
| Cambrian | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:48`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:59`） | `CambrianReceiveHandler`（L96（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:96`）），重写音频模式与电量 |
| OpenFeel | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:43`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:54`） | `OpenfreeReceiveHandler`（L93（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:93`）），重写音频模式 |
| OpenFun | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfun_device_config.dart:43`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfun_device_config.dart:54`） | `RsCommonReceiveHandler`（L91（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfun_device_config.dart:91`）） |
| SportFeel | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:43`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:54`） | `RsCommonReceiveHandler`（L91（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:91`）） |
| SWIFT | Sender（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:43`）、Receiver（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:54`） | `SwiftReceiveHandler`（L91（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:91`）），只重写电量 |

| 环节 | 结论 | 细节与证据 |
|---|---|---|
| 广播识别 | 与 V2 共用扫描器和前缀表；7 个配置各有一组 4 字节广播前缀。通用配置 `0a0b0a0b` 受开发调试过滤规则约束（未开启 `isDeviceDebug` 与 `agreeApp` 时直接被过滤丢弃），并非未识别设备的通用兜底；OpenFeel 有 `0b0a0a0b`/`0b0a0a0c`/`0b0a0a0d` 三个前缀 | model_registry.json（`rose_reverse/analysis/model_registry.json`） 的 `device_identifiers`；过滤逻辑（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:1061`） |
| 广播字段 | `getInfoFromRawData` 的 rs_common 分支按设备类型取经典地址：BudsFree 特例要求 rawData 长度 ≥27、取字符 `[16,28)` 经 `to2` 冒号格式化；其余要求 ≥22、取 `[10,22)`；长度不足记录警告日志（`[rs_common] BUDSFREE rawData 解析错误` 或 `[rs_common] rawData 解析错误`）并放弃本条广播 | getInfoFromRawData（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:30`）（rs_common 分支从 L240（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:240`） 开始，BudsFree 特例 0x749088（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:277`）） |
| 控制通道选择 | `ConnectManager._shouldUseSppControl`：SPP 可用、型号允许、未强制 BLE、非 LE Audio 时走 SPP | _shouldUseSppControl（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:4614`） |
| 控制特征 | 默认 BLE 服务/写/通知为 `000000fe`/`000000f1`/`000000f2`；设备类型 1、2 另有其他 UUID 组。RS Common 的 7 个配置都由 `customConfigEarObject` 设定 SPP UUID | normalBleServiceConfig（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、model_registry.json（`rose_reverse/analysis/model_registry.json`） |
| 发送帧 | `FF SS LL BODY AA`，无校验和；`LL` 只数 BODY（命令+参数）；`transform` 原样返回（推断：AOT 与 `ZtSender.transform` 合并），所以方法里写好的完整帧直接下发 | setEarLanguageEnglish（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:10`）、getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`）、ZtSender.transform（0xdfaaf4，AOT 合并候选）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2926`） |
| 初始化 | 配置分派目标决定查询序列：4 个配置共用只调用 `getInfo` 的闭包；BudsFree（加发 `FF 05 08 FA 2A 09 0C 0E 07 01 2B AA`，写死 `SS=05`）、Cambrian（发送 `getInfo` 后经 500 ms 延时定时器再重发一次 `getInfo`）、OpenFree（加发 `FF 00 02 FA 2B AA`）各有专用 `sendInitCommands`。`getInfo` 的 BODY 是 `fa` + 14 个 ID；版本缺失重试对 rs_common 直接调用 `getInfo` | 版本查询重试（`rose_reverse/analysis/blutter/asm/roselink/lib/global_controller.dart:2859`）、BudsFree（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:431`）、Cambrian（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:748`）、OpenFree（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:366`）、SportFree 共享闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:574`） |
| 命令编码 | `RsCommonSender` 每个方法直接返回完整帧；固定命令与参数族见机器目录 | rs_common_sender_commands.json（`rose_reverse/analysis/rs_common_sender_commands.json`） |
| 接收解析 | `process` 按输入串字符位置分派：tag=`[6,8)`，value=`[9,10)`，电量=`[8,10)`/`[10,12)`/`[12,14)`，固件=`[9,14)`，按键按字符下标常量 `[0xb,0xf,0x13,0x1f,0x23,0x27]`（十进制 11/15/19/31/35/39）切单字符；不清洗空格、不校验帧头/长度/校验和、不分包缓存。`RsCommonReceiveHandler` 基类仅实现固件、降噪、EQ、电量 4 个回调，音频等其他回调落入合并实现（如 `audioModeReceived` 0xe2fdd4 写控制器 `field_63`）；OpenFree 与 Cambrian 专属重写音频模式 | process（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`）、基类 handler（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:8`）；机器布局 rs_common_receiver_layouts.json（`rose_reverse/analysis/rs_common_receiver_layouts.json`） |
| 客户端状态建议 | 发送完成只代表写入成功，不代表耳机接受；状态以接收 Tag 为准；没有序号/ACK 可配对，重试只能在应用层做，并避免把乐观 UI 当成设备状态 | 推断：由 `RCommandLostSender` 的发送即忘语义与 `process` 无确认逻辑归纳；参见 Sender 基类（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/r_sender.dart:13`） |

`rs_common_sender_commands.json` 和 `rs_common_receiver_layouts.json` 覆盖全族 Sender/Receiver；`applicability` 一律是 `shared_sender_only_not_proven_model`，表示只在共享 Sender 中确认了编码，型号界面是否调用仍需逐个核对。目录没有收录的 Sender 方法（如果有）以 rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart`） 为准。

例如 `setNoiseModeNormal` 直接生成 `FF 00 02 12 04 AA`，`getInfo` 生成 `FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA`；两帧都没有校验和字节。

### 默认传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | `000000fe-0000-1000-8000-00805f9b34fb` | 静态 | normalBleServiceConfig（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`） |
| 写特征 | `000000f1-0000-1000-8000-00805f9b34fb` | 静态 | 同上 |
| 通知特征 | `000000f2-0000-1000-8000-00805f9b34fb` | 静态 | 同上 |
| SPP 是否允许 | 按型号：Cambrian、OpenFun、SportFeel 允许；RS-Common、BudsFree i、OpenFeel、SWIFT 不允许 | 静态 | model_registry.json（`rose_reverse/analysis/model_registry.json`） |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | 同上（允许 SPP 的型号） |
| 广播中的经典地址 | rs_common 分支：一般取 rawData 字符 `10,22)`；BudsFree 特例取 `[16,28)` | 静态 | [getInfoFromRawData（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:240`） |
| 写入间隔 | 默认 100 ms（`gapAfterMs` 默认参数） | 静态 | RQueueMamager.send（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/r_queue_mamager.dart:17`） |

## 按型号变化

| 项 | 去哪里查 |
|---|---|
| 识别前缀、SPP 是否允许、控制/OTA SPP UUID、CMIIT | model_registry.json（`rose_reverse/analysis/model_registry.json`） |
| 配置类 cid、覆写的方法、构造参数 | Blutter 配置类文件头部的 `// class id:` 与方法列表 |
| 初始化目标 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv`） 第 `0x1000 + 0xd8ba + cid` 项；本文已列出 4 个目标 |
| 开放的控件（型号能力） | 配置类中 `Allocate*WidgetModel` 的调用；legacy_capabilities.md（`rose_reverse/analysis/legacy_capabilities.md`） 的先行矩阵与配置证据行 |
| 控件取值 | 配置构造参数的 Dart Smi（右移 1 位），例如 `NoiseType`、`DeviceAudioType`、`DeviceEQTypeModel`、`TouchCtlType`、`IntValueModel`；没有 V2 的 `v2_capabilities.json` 等价物 |
| 选项差异 | OpenFeel 音频模式与触控动作按设备 ID 后缀（`0b0a0a0b`/`0b0a0a0c`/`0b0a0a0d`）变化，见 legacy_capabilities.md（`rose_reverse/analysis/legacy_capabilities.md`） |
| 电量槽与单体 | BudsFree/OpenFun/SportFeel/SWIFT/Cambrian 的 handler 或配置；Cambrian、SWIFT 用单体（仓槽）：其 ReceiveHandler 仅取 `RBatteryData` 偏移 0x17（字符 `12,14)`）写入仓电量 `field_27` 并置 `caseCharging=false`，左右槽保留未变 |
| OTA 路由 | 配置的 `otaType`；Cambrian 为 AB，RS-Common 为 `none`，其余无 OTA 入口；`otaType=none` 时进入 OTA 页面直接返回 Null（[0xb45b30（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3305`）） |

analysis/model_brief.py（`rose_reverse/analysis/model_brief.py`） 以型号名为参数，把上表各项和下面的控件对应关系汇总成简报，并列出索引回答不了的缺口。

## 控件与命令

控件模型类定义在 `roselink/rose_bluetooth/config/widget_model.dart`，界面在 `roselink/modules/device_control/widget`，经 `RCommander` 调用当前配置的 Sender；取值由该型号的枚举决定。`model_brief.py` 读取本表：第三列只写方法名；写“结构”表示容器或展示控件，写“范围外”表示 OTA 或云端业务入口，写“—”表示尚未找到对应的 Sender 方法。没有核对控件调用的行在说明列写“按方法名推断”。

| 控件类 | 功能 | Sender 方法 | 说明 |
|---|---|---|---|
| `RDeviceComponentNoiseBarWidgetModel` | 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind`、`setAncLevel`、`setTransLevel` | 可选模式取 `NoiseType`；按方法名推断 |
| `DeviceEqPanelItemWidgetModel` | EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | UI 的 EQ 预设逻辑直接调用这三个方法；没有自定义 EQ |
| `DeviceGamePanelItemWidgetModel` | 游戏模式 | `setGameModeOn`、`setGameModeOff` | `DeviceGamePanelItem` 经 `DeviceGameModeIntent.setGameMode`（0x754f04（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_game_panel_item.dart:192`））调 `RCommander` 发送 `0E 01`/`0E 00` |
| `DeviceAudioPanelItemWidgetModel` | 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLc3`、`setAudioMulDevice` | 按方法名推断；可选项取 `DeviceAudioType`，线值即 `2B` 参数 |
| `DeviceMulDevicePanelItemWidgetModel` | 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `DeviceMulDevicePanelItem` 经 `DeviceMulDeviceIntent.setMulDevice`（0xb937ec（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_mul_device_panel_item.dart:421`））调 `setConnectMulOn`（`2B 04`）/`setConnectMulOff`（`2B 00`，切回 AAC/SBC） |
| `RDeviceTouchSettingItemWidgetModel` | 触控 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong`、`setTouchOn`、`setTouchOff` | 槽位取 `TouchCtlType`，动作取 `IntValueModel`；缺少 `TouchCtlType.long` 时 UI 隐藏长按选项 |
| `RDeviceFindItemWidgetModel` | 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | 接收是事件；经 `openFindDevice`（0x75a870（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:2052`））调 `setFindDeviceLeft`/`Right`/`Both`/`Off` 发送 `5E 01`..`04` |
| `DeviceDingLevelPanelItemWidgetModel` | 提示音音量 | `setDingLevel` | `DeviceDingLevelPanelItem` 经 `DeviceDingLevelIntent.setDingLevel`（0x754c3c（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:654`））调 `setDingLevel` 发送 `5C 01`..`05` |
| `DeviceDetectionSwitchPanelItemWidgetModel` | 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | 按方法名推断；`10 00` 开、`10 02` 关 |
| `DeviceSpatialSwitchPanelItemWidgetModel` | 空间音频 | `setSpatialAudioSwitchOn`、`setSpatialAudioSwitchOff`、`setSpatialAudioModeHead`、`setSpatialAudioModeOff` | `DeviceSpatialSwitchPanelItem` 经 `DeviceSpatialSwitchIntent.setSpatialSwitch`（0x74ab04（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_spatial_switch_panel_item.dart:172`））调 `setSpatialAudioSwitchOn`/`Off` 发送 `33 01`/`33 00`；ModeHead/ModeOff 只是开关的别名 |
| `RDeviceComponentDisplayBarWidgetModel` | 电量展示 | 结构 | 电量槽由型号决定 |
| `DeviceOptionPanelWidgetModel` | 面板容器 | 结构 | |
| `RDeviceMoreItemWidgetModel` | 更多设置入口 | 结构 | |
| `DeviceOtaPanelItemWidgetModel` | OTA | 范围外 | OTA 是独立协议 |
| `RDeviceStarburstBusinessEntryItemWidgetModel` | 星芒业务入口 | 范围外 | 云端业务，不是耳机控制协议 |

## 全族已知的坑

- 发送帧没有校验和；接收 `process` 也不校验长度或校验和，不能照搬 V2 的 `CC` 或分包状态机（发送帧（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:10`）、process（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`））。
- `RsCommonSender` 没有自己的 `transform` 文本，推断：AOT 很可能把它与 `ZtSender.transform` 合并；后者原样返回输入，因此固定方法里的 `SS` 都是 `00`，没有发送序号递增（ZtSender.transform（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2926`））。
- BudsFree 初始化的第二帧使用 `SS=05`（`ff 05 08 fa 2a 09 0c 0e 07 01 2b aa`），是全族唯一见到的非零序号（BudsFree sendInitCommands（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:431`））。
- 触控槽位的动作参数按十进制字符串插值到 `... 0<action>`；传 10 及以上会多出一个字符，声明长度仍是 3，帧被破坏（setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1372`））。
- `0E` 同时承载触控和游戏：`0E 00` 既是触控开也是游戏关，`0E 01` 是游戏开，`0E 02` 是触控关（setTouchOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:879`）、setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1738`））。
- `2B` 同时承载音频编码和多点连接：`00` AAC/SBC、`01` LDAC、`02` LHDC、`03` LC3、`04` 多点；关多点会发送 `2B 00`，等于切回 AAC/SBC（setAudioAacSbc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1612`）、setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2211`））。
- `getInfo` 只有 14 个 ID（V2 是 29 个），并且它是共享批量查询，不能据此给某个型号增加能力（getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`））。
- `getEarLanguage` 会发送 `FA 31`，但 `process` 的 tag 表中没有 `31`，App 不处理语言查询的单独回包（getEarLanguage（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:118`）、process（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`））。
- 等级/模式类方法在参数越界时返回空列表而不是抛错：`setFindDevice` 只接受 1–4，`setAncLevel`/`setTransLevel`/`setDingLevel` 只接受 1–5（setFindDevice（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:195`）、setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:403`））。
- 接收的 tag/value 是输入字符串的字符下标；Blutter 里 `substring` 的 end 参数是 tagged Smi，本文的下标已右移一位。输入串是否带空格或前缀在文本中看不出，按字节解析前必须先取到真实通知格式（process（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`））。
- 电量三个字节按 16 进制解析后，左右耳各取二进制最高位作为入仓标志；仓电量字节没有独立的有效性校验，客户端应自行判断是否沿用缓存（battery handler（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`））。
- 通用配置 RS-Common 的 `otaType` 是 `none`，但配置仍会构建 OTA 面板；不要把面板存在当成支持某种 OTA（legacy_capabilities.md（`rose_reverse/analysis/legacy_capabilities.md`））。
- 开发者调试过滤：`DeviceConfig`（0xa0d2c4（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:1061`））在未开启调试模式（`isDeviceDebug` 与 `agreeApp`）时直接过滤丢弃 `RS_COMMON`（前缀 `0a0b0a0b`）与 `RS_COMMON_V2` 设备并输出“未开启开发者调试模式，基础协议/非正式版设备被过滤”，因此 `0a0b0a0b` 不是未识别设备的通用兜底。
- EQ Tag `2A` 映射：`RsCommonReceiveHandler.eqDataReceived`（0xe35d9c（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:238`））中线值 `0` 对应控制器 `1`（HIFI / BALANCE），线值 `1` 对应控制器 `0`（POP / BASS），线值 `2` 对应控制器 `2`（ROCK）；线值 `0` 与 `1` 存在反转。
- 触控动作列表与开关差异：`RTouchInfoModel`（touch_info.dart#L8（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:8`））中若缺少 `TouchCtlType.long`，长按设置在 UI 上被隐藏且 `setLeft/RightTouchLong` 永不触发；`field_23` 开关在 SportFree/Cambrian 为 true，Swift 为 false，OpenFun 仅当 `deviceId.substring(0,8) == "0b0a0a10"` 时为 true；OpenFree 的 `0b0a0a0b`/`0c` 前缀使用 5 项动作列表（无无功能 0）。
- 按键解析下标：`RsCommonReceiver._handleKeyData`（0xe42b68（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:120`））引用的常量列表 `[0xb, 0xf, 0x13, 0x1f, 0x23, 0x27]`（十进制 11, 15, 19, 31, 35, 39）在对象池中为已解码数值，闭包取 `i, i+1)` 截取单字符并转十进制整数，不是 Smi 需右移。
- `getInfo` 包含未处理的 ID `12`：查询帧包含 `12`，但 `RsCommonReceiver.process`（[0xe433e0（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`））没有任何针对 Tag `12` 的处理分支，回包会被静默忽略。
- 单体电量解析：SWIFT 与 Cambrian 的 ReceiveHandler（0xe403b8（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/swift_receive_handler.dart:10`）、0xe4019c（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/cambrian_receive_handler.dart:140`））只从 `RBatteryData` 偏移 0x17 读取单体电量存入仓电量 `field_27`，强制置 `caseCharging=false` 并忽略左右槽。
- OTA 页面入口防护：`DeviceOtaController.enterPage`（0xb45b30（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3305`））在 `otaType == none` 时直接返回 `Null`，不初始化 OTA 管理器。

## 维护

整理型号时发现的新的全族事实，写回本文对应的表格或列表，并在“细节与证据”中注明出处。Sender/Receiver 目录由 build_rs_common_catalog.py（`rose_reverse/analysis/build_rs_common_catalog.py`） 生成；改完脚本后重跑 `python3 -I analysis/build_rs_common_catalog.py`，不要手改 JSON。控件与命令的对应关系只在本文维护，`model_brief.py` 从这里读取。型号文档写“同族默认”的项见 [默认传输](#默认传输)。
