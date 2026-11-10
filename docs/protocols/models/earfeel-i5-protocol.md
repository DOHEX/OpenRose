# EarFeel-i5 控制协议

配置类 `EarfreeI5DeviceConfig`（cid 501），发送器 `Zt229Sender extends ZtSender`、接收器 `ZTReceiver`、处理器 `EarfreeI5ReceiveHandler`。APK 基线 RoseLink 3.8.0（versionCode 5310，APK（`rose_reverse/RoseLink_3.8.0.apk`），SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [ZT 协议族](../families/zt.md)。2026-10-18 在 Linux 上对单副 i5 完成 BLE 连接和 13 条初始化读取实机验证（实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`）、读取记录（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl`））；设置写入、持久化、寻机响铃和 OTA 均未实测。除特别注明外，本文的字节级结论来自静态分析。本文只写本型号与族文档不同的部分和本型号取值。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `525300010001` | `EARFREE-i5` | `EarFeel-i5` | 静态 | 构造（0x68d8ec）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:10`）、customConfigEarObject（0x8262a0）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:737`） |

系统设备名、厂商页面和历史文档还使用 `EarFree i5`、`EARFEEL i5`、`EARFREE i5`；实机扫描名称为 `ROSE EARFREE i5`（实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。CMIIT ID 为 `26J51U5A3756`（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:20`））。厂商页面同时使用 EARFEEL i5 与 EARFREE i5 名称，并确认其 RoseLink 控制与 LDAC 支持（[厂商产品页](https://global.roseselsa.com/products/earfree-i5-anc-tws-earbuds)）。

识别看 BLE 广播 `manufacturerData` 规范化后的前 12 位，不按可修改的设备名识别；算法与匹配顺序见族文档 [广播识别](../families/zt.md#广播识别)。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 否 | 静态 | 配置 `sppSupport="false"`（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:773`） |
| 控制 SPP UUID | 无 | 静态 | 同上；配置未写入 SPP UUID |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| OTA 服务 | `65786365-6c70-6f69-6e74-2e636f6d0000` | 静态 | normalBleOtaServiceConfig（0x824cf8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`）、实机服务发现（`rose_reverse/analysis/i5_hardware_validation.md`） |
| OTA 写入/通知特征 | `65786365-6c70-6f69-6e74-2e636f6d0001` | 静态 | 同上 |

控制服务 `011bf5da-0000-1000-8000-00805f9b34fb` 与 `7777`/`8888` 特征已在实机服务发现中出现，OTA 服务也仅发现、未执行升级（实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。默认传输的其余细节见族文档 [默认传输](../families/zt.md#默认传输)。

## 初始化

初始化目标：GDT+0xd8ba 按 cid 501 分派到表项 60079（分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60080`））→ `EarfreeI5DeviceConfig::sendInitCommands`（0x8e25cc（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:788`））。共 13 条查询，电量随 `getInfo` 返回，不需要 HeadFeel 的 `getBattery=0103`。13 条均已在 2026-10-18 的单副 i5 上实机读回；初始化分派与共享查询帧的族级说明见 [初始化和读取](../families/zt.md#初始化和读取)。

| 次序 | App 方法 | 完整 TX hex | 读回 Tag | 状态 | 出处 |
|---:|---|---|---|---|---|
| 1 | `getInfo` | `08 EE 00 00 00 01 01 0A 00 02` | `0101` | 实机 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`）、实机 L3（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:3`） |
| 2 | `getAnc` | `08 EE 00 00 00 06 02 0A 00 08` | `0602` | 实机 | getAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:801`）、实机 L6（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:6`） |
| 3 | `getEq` | `08 EE 00 00 00 02 01 0A 00 03` | `0201` | 实机 | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2731`）、实机 L8（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:8`） |
| 4 | `getGameMode` | `08 EE 00 00 00 06 03 0A 00 09` | `0603` | 实机 | getGameMode（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2392`）、实机 L10（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:10`） |
| 5 | `getAudioLdac` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 实机 | getAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`）、实机 L12（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:12`） |
| 6 | `getVersion` | `08 EE 00 00 00 0D 01 0A 00 0E` | `0d01` | 实机 | getVersion（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2796`）、实机 L15（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:15`） |
| 7 | `getKey` | `08 EE 00 00 00 04 01 0A 00 05` | `0401` | 实机 | getKey（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1936`）、实机 L17（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:17`） |
| 8 | `getTouch` | `08 EE 00 00 00 0A 01 0A 00 0B` | `0a01` | 实机 | getTouch（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1407`）、实机 L19（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:19`） |
| 9 | `getAncList` | `08 EE 00 00 00 06 05 0A 00 0B` | `0605` | 实机 | getAncList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:866`）、实机 L22（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:22`） |
| 10 | `getEarDetection` | `08 EE 00 00 00 0C 01 0A 00 0D` | `0c01` | 实机 | getEarDetection（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1206`）、实机 L23（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:23`） |
| 11 | `getAncLevel` | `08 EE 00 00 00 06 07 0A 00 0D` | `0607` | 实机 | getAncLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4598`）、实机 L25（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:25`） |
| 12 | `getAncTransLevel` | `08 EE 00 00 00 06 04 0A 00 0A` | `0604` | 实机 | getAncTransLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4458`）、实机 L27（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:27`） |
| 13 | `getDingLevel` | `08 EE 00 00 00 0E 03 0A 00 11` | `0e03` | 实机 | getDingLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4736`）、实机 L29（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:29`） |

`getConnectMul` 在 i5 的父类中调用 `getAudioLdac`，仍发送 `08 01`，不是 HeadFeel 的独立 `08 02` 查询。

## 电量

`0101` 通知，左/右/盒三槽；偏移布局（byte11 左、byte12 右、byte13/14 左右充电标志、byte15 盒，`FF` 表示盒数据缺失）与族文档相同（静态），见 [电量](../families/zt.md#电量)。

i5 handler 的显示逻辑：原 App 对左/右只显示 `1..100`，`0` 或超范围显示 `-`；盒值 `FF` 不更新盒显示，有盒数据时还需任意一耳的充电标志为 true 且值在 `1..100` 内，才显示并缓存盒电量。第三方应保留 `raw_percent`、`display_percent` 和 `case_present`，避免把无数据写成 0%。实机回包 `09 ff 00 00 01 01 01 11 00 00 01 64 62 00 00 ff e2` 对应左 100%、右 98%、盒缺失（实机 L3（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:3`））；`case_percent` 为 null 表示盒电量未提供。证据：i5 电量显示逻辑（0xe41678）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:698`）。

## 设置命令

BODY 从 group 开始，长度与校验按族文档 [发送帧](../families/zt.md#发送帧) 自动生成；表中“取值”是 App 在本型号界面提供的线值，不代表 Sender 接受的全部值。设置返回与读取确认规则见族文档 [设置命令与参数](../families/zt.md#设置命令与参数)。i5 的各控件构造（包括 OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`））都没有传 `sinceVersion`，固件门槛均为 `—`。除寻机的 `0e02`（没有独立查询，实机未触发响铃）外，其余 12 个读回 Tag 均已在 2026-10-18 的初始化读取中出现，但设置写入本身没有实机证据，因此写入行状态均为静态；设备是否接受在实测前未知。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | `getInfo` | `01 01` | 左/右/盒 | — | `0101` | 实机 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`）、实机 L3（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:3`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeWind`、`setNoiseModeTrans` | `06 82` | ANC=`01 00 00 00`；普通=`00 01 00 00`；抗风=`00 00 01 00`；通透=`00 00 00 01` | — | `0602` | 静态 | 四分支（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:501`） |
| ANC 等级 | `setAncLevel` | `06 87` | UI 1/3/5 = `00/01/02` | — | `0607` | 静态 | Zt229Sender::setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523`） |
| 通透等级 | `setTransLevel` | `06 84` | UI 1/3/5 = `00/01/02` | — | `0604` | 静态 | Zt229Sender::setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4383`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight` | `02 81` | POP=`00`、HIFI=`01`、ROCK=`02`、LIGHT=`03` | — | `0201` | 静态 | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2664`）、i5 EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:164`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `06 83` | 开=`00`、关=`01` | — | `0603` | 静态 | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2325`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `08 81` | 开=`00`、关=`01`；关闭同时切 LDAC | — | `0801` | 静态 | setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2932`）、关闭→LDAC（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2216`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac` | `08 81` | AAC/SBC=`00`、LDAC=`01` | — | `0801` | 静态 | setAudioAacSbc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2131`） |
| 触摸绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `04 81` | `[side][gesture][action]`；side 左=`00` 右=`01`；gesture 单=`00` 双=`01` 三=`02` 长=`03`；action 见族文档 [触摸绑定](../families/zt.md#触摸绑定) | — | `0401` | 静态 | setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1878`）、i5 动作列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:60`） |
| 触摸功能 | `setTouchOn`、`setTouchOff` | `0A 81` | 开=`00`、关=`01` | — | `0a01` | 静态 | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1340`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `0E 82` | 左/原 App 双耳=`00 01`；右=`01 01`；停止=`03 00` | — | `0e02` | 静态 | setFindDeviceLeft（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:208`）、setFindOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4349`） |
| 佩戴检测 | `setEarDetectionOn`、`setEarDetectionOff` | `0C 81` | 开=`00`、关=`01` | — | `0c01` | 静态 | setEarDetectionOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1139`） |
| 提示音等级 | `setDingLevel` | `0E 83` | UI `1..5` = `00..04` | — | `0e03` | 静态 | Zt229Sender::setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4663`） |
| 降噪轮换 | `setAncLine` | `06 85` | `[普通][通透][抗风][ANC]`，每项 `00/01` | — | `0605` | 静态 | setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`） |
| 星芒业务入口 | 范围外 | — | — | — | — | 未知 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:340`） |
| OTA | 范围外 | — | — | — | — | 未知 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:200`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:282`） |

星芒业务是 App 的业务入口；本次未在 i5 Sender 中找到与之对应的独立控制报文，不将其当成耳机硬件能力（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:340`））。

i5 的控件列表限定了可选值：噪声 4 种、EQ 4 个预设、音频模式 2 种（噪声列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:131`）、EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:164`）、音频列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:244`））；`setAudioLhdc`、`setAudioLdacLhdc`、`setEqCustom`、`setEqCustomData` 不构成本型号能力。

### 能力摘要

以下为 i5 配置与 Sender 的静态能力摘要；逐控件 BODY/取值见上表，实机范围见文首。

| 能力 | i5 可实现的控制/读取 | 约束 |
|---|---|---|
| 电量 | 左耳、右耳、充电盒；左右充电状态 | 盒值 `FF` 表示缺失；App 只在任一耳充电时显示盒百分比 |
| 降噪 | ANC、关闭/普通、抗风、通透 | 单独切换四种模式 |
| 等级 | ANC 三档、通透三档 | UI `1/3/5` 对应线值 `0/1/2` |
| EQ | POP、HIFI、ROCK、LIGHT | 本型号未发现自定义 EQ 控制入口 |
| 触摸 | 开关；左右单击、双击、三击、长按绑定 | 每槽 8 种动作 |
| 游戏模式 | 开关、读取 | 线值 `00` 开、`01` 关 |
| 佩戴检测 | 开关、读取 | 控制的是检测功能是否启用，回包不是实时佩戴事件 |
| 音频模式 | AAC/SBC、LDAC | 与多点功能共用状态；不传输音频数据 |
| 多点连接 | 开关、读取 | 开启 → AAC/SBC；关闭 → LDAC |
| 寻机 | 左、右、停止；原 App 的“双耳”入口 | “双耳”发送与“左耳”完全相同的命令，实际响铃范围待测 |
| 提示音音量 | 五档、读取 | UI `1..5` 对应线值 `0..4` |
| 降噪轮换 | 四种模式是否参与触摸循环 | flag 顺序不同于模式切换命令 |
| 固件 | 版本读取；WQ OTA | 独立 OTA GATT 通道；见 [OTA](#ota) |
| 提示音语言 | 中文/英文固件切换 | 通过 OTA 固件语言选择，无已证实的 i5 独立普通控制命令 |

## 接收字段

i5 的字段处理差异集中在 `0e02` 的 UI 左右标志交叉赋值（见下表）、`0e03` 的等级加 1（[族文档接收 Tag 表](../families/zt.md#接收-tag-与处理)）和 `0101` 的电量显示（[电量](#电量)）。其余 Tag 的字段读取、最小长度和异常处理同族文档（[通知解析](../families/zt.md#通知解析)）。`0e02` 的 byte9 是方向 `00` 左、`01` 右、`02` 双耳，byte10 是状态 `00/01`。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0e02` | 寻机方向/状态 → UI 左右 finding 标志 | 交叉赋值，见下表；`00 00` 末尾还有一次全部置 false 的特例 | 静态 | findMeReceived（0xd15fa8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:10`） |

| 通知 payload | 左 finding | 右 finding |
|---|---:|---:|
| `00 01` | true | false |
| `00 00` | false | false（末尾特例覆盖） |
| `01 01` | false | true |
| `01 00` | true | false |
| `02 01` | true | true |
| `02 00` | false | false |

这个交叉赋值是 App 行为，实际硬件是否同时切换另一耳响铃未验证；方向 `03` 未在此 handler 分支中处理。`0101` 的电量显示逻辑见[电量](#电量)，`0e03` 的 i5 handler 加 1 与 `0602` 的尾部精确匹配见族文档。

## 未实现与未知

以下缺口的查证范围是 i5 配置类、`Zt229Sender`/`ZtSender`、`ZTReceiver`/`EarfreeI5ReceiveHandler` 和 i5 实机记录；没有设置写入或 OTA 实机样本。

- 设置写入、持久化、寻机响铃范围和 OTA 没有实机验证；本轮实机只发送初始化查询（实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。
- 控件到 Sender 的逐项调用没有单独反查：设置表的 BODY 与取值来自 `Zt229Sender` 覆写和 i5 配置的 `Allocate*WidgetModel` 列表（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:113`）），共享控件类如何回调 Sender 只按方法名对应，与族文档“按方法名推断”的说明相同。
- `setFindDeviceBoth` 原样调用 `setFindDeviceLeft`；接收器虽能解析方向 `02`，不能证明发送 `02 01` 可启动双耳寻机（[音频、多点和寻机](../families/zt.md#音频多点和寻机)）。
- `0602` 降噪模式回包按 byte9 起的全部尾部精确匹配，第五字节字段性质未证明；未知尾部 App 会退回普通模式（[降噪模式尾部的精确匹配](../families/zt.md#降噪模式尾部的精确匹配)）。
- RX 固定前缀、byte7/8 长度、末字节求和或 CRC 未验证；不能按 TX 规则筛掉可能合法的 RX（[分派和验证边界](../families/zt.md#分派和验证边界)）。
- `0e02` 的停止方向 `03` 未在 i5 handler 分支中处理；设备对停止回包的实际方向未知（[固件版本与寻机](../families/zt.md#固件版本与寻机)）。
- 提示音语言切换没有独立的普通控制命令，只能通过 WQ OTA 选择语言固件；完整 OTA 流程、E2 偏移单位、设备签名/拒绝条件和中断恢复未验证（[WQ 未知与缺口](../ota/wq.md#未知与缺口)）。
- `getInfoFromRawData` 的 zt 分支会把 rawData 片段格式化成地址样式，但未证明与广播中的经典地址或连接地址的对应关系；连接使用系统扫描对象地址（[广播识别](../families/zt.md#广播识别)）。
- 自定义 EQ 不在 i5 配置中；Sender 的 `setEqCustom`/`setEqCustomData` 不构成本型号能力（EQ 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:164`））。

## OTA

i5 使用 WQ OTA（构造 OTA 类型（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:30`））：独立 GATT 服务 `65786365-6c70-6f69-6e74-2e636f6d0000` 和读写/通知特征 `65786365-6c70-6f69-6e74-2e636f6d0001`（见“传输”表），与普通 `08 EE` 控制帧无关。OTA 面板开启 `hasEnglish` 和 `ota_upgrade_language_switch`（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`）、语言开关（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:389`）），提示音语言切换通过选择对应语言固件再升级实现，没有普通控制通道的语言设置命令。面板没有写 `sinceVersion`，没有可解码的固件门槛。

`keyId` 来自设备 `trackingName`（`EARFREE-i5`）。2026-10-14 以 `keyId="earfree-i5"`、`pid=""`、`tag=1`、`clientVersion="5310"` 匿名请求，`lang="zh"` 与 `"en"` 均返回版本 125 和下载 URL（中文响应（`rose_reverse/analysis/ota_links/i5-zh-client5310.json`）、英文响应（`rose_reverse/analysis/ota_links/i5-en-client5310.json`））；实机 `0d01` 读回的 App 版本文本也是 `125`（实机 L15（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:15`））。元数据请求、下载链、字节格式、窗口与 ACK、状态机见 [WQ OTA 方案](../ota/wq.md)，未闭合项见 [WQ 未知与缺口](../ota/wq.md#未知与缺口)。OpenRose 不实现 OTA；本节只作记录。
