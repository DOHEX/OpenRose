# RS-Common 控制协议

配置类 `RsCommonDeviceConfig`（cid 536），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common 协议族](../families/rs-common.md)。泛型配置，不对应具体产品，未列入 App 的 43 款耳机配置清单（收录于 `supported-devices.json` 的 `non_headphone_and_generic_types`）。在 App 识别流程（`DeviceConfig`）中，只有广播 `rawData` 前缀匹配 `0a0b0a0b` 且在 App 开启开发者调试模式（`isDeviceDebug` 与 `agreeApp`）时才会加载此配置；非调试模式下作为“基础协议/非正式版设备”被直接过滤丢弃，并不是未识别设备的通用兜底。无实机记录，以下结论均为静态分析，设置命令是否被设备接受未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 泛型/调试配置 | `0a0b0a0b` | `RS-Common` | `RS-Common` | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:20`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:624`） |

配置中的内部名与显示名均为 `RS-Common`，无 CMIIT ID。前缀匹配由 `DeviceConfig.identifyDevice` 在广播规范化后的 24/16/12 位十六进制字符串中逐级匹配（identifyDevice（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`））；匹配到 `0a0b0a0b` 后返回枚举 `RS_COMMON`（序号 1）。在构建设备对象时，App 校验若属于 `RS_COMMON` 或 `RS_COMMON_V2`，必须开启开发者调试权限，否则输出日志并返回空对象跳过（DeviceConfig 过滤（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:1083`））。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认（否；配置 `sppSupport="false"`） | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:639`） |
| 控制 SPP UUID | 同族默认（`0cf12d31-fac3-4553-bd80-d6832e7b3931`；虽然 `sppSupport="false"` 仍设定了 UUID） | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:633`） |
| 广播中的经典地址 | 同族默认（取 rawData `[10,22)`） | 同族 | 同族 |
| 写入间隔 | 同族默认（100 ms） | 同族 | 同族 |

