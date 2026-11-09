# HeadFeel 控制协议

配置类 `HeadfreeDeviceConfig`（cid 500），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [ZT 协议族](../families/zt.md)。没有 HeadFeel 实机记录；以下结论均为静态分析，设置命令是否被设备接受未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售头戴 | `525300020002` | `headfree` | `HeadFeel` | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:10`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:702`） |

系统设备名、旧文档和配置还使用 `HeadFree`、`headfree`、`HeadFree / HeadFeel`。CMIIT ID 为 `25J5151GT231`。识别按广播 `manufacturerData` 的 12 位前缀 `525300020002`，不是按设备名。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | `011bf5da-0000-1000-8000-00805f9b34fb` | 静态 | normalBleServiceConfig（0x74990c）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`） |
| 写特征 | `00007777-0000-1000-8000-00805f9b34fb` | 静态 | 同上 |
| 通知特征 | `00008888-0000-1000-8000-00805f9b34fb` | 静态 | 同上 |
| SPP 是否允许 | 是 | 静态 | 配置 `sppSupport="true"`（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:742`） |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:744`） |
| 广播中的经典地址 | 未解析；连接使用系统扫描对象的 remote id/address | 未知 | [族文档](../families/zt.md#全族共用) |
| 写入间隔 | 同族默认 100 ms（HeadFeel 未单独核对） | 推断 | [连接流程](../models/earfeel-i5-protocol.md#23-连接流程) |
| OTA SPP UUID | `66666666-6666-6666-6666-666666666666` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:746`） |
| OTA BLE 服务 | `66666666-6666-6666-6666-666666666666` | 推断 | 配置 field_6b（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:746`）、normalBleOtaServiceConfig 字段用法（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`） |
| OTA BLE 特征 | `77777777-7777-7777-7777-777777777777` | 推断 | 配置 field_77/7b（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:748`） |

HeadFeel 的 SPP 配置只写入 UUID；普通 BLE 服务/特征仍沿用 ZT 默认值。OTA 字段的“服务/特征”归类是按 `normalBleOtaServiceConfig` 的字段用法推断，未做 BES OTA 实机发现。

## 初始化

HeadFeel 的初始化有 17 条查询，包含自定义 EQ、增益、单体电量、多点状态与多点名称列表；没有佩戴检测和 ANC 等级查询。

| 次序 | App 方法 | 完整 TX hex | 读回 Tag | 状态 | 出处 |
|---:|---|---|---|---|---|
| 1 | `getInfo` | `08 EE 00 00 00 01 01 0A 00 02` | `0101` | 静态 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`） |
| 2 | `getAnc` | `08 EE 00 00 00 06 02 0A 00 08` | `0602` | 静态 | getAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:801`） |
| 3 | `getEq` | `08 EE 00 00 00 02 01 0A 00 03` | `0201` | 静态 | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2731`） |
| 4 | `getCustomEq` | `08 EE 00 00 00 02 02 0A 00 04` | `0202` | 静态 | Zt231Sender::getCustomEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4132`） |
| 5 | `getGainLevel` | `08 EE 00 00 00 02 03 0A 00 05` | `0203` | 静态 | Zt231Sender::getGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3513`） |
| 6 | `getGameMode` | `08 EE 00 00 00 06 03 0A 00 09` | `0603` | 静态 | getGameMode（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2392`） |
| 7 | `getAudioLdac` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 静态 | getAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`） |
| 8 | `getVersion` | `08 EE 00 00 00 0D 01 0A 00 0E` | `0d01` | 静态 | getVersion（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2796`） |
| 9 | `getKey` | `08 EE 00 00 00 04 01 0A 00 05` | `0401` | 静态 | getKey（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1936`） |
| 10 | `getTouch` | `08 EE 00 00 00 0A 01 0A 00 0B` | `0a01` | 静态 | getTouch（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1407`） |
| 11 | `getBattery` | `08 EE 00 00 00 01 03 0A 00 04` | `0103` | 静态 | Zt231Sender::getBattery（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4210`） |
| 12 | `getLoseLoss` | 未知 | 未知 | 未知 | RCommander.getLoseLoss（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:3883`） |
| 13 | `getConnectMul` | `08 EE 00 00 00 08 02 0A 00 0A` | `0802` | 静态 | Zt231Sender::getConnectMul（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3950`） |
| 14 | `getMulDeviceNameList` | `08 EE 00 00 00 08 03 0A 00 0B` | `0803` | 静态 | Zt231Sender::getMulDeviceNameList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3885`） |
| 15 | `getAncTransLevel` | `08 EE 00 00 00 06 04 0A 00 0A` | `0604` | 静态 | getAncTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4458`） |
| 16 | `getDingLevel` | `08 EE 00 00 00 0E 03 0A 00 11` | `0e03` | 静态 | Zt229Sender::getDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4736`） |
| 17 | `getAncList` | `08 EE 00 00 00 06 05 0A 00 0B` | `0605` | 静态 | getAncList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:866`） |

