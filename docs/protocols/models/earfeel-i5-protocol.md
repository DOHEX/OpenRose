# EarFeel-i5 控制协议

配置类 `EarfreeI5DeviceConfig`（cid 501），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [ZT 协议族](../families/zt.md)。2026-10-18 在 Linux 上对单副 i5 完成 BLE 连接和 13 条初始化读取实机验证；设置写入、持久化、寻机响铃和 OTA 均未实测。除特别注明外，本文的字节级结论来自静态分析。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `525300010001` | `EARFREE-i5` | `EarFeel-i5` | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:10`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:737`） |

系统设备名、厂商页面和历史文档还使用 `EarFree i5`、`EARFEEL i5`、`EARFREE i5`；实机扫描名称为 `ROSE EARFREE i5`（实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。CMIIT ID 为 `26J51U5A3756`。识别看 BLE 广播 `manufacturerData` 的前 12 位（算法见 [§2.1](#21-广播识别)），不要按可修改的设备名识别。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | `011bf5da-0000-1000-8000-00805f9b34fb` | 静态 | normalBleServiceConfig（0x74990c）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、[§2.2](#22-gatt-uuid-与写入属性) |
| 写特征 | `00007777-0000-1000-8000-00805f9b34fb` | 静态 | 同上；已由实机发现服务确认 |
| 通知特征 | `00008888-0000-1000-8000-00805f9b34fb` | 静态 | 同上；已由实机发现服务确认 |
| SPP 是否允许 | 否 | 静态 | 配置 `sppSupport="false"`（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:773`） |
| 控制 SPP UUID | 无 | 静态 | 配置未写入 SPP UUID |
| 广播中的经典地址 | 未解析；连接使用系统扫描对象的 remote id/address | 未知 | [§2.1](#21-广播识别)、[§2.3](#23-连接流程) |
| 写入间隔 | 正常 100 ms；OHOS 分支 350 ms | 静态 | [§2.3](#23-连接流程) |
| OTA 服务 | `65786365-6c70-6f69-6e74-2e636f6d0000` | 静态 | normalBleOtaServiceConfig（0x824cf8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`）、实机服务发现（`rose_reverse/analysis/i5_hardware_validation.md`） |
| OTA 写入/通知特征 | `65786365-6c70-6f69-6e74-2e636f6d0001` | 静态 | 同上 |

普通 i5 报文最长 14 字节，不依赖大 MTU；App 公共 BLE 层尝试请求 MTU 512，协商结果由设备和系统决定。写特征支持 `writeWithoutResponse` 时使用无响应写，否则使用有响应写；GATT 写响应不等同业务状态回包。

## 初始化

按 [§4](#4-初始化和读取命令) 的顺序发送。13 条查询均已在 2026-10-18 的单副 i5 上实机验证；电量随 `getInfo` 返回，不需要套用 HeadFeel 的 `getBattery=0103`。

| 次序 | App 方法 | 完整 TX hex | 读回 Tag | 状态 | 出处 |
|---:|---|---|---|---|---|
| 1 | `getInfo` | `08 EE 00 00 00 01 01 0A 00 02` | `0101` | 实机 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`） |
| 2 | `getAnc` | `08 EE 00 00 00 06 02 0A 00 08` | `0602` | 实机 | getAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:801`） |
| 3 | `getEq` | `08 EE 00 00 00 02 01 0A 00 03` | `0201` | 实机 | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2731`） |
| 4 | `getGameMode` | `08 EE 00 00 00 06 03 0A 00 09` | `0603` | 实机 | getGameMode（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2392`） |
| 5 | `getAudioLdac` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 实机 | getAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`） |
| 6 | `getVersion` | `08 EE 00 00 00 0D 01 0A 00 0E` | `0d01` | 实机 | getVersion（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2796`） |
| 7 | `getKey` | `08 EE 00 00 00 04 01 0A 00 05` | `0401` | 实机 | getKey（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1936`） |
| 8 | `getTouch` | `08 EE 00 00 00 0A 01 0A 00 0B` | `0a01` | 实机 | getTouch（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1407`） |
| 9 | `getAncList` | `08 EE 00 00 00 06 05 0A 00 0B` | `0605` | 实机 | getAncList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:866`） |
| 10 | `getEarDetection` | `08 EE 00 00 00 0C 01 0A 00 0D` | `0c01` | 实机 | getEarDetection（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1206`） |
| 11 | `getAncLevel` | `08 EE 00 00 00 06 07 0A 00 0D` | `0607` | 实机 | getAncLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4598`） |
| 12 | `getAncTransLevel` | `08 EE 00 00 00 06 04 0A 00 0A` | `0604` | 实机 | getAncTransLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4458`） |
| 13 | `getDingLevel` | `08 EE 00 00 00 0E 03 0A 00 11` | `0e03` | 实机 | getDingLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4736`） |

`getConnectMul` 在 i5 的父类中调用 `getAudioLdac`，仍发送 `08 01`，不是 HeadFeel 的独立 `08 02` 查询。初始化顺序证据：sendInitCommands（0x8e25cc）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:788`）。

## 电量

`0101` 通知，左/右/盒三槽：

| 偏移 | App 解释 |
|---|---|
| 9–10 | 本次电量处理函数未消费；保持 raw |
| 11 | 左耳电量，完整字节无符号整数 |
| 12 | 右耳电量 |
| 13 | 左耳充电状态，`00=false`，其他=true |
| 14 | 右耳充电状态，`00=false`，其他=true |
| 15 | 盒电量，`FF` 表示没有盒数据 |

原 App 对左/右只显示 `1..100`，`0` 或超范围显示 `-`；盒值 `FF` 不更新盒显示，有盒数据时还需任意一耳充电标志为 true 且值在 `1..100` 内才显示并缓存。实机回包 `09 ff 00 00 01 01 01 11 00 00 01 64 62 00 00 ff e2` 对应左 100%、右 98%、盒缺失。细节和缓存规则见 [§6.2](#62-电量)。

## 设置命令

BODY 从 group 开始，长度按 [§3](#3-常规发送帧) 自动生成；表中“取值”是 App 在本型号界面提供的线值，不代表 Sender 接受的全部值。i5 的设置写入没有实机证据，状态均为静态；设备是否接受在实测前未知。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | `getInfo` | `01 01` | 左/右/盒 | — | `0101` | 实机 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeWind`、`setNoiseModeTrans` | `06 82` | ANC=`01 00 00 00`；普通=`00 01 00 00`；抗风=`00 00 01 00`；通透=`00 00 00 01` | — | `0602` | 静态 | 四分支（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:501`） |
| ANC 等级 | `setAncLevel` | `06 87` | UI 1/3/5 = `00/01/02` | — | `0607` | 静态 | Zt229Sender::setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523`） |
| 通透等级 | `setTransLevel` | `06 84` | UI 1/3/5 = `00/01/02` | — | `0604` | 静态 | Zt229Sender::setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4383`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight` | `02 81` | POP=`00`、HIFI=`01`、ROCK=`02`、LIGHT=`03` | — | `0201` | 静态 | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2664`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `06 83` | 开=`00`、关=`01` | — | `0603` | 静态 | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2325`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `08 81` | 开=`00`、关=`01`；关闭同时切 LDAC | — | `0801` | 静态 | setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2932`）、关闭→LDAC（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2216`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac` | `08 81` | AAC/SBC=`00`、LDAC=`01` | — | `0801` | 静态 | setAudioAacSbc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2131`） |
| 触摸绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `04 81` | `[side][gesture][action]`；side 左=`00` 右=`01`；gesture 单=`00` 双=`01` 三=`02` 长=`03`；action 见 [§5.1](#51-触摸绑定) | — | `0401` | 静态 | setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1878`） |
| 触摸功能 | `setTouchOn`、`setTouchOff` | `0A 81` | 开=`00`、关=`01` | — | `0a01` | 静态 | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1340`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `0E 82` | 左/原 App 双耳=`00 01`；右=`01 01`；停止=`03 00` | — | `0e02` | 静态 | setFindDeviceLeft（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:208`）、setFindOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4349`） |
| 佩戴检测 | `setEarDetectionOn`、`setEarDetectionOff` | `0C 81` | 开=`00`、关=`01` | — | `0c01` | 静态 | setEarDetectionOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1139`） |
| 提示音等级 | `setDingLevel` | `0E 83` | UI `1..5` = `00..04` | — | `0e03` | 静态 | Zt229Sender::setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4663`） |
| 降噪轮换 | `setAncLine` | `06 85` | `[普通][通透][抗风][ANC]`，每项 `00/01` | — | `0605` | 静态 | setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`） |
| 星芒业务入口 | 范围外 | — | — | — | — | 未知 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:340`） |
| OTA | 范围外 | — | — | — | — | 未知 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:200`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:282`） |

## 接收字段

本型号没有与族文档不同的接收字段处理；`0101`、`0602`、`0201`、`0603`、`0801`、`0d01`、`0401`、`0a01`、`0605`、`0c01`、`0607`、`0604`、`0e03` 已在实机初始化读取中确认可解析。字段布局、分派边界和异常处理见 [ZT 族文档](../families/zt.md#接收-tag-与处理) 与 [§6](#6-通知解析)。

## 未实现与未知

以下缺口的查证范围是 i5 配置类、Sender/Receiver/Handler、i5 实机记录和 OTA 研究；没有设置写入或 OTA 实机样本。

- 设置写入、持久化、寻机响铃范围和 OTA 没有实机验证；本轮实机只发送初始化查询（实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。
- `setFindDeviceBoth` 原样调用 `setFindDeviceLeft`；接收器虽能解析方向 `02`，不能证明发送 `02 01` 可启动双耳寻机（[§5.2](#52-音频多点和寻机的差异)）。
- `0602` 降噪模式回包按 byte9 起的全部尾部精确匹配，第五字节字段性质未证明；未知尾部 App 会退回普通模式（[§6.3](#63-降噪模式尾部的精确匹配)）。
- RX 固定前缀、byte7/8 长度、末字节求和或 CRC 未验证；不能按 TX 规则筛掉可能合法的 RX（[§6.1](#61-分派和验证边界)）。
- `0e02` 的停止方向 `03` 未在 i5 handler 分支中处理；设备对停止回包的实际方向未知（[§6.4](#64-固件版本与寻机)）。
- 提示音语言切换没有独立的普通控制命令，只能通过 WQ OTA 选择语言固件；完整 OTA 流程、E2 偏移单位、设备签名/拒绝条件和中断恢复未验证（[§9](#9-wq-ota-与语言固件)）。
- `getInfoFromRawData` 的 zt 分支会把 rawData 片段格式化成地址样式，但未证明与广播中的经典地址或连接地址的对应关系；连接使用系统扫描对象地址（[族文档](../families/zt.md#全族共用)）。
- 自定义 EQ 不在 i5 配置中；Sender 的 `setEqCustom`/`setEqCustomData` 不构成本型号能力（EQ 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:164`））。

## OTA

i5 使用 WQ OTA（构造 OTA 类型（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:30`））：独立 GATT 服务 `65786365-6c70-6f69-6e74-2e636f6d0000` 和读写/通知特征 `65786365-6c70-6f69-6e74-2e636f6d0001`，与普通 `08 EE` 控制帧无关。OTA 面板开启 `hasEnglish` 和 `ota_upgrade_language_switch`（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`）），提示音语言切换通过选择对应语言固件再升级实现，没有普通控制通道的语言设置命令。元数据请求、下载链、E2/E5 字面算法和超时状态机见 [§9](#9-wq-ota-与语言固件)。OpenRose 不实现 OTA；本节只作记录。

## 附录：详细证据（保留）

### 1. 范围、证据与能力

本说明依据 **RoseLink 3.8.0（versionCode 5310）Android APK 的 ARM64 Flutter AOT 静态代码**恢复。常规控制已整理到发现、GATT、发送帧、参数、通知解析和状态同步层，可据此编写第三方控制客户端。2026-10-14 已验证匿名 OTA 元数据请求、更新包资源 HEAD 与前 64 字节 Range 响应，见 [OTA 更新包链接](../ota-package-links.md)。2026-10-18 已在 Linux 上验证单副 i5 的 BLE 连接和 13 项初始化读取，见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`）。**设置命令和 OTA 尚未实机验证，也未下载完整固件**；已恢复 App 的打包和状态机，但 E2 偏移单位、设备拒绝条件及中断恢复尚待实测，不能视为已验证的刷机方案。

- APK：`rose_reverse/RoseLink_3.8.0.apk`
- 包名：`cn.ikaile.ruoshui.client`。
- SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。
- App 内部名 `EARFREE-i5`，显示名 `EarFeel-i5`，配置 `EarfreeI5DeviceConfig`，发送器 `Zt229Sender extends ZtSender`，接收器 `ZTReceiver`，处理器 `EarfreeI5ReceiveHandler`。
- 发现标识 `525300010001`；常规控制协议 `ZT`；App 对本型号配置 `sppSupport="false"`。

厂商页面同时使用 EARFEEL i5 与 EARFREE i5 名称，并确认其 RoseLink 控制与 LDAC 支持。这里只用它核对型号别名；下文线协议来自 APK。[厂商产品页](https://global.roseselsa.com/products/earfree-i5-anc-tws-earbuds)

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
| 固件 | 版本读取；WQ OTA | 独立 OTA GATT 通道；见第 9 节 |
| 提示音语言 | 中文/英文固件切换 | 通过 OTA 固件语言选择，无已证实的 i5 独立普通控制命令 |

“星芒业务”是 App 的业务入口；本次未在 i5 Sender 中找到与之对应的独立控制报文，不将其当成耳机硬件能力。

型号证据：构造与能力配置（0x68d8ec）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:10`）、名称、类型、SPP 配置（0x8262a0）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:737`）。

### 2. 设备发现与连接

#### 2.1 广播识别

App 使用 BLE 广播的 `manufacturerData`，不是按可修改的设备名识别。其规范化算法如下，结果转小写；将前 12 个 hex 字符与设备表匹配：

```python
ids = sorted(manufacturer_data)
raw = (b"".join(i.to_bytes(2, "little") for i in ids)
       + b"".join(bytes(manufacturer_data[i]) for i in ids))
is_i5 = raw.hex().startswith("525300010001")
```

单个 manufacturer 项时，company id `0x5352` 序列化为 `52 53`，该项 payload 前四字节为 `00 01 00 01`。这是按 App 标识和规范化算法推导的匹配例，**不是实机抓到的广播**。存在多项时 App 将所有 company id 放在所有 payload 之前，不能改为逐项交错拼接。保存系统扫描对象的 remote id/address 用于连接；不要用型号前缀或广播中的其他疑似 MAC 字段替换它。

发现证据：manufacturerData 规范化（0xa0daa8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/scanner/r_ble_scanner.dart:3737`）、identifyDevice 前缀匹配（0x90a2ac）（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`）。

#### 2.2 GATT UUID 与写入属性

| 通道 | 完整 UUID | 用途 |
|---|---|---|
| 常规服务 | `011bf5da-0000-1000-8000-00805f9b34fb` | 正常控制 |
| 常规写特征 | `00007777-0000-1000-8000-00805f9b34fb` | 发送 `08 EE` 控制帧 |
| 常规读/通知特征 | `00008888-0000-1000-8000-00805f9b34fb` | 订阅控制状态通知 |
| OTA 服务 | `65786365-6c70-6f69-6e74-2e636f6d0000` | WQ 升级 |
| OTA 读写/通知特征 | `65786365-6c70-6f69-6e74-2e636f6d0001` | 同一特征承担 OTA 写入与通知 |

UUID 由配置里的 32/16 位值 `011bf5da`、`7777`、`8888` 经 Bluetooth 标准基 UUID 扩展，不能把 32 位服务值截成 16 位。匹配时忽略大小写。

App 检查写特征 `writeWithoutResponse` 属性；支持时使用无响应写，不支持时用有响应写。这里的 GATT 写响应与业务状态回包是两回事。第三方客户端必须发现实际属性再选择。普通 i5 报文最长 14 字节，不依赖超大 MTU；App 的公共 BLE 层尝试请求 **MTU 512**，协商结果由设备/系统决定。OTA 的 217 字节分片需另外处理可写大小。

证据：normalBleServiceConfig（0x74990c）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、Guid.str128（0xa257ec）（`rose_reverse/analysis/blutter/asm/flutter_blue_plus_platform_interface/src/guid.dart:322`）、属性决定写入方式（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:5921`）、writeWithoutResponse 字段（`rose_reverse/analysis/blutter/asm/flutter_blue_plus/flutter_blue_plus.dart:12072`）、MTU 请求值 512（`rose_reverse/analysis/blutter/asm/flutter_blue_plus/flutter_blue_plus.dart:11300`）。

#### 2.3 连接流程

1. 让耳机保持唤醒，扫描并匹配广播；用户指定系统设备标识时，仍须在连接后核对服务与特征。
2. 连接 BLE，发现服务，取得本服务内的 `7777`、`8888` 特征。
3. **先订阅 `8888` 通知并完成 CCCD 设置，再发送查询**。底层系统需要配对时通过系统配对流程完成；本次没有发现 i5 自定义密码、会话 token 或加密握手，不能据此断言所有固件都不要求系统配对。
4. 以单一队列发送第 4 节的 13 条初始化查询。原 App 在本地 write 完成后留 **100 ms** 间隔；OHOS 特定分支为 350 ms。没有统一业务 ACK 等待和自动重发。
5. 将通知应用到逐字段状态缓存。BLE 已连接、订阅已成功、协议已收到可解析回包、各字段已加载，应分别表示。
6. 断连后清空待发命令和查询等待，重连重新发现/订阅并读取状态。重新查询即可建立当前状态，无须复用旧会话。

原 App 控制队列是 FIFO：`await write → gap → next`。所有查询和设置共用队列，常规控制按一条完整帧一次 GATT write 发送。**100 ms 是发送间隔，不是设备回复超时**。在这条接收路径中没有看到跨通知重组或按接收长度拼包；先按一次通知解析一次报文，若实机出现拆包，应先抓包确认接收帧边界。

**Linux 双模连接实测补充（2026-10-18）**：系统已有经典蓝牙配对时，第一次连接可能只建立经典链路，表现为“已连接”但没有 GATT 服务。参考客户端在空服务表或明确的 GATT `NotConnected` 错误下补连 BLE，并等待控制特征发布；首条只读查询用于进一步确认链路。指定地址时可复用已配对、型号匹配的 i5 缓存对象；正常 BLE 连接不重复补连。配对记录不需删除，UUID 也不需修改。实际记录与验证范围见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`）。

连接/通知证据：订阅通知（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:8709`）、通知字节转 hex 后送 Receiver（0xe55164）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:8997`）。队列证据与实现选择详见 发送队列笔记（`rose_reverse/analysis/i5_queue_protocol.md`）。

### 3. 常规发送帧

本文所有字节偏移从 **0** 开始，区间为左闭右开；hex 以两个字符表示一个字节。`group/op` 是按报文位置命名，未确认厂商字段名称。

| 偏移 | 字节数 | 发送值/含义 |
|---|---:|---|
| 0 | 1 | 固定 `08` |
| 1 | 1 | 固定 `EE` |
| 2–4 | 3 | 固定 `00 00 00`；语义未确定，保持原值 |
| 5 | 1 | group |
| 6 | 1 | operation |
| 7–8 | 2 | **全帧总字节数，小端**，包含最后的校验字节 |
| 9..N−2 | 可变 | payload |
| N−1 | 1 | 前面所有字节之和的低 8 位 |

```python
body = bytes.fromhex("08 ee 00 00 00") + bytes([group, op])
body += (10 + len(payload)).to_bytes(2, "little") + payload
packet = body + bytes([sum(body) & 0xff])
```

无 payload 查询为 10 字节；单字节设置为 11 字节；寻机 12 字节；触摸绑定 13 字节；降噪模式和轮换列表 14 字节。`ZtSender.transform` 原样返回，不再增加其他封装。这里只确认 **TX 校验**，不得把公式未经验证地套到 RX 或 OTA。

证据：transform（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2926`）、CommandUtil.checkSum（0x795b24）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:189`）；固定帧逐字节提取见 原始命令常量（`rose_reverse/analysis/i5_fixed_sender_commands.json`）。

### 4. 初始化和读取命令

按照下表顺序发送。电量随 `getInfo` 返回，无须套用 HeadFeel 的 `getBattery=0103`。

| 次序 | App 方法 | 完整 TX hex | 对应状态命令 | Sender 证据 |
|---:|---|---|---|---|
| 1 | `getInfo` | `08 EE 00 00 00 01 01 0A 00 02` | `0101` | 0xdfa7a8（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`） |
| 2 | `getAnc` | `08 EE 00 00 00 06 02 0A 00 08` | `0602` | 0x81b120（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:801`） |
| 3 | `getEq` | `08 EE 00 00 00 02 01 0A 00 03` | `0201` | 0xdfa110（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2731`） |
| 4 | `getGameMode` | `08 EE 00 00 00 06 03 0A 00 09` | `0603` | 0xc78fd4（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2392`） |
| 5 | `getAudioLdac` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 0xc6e364（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`） |
| 6 | `getVersion` | `08 EE 00 00 00 0D 01 0A 00 0E` | `0d01` | 0xdfa494（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2796`） |
| 7 | `getKey` | `08 EE 00 00 00 04 01 0A 00 05` | `0401` | 0xc6d5a8（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1936`） |
| 8 | `getTouch` | `08 EE 00 00 00 0A 01 0A 00 0B` | `0a01` | 0xb6de90（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1407`） |
| 9 | `getAncList` | `08 EE 00 00 00 06 05 0A 00 0B` | `0605` | 0x81e480（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:866`） |
| 10 | `getEarDetection` | `08 EE 00 00 00 0C 01 0A 00 0D` | `0c01` | 0x830d58（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1206`） |
| 11 | `getAncLevel` | `08 EE 00 00 00 06 07 0A 00 0D` | `0607` | 0x80d754（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4598`） |
| 12 | `getAncTransLevel` | `08 EE 00 00 00 06 04 0A 00 0A` | `0604` | 0x80b5a0（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4458`） |
| 13 | `getDingLevel` | `08 EE 00 00 00 0E 03 0A 00 11` | `0e03` | 0x823770（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4736`） |

`getConnectMul` 在 i5 的父类中调用 `getAudioLdac`，仍发送 `08 01`，不是 HeadFeel 的独立 `08 02` 查询。初始化顺序证据：sendInitCommands（0x8e25cc）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:788`）。

### 5. 设置命令与参数

以下表格给出 frame 函数中的 group/op 和 payload；长度、校验按第 3 节生成。设置返回在同一 `8888` 通知通道；已证实的状态分派使用读取操作码，例如 `0602`，**不要自动把收到的 op 清除 `0x80` 后当成已确认状态**。

| 功能 | group/op | payload | 字节数 | 读取确认 | 证据 |
|---|---|---|---:|---|---|
| 降噪模式 | `06 82` | ANC `01 00 00 00`；普通 `00 01 00 00`；抗风 `00 00 01 00`；通透 `00 00 00 01` | 14 | `getAnc` | 模式四分支（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:501`） |
| EQ | `02 81` | `00` POP，`01` HIFI，`02` ROCK，`03` LIGHT | 11 | `getEq` | EQ（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2457`） |
| 游戏模式 | `06 83` | `00` 开，`01` 关 | 11 | `getGameMode` | 游戏开关（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2256`） |
| 佩戴检测功能 | `0C 81` | `00` 开，`01` 关 | 11 | `getEarDetection` | 检测开关（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1070`） |
| 触摸功能 | `0A 81` | `00` 开，`01` 关 | 11 | `getTouch` | 触摸开关（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1271`） |
| 音频模式 / 多点 | `08 81` | `00` AAC/SBC 且多点开；`01` LDAC 且多点关 | 11 | `getAudioLdac` | AAC 路径（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2131`）、关多点→LDAC（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2216`） |
| ANC 等级 | `06 87` | `00/01/02` → UI `1/3/5` | 11 | `getAncLevel` | i5 ANC 等级（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523`） |
| 通透等级 | `06 84` | `00/01/02` → UI `1/3/5` | 11 | `getAncTransLevel` | i5 通透等级（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4383`） |
| 提示音音量 | `0E 83` | `00..04` → UI `1..5` | 11 | `getDingLevel` | i5 提示音音量（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4663`） |
| 触摸绑定 | `04 81` | `[side][gesture][action]`，见下表 | 13 | `getKey` | 八种绑定方法（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1472`） |
| 降噪轮换列表 | `06 85` | `[普通][通透][抗风][ANC]`，每项 `00/01` | 14 | `getAncList` | setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`） |
| 寻机 | `0E 82` | 左/原 App 双耳 `00 01`；右 `01 01`；全部停止 `03 00` | 12 | `0e02` 通知，无独立查询 | 双耳别名（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:120`）、左耳（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:208`）、i5 停止（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4349`） |

#### 5.1 触摸绑定

`side=00` 左、`01` 右；`gesture=00` 单击、`01` 双击、`02` 三击、`03` 长按。

| action | 功能 | 参考代码名称 |
|---|---|---|
| `00` | 音量增加 | `volume_up` |
| `01` | 音量降低 | `volume_down` |
| `02` | 上一曲 | `previous_track` |
| `03` | 下一曲 | `next_track` |
| `04` | 语音助手 | `voice_assistant` |
| `05` | 播放/暂停 | `play_pause` |
| `06` | 无功能 | `none` |
| `07` | 降噪模式轮换 | `noise_cycle` |

注意：这是**设置触摸动作的绑定**，不是让客户端立即播放/调音量的命令。i5 的动作列表证据：i5 专用列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:60`）；具体数值来自 IntValueModel 对象（`rose_reverse/analysis/blutter/objs.txt:1027`）。

#### 5.2 音频、多点和寻机的差异

音频与多点耦合由 i5 UI 配置明确规定：`useAudioModeNotMulDeviceMode=true`，多点对应音频值 `0`，非多点对应 `1`。`setAudioAacSbc` 调用父类 `setConnectMulOn`；父类 `setConnectMulOff` 直接调用 AOT 中标作 `Zt231Sender.setAudioLdac` 的共享函数，生成 `01`。这里的类名是去重后的静态标签，不代表该命令只适用 HeadFeel。没有单独解析 `RCommander.setAudioLdac` 的 GDT 项，本说明使用已确认的继承调用路径和具体帧，不把它说成动态运行验证。

证据：i5 多点配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:221`）、父类关闭多点（0xc77d10）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2216`）、共享 LDAC 数据包（0xc6e104）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3749`）。

`setFindDeviceBoth` 原样调用 `setFindDeviceLeft`。接收器虽能解析方向 `02` 的双耳状态，**这不能证明发送 `02 01` 可启动双耳寻机**。参考代码复现原 App 的别名，不虚构新的双耳启动命令。i5 停止命令的方向值是 `03`；接收器处理的方向值为 `00/01/02`，不能假定停止回包也一定是 `03 00`。

#### 5.3 可直接用于实现检查的 TX 示例

```text
# ANC
08 EE 00 00 00 06 82 0E 00 01 00 00 00 8D
# LDAC（同时关闭多点）
08 EE 00 00 00 08 81 0B 00 01 8B
# ANC UI 5级
08 EE 00 00 00 06 87 0B 00 02 90
# 左耳双击 → 播放/暂停
08 EE 00 00 00 04 81 0D 00 00 01 05 8E
# 仅普通、ANC 参加轮换
08 EE 00 00 00 06 85 0E 00 01 00 00 01 91
# 停止寻机
08 EE 00 00 00 0E 82 0C 00 03 00 95
```

### 6. 通知解析

#### 6.1 分派和验证边界

接收器将一帧转为无空格 hex；使用 `hex[10:14]`，即 **byte5/6**，作命令键。普通 payload 从 byte9 开始。**已恢复的 RX 是字段偏移解析，不是已证明的完整帧校验器**：该路径未验证 RX 固定前缀、byte7/8 长度、末字节求和或 CRC。不要让 TX 构帧规则筛掉可能合法的 RX，也不要将这些未知字段当成序号或 ACK。

收到通知后保存原始字节、命令、时间，再按下表检查最小长度并解码。表中的最小长度是为安全读取字段而定义，不是已证明的设备帧总长度；长度未知、命令未知、枚举未知时保存原始数据并报告 unknown。原 App 有些分支会默认显示“关闭”，参考实现选择保留 unknown，避免把异常包当成可靠状态。

| 命令键 | 字段读取 | 解释 | 最小解析字节数 |
|---|---|---|---:|
| `0101` | byte11、12、15 | 左、右、盒电量；完整布局见下节 | 16 |
| `0d01` | byte9、10、11 **低 nibble** | 各自按 hex 转整数，再将十进制文本无分隔拼接；byte12不参与最终版本文本 | 13（原 App 长度保护） |
| `0201` | byte9 **低 nibble** | `0/1/2/3` → POP/HIFI/ROCK/LIGHT | 10 |
| `0401` | byte9..16 **低 nibble** | 左单/双/三/长，然后右单/双/三/长；动作值见第5节 | 17 |
| `0602` | byte9 到通知结尾 | 整段精确匹配，下表列出 | 14 |
| `0603` | byte9 **低 nibble** | `0` 游戏开，`1` 关 | 10 |
| `0604` | byte9 **完整字节** | `0/1/2` 通透 UI `1/3/5` | 10 |
| `0605` | byte9..12 **低 nibble** | 普通/通透/抗风/ANC 是否参加轮换 | 13 |
| `0607` | byte9 **完整字节** | `0/1/2` ANC UI `1/3/5` | 10 |
| `0801` | byte9 **低 nibble** | `0` AAC/SBC + 多点；`1` LDAC + 单点 | 10 |
| `0a01` | byte9 **低 nibble** | `0` 触摸功能开，`1` 关 | 10 |
| `0c01` | byte9 **低 nibble** | `0` 佩戴检测功能开，`1` 关 | 10 |
| `0e02` | byte9/10 **完整字节** | 寻机方向和状态；见下节 | 11 |
| `0e03` | byte9 **低 nibble** | `0..4` 加1 → 提示音 UI `1..5` | 10 |

共享 Receiver 还有 `0802` 分支：byte9 低 nibble `1` 表示多点开、`0` 关；i5 初始化不查询它，因此只能作为额外兼容解析，不能据此复制 HeadFeel 的独立多点协议。共享 `0103`、`0202/0203`、`0803`、`0e06`、`0b01` 等分支也不自动构成本型号的能力。

证据：process 分派（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`）、触摸8槽读取（0xe52df8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2418`）、8项映射到左右4槽（0x92c0a4）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/r_receiver_tool.dart:434`）。

#### 6.2 电量

`0101` 通知至少需要 byte15：

| 偏移 | App 解释 |
|---|---|
| 9–10 | 本次电量处理函数未消费；保持 raw |
| 11 | 左耳电量，按完整字节无符号整数读取 |
| 12 | 右耳电量 |
| 13 | 左耳充电状态，`00=false`，其他=true |
| 14 | 右耳充电状态，`00=false`，其他=true |
| 15 | 盒电量，`FF` 表示没有盒数据 |

原 App 对左/右只显示 `1..100`，`0` 或超范围显示 `-`。盒值 `FF` 不更新盒显示；有盒数据时，还需任意一耳的充电标志为 true 且值在 `1..100` 内，才显示并缓存盒电量。第三方可以保留 `raw_percent`、`display_percent` 和 `case_present`，避免把无数据直接写成 0%。这两个 bool 已由 UI 的 `chargingState` 和充电图标用途确认；是否也表示“在仓且已充满”等实际状态边界仍须实测。

证据：电量偏移（0xe53748）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3326`）、i5电量显示逻辑（0xe41678）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:698`）、chargingState 充电图标（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_display.dart:2615`）。

#### 6.3 降噪模式尾部的精确匹配

原 App 匹配 **byte9 开始的全部剩余数据**，包含下表第五个字节。第五字节的字段性质尚未证明，不称为校验字节：

| 全部尾部 hex | 模式 | App 内部模式值 |
|---|---|---:|
| `01 00 00 00 20` | ANC | 3 |
| `00 01 00 00 20` | 普通/关闭 | 0 |
| `00 00 01 00 20` | 抗风 | 2 |
| `00 00 00 01 20` | 通透 | 1 |
| `02 00 00 00 21` | auto，共享解析分支；i5 UI没有该模式 | 4 |

未知尾部原 App 记录警告并退回普通模式。第三方应显示未知并保留整帧，避免误报。不能仅用“payload首个非零flag”代替原 App 的匹配而宣称完全等价。

证据：_handleANCData（0xe52914）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1939`）。

#### 6.4 固件版本与寻机

版本例：byte9..11 为 `9A 0B 0C` 时，得到整数 `[10,11,12]`，原 App 版本文本是 **`101112`**，不是 `ABC`、`10.11.12` 或完整三字节整数。应同时保留这三个值和原始报文。证据：_handleFirmwareVersion（0xe533cc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2987`）。

寻机通知 `0e02` 的 byte9 是方向 `00` 左、`01` 右、`02` 双耳；byte10 是状态 `00/01`。i5 handler 对 UI 左右标志执行如下规则，`00 00` 最后还有一次全部置 false 的特例：

| 通知 payload | 左 finding | 右 finding |
|---|---:|---:|
| `00 01` | true | false |
| `00 00` | false | false（末尾特例覆盖） |
| `01 01` | false | true |
| `01 00` | true | false |
| `02 01` | true | true |
| `02 00` | false | false |

这个交叉赋值是 App 行为；实际硬件是否同时切换另一耳响铃仍未验证。方向 `03` 未在此 handler 分支中处理。证据：findMeReceived（0xd15fa8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:10`）。

等级 `0/1/2→1/3/5` 的共享转换证据：通透档位转换（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:265`）、ANC档位转换（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:414`）。这些是 AOT 去重后输出的函数标签；i5 的 Sender 参数映射与共享 Receiver 单字节分支也独立支持相同线值。降噪轮换顺序还由 i5 的4个模型对象（`rose_reverse/analysis/blutter/objs.txt:1849`） 和 UI发送顺序（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_anc_line_panel_item.dart:798`） 交叉核对。

### 7. 客户端状态同步与异常处理

- `write_complete` 只表示底层 write 返回。`state_confirmed` 应来自可解析通知；要确认一次设置，串行发设置并随后读取对应状态，比较字段值。
- 本协议未确认业务事务 id。一个字段有多个未完成设置时不要用“下一个回包”机械匹配先前请求；缓存最新状态、记录发送时间并串行处理同字段确认更稳妥。这是客户端设计建议。
- 原 App 未给出通用查询超时；第三方可配置如 2 秒的初始等待值，但需标明是实现参数、实测后调整。缺失字段显示未加载；对幂等查询可有限重试，不能把查询重试策略直接套在寻机和 OTA 写入上。
- 不自动将未知枚举降级为关；不将多个通知任意连接后按 TX 长度拆分。保留原始报文，待确认帧边界和兼容版本。
- 多点开关更新时同步音频状态；LDAC 切换时同步多点状态。音频编码器实际连接与 A2DP 状态由系统负责，BLE 回包只反映耳机设置。

### 8. 参考实现、验证与交付文件

- [机器可读协议目录](earfeel-i5-protocol.json)：UUID、初始化顺序、32条固定命令变体、13类设置参数、14类核心通知布局、源文件/行号/地址。
- 无依赖 Python 编解码（`rose_reverse/examples/i5_protocol.py`）：构帧、校验、广播识别、参数检查、通知解析及未知值保留。
- Bleak 连接示例（`rose_reverse/examples/i5_ble_client.py`）：扫描、发现特征、订阅、串行写、初始化、设置后读取状态、Linux 双模连接及缓存兼容、断连处理。寻机示例退出前发送停止命令；未提供 OTA 写入入口。
- 离线验证（`rose_reverse/tests/test_i5_protocol.py`）：32个固定变体与独立 APK 常量比对，动态设置帧、字节偏移、低半字节规则、触摸/轮换顺序、异常包、写属性选择与断连检查，共10项测试通过。
- 连接回归测试（`rose_reverse/tests/test_i5_connection.py`）：双模连接的空服务表、缓存 GATT、首条查询检查、正常 BLE、特征延迟发布、断开收尾和缓存型号校验。

在准备好的 Python 环境安装 `bleak==3.0.2` 后，从项目根目录使用：

```bash
python3 examples/i5_ble_client.py --scan
python3 examples/i5_ble_client.py --device '扫描返回的系统标识' --seconds 30
python3 examples/i5_ble_client.py --device '扫描返回的系统标识' \
  --command '{"name":"set_noise_mode","mode":"anc"}'
python3 examples/i5_ble_client.py --device '扫描返回的系统标识' \
  --command '{"name":"set_touch_key","side":"left","gesture":"double","action":"play_pause"}'
python3 -m unittest discover -s tests -v
```

Bleak 示例明确选择 `response=`，使用扫描得到的设备对象连接并订阅实际特征对象；接口依据 [BleakClient 官方文档](https://bleak.readthedocs.io/en/latest/api/client.html) 和 [BleakScanner 官方文档](https://bleak.readthedocs.io/en/latest/api/scanner.html)。仅编解码模块与离线测试不依赖 Bleak。

**验证范围**：离线测试中的 RX 缓冲区是刻意构造的偏移测试数据，测试通过本身不等于硬件通过。2026-10-18 另有 单副 i5 实机初始化读取记录（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl`），验证范围见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`）。设置后的回包与持久化行为、寻机“双耳”入口实际响铃范围、完整 RX 传输语义及其他固件仍待核实，不补造报文。

### 9. WQ OTA 与语言固件

以下补充来自同一 APK；可复现 App 静态发送行为。参考 BLE 客户端未暴露 OTA 写入入口。

#### 9.1 固件来源及语言切换

`WqOta.otaC0E1 @0xaca954` 先调用 `DeviceOtaController.compareOtaDataWithEncodeData`，通过后读取 `getOtaContent`，非空才发送启动指令。这个 APK 的 `compareOtaDataWithEncodeData @0xac05a0` **直接返回 true**，没有验证 `encodeData` 的逻辑，不能把这个函数名当成完整性验证证据。

- 启动函数（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:15`）
- compareOtaDataWithEncodeData（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1723`）

下载链为 `DeviceOtaController.getOtaContent @0xac01e0`（读取自身 `field_57`）→ `DeviceService.getOtaContent @0xac0270` → `ApiDeviceService.getOtaContent @0xac0304` → `StaticResourceClient.getText @0xac0368`。后者把 URL 交给 HTTP GET，返回 String；若响应是字节列表，则 UTF-8 解码。控制器空响应转为 `""`。WQ 直接把返回文本保存在 `latestData`，随后对它按十六进制字符索引截取，因此升级输入是**十六进制文本**，而不是原始 `.bin` 字节或 Base64；固件字符索引前没有解密、解压、规范化空白。发送工具 `CommonUtil.stringToList @0x820190` 会在已经截取并构造帧之后移除 ASCII 空格和 `0x`，所以仍应使用无空白、无前缀的纯 hex 输入以保持偏移意义。

- 下载入口（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1666`）
- ApiDeviceService（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:437`）
- HTTP 与 UTF-8（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:642`）

URL 来自服务端响应 **`data.otaUrl`**；`ApiClient._processResponse` 先取 `data`，`DeviceService._buildOtaResult @0xacc120` 将其中的 `otaUrl` 重命名为 App 内部字段 **`otaContent`**，控制器再保存这个 URL。这一步只转换元数据，没有下载固件文本；之后 `getOtaContent` 才按上面的 GET 链下载文本。`encodeData` 也被保存，但上述 WQ 校验函数没有使用它。元数据请求是 `POST /app/ota/v2/get-by-key-id-and-tag`，参数 `keyId`、`pid`、`tag`、`clientVersion`、`lang`，`token=false`。自身耳机目标 `tag=1`，`pid=""`。`lang` 是 `WqOta.init` 传入的 deviceLang，经 `checkOtaNewVersion` → `fetchDeviceOtaMsg` → `fetchDeviceOtaByTag` 送到服务端；i5 配置开启 `ota_upgrade_language_switch`。因而提示音语言切换是**选择对应语言固件再 OTA**，没有看到 i5 普通控制通道独立的设置语言命令。

- 元数据请求及 lang（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:476`），`@0xacc6d4`；接口路径 `:650`
- 服务端 otaUrl → 内部 otaContent（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10882`），读取 `otaUrl @0xacc5ec`，赋到 `otaContent`
- 控制器内部 otaContent/encodeData（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3197`）
- 自身目标 tag=1（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:2247`）
- pid 为空（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10373`）
- 传入语言（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1178`）
- i5 语言升级能力（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:389`）

`keyId` 来自设备 trackingName，不来自广播型号识别 ID。已证实链：i5 配置写 `EarObject.field_1b="EARFREE-i5"` → `RName.trackingName @0x7547a8` 读此字段并调用 `GDT[cid-0x1000]` → `DeviceOtaController.initOtaManager @0xb45d64` 把结果交给 `OtaManager.attachSession` → `bindSession @0xb46274` 保存到控制器 `field_23` → `fetchDeviceOtaMsg` 取它作为 `fetchDeviceOtaByTag` 的 keyId。trackingName 的附加颜色后缀只针对 `ceramics-x`、`ceramics-x-7034`、`explorer`。2026-10-14 以 **`keyId="earfree-i5"`**、`pid=""`、`tag=1`、`clientVersion="5310"` 匿名请求，`lang="zh"` 和 `"en"` 均成功返回 i5 的版本 125 与下载 URL，已验证这个请求大小写可用；`525300010001` 不应当作 cloud keyId。响应中的 `data.keyId="8"` 是服务端记录字段，不应替换请求的型号名称。

- i5 内部名写入（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:742`）
- trackingName（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/object_info.dart:6`）
- 读取 trackingName 并 attachSession（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3622`）
- attachSession → bindSession（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_manager.dart:281`）
- bindSession 保存设备名（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3737`）

`clientVersion` 是 App 的 buildNumber 转字符串，当前 APK 应为 `"5310"`，而不是 `"3.8.0"`：ApiDeviceService 读取 `SystemService.field_1f.value.toString`（:590（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:590`）），该字段在 `initVersion` 中由 `PackageInfo.field_13` 解析（system_service.dart:605（`rose_reverse/analysis/blutter/asm/roselink/core/services/system/system_service.dart:605`））；`PackageInfo.toString` 显示 field_13 就是 buildNumber（package_info_plus.dart:345（`rose_reverse/analysis/blutter/asm/package_info_plus/package_info_plus.dart:345`））。

APK 的 API/静态资源默认 base URL 是 `https://api-internal.rosetechnic.com`，见 ApiClient（`rose_reverse/analysis/blutter/asm/roselink/core/api/api_client.dart:1412`） 与 StaticResourceClient（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:592`）。WQ 页两个语言选项调用元数据请求时明确传 `"en"`、`"zh"`，见 语言回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:1203`）、:1324（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:1324`）。2026-10-14 上述匿名 API 返回成功；中文、英文资源的 HEAD 均为 200，`bytes=0-63` Range 均为 206，采样 64 字节全部为 ASCII hex。结果仅验证元数据与资源可访问性、文本前缀，不是完整固件或刷机验证。链接带时效签名，应在需要时重新请求元数据；当前链接、时效和原始响应见 [OTA 更新包链接](../ota-package-links.md)、中文响应（`rose_reverse/analysis/ota_links/i5-zh-client5310.json`）、英文响应（`rose_reverse/analysis/ota_links/i5-en-client5310.json`）。第三方仍须使用已知兼容的固件；不能据此确认任意固件可用。

#### 9.2 通道与状态机

OTA 使用独立 GATT 服务 `65786365-6c70-6f69-6e74-2e636f6d0000` 和读写/通知特征 `65786365-6c70-6f69-6e74-2e636f6d0001`。与普通 `08 EE` 控制帧无关；不能给 OTA 数据追加普通控制通道求和校验。

`resetOtaServer` 是本地重新准备 OTA GATT 通道，保持已有控制连接；不是向耳机发送“重启”命令。其 BLE 实现先确认普通控制通道已就绪且设备仍连接，再标记 OTA 未就绪，清除 OTA 服务/读写特征引用和服务缓存，重新 discoverServices，寻找 OTA service 与配置的读/写特征，然后取消旧 `onValueReceived` 订阅并订新通知，通过 `_setNotifyValueWithRetry` 启用特征通知，成功后标记 OTA ready。第三方对应实现是**在原连接上重新发现、匹配、订阅 OTA GATT 特征**；仅重复用户态通知回调注册不能完全复现此恢复路径。

- BLE resetOtaServer（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:227`），`@0xe3631c`，在已有 reset Future 未完成时返回同一 Future
- 清理 OTA 引用并重新准备（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:420`），`@0xe3652c`
- 重新发现/找服务（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:555`），`@0xe36680`
- 匹配 OTA 特征（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:843`），`@0xe3697c`
- 取消旧订阅并订新通知（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:1075`），`@0xe36c00`

`process @0xe2b060` 对通知的 hex 字符串执行 `substring(6)`，即忽略前三字节；下表的 `H` 是**去掉前三字节后的 hex 文本**。App 不在此处验证前三字节值，也没有显示接收拼包。消息类型是 `H[0:4]`，回复使用 `00xx`，发送使用 `C0xx`。所有偏移为字符索引、半开区间。

| 阶段 | 发送字节 | 回复处理 |
|---|---|---|
| 查询 | `70 07 6E C0 02 00 05 00 FF FF FF FF 33` | `H[0:4]=0002` 且 `len(H)=64`，解析版本和状态；版本来自 `H[16:20]`、`H[20:24]`，均为 16 进制数，选择较小值 |
| 开始 | `70 07 6E C0 E1 00 01 1D 33` | `00e1`：保存完整 H，按下节生成 E2 |
| 头部数据 | E2 动态帧 | `00e2` 且 `H[12:14]==03` → E3；否则 App 错误码 04 |
| 开始块传输 | `70 07 6E C0 E3 00 01 1F 33` | `00e3` → 解析块请求 |
| 块数据 | E5 动态帧，分片连续写入 | `00e5` → 去掉 H 最后两字符，再解析块请求 |
| 完成传输 | `70 07 6E C0 E6 00 01 C1 33` | `00e6` 且 `H[12:14]==00` → E8；否则 500 ms 后再发 E6 |
| 完成升级 | `70 07 6E C0 E8 00 01 C1 33` | `00e8` 且 `H[12:14]==00` → 成功并发送 C003；否则 500 ms 后再发 E8 |
| 结束 | `70 07 6E C0 03 00 02 00 00 33` | App 标记成功之后发送；没有单独等待这个命令应答 |

前三字节 `70 07 6E` 和尾部 `33` 是 App 发出的固定值；是否称为同步字、包尾及其设备端校验含义尚未证明。

- 接收预处理/去重/去前缀（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1380`）
- C002 版本字段（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1602`）
- E1/E2/E3/E5 分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2293`）
- E6/E8 轮询和成功 C003（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2499`）
- E8 固定帧（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2726`），`@0xe2bf80`
- E6/E3 固定帧（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3681`），`@0xe2c9b4 / 0xe2ca20`

#### 9.3 E2 与 E5 的字面算法

记 `F` 为固件 hex 文本；所有数值转 hex 都左侧补 `0`，顺序按字符串书写，是大端字段。

#### E2

`otaC0E2 @0xe2ca8c`：

```python
n = int(H[20:24], 16)
o = int(H[12:20], 16)
payload_hex = F[o:o + 2*n]
tx = bytes.fromhex("70076ec0e2" + f"{n+1:04x}" + "1e" + payload_hex + "33")
```

**注意 App 的 o 没有乘 2**：`wq.dart:3850–3864` 把 `o` 原样交给 substring，仅长度乘 2。这可能是回复使用 hex 字符偏移，也可能是固件入口特殊约定/现有 bug；静态证据无法判定。复现客户端应保留此行为并抓取真实 E1 回复再确认，不能擅自改成 `F[2*o:...]`。

- E2 完整算法（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3769`）

#### E3/E5 请求与 E5 数据

`_updateNextBlock @0xe2bfec`：

```python
status = int(H[12:14])  # App 用十进制 parse；00 成功
if status != 0: raise OtaError("02")
offset_hex = H[14:22]
if offset_hex == "00000000": send_E6()
else:
    offset = int(offset_hex, 16)  # 这里明确是字节偏移
    count = int(H[22:26], 16)
    block = bytes.fromhex(F[2*offset:2*offset + 2*count])  # 限制到固件结尾
    block_sequence = (block_sequence + 1) & 0xff  # 构造时初值 0，第一块 1
    crc = zlib.crc32(block) & 0xffffffff
    frame = (bytes.fromhex("70076ec0e5")
             + (len(block)+9).to_bytes(2, "big")
             + bytes([block_sequence])
             + bytes.fromhex(offset_hex)
             + block + crc.to_bytes(4, "big") + b"\x33")
    for start in range(0, len(frame), 217):
        send_ota(frame[start:start+217])
```

217 是**整个 E5 帧的分片最大字节数**，包括头部/尾部所在片；并非每片独立 E5 帧。原程序按 434 个 hex 字符分片，后一片接着前一片写。片间通过 `Timer.run` 排程，没有显式等待 ACK；最后一片发完才等下一次块请求。要一次 GATT 写入 217 字节通常需 ATT MTU ≥220，具体 BLE write 行为应结合连接器实现及设备特征属性。

CRC 只覆盖 `block`，不覆盖 E5 包头、序号、偏移。标准 CRC-32/ISO-HDLC：反射多项式 `0xEDB88320`，初始化 `0xFFFFFFFF`，最终按位取反/`xorout=0xFFFFFFFF`；输出 8 位 hex 即 4 字节大端。可用 `zlib.crc32(block)` 复现。

- 请求解析与序号（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2770`）
- E5 构造及分片（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3133`），`@0xe2c3d0`
- CRC hex 字符转实际字节（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:107`），`@0xac506c`
- CRC 初始值/最终取反（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:163`），`@0xac50fc`
- CRC 多项式（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:364`），`@0xac54b4`

#### 9.4 超时、重复回复及缺口

`increaseTimeOutCount @0xacab38` 使用 1 秒 Future.delayed 周期；`Duration@db0781` 的微秒值 `0xf4240=1000000`，见 objs.txt（`rose_reverse/analysis/blutter/objs.txt:49060`）。启动阶段立即调用一次计数函数，因此首次计数从 0→1 是立即发生，而后按秒增加；**应称为每秒计数，不应武断写成精确“3 秒首次重试”**。有效通知先把计数归零；计数到 3 时仅重发保存的最后一次写入数据（E5 分片时保存的是最后一片），随后清空保存的数据；计数超过 10 时停止并报错 03。这个策略不能证明设备能从任意位置继续升级。

C002 初始化查询：发出后 500 ms 检查初始化是否完成，未完成增加 retryCount；retryCount≤6 时等待 **2 秒** 后 resetOtaServer，再重发查询，超过 6 停止。2 秒对象 `Duration@db0831` 的微秒值 `0x1e8480=2000000`，见 objs.txt（`rose_reverse/analysis/blutter/objs.txt:49104`）；与升级过程的 1 秒计数循环不同。

超时及块发送还依赖 `field_2b` 页面存活/允许继续标志；WQ 页进入时置 true、退出时置 false，见 进入页面（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:1879`）、退出页面（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:2076`）。`field_3f` 是升级进行标志，E1 发出后置 true。计数循环只在两标志都 true 时继续，避免把构造器初始 false 误读成设备取消回复。

`process` 会忽略**内容相同且距上次不超过 20 ms** 的通知；其余通知（在命令有效性验证之前）都会把超时计数清零。E6/E8 非成功状态每 500 ms 再查询，对象 `Duration@db0791` 的微秒值 `0x7a120=500000`，见 objs.txt（`rose_reverse/analysis/blutter/objs.txt:49064`）。

仍需实机/完整固件样本核实：完整 OTA 通知原始前缀、跨通知重组与设备 MTU，E2 偏移单位与完整固件文本结构，查询返回各状态位（含左右耳/双耳升级指示）语义，固件与硬件版本的兼容条件，设备端签名验证/拒绝条件，升级中断与重新连接恢复。普通控制客户端可以独立实现；宣称“OTA 已实机验证”或“可刷任意固件”超出当前证据。
