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
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 是（配置 `sppSupport="true"`） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:742`） |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:744`） |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认（HeadFeel 未单独核对） | 同族 | 同族 |
| OTA 通道 | 见 [BES 方案](../ota/bes.md#通道与-uuid) | 静态 | [BES 方案](../ota/bes.md#通道与-uuid) |

HeadFeel 配置允许 SPP 并指定了控制 SPP UUID；普通 BLE 服务/特征沿用 ZT 默认值。OTA 使用独立通道，UUID 与归类详见 [BES 方案](../ota/bes.md#通道与-uuid)。连接与写入规则见 [ZT 族文档连接与写入](../families/zt.md#连接与写入)。

## 初始化

HeadFeel 的初始化有 17 条查询，包含自定义 EQ、增益、单体电量、音频/LoseLoss、多点状态与多点名称列表；没有佩戴检测和 ANC 等级查询。

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
| 12 | `getLoseLoss` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 静态 | RCommander.getLoseLoss（0x8e29d4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:3883`）、GDT 分派 67111 / ZtSender::getAudioLdac（0xc6e364）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`） |
| 13 | `getConnectMul` | `08 EE 00 00 00 08 02 0A 00 0A` | `0802` | 静态 | Zt231Sender::getConnectMul（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3950`） |
| 14 | `getMulDeviceNameList` | `08 EE 00 00 00 08 03 0A 00 0B` | `0803` | 静态 | Zt231Sender::getMulDeviceNameList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3885`） |
| 15 | `getAncTransLevel` | `08 EE 00 00 00 06 04 0A 00 0A` | `0604` | 静态 | getAncTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4458`） |
| 16 | `getDingLevel` | `08 EE 00 00 00 0E 03 0A 00 11` | `0e03` | 静态 | Zt229Sender::getDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4736`） |
| 17 | `getAncList` | `08 EE 00 00 00 06 05 0A 00 0B` | `0605` | 静态 | getAncList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:866`） |

初始化顺序证据：sendInitCommands（0x8e27b0）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:761`）；分派表项 60078（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60078`））。`RCommander.getLoseLoss`（`0x8e29d4`）通过 GDT 选择器 `0xf495` 分派（表项 67111，`0xc6e364`，由 Dart AOT 编译器与 `getAudioLdac` 相同逻辑去重合并），发送与 `getAudioLdac` 完全相同的 `0801` 查询帧 `08 EE 00 00 00 08 01 0A 00 09`，读回 Tag 亦为 `0801`。

## 电量

单体电量，复用 `BatteryType.charge` 槽；配置只列一个 `BatteryType`，不是左/右/盒三槽。HeadFeel 初始化与读取使用 `0103` 查询（`08 EE 00 00 00 01 03 0A 00 04`）。

`0103` 回包由 `ZTReceiver._handleHeadfreeBatteryData`（`0xe535f4`）处理：截取 `hex` 的第 18..20 字符（`substring(18, 20)`，立即数 40 为 Dart Smi 解码后的 20），即 payload 首字节（**byte 9**），按 16 进制解析为整数后写入 `RBatteryData.charge` 槽，左/右耳固定填 `0xFF`（缺失标志）；`HeadfreeReceiveHandler.batteryDataReceived`（`0xe41a88`）随后将 charge 槽更新为单体电量百分比。证据：电量处理（0xe535f4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`）、handler（0xe41a88）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:1189`）；详见 [族文档电量](../families/zt.md#电量)。

## 设置命令

BODY 从 group 开始，长度和校验按族文档发送帧规则生成。HeadFeel 的设置写入没有实机证据，状态均为静态；设备是否接受在实测前未知。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量读取 | `getBattery` | `01 03` | 单体 charge | — | `0103` | 静态 | Zt231Sender::getBattery（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4210`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeWind`、`setNoiseModeTrans` | `06 82` | ANC=`01 00 00 00`；普通=`00 01 00 00`；抗风=`00 00 01 00`；通透=`00 00 00 01` | — | `0602` | 静态 | 四分支（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:501`） |
| ANC 等级 | `setAncLevel` | `06 87` | UI `1/3/5` → 线值 `01/02/03` | — | `0607` | 静态 | 基类 setAncLevel（0x80b8e4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:426`） |
| 通透等级 | `setTransLevel` | `06 84` | UI `1/3/5` → 线值 `01/02/03` | — | `0604` | 静态 | 基类 setTransLevel（0x80b468）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:351`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `02 81` | POP=`00`、HIFI=`01`、ROCK=`02` | — | `0201` | 静态 | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2664`） |
| 自定义 EQ 选择 | `setEqCustom` | `02 81` | 自定义=`03` | 130 | `0201`/`0202` | 静态 | Zt231Sender::setEqCustom（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4156`） |
| 自定义 EQ 数据 | `setEqCustomData` | `02 82` + 10 字节 | UI 十段 `−6..+6`，步长 1；`toInt` 后正数原值、负数 `0x80\|abs` | 130 | `0202` | 静态 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4015`）、滑块（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:3886`） |
| 增益等级 | `setGainLevel` | `02 83` | UI `0/1/2` → 线值 `01/02/03` | 130 | `0203` | 静态 | Zt231Sender::setGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3239`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `06 83` | 开=`00`、关=`01` | — | `0603` | 静态 | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2325`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `08 82` | 开=`01`、关=`00` | — | `0802` | 静态 | Zt231Sender::setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4275`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLdacLhdc` | `08 81` | AAC/SBC=`00`、LDAC=`01`、LHDC=`02`、LDAC+LHDC=`03` | — | `0801` | 静态 | setAudioLdacLhdc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3611`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | 左/右/双耳 `0E 82`；停止 `0E 02` | 左/双耳=`00 01`；右=`01 01`；停止=`03 00` | — | `0e02` | 静态 | setFindDeviceLeft（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:208`）、Zt231Sender::setFindOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3205`） |
| 触摸绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchTwo`、`setRightTouchThree` | `04 81` | `[side][gesture][action]`；电源键 side=`00`；ANC 键 side=`01`；gesture 单=`00` 双=`01` 三=`02`；action 见下 | — | `0401` | 静态 | setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1878`）、touch.dart（0xb29f10）（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:2098`） |
| 触摸功能 | `setTouchOn`、`setTouchOff` | `0A 81` | 开=`00`、关=`01` | — | `0a01` | 静态 | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1340`） |
| 提示音等级 | `setDingLevel` | `0E 03` | UI `1..5` = `01..05` | — | `0e03` | 静态 | Zt231Sender::setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3537`） |
| 降噪轮换 | `setAncLine` | `06 85` | `[普通][通透][抗风][ANC]`，每项 `00/01` | — | `0605` | 静态 | setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`） |
| OTA | 范围外 | — | 见 [OTA](#ota) | — | — | 静态 | OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:208`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:293`） |

表中的 130 门槛来自 自定义 EQ 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:169`） 的 `customSinceVersion=0x104` 和 增益控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:217`） 的 `field_7=0x104`；这是 Dart tagged Smi，右移一位后为 130，不是 260。

HeadFeel 的触控配置（`RTouchInfoModel`，touchInfo（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:588`））包含电源键（`click-button-power`）和 ANC 键（`ANC`）两组，界面组件 `_RTouchCtlListState`（touch.dart（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:4600`））将电源键绑定至左耳槽位（`setLeftTouch*`，`side=00`），将 ANC 键绑定至右耳槽位（`setRightTouch*`，`side=01`）。电源键手势为单击/双击/三击，ANC 键为双击/三击，两键均不支持长按。动作列表共 8 项：`00` 音量增加、`01` 音量降低、`02` 上一曲、`03` 下一曲、`04` 语音助手、`05` 播放/暂停、`06` 无功能、`07` ANC 轮换（对象池（`rose_reverse/analysis/blutter/objs.txt:1027`））。

## 接收字段

HeadFeel 与族文档不同的处理：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0103` | 单体电量 | byte 9 为电量百分比；左/右固定设为 `0xFF`，只更新 `charge` 槽 | 静态 | _handleHeadfreeBatteryData（0xe535f4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`）、handler（0xe41a88）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:1189`） |
| `0202` | 自定义 EQ 数据 | 交 `customEqDataReceived`（`0xcd545c`）；对应写入的 10 个增益字节 | 静态 | _handleCustomEqData（0xe531e0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2800`） |
| `0203` | 增益等级 | wire `1..3`，handler 减 1 后映射 UI `0..2` | 静态 | _handleGainLevel（0xe530bc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2690`） |
| `0801` | 音频模式 / LoseLoss | byte 9 低 nibble（`hex[19:20]`）解析为整数，由 `audioModeReceived`（`0xe2fe50`）更新控制器；`getAudioLdac` 与 `getLoseLoss` 共用此回包 | 静态 | _handleConnectionMode（0xe52af0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2128`）、audioModeReceived（0xe2fe50）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:563`） |
| `0802` | 多点状态 | byte 9 低 nibble `1` 开、`0` 关 | 静态 | _handleMultiDevice（0xe51f14）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:989`） |
| `0803` | 多点设备名称列表 | 由 `ZTReceiver._handleMultiDeviceNameList`（`0xe51d1c`）解析，分派到 `HeadfreeReceiveHandler` 的空实现（GDT 25048，`0xe593f0`，直接返回 null），静默丢弃不更新状态 | 静态 | _handleMultiDeviceNameList（0xe51d1c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:789`）、GDT 分派 25048（0xe593f0）（`rose_reverse/analysis/i7_dispatch/table.tsv:25049`） |
| `0e03` | 提示音等级 | handler 直接取 byte 9，不加 1；UI 1..5 与线值 1..5 同值 | 静态 | _handlePromptToneLevelHeadfree（0xe52518）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1552`） |
| `0e02` | 寻机 | `ZTReceiver._handleEarFind`（`0xe52340`）分派到 `findMeReceived`（GDT 13596），因 `HeadfreeReceiveHandler` 未覆写而继承抛出 `RFunctionLostException`（`0xe41b38`），被 `BleConnector` 捕获跳过；回包不更新 UI | 静态 | _handleEarFind（0xe52340）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1372`）、抛出异常 stub（0xe41b38）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/r_receive_handler.dart:1094`）、捕获跳过（0xe553a0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:9221`） |
| `0604`、`0607` | 通透/ANC 等级 | 接收 `0/1/2` 由 `HeadfreeReceiveHandler` 映射为 UI `1/3/5`；设置时基类发送 `01/02/03`，读写线值存在 1 档偏差 | 静态 | transLevelReceived（0xdffcf8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:265`）、ancLevelReceived（0xe2f694）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:414`） |
| `0c01` | 佩戴检测 | handler 有 `inEarDetectionReceived`，但配置没有设置入口和初始化查询 | 静态 | HeadfreeReceiveHandler（0xe34ad4）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:883`） |
| `0101`、`0201`、`0401`、`0602`、`0603`、`0605`、`0a01`、`0d01` | 电量、EQ、按键、降噪、游戏、轮换、触摸、版本 | 同族文档；由 `HeadfreeReceiveHandler` 处理 | 静态 | [族文档](../families/zt.md#接收-tag-与处理) |

`0d02` 没有在 `ZTReceiver.process` 中找到分派；`getEarLanguage` 虽然存在，HeadFeel 初始化和控制器中没有使用它的路径。语言设置方法见“未实现与未知”。

## 未实现与未知

以下缺口的查证范围是 HeadFeel 配置类、`ZtSender`/`Zt231Sender`、`ZTReceiver`/`HeadfreeReceiveHandler` 和现有索引；没有 HeadFeel 实机样本。

- 没有 HeadFeel 实机记录；所有设置写入、持久化、响铃范围和 OTA 都未知。
- `getLoseLoss`：静态分析证明其分派至 `0xc6e364` 发送 `0801` 查询，但为什么在已发送 `getAudioLdac`（同样发送 `0801`）后重复查询同一 Tag，以及固件是否对二者有区分，缺乏实机证据。
- ANC 与通透等级的线值偏差：App 发送使用 `01/02/03`（基类 `ZtSender` 实现），而回包解析将 `00/01/02` 映射到 UI `1/3/5`；硬件固件实际返回的线值集未知，需实机验证。
- 寻机 `setFindOff` 发送 `0E 02 03 00`，左/右/双耳发送 `0E 82`；且 `0e02` 回包在 App 内部因 `findMeReceived` 未实现抛出 `RFunctionLostException` 被 `BleConnector` 吞掉，硬件实际响铃与停止逻辑需实机验证。
- 提示音设置使用 `0E 03` 且 UI `1..5` 原样发送，和 i5 的 `0E 83`/UI−1 不同；接收端也不加 1。没有 HeadFeel 回包样本。
- 增益和自定义 EQ 的固件门槛是 tagged Smi `0x104`，实际 130；配置门槛不等于设备已升级到该版本。
- 自定义 EQ 共用面板标签为 20、100、300、500、1k、2k、3k、5k、8k、15k Hz，范围 −6..+6、步长 1（标签（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2507`）、范围（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:1347`）、取整和限幅（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:3886`））；这些是界面参数，固件滤波中心、Q 值和实际声学效果未知。
- 初始化不查询 ANC 等级（`0607`），只查询通透等级和轮换；配置的 NoiseBar 等级 UI 与实际读回路径未闭合。
- SPP 配置允许，但广播中经典地址的解析、SPP 实际连接和特征选择都未核对。
- `getEarLanguage`/`setEarLanguageEnglish`/`setEarLanguageChinese` 在 Sender 中存在，但没有 HeadFeel 配置调用点，接收端也没有 `0d02` 分派；不能据此增加普通控制语言能力。
- `getInfoFromRawData` 的 ZT 分支会把 rawData 片段格式化成地址样式，但未证明是经典地址或连接地址；连接使用系统扫描对象地址。
- OTA 的固件兼容/签名、会话、断点续传、左右耳角色和覆盖后复位等详见 [BES 方案的未知与缺口](../ota/bes.md#未知与缺口)。

## OTA

HeadFeel 的 OTA 类型是 BES（`DeviceOtaType.bes`，枚举值 2，构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:30`）），属于 BES OTA 方案。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `bes` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:30`）、OTA 面板:351（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`） |
| 门槛 | 无（面板未写 `sinceVersion`） | 静态 | OTA 面板:351（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`）、[BES 型号表](../ota/bes.md#通道与-uuid) |
| hasEnglish | 无（面板未配置） | 静态 | OTA 面板:351（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`） |
| 语言切换 | 无（配置无 OTA 语言开关） | 静态 | 同上 |
| needCheckWwsMode | 未配置 | 静态 | 同上 |

OTA 通道与 UUID 见 [BES 方案](../ota/bes.md#通道与-uuid)。帧格式、传输窗口、分段与 ACK、状态机、固件下载链和完整缺口都在 [BES 方案文档](../ota/bes.md)，本文不重复。HeadFeel 的 BES 会话细节没有单独恢复，也没有实机 OTA 记录。OpenRose 不实现 OTA，本节只作记录。