初始化顺序证据：sendInitCommands（0x8e27b0）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:761`）；分派表项 60078（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60078`））。`getLoseLoss` 的帧和读回 Tag 未恢复，见“未实现与未知”。

## 电量

单体电量，复用 `BatteryType.charge` 槽；配置只列一个 `BatteryType`，不是左/右/盒三槽。HeadFeel 用 `0103` 查询，`_handleHeadfreeBatteryData` 把左/右固定为 `FF`，只更新 charge 字段；`HeadfreeReceiveHandler.batteryDataReceived` 把 charge 值显示为百分比。

`0103` 的精确字节/位布局未闭合：接收器对 `substring(18,40)` 做整数解析后写入 charge 槽，但没有 HeadFeel 抓包或完整固件样本，不能证明该范围就是一个字节的百分比。第三方实现应保留 raw 值并标为未知，不要把 i5 的 `0101` 左/右/盒布局套过来。证据：电量处理（0xe535f4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`）、handler（0xe41a88）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:1189`）。

## 设置命令

BODY 从 group 开始，长度和校验按族文档发送帧规则生成。HeadFeel 的设置写入没有实机证据，状态均为静态；设备是否接受在实测前未知。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量读取 | `getBattery` | `01 03` | 单体 charge | — | `0103` | 静态 | Zt231Sender::getBattery（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4210`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeWind`、`setNoiseModeTrans` | `06 82` | ANC=`01 00 00 00`；普通=`00 01 00 00`；抗风=`00 00 01 00`；通透=`00 00 00 01` | — | `0602` | 静态 | 四分支（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:501`） |
| ANC 等级 | `setAncLevel` | `06 87` | 若 UI 按 i5 的 1/3/5 传参，基类线值为 `01/02/03`；实际传参未核 | — | `0607` | 静态 | 基类 setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:426`） |
| 通透等级 | `setTransLevel` | `06 84` | 若 UI 按 i5 的 1/3/5 传参，基类线值为 `01/02/03`；实际传参未核 | — | `0604` | 静态 | 基类 setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:351`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `02 81` | POP=`00`、HIFI=`01`、ROCK=`02` | — | `0201` | 静态 | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2664`） |
| 自定义 EQ 选择 | `setEqCustom` | `02 81` | 自定义=`03` | 130 | `0201`/`0202` | 静态 | Zt231Sender::setEqCustom（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4156`） |
| 自定义 EQ 数据 | `setEqCustomData` | `02 82` + 10 字节 | 10 个 double 经 `toInt` 后编码：正数原值、负数 `0x80\|abs` | 130 | `0202` | 静态 | Zt231Sender::setEqCustomData（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4015`） |
| 增益等级 | `setGainLevel` | `02 83` | UI `0/1/2` → 线值 `01/02/03` | 130 | `0203` | 静态 | Zt231Sender::setGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3239`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `06 83` | 开=`00`、关=`01` | — | `0603` | 静态 | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2325`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `08 82` | 开=`01`、关=`00` | — | `0802` | 静态 | Zt231Sender::setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4275`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLdacLhdc` | `08 81` | AAC/SBC=`00`、LDAC=`01`、LHDC=`02`、LDAC+LHDC=`03` | — | `0801` | 静态 | setAudioLdacLhdc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3611`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | 左/右/双耳 `0E 82`；停止 `0E 02` | 左/原 App 双耳=`00 01`；右=`01 01`；停止=`03 00` | — | `0e02` | 静态 | setFindDeviceLeft（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:208`）、Zt231Sender::setFindOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3205`） |
| 触摸绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `04 81` | `[side][gesture][action]`；side 左=`00` 右=`01`；gesture 单=`00` 双=`01` 三=`02`；action 见下 | — | `0401` | 静态 | setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1878`） |
| 触摸功能 | `setTouchOn`、`setTouchOff` | `0A 81` | 开=`00`、关=`01` | — | `0a01` | 静态 | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1340`） |
| 提示音等级 | `setDingLevel` | `0E 03` | UI `1..5` = `01..05` | — | `0e03` | 静态 | Zt231Sender::setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3537`） |
| 降噪轮换 | `setAncLine` | `06 85` | `[普通][通透][抗风][ANC]`，每项 `00/01` | — | `0605` | 静态 | setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`） |
| OTA | 范围外 | — | — | — | — | 未知 | OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:208`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:293`） |

表中的 130 门槛来自 自定义 EQ 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:169`） 的 `customSinceVersion=0x104` 和 增益控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:217`） 的 `field_7=0x104`；这是 Dart tagged Smi，右移一位后为 130，不是 260。

HeadFeel 的触控配置是一个 `RTouchInfoModel`，包含电源键和 ANC 键两组：电源键手势为单击/双击/三击，ANC 键为双击/三击，没有长按。动作列表 8 项：`00` 音量增加、`01` 音量降低、`02` 上一曲、`03` 下一曲、`04` 语音助手、`05` 播放/暂停、`06` 无功能、`07` ANC 轮换。两个按键到 `side=00/01` 的映射未核对（touchInfo（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:588`）、动作列表（`rose_reverse/analysis/blutter/objs.txt:1027`））。

