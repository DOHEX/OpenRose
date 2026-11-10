# EarFeel i7 控制协议

配置类 `EarfeelI7DeviceConfig`（cid 523，继承 `BaseDeviceConfig`，不是 `RsCommonV2DeviceConfig` 的子类），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common V2 协议族](../families/rs-common-v2.md)。Sender 为 `RsCommonV2Sender`，Receiver 为 `RsCommonV2Receiver`，handler 为 `RsCommonV2ReceiveHandler`（class id 421）。没有 i7 实机记录：以下均为静态分析，只在离线编解码测试中核对过帧和字段，没有连接耳机、没有抓包，也没有执行 OTA；设备是否接受各设置未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `b8e5290f` | `earfeel-i7` | `EarFeel i7` | 静态 | 构造:10（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:10`）、定制:1024（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1024`） |

零售名 `EarFeel i7`（厂商站点写作 `EARFEEL i7`），内部名 `earfeel-i7`；没有找到其他旧名/别名条目。识别按规范化 manufacturer 数据的前 8 个十六进制字符 `b8e5290f`，不按设备名。CMIIT ID 为 `26J51U5AF317`（构造:20（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:20`））。厂商站点也列出 EARFEEL i7，仅用于核对产品名称，字节协议以 APK 为证：[厂商产品列表](https://global.roseselsa.com/collections/wireless-earbuds)。

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
| OTA SPP UUID | `66666666-6666-6666-6666-666666666666` | 静态 | customConfigEarObject:1054（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1054`）、[BES 方案](../ota/bes.md#通道与-uuid) |
| BLE OTA 服务 | `66666666-6666-6666-6666-666666666666` | 推断 | 字段:1078（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1078`）、[归类依据](../ota/bes.md#通道与-uuid) |
| BLE OTA 写/通知特征 | `77777777-7777-7777-7777-777777777777` | 推断 | 字段:1080（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1080`）、[归类依据](../ota/bes.md#通道与-uuid) |

同族默认的取值与证据见 [族文档的默认传输表](../families/rs-common-v2.md#默认传输)。i7 在 `customConfigEarObject` 直接写 SPP 控制 UUID 和 OTA 字段，不调用 `normalBleOtaServiceConfig`；把 `6666…`/`7777…` 分别解释为 BLE OTA 服务和写/通知特征属于推断，未做 OTA 服务实机发现。控制通道选择、MTU、订阅和队列规则同族文档。

## 初始化

| 分派 | 表项 | 目标 | 发送的查询 | 状态 | 出处 |
|---|---|---|---|---|---|
| GDT+0xd8ba，cid 523 | 60101（`table.tsv` 第 60102 行） | `0x8e2444` | 仅 `getInfo` | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60102`）、补核（`rose_reverse/analysis/i7_dispatch_findings.md`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`） |

目标函数只调用 `RCommander.getInfo`，不查询自定义 EQ `3E`；AOT 把共享闭包标成 SportFree 匿名 closure，不代表 i7 使用 SportFree 配置。`getInfo` 的 29 个 ID 和完整 35 字节帧见族文档 [初始化与查询](../families/rs-common-v2.md#初始化与查询)。客户端额外补读 `FA 3E` 是客户端选择，不是官方初始化。

## 电量

| 布局 | 槽 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 左右耳与仓 | `left`、`right`、`charge` | 共享 `0C` 字段的三个字节；百分比、入仓 flag、仓缓存与新鲜度规则同族 | 静态 | BatteryType:130（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:130`）、[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存) |

仓 byte 的 bit7 只作为 flag 保存，不能据此称为“充电中”；仓电量是否有效取决于入仓耳读数，规则同族。具体语义仍是未知。

## 设置命令

i7 配置的每个控件一行。BODY 只写命令体，完整帧的 `FF SS LL BODY CC AA` 封装、序号和校验见族文档 [发送帧](../families/rs-common-v2.md#发送帧)。除 AI 入口外，命令体和配置都是静态事实；设备是否接受仍未知。“读回”列的 `getInfo` 表示该 ID 在共享批量查询中，App 不保证每次设置后都单独查询，最终状态以通知回包为准，`—` 表示没有查询方法。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 左、右、仓三槽 | — | `getInfo`（`0c`） | 静态 | 显示控件:127（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:127`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind` | `09 01`、`09 02`、`09 03`、`09 04` | ANC=`01`；普通=`02`；通透=`03`；抗风=`04` | — | `getInfo`（`09`） | 静态 | 控件:185（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:185`）、[族命令](../families/rs-common-v2.md#设置命令) |
| ANC 等级 | `setAncLevel` | `2c value` | 低/中/高=`01/03/05` | — | `getInfo`（`2c`） | 静态 | 控件:208（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:208`）、setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1510`） |
| 通透等级 | `setTransLevel` | `2d value` | 低/中/高=`01/03/05` | — | `getInfo`（`2d`） | 静态 | 控件:208（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:208`）、setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1450`） |
| 自动 ANC | `setAncAutoOn` | `09 05` | 开=`05`；关=fallback `09 04` | — | `getInfo`（`09`） | 静态 | 控件:747（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:747`）、[关闭行为](../families/rs-common-v2.md#自动-anc-关闭) |
| 动态自动 ANC | `setAncAutoDynamicOn` | `09 07` | 开=`07`；关=fallback `09 04` | — | `getInfo`（`09`） | 静态 | 控件:760（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:760`）、[关闭行为](../families/rs-common-v2.md#自动-anc-关闭) |
| 降噪轮换 | `setAncLine` | `36 [ANC][抗风][普通][通透]` | 每项 `00/01`；i7 界面顺序 普通/通透/抗风/ANC | — | `getInfo`（`36`） | 静态 | 控件:867（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:867`）、[族轮换](../families/rs-common-v2.md#anc-轮换) |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight`、`setEqEap` | `2a 00`、`2a 01`、`2a 02`、`2a 03`、`2a 06` | POPS=`01`、HIFI=`00`、ROCK=`02`、LIGHT=`03`、EAP=`06` | EAP 152 | `getEq`（`2a`） | 静态 | 控件:272（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:272`）、EAP 模型（`rose_reverse/analysis/blutter/objs.txt:1711`） |
| 自定义 EQ 选择 | `setEqCustom` | `2a 04` | custom=`04` | — | `getEq`（`2a`） | 静态 | 控件:272（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:272`）、setEqCustom（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4003`） |
| 自定义 EQ 数据 | `setEqCustomData` | `3e` + 10 字节 | 10 个 −6..+6 整数，符号位加绝对值 | — | `getCustomEq`（`3e`） | 静态 | 控件:272（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:272`）、[族 EQ 编码](../families/rs-common-v2.md#自定义-eq) |
| 增益 | `setGainLevel`、`getGainLevel` | `45 value`；查询 `fa 45` | 低/中/高=`00/01/02` | 213 | `getGainLevel`（`45`） | 静态 | 控件:333（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:333`）、setGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1914`） |
| 听力保护 | `setHearingProtection` | `46 value` | 关闭=`00`；75/80/85/90/95/100 dB=`01..06` | 213 | `getInfo`（`46`） | 静态 | 控件:355（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:355`）、setHearingProtection（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1855`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e 01`、`0e 00` | 开=`01`、关=`00` | — | `getInfo`（`0e`） | 静态 | 控件:379（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:379`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 01`、`32 00` | 开=`01`、关=`00` | — | `getInfo`（`32`） | 静态 | 控件:392（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:392`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLc3` | `2b 00`、`2b 01`、`2b 02`、`2b 03` | AAC/SBC=`00`、LDAC=`01`、LHDC=`02`、LC3=`03` | — | `getInfo`（`2b`） | 静态 | 控件:508（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:508`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `07 00`、`07 01` | 开=`00`、关=`01` | — | `getInfo`（`07`） | 静态 | 控件:530（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:530`）、setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） |
| 触控按键 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | 左耳 `01/02/03/04`、右耳 `11/12/13/14`；action 0 无、1 播放暂停、2 上一首、3 下一首、4 音量+、5 音量−、6 游戏、7 语音助手、8 ANC 循环 | — | `getInfo`（`01`） | 静态 | 控件:530（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:530`）、[族触摸动作](../families/rs-common-v2.md#触摸动作) |
| 佩戴检测 | `setEarDetectionOn`、`setEarDetectionOff` | `08 00`、`08 01` | 开=`00`、关=`01` | — | `getInfo`（`08`） | 静态 | 控件:696（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:696`）、setEarDetectionOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2114`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2f 01`、`2f 02`、`2f 03`、`2f 04` | 左=`01`、右=`02`、双耳=`03`、停=`04` | — | `getInfo`（`2f`）；回包含 05/06 单耳停止 | 静态 | 控件:600（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:600`）、[族寻机](../families/rs-common-v2.md#寻机) |
| 提示音 | `setDingLevel` | `2e value` | 1..5=`01..05`；关闭=`00` | 304（仅关闭选项） | `getInfo`（`2e`） | 静态 | 控件:810（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:810`）、关闭判定:900（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:900`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置:243（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:243`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置:539（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:539`） |
| AI 业务入口 | `setStarburstSdk`、`setAiPageState` | `41 44 42 41 4f` + SDK 字节；`setAiPageState` 带页面 id 和 active 标志 | 入口前缀 ASCII `ADBAO` | 300 | — | 未知 | 配置:453（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:453`）、setStarburstSdk:66（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:66`）、setAiPageState:10（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:10`） |
| OTA | 范围外 | — | 见 [OTA](#ota) | — | — | 静态 | 配置:609（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:609`）、[BES 方案](../ota/bes.md) |

门槛都是 Dart Smi 右移一位后的整数，比较对象是固件显示文本转成的十进制整数，不是系统 App 版本或 semantic version；解释见族文档 [门槛](../families/rs-common-v2.md#门槛)。EAP 预设的对象池 `EarVersionCheck off_8_Smi=0x130` 是 152；增益与听力保护控件 `field7=0x1aa` 是 213；提示音关闭 `field27=0x260` 是 304；AI 入口容器 `sinceVersion=0x258` 是 300。提示音 1..5 本身不受 304 限制。

ANC/通透等级的三档到 `01/03/05` 的对应是按 i7 三档控件和 Sender 接受值归纳（推断）；Sender 直接透传 1 字节，不做 i5 的 1/3/5→0/1/2 换算，`2c`/`2d` 的完整接受集是 1..5。增益线值 `00/01/02` 只代表三档，没有具体 dB 标定；听力保护的 75..100 dB 是界面阈值标签，不是声压测量。`08` 是佩戴检测功能开关，不是实时佩戴事件。降噪轮换的线上顺序与界面顺序不同，i7 模型按 普通/通透/抗风/ANC 定义，编码见族文档 [ANC 轮换](../families/rs-common-v2.md#anc-轮换)。自动/动态自动 ANC 没有独立关闭命令，关闭时发送抗风 fallback `09 04` 并乐观显示 ANC，最终状态以 `09` 回包为准。自定义 EQ 的 `2A 04` 选槽、`3E` 十字节编码和频段标签同族文档 [自定义 EQ](../families/rs-common-v2.md#自定义-eq)。音频模式与多点是独立命令，不互相耦合；实际编码协商由系统和耳机负责，不能据独立报文断言所有 Codec 与多点组合都能同时工作。寻机的双耳是独立命令，回包含单耳停止事件，不能把一次回包当成完整双耳状态。

## 接收字段

所有 Tag 的处理与取值同族文档 [字段表](../families/rs-common-v2.md#字段表)，没有 i7 专属分支；i7 使用共享 `RsCommonV2ReceiveHandler`（class id 421）。`09` 的 `06` 深度 ANC 可被共享 receiver 解析，但 i7 配置没有深度 ANC 控件，不能据此增加能力。电量槽布局的型号差异见 [电量](#电量)；`0C` 仓 bit7 语义、`34` 的 main/sub MAC 与左右耳对应仍未确定。

## 未实现与未知

- 没有 i7 实机记录：所有设置写入、持久化、返回时序、广播原文和 SPP 配对/SDP 都未验证；状态为静态的分析不能代替设备确认。
- 广播只有规范化规则的静态推导，没有 i7 广播样本；`H` 中 bytes 4..5、8..10 的语义此路径未读，经典地址字段未实测。
- `0C` 仓 byte 的 bit7 只保留为 flag，不能确定“充电中”；仓电量何时算有效、缓存如何显示同族文档，仍无实机样本。
- `34` 的 12 字节按 main/sub 两个 6 字节 MAC 原序输出，没有证据表明前 6 字节是左耳或右耳。
- `09 06` 深度 ANC 只在共享 receiver 和 Sender 中存在，i7 没有对应 UI；不要因为能解析或 Sender 有方法就增加能力。
- i7 配置没有提示音语言控件；共享 Sender 的语言方法、BES 页的本地化不等于 i7 固件支持语言切换，OTA 也没有 `ota_upgrade_language_switch`。
- 未配置的空间音频、耳道自适应、轻拍控制、低音增强、独立麦克风、录音等控件不进入本型号配置；“App 未配置”只是静态事实，不能证明硬件不支持。
- AI 业务入口有 `sinceVersion=300`、`needConnect=true`，但没有恢复 SDK 内层消息和云业务；它不是基础耳机控制，读回和失败语义未知。
- 自定义 EQ 的界面频段标签和滤波器参数未与声学测量对应；`3E` 不携带频点、Q 或槽位，不能从 App 推断实际滤波中心。
- OTA 的固件兼容/签名、配置尾部 3 字节含义、未知 opcode 的回包长度、GATT 跨帧拼接、断点续传、左右耳角色、`7B` side 语义和覆盖后复位都未验证；详见 [BES 方案的未知与缺口](../ota/bes.md#未知与缺口)。

## OTA

i7 的 OTA 路由为 BES（`DeviceOtaType.bes`，枚举索引 2），OTA 面板 `needCheckWwsMode=true`，面板没有写 `sinceVersion`，因此没有可解码的固件版本门槛。配置没有独立 LangItem，也没有 OTA 语言开关；OTA 面板不带 `hasEnglish`。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `bes` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:30`）、OTA 面板:609（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:609`） |
| 门槛 | 无（面板未写 `sinceVersion`） | 静态 | OTA 面板:609（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:609`）、[BES 型号表](../ota/bes.md) |
| needCheckWwsMode | `true` | 静态 | OTA 面板:618（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:618`） |
| 元数据 keyId | `earfeel-i7` | 静态 | [BES 固件来源](../ota/bes.md#固件来源与完整性) |
| 服务端样本 | 2026-10-14 匿名请求返回版本 304（资源名含 V3.0.4）；`clientVersion=4150` 仍通过，`4149` 被拒 | 静态 | [BES 固件来源](../ota/bes.md#固件来源与完整性)、[OTA 更新包链接](../ota-package-links.md) |

OTA SPP/BLE 的 UUID 见 [传输](#传输)。帧格式、传输窗口、分段与 ACK、状态机、固件下载链和完整缺口都在 [BES 方案](../ota/bes.md)，本文不重复。i7 的 BES 会话细节没有单独恢复，也没有实机 OTA 记录。