RS-Common 未配置 OTA 服务或特征 UUID，OTA 类型为 `none`（见 [OTA](#ota)）。

## 初始化

RS-Common 初始化使用全族共享闭包（分派表项 60114，与 SportFeel、SWIFT、OpenFun 相同），只发送一条 `getInfo` 批量查询帧，无其它专用查询。

| 次序 | App 方法 | 完整 TX hex | 读回 Tag | 状态 | 出处 |
|---:|---|---|---|---|---|
| 1 | `getInfo` | `FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA` | `01`、`07`、`08`、`09`、`0c`、`0d`、`0e`、`2a`、`2b`、`2c`、`2d`、`2e`、`33` | 静态 | RsCommonSender::getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`） |

初始化调用链证据：分派表项 60114（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60115`）），指向共享闭包 SportfreeDeviceConfig::<anonymous closure>（0x8e2444）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:574`）。查询帧包含 14 个 ID，覆盖固件版本、按键、触控开关、佩戴检测、降噪模式、电量、游戏模式、EQ、音频模式、降噪/通透等级、提示音音量与空间音频。

## 电量

电量槽布局为左耳、右耳与充电盒（三槽：`BatteryType.left`、`BatteryType.right`、`BatteryType.charge`），静态配置于 `RDeviceComponentDisplayBarWidgetModel`（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:105`））。

RS-Common 沿用全族基线 `RsCommonReceiveHandler`，未覆写电量处理；回包 Tag 为 `0c`，由 `RsCommonReceiver._handleBatteryData` 解析：
- 左耳电量：字符 `[8,10)` 16 进制解析，最高位为入仓标志
- 右耳电量：字符 `[10,12)` 16 进制解析，最高位为入仓标志
- 充电盒电量：字符 `[12,14)` 16 进制解析，无独立入仓位校验

详见 [RS Common 协议族文档](../families/rs-common.md#全族共用) 与 接收解析（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`）。

## 设置命令

BODY 遵循 RS Common 帧结构（`FF 00 LL BODY AA`，无校验和）。RS-Common 未设置固件版本门槛（全部为 `—`）。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | `getInfo` | `FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33` | 左/右/盒三槽展示 | — | Tag `0c` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:101`）；RsCommonSender::getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind` | `12 02`（ANC）、`12 04`（关闭）、`12 06`（通透）、`12 08`（抗风） | ANC=`12 02`；关闭=`12 04`；通透=`12 06`；抗风=`12 08` | — | Tag `09` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:122`）；RsCommonSender::setNoiseModeAnc 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:642`） |
| ANC 等级 | `setAncLevel` | `58 <level>` | 3 级（低/中/高）；Sender 接受 1–5 | — | Tag `2c` | 推断 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:143`）；RsCommonSender::setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:403`） |
| 通透等级 | `setTransLevel` | `5a <level>` | 3 级（低/中/高）；Sender 接受 1–5 | — | Tag `2d` | 推断 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:145`）；RsCommonSender::setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:329`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2a 01`（POP）、`2a 00`（HIFI）、`2a 02`（ROCK） | POP=`2a 01`；HIFI=`2a 00`；ROCK=`2a 02` | — | Tag `2a` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:155`）；RsCommonSender::setEqPop 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2049`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e 01`（开）、`0e 00`（关） | 开=`0e 01`；关=`0e 00` | — | Tag `0e` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:198`）；RsCommonSender::setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1738`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `5e 01`（左）、`5e 02`（右）、`5e 03`（双耳）、`5e 04`（关） | 左=`5e 01`；右=`5e 02`；双耳=`5e 03`；关=`5e 04` | — | 无（事件） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:211`）；RsCommonSender::setFindDeviceLeft 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:307`） |
| 触控设置 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `01 <slot> 0<action>` | 槽位见下；动作 0–5 见下 | — | Tag `01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:578`）；RsCommonSender::setLeftTouchOne 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1372`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | `0e 00`（开）、`0e 02`（关） | 开=`0e 00`；关=`0e 02` | — | Tag `07` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:594`）；RsCommonSender::setTouchOn 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:934`） |
| 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | `10 00`（开）、`10 02`（关） | 开=`10 00`；关=`10 02` | — | Tag `08` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:374`）；RsCommonSender::setEarDetectionOn 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:826`） |
| 提示音音量 | `setDingLevel` | `5c <level>` | 1–5 | — | Tag `2e` | 推断 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:425`）；RsCommonSender::setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:697`） |
| 空间音频开关 | `setSpatialAudioSwitchOn`、`setSpatialAudioSwitchOff` | `33 01`（开）、`33 00`（关） | 开=`33 01`；关=`33 00` | — | Tag `33` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:478`）；RsCommonSender::setSpatialAudioSwitchOn 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1822`） |
| 空间音频模式 | `setSpatialAudioModeHead`、`setSpatialAudioModeOff` | `33 01`（头部追踪开）、`33 00`（关） | 开=`33 01`；关=`33 00` | — | Tag `33` | 静态 | RsCommonSender::setSpatialAudioModeHead 等（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1813`） |
| 面板容器 | 结构 | — | 容器（主页与更多设置页） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:189`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:229`） |
| OTA | 范围外 | — | `otaType=none` | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:281`） |

触控参数说明：
- 触控槽位（`TouchCtlType`）：左单=`01`、左双=`02`、左三=`03`；右单=`11`、右双=`12`、右三=`13`。未配置长按（`TouchCtlType.long`）。
- 触控动作（`IntValueModel`）：无功能=`0`、播放/暂停=`1`、上一曲=`2`、下一曲=`3`、音量加=`4`、音量减=`5`。

## 接收字段

同族文档（RS-Common 使用全族基线 `RsCommonReceiveHandler`，各 Tag 的解析、分派与状态写入完全遵循 [RS Common 协议族文档](../families/rs-common.md#全族共用)）。

涉及 Tag 概括：`01`（按键设置）、`07`（触控开关）、`08`（佩戴检测）、`09`（降噪模式）、`0c`（电量）、`0d`（固件版本）、`0e`（游戏模式）、`2a`（EQ 预设）、`2b`（音频模式/多点连接）、`2c`（ANC 等级）、`2d`（通透等级）、`2e`（提示音音量）、`33`（空间音频）。全部无型号特例覆写。

## 未实现与未知

- 实机验证缺失：所有结论来自 RoseLink 3.8.0 逆向反编译静态分析，缺少 RS-Common 对应硬件的实际通信捕获记录，设备是否接受各命令及回包行为在实机验证前均为未知。
- 泛型配置用途：广播前缀 `0a0b0a0b` 仅用于内部开发调试，非调试模式下 App 直接过滤丢弃（输出“未开启开发者调试模式，基础协议/非正式版设备被过滤”）。未查到具体市售产品采用此前缀，推断为内部协议样机或基础通信测试工程配置。
- ANC 与通透等级 UI 挡位映射：配置各提供 3 级（`low`、`medium`、`high`），而 Sender 底层接受 1–5；UI 是否映射为 1/2/3 或 1/3/5 在 UI 绑定闭包代码中未完全闭合，标为推断。
- 寻机无状态读回：寻机命令触发后无独立 Tag 反馈状态，App 仅发送执行帧。
- 提示音音量与空间音频的实际设备支持：虽然配置构造中包含控件，但基础工程机是否支持空间音频（`33`）及提示音音量（`5c`）未知。

## OTA

RS-Common 的 OTA 类型为 `none`（`DeviceOtaType.none`，构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:27`））。虽然主页选项面板中构造了 `DeviceOtaPanelItemWidgetModel`（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/rs_common_device_config.dart:281`）），但其未配置任何 OTA BLE 服务/特征 UUID，也未配置 OTA SPP UUID，App 实际上不提供任何固件升级路径。详见 [RS Common 协议族文档已知的坑](../families/rs-common.md#全族已知的坑)。OpenRose 不实现 OTA，本节只作记录。