## 接收字段

HeadFeel 与族文档不同的处理：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0103` | 单体电量 | 只更新 charge 槽，左右固定 `FF`；精确字节布局未闭合 | 静态 | _handleHeadfreeBatteryData（0xe535f4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`） |
| `0202` | 自定义 EQ 数据 | 交 `customEqDataReceived`；对应写入的 10 个增益字节 | 静态 | _handleCustomEqData（0xe531e0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2800`） |
| `0203` | 增益等级 | wire `1..3`，handler 减 1 后映射 UI `0..2` | 静态 | _handleGainLevel（0xe530bc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2690`） |
| `0802` | 多点状态 | byte9 低 nibble `1` 开、`0` 关 | 静态 | _handleMultiDevice（0xe51f14）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:989`） |
| `0803` | 多点设备名称列表 | 由 `ZTReceiver._handleMultiDeviceNameList` 处理；未在 `HeadfreeReceiveHandler` 找到同名回调 | 静态 | _handleMultiDeviceNameList（0xe51d1c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:789`） |
| `0e03` | 提示音等级 | handler 直接取 byte9，不加 1；UI 1..5 与线值 1..5 同值 | 静态 | _handlePromptToneLevelHeadfree（0xe52518）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1552`） |
| `0e02` | 寻机 | 方向 `00/01/02`、状态 `00/01`；但 `HeadfreeReceiveHandler` 没有 `findMeReceived`，接收状态是否更新未核 | 未知 | _handleEarFind（0xe52340）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1372`）、handler 方法表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:8`） |
| `0c01` | 佩戴检测 | handler 有 `inEarDetectionReceived`，但配置没有设置入口和初始化查询 | 静态 | HeadfreeReceiveHandler（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:883`） |
| `0604`、`0607` | 通透/ANC 等级 | 接收 `0/1/2` 由 `HeadfreeReceiveHandler` 映射为 UI `1/3/5`；基类发送对 1/3/5 映射为 `1/2/3`，是否与 UI 实际传参一致未核 | 静态 | transLevelReceived（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:265`）、ancLevelReceived（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:414`） |
| `0101`、`0201`、`0401`、`0602`、`0603`、`0605`、`0801`、`0a01`、`0d01` | 电量、EQ、按键、降噪、游戏、轮换、音频、触摸、版本 | 同族文档；由 `HeadfreeReceiveHandler` 处理 | 静态 | [族文档](../families/zt.md#接收-tag-与处理) |

`0d02` 没有在 `ZTReceiver.process` 中找到分派；`getEarLanguage` 虽然存在，HeadFeel 初始化和控制器中没有使用它的路径。语言设置方法见“未实现与未知”。

## 未实现与未知

以下缺口的查证范围是 HeadFeel 配置类、`ZtSender`/`Zt231Sender`、`ZTReceiver`/`HeadfreeReceiveHandler` 和现有索引；没有 HeadFeel 实机样本。

- 没有 HeadFeel 实机记录；所有设置写入、持久化、响铃范围和 OTA 都未知。
- `getLoseLoss` 是初始化第 12 条查询，但 `ZtSender`/`Zt231Sender` 没有同名实现，分派表中也没有对应函数；查询帧、读回 Tag 和语义都未恢复（HeadFeel 初始化调用（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:864`）、RCommander.getLoseLoss（0x8e29d4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:3883`））。
- `0103` 单体电量的精确字节/位解析未闭合；接收器把一段 substring 解析成整数后写入 charge 槽，但没有抓包证明它就是一个百分比字节。
- 两个按键到触摸命令 `side=00/01` 的映射未核对；配置只给出电源键/ANC 键的手势列表。
- `setFindOff` 发送 `0E 02 03 00`，左/右/双耳发送 `0E 82`；为什么停止使用另一个 operation、设备是否接受，均未实测。`HeadfreeReceiveHandler` 也没有 `findMeReceived`，寻机回包可能不更新 UI。
- 提示音设置使用 `0E 03` 且 UI `1..5` 原样发送，和 i5 的 `0E 83`/UI−1 不同；接收端也不加 1。没有 HeadFeel 回包样本。
- 增益和自定义 EQ 的固件门槛是 tagged Smi `0x104`，实际 130；配置门槛不等于设备已升级到该版本。
- 自定义 EQ 数据为 10 个 double 经 `toInt` 后的符号位+绝对值编码；频段标签、取值范围和声学含义未核对。
- 初始化不查询 ANC 等级（`0607`），只查询通透等级和轮换；配置的 NoiseBar 等级 UI 与实际读回路径未闭合。
- 等级命令的传参未核：HeadFeel 继承基类 `setAncLevel`/`setTransLevel`；若 UI 沿用 i5 的 `1/3/5` 档位，基类会发 `01/02/03`，而 `HeadfreeReceiveHandler` 把回包 `00/01/02` 映射为 UI `1/3/5`。i5 覆盖了这两个方法并发送 `00/01/02`。HeadFeel UI 实际传入 0/1/2 还是 1/3/5、写入与读回是否同一线值，都需实机确认。
- SPP 配置允许，但广播中经典地址的解析、SPP 实际连接和特征选择都未核对。
- BES OTA 的路由、UUID 使用、会话、签名和断点恢复都未研究；`6666`/`7777` 的服务/特征归类是按字段用法推断，不是 BES 实机发现。
- `getEarLanguage`/`setEarLanguageEnglish`/`setEarLanguageChinese` 在 Sender 中存在，但没有 HeadFeel 配置调用点，接收端也没有 `0d02` 分派；不能据此增加普通控制语言能力。
- `getInfoFromRawData` 的 ZT 分支会把 rawData 片段格式化成地址样式，但未证明是经典地址或连接地址；连接使用系统扫描对象地址。

## OTA

HeadFeel 的 OTA 类型是 BES（`DeviceOtaType.bes`，构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:30`）），不是 i5/i3 的 WQ。配置写入 OTA SPP UUID `66666666-6666-6666-6666-666666666666`，并把 BLE OTA 服务/特征配置为 `6666...`/`7777...`（服务/特征的归类为推断）。HeadFeel 的 OTA 面板没有 `hasEnglish`，配置也没有 OTA 语言切换；不能套用 i5 的 WQ 状态机、语言固件或 `keyId`。BES 会话未恢复，OpenRose 不实现 OTA，本节只作记录。证据：OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:702`）。
