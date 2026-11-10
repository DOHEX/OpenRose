# OpenFeel MK2 控制协议

配置类 `OpenfeelMk2DeviceConfig`（cid 508，继承 `BaseDeviceConfig`，不是 `RsCommonV2DeviceConfig` 的子类），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common V2 协议族](../families/rs-common-v2.md)。Sender 为 `RsCommonV2Sender`，Receiver 为 `RsCommonV2Receiver`，handler 为 `RsCommonV2ReceiveHandler`（class id 421）。没有 OpenFeel MK2 实机记录：以下均为静态分析，只在离线编解码测试中核对过帧和字段，没有连接耳机、没有抓包，也没有执行 OTA；设备是否接受各设置未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售开放式 TWS | `0b0eb657` | `openfeel_mk2` | `OpenFeel MK2` | 静态 | 构造:10（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:10`）、定制:660（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:660`） |

零售名 `OpenFeel MK2`，内部名 `openfeel_mk2`；无其他别名条目。识别按规范化 manufacturer 数据的前 8 个十六进制字符 `0b0eb657`，不按设备名；单 manufacturer 数据项时推导公司标识 `0x0E0B`、载荷前两字节 `B6 57`。CMIIT ID 为 `26J51U5A3186`（构造:20（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:20`））。`OpenFeel MK2` 为开放式（Open-ear）气传导/耳挂耳机，与早期 RS Common 架构的 `OpenFree`（内部名 `openfree`，识别前缀 `0b0a0a0b/0c/0d`）不同。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认 | 同族 | 同族 |
| 控制 SPP UUID | 同族默认 | 同族 | 同族 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| OTA SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | customConfigEarObject:694（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:694`） |
| BLE OTA 服务 | `0000ff12-0000-1000-8000-00805f9b34fb` | 静态 | normalBleOtaServiceConfig:1230（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1230`） |
| BLE OTA 写特征 | `0000ff15-0000-1000-8000-00805f9b34fb` | 静态 | normalBleOtaServiceConfig:1232（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1232`） |
| BLE OTA 通知特征 | `0000ff14-0000-1000-8000-00805f9b34fb` | 静态 | normalBleOtaServiceConfig:1236（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1236`） |

同族默认的取值与证据见 [族文档的默认传输表](../families/rs-common-v2.md#默认传输)。OpenFeel MK2 的控制 SPP 与 OTA SPP 复用同一 UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`（定制:692（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:692`））。BLE OTA 通道由 `normalBleOtaServiceConfig` 为 AB 方案分配 16 位 UUID `ff12`、`ff15`、`ff14`，在 Bluetooth Base UUID 展开下得到上述 128 位 UUID。控制通道选择、MTU、订阅和队列规则同族文档。

## 初始化

| 分派 | 表项 | 目标 | 发送的查询 | 状态 | 出处 |
|---|---|---|---|---|---|
| GDT+0xd8ba，cid 508 | 60086（`table.tsv` 第 60087 行） | `0x8e2444` | 仅 `getInfo` | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60087`）、补核（`rose_reverse/analysis/i7_dispatch_findings.md`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`） |

目标函数只调用 `RCommander.getInfo`，不查询自定义 EQ `3E`；AOT 把共享闭包标成 SportFree 匿名 closure，不代表 OpenFeel MK2 使用 SportFree 配置。`getInfo` 的 29 个 ID 和完整 35 字节帧见族文档 [初始化与查询](../families/rs-common-v2.md#初始化与查询)。客户端额外补读 `FA 3E` 是客户端选择，不是官方初始化。

## 电量

| 布局 | 槽 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 左右耳与仓 | `left`、`right`、`charge` | 共享 `0C` 字段的三个字节；百分比、入仓 flag、仓缓存与新鲜度规则同族 | 静态 | BatteryType:130（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:130`）、[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存) |

仓 byte 的 bit7 只作为 flag 保存，不能据此称为“充电中”；仓电量是否有效取决于入仓耳读数，规则同族。具体语义仍是未知。

## 设置命令

OpenFeel MK2 配置的每个控件一行。BODY 只写命令体，完整帧的 `FF SS LL BODY CC AA` 封装、序号和校验见族文档 [发送帧](../families/rs-common-v2.md#发送帧)。除 AI 入口外，命令体和配置都是静态事实；设备是否接受仍未知。“读回”列的 `getInfo` 表示该 ID 在共享批量查询中，App 不保证每次设置后都单独查询，最终状态以通知回包为准，`—` 表示没有查询方法。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 左、右、仓三槽 | — | `getInfo`（`0c`） | 静态 | 显示控件:126（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:126`） |
| 等响低音增强 | `setEqBassPlus` | `2a 05 value` | 预设点击=发送当前 level；等级 1..3 对应 `00/01/02` | — | `getInfo`（`2a`） | 静态 | 控件:153（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:153`）、setEqBassPlus:4044（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4044`） |
| 自定义 EQ 选择 | `setEqCustom` | `2a 04` | custom=`04` | — | `getEq`（`2a`） | 静态 | 控件:144（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:144`）、setEqCustom:4003（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4003`） |
| 自定义 EQ 数据 | `setEqCustomData` | `3e` + 10 字节 | 10 个 −6..+6 整数，符号位加绝对值；频点 20Hz..15kHz | — | `getCustomEq`（`3e`） | 静态 | 控件:144（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:144`）、频点与滑块:2424（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2424`） |
| 增益 | `setGainLevel`、`getGainLevel` | `45 value`；查询 `fa 45` | 低/中/高=`00/01/02` | — | `getGainLevel`（`45`） | 静态 | 控件:195（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:195`）、setGainLevel:1914（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1914`） |
| 听力保护 | `setHearingProtection` | `46 value` | 关闭=`00`；75/80/85/90/95/100 dB=`01..06` | — | `getInfo`（`46`） | 静态 | 控件:204（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:204`）、setHearingProtection:1855（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1855`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e 01`、`0e 00` | 开=`01`、关=`00` | — | `getInfo`（`0e`） | 静态 | 控件:213（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:213`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 01`、`32 00` | 开=`01`、关=`00` | — | `getInfo`（`32`） | 静态 | 控件:226（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:226`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLc3` | `2b 00`、`2b 01`、`2b 02`、`2b 03` | AAC/SBC=`00`、LDAC=`01`、LHDC=`02`、LC3=`03` | — | `getInfo`（`2b`） | 静态 | 控件:312（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:312`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `07 00`、`07 01` | 开=`00`、关=`01` | — | `getInfo`（`07`） | 静态 | 控件:334（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:334`）、setTouchOn:2420（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） |
| 触控按键 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | 左耳 `01/02/03/04`、右耳 `11/12/13/14`；action 0 无、1 播放暂停、2 上一首、3 下一首、4 音量+、5 音量−、6 游戏、7 语音助手（无 8 ANC 循环） | — | `getInfo`（`01`） | 静态 | 控件:334（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:334`）、无ANC触控列表:594（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:594`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2f 01`、`2f 02`、`2f 03`、`2f 04` | 左=`01`、右=`02`、双耳=`03`、停=`04` | — | `getInfo`（`2f`）；回包含 05/06 单耳停止 | 静态 | 控件:392（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:392`）`、[族寻机](../families/rs-common-v2.md#寻机) |
| 提示音 | `setDingLevel` | `2e value` | 1..5=`01..05`；关闭=`00` | — | `getInfo`（`2e`） | 静态 | 控件:501（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:501`）、setDingLevel:2076（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2076`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置:186（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:186`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置:343（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:343`） |
| 星芒业务入口 | `setStarburstSdk`、`setAiPageState` | `41 44 42 41 4f` + SDK 字节；`setAiPageState` 带页面 id 和 active 标志 | 入口前缀 ASCII `ADBAO` | — | — | 未知 | 配置:271（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:271`）、setStarburstSdk:66（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:66`） |
| OTA | 范围外 | — | 见 [OTA](#ota) | — | — | 静态 | 配置:401（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:401`）、AB 实现（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:8`） |

OpenFeel MK2 与封闭入耳式（如 i7）的关键差异是**无 ANC 面板**（无降噪、通透、抗风模式与档位控件，无动态自动 ANC，无 ANC 轮换命令 `36`），且触控按键动作列表中显式移除了 `08`（ANC 循环切换），配置使用 `rsCommonV2TouchListWithoutAnc`（代码:594（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:594`））。

EQ 面板配置了预设 `eq-bass-loudness-boost`（枚举值 5，本地化名称“等响低音增强”，构造:153（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:153`））与自定义 EQ：
1. 点击等响低音增强预设时，UI（`0xb92004`）读取当前保存的 level 并调用 `DeviceEqBassIntent.setEqBass`（`0x752524`），经 `RCommander.setEqBassPlus`（`0x75264c`）最终由 `RsCommonV2Sender.setEqBassPlus`（`0xc9d048`）发送 `2A 05 <value>`。
2. 激活该预设后下方展开等级选择栏 `RHintMessageBoxItemByLevel`，配置参数 `bassLoudnessBoostValue: 3`（Dart Smi 6 右移 1 位，配置:148（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:148`）），渲染 3 档（1、2、3），点击各档位通过 `setEqBass(index - 1)` 分别发送 `2A 05 00`（1 档）、`2A 05 01`（2 档）、`2A 05 02`（3 档）。Sender 的参数范围为 0..4。
3. 自定义 EQ 的 10 频段频点固定为 `20, 100, 300, 500, 1k, 2k, 3k, 5k, 8k, 15k`（面板定义:2507（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2507`）），滑块调节范围为 `−6 dB` 至 `+6 dB`（步长 1 dB），编码规则同族文档 [自定义 EQ](../families/rs-common-v2.md#自定义-eq)。本型号未设置 `customSinceVersion`，自定义 EQ 无版本门槛。

增益（`45`）线值 `00/01/02` 对应三档（低/中/高）；听力保护（`46`）线值 `00`（关）及 `01..06`（75..100 dB）为界面阈值标签，非声学实测；两者在配置构造时均未写入版本门槛字段（i7 为 213，本型号为无）。提示音控件未配置版本字段，支持直接选择关闭档位（`00`）。星芒业务入口仅配置 `needConnect: true`，无版本门槛限制。

## 接收字段

所有 Tag 的处理与取值同族文档 [字段表](../families/rs-common-v2.md#字段表)，使用共享 `RsCommonV2ReceiveHandler`（cid 421）。与族文档不同之处：
1. OpenFeel MK2 没有 ANC 硬件与控件，固件通常不上报 `09`（降噪模式）、`2C`（ANC 档位）、`2D`（通透档位）、`36`（ANC 轮换）等 Tag；即使 Receiver 具备解析能力，App 亦无对应展示。
2. 触控按键 Tag `01` 上报的动作值中不包含 `08`（ANC 循环）。
3. 电量槽布局见 [电量](#电量)；`0C` 仓 bit7 语义、`34` MAC 左右耳对应同族未知。

## 未实现与未知

- 没有 OpenFeel MK2 实机记录：所有设置写入、持久化、返回时序、广播原文和 SPP 配对/SDP 都未验证；状态为静态的分析不能代替设备确认。
- 广播只有规范化规则的静态推导，没有 OpenFeel MK2 广播样本；经典地址字段未实测。
- `0C` 仓 byte 的 bit7 只保留为 flag，不能确定“充电中”；仓电量何时算有效、缓存如何显示同族文档，仍无实机样本。
- `34` 的 12 字节按 main/sub 两个 6 字节 MAC 原序输出，没有证据表明前 6 字节是左耳或右耳。
- 等响低音增强（`2A 05`）的 3 档（`00/01/02`）在耳机 DSP 中的具体频段中心、增益曲线与动态等响特性未经验证。
- 自定义 EQ 的界面频段标签（20Hz..15kHz）和滤波器参数未与声学测量对应；`3E` 不携带频点或 Q 值，不能从 App 推断实际滤波中心。
- 增益与听力保护的线值对应关系未经实测声压标定。
- 星芒业务（ADBAO）具体交互未知，未恢复 SDK 内层消息和云业务。
- OTA 的握手时序、完整固件校验、断点续传与失败恢复尚未通过抓包验证，详见 [OTA](#ota)。

## OTA

OpenFeel MK2 的 OTA 路由为 AB（`DeviceOtaType.ab`，构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:30`）），由 `AbOta` 执行升级。OTA 方案通用的通道定义、帧格式、分块窗口与 ACK、状态机见 [AB OTA 方案](../ota/ab.md)。OpenRose 不实现 OTA，此节只作记录。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `ab` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:30`）、OTA 面板:401（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:401`） |
| 门槛 | 无（面板未配置 `sinceVersion`） | 静态 | OTA 面板:405（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:405`） |
| 前置条件 | 双耳出仓（`needCheckWwsMode` 默认 true；出仓校验失败报错 Code 10000） | 静态 | 默认参数（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:5330`）、出仓校验:5960（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:5960`） |
| BLE OTA 服务 | `0000ff12-0000-1000-8000-00805f9b34fb`（16 位 `ff12`） | 静态 | 配置:1230（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1230`） |
| BLE OTA 写特征 | `0000ff15-0000-1000-8000-00805f9b34fb`（16 位 `ff15`） | 静态 | 配置:1232（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1232`） |
| BLE OTA 通知特征 | `0000ff14-0000-1000-8000-00805f9b34fb`（16 位 `ff14`） | 静态 | 配置:1236（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1236`） |
| SPP OTA UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931`（复用控制 SPP UUID） | 静态 | 定制:694（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:694`） |
| 元数据 keyId | `openfeel_mk2` | 静态 | 定制:665（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:665`） |
