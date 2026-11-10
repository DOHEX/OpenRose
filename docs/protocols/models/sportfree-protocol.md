# SportFeel 控制协议

SportFeel 使用配置类 `SportfreeDeviceConfig`（cid 535），分析基线为 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common 协议族](../families/rs-common.md)。本型号没有实机连接、广播抓包或通知抓包记录；以下结论全部来自静态分析与分派表核对，App 为该型号配置的控制命令是否被设备固件接受未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `0b0a0a0f` | `sportfree` | SportFeel | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:24`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:534`） |

系统设备名里可能出现的零售名称是 `SportFeel`；内部名称为 `sportfree`，代码枚举为 `SPORTFREE`（序号 21，objs.txt（`rose_reverse/analysis/blutter/objs.txt:40275`））。在开发文档与内部物料中亦称为 `SportFree`。配置中登记的 CMIIT ID 为 `26J51U5AK389`（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:21`）），该标识仅作设备认证信息，不参与蓝牙广播匹配或协议帧通信。识别只使用 4 字节（8 位十六进制）广播前缀 `0b0a0a0f`，经广播 `manufacturerData` 规范化后由扫描器统一匹配。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 允许（`true`） | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:564`） |
| 控制 SPP UUID | 同族默认 | 同族 | 同族 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |

SportFeel 在 `customConfigEarObject` 中显式配置 `sppSupport = "true"`，设置控制 SPP UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:564`））。连接管理在系统支持且未强制 BLE 时优先使用 SPP 控制通道。未配置 OTA 服务或 OTA SPP UUID。各项默认值详见 [RS Common 默认传输](../families/rs-common.md#默认传输)。

## 初始化

配置类 cid 535 在全局分派表中对应表项 60113（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60114`）），目标为共享匿名闭包 `0x8e2444` `SportfreeDeviceConfig::<anonymous closure>`（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:574`））。

该闭包直接调用 `RCommander.getInfo()`（r_commander.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:2104`） / rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`）），不发送其它查询帧。

完整发送帧如下：

```text
FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA   getInfo
```

`getInfo` 的 BODY 包含 14 个查询 ID。该闭包系 AOT 编译期与其它 RS Common 配置合并的公共初始化闭包，包含 `08`（佩戴）、`09`/`12`/`2C`/`2D`（降噪/通透）、`2A`（EQ）等 ID，但 SportFeel 配置中未开放相应控件，查询存在并不代表硬件具备对应能力。回包处理见“接收字段”。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左耳、右耳两槽；无充电盒槽 | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:105`） |
| 回包字段 | `input8,10)` 左耳、`input[10,12)` 右耳、`input[12,14)` 充电盒；16 进制解析，最高位（bit7）为入仓标志 | 静态 | [RsCommonReceiver.process（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`） |
| 本型号消费方式 | 更新左耳、右耳两个响应式百分比字符串；盒电量若为 `0xFF` 打印“没有充电仓数据”，界面不展示盒电量 | 静态 | RsCommonReceiveHandler（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:380`） |

电量查询随初始化 `getInfo`（ID `0C`）下发，回包 Tag 为 `0C`。`RsCommonReceiver` 构建 `RBatteryData` 传递给 `RsCommonReceiveHandler.batteryDataReceived`。本型号电量展示条（`RDeviceComponentDisplayBarWidgetModel`）构造时仅传入 `[BatteryType.left, BatteryType.right]`（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:105`）），因此 App 界面只展示左耳与右耳电量，忽略充电盒槽位。

## 设置命令

BODY 从命令字节开始，完整发送帧格式为 `FF 00 LL BODY AA`，无校验和。SportFeel 控件均无固件版本门槛（`Smi` 为空）。设置写入没有实机验证，状态均为静态；设备端是否接受命令在实测前未知。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右两槽 | — | `getInfo`，回包 Tag `0C` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:102`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0E 01` / `0E 00` | 开=`01`、关=`00` | — | `getInfo`，回包 Tag `0E` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:120`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1738`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `2B 04` / `2B 00` | 开=`04`、关=`00`（关闭同时切回 AAC/SBC） | — | `getInfo`，回包 Tag `2B` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:133`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2211`） |
| 面板容器（主页） | 结构 | — | 容器（内含游戏模式、多点连接） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:188`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `5E 01` / `5E 02` / `5E 03` / `5E 04` | 左耳=`01`、右耳=`02`、双耳=`03`、停止=`04` | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:197`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:195`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `01 <slot> 0<action>` | 槽位：左单=`01 01`、左双=`01 02`、左三=`01 03`、右单=`01 11`、右双=`01 12`、右三=`01 13`；动作：无功能=`0`、播放暂停=`1`、上一曲=`2`、下一曲=`3`、音量加=`4`、音量减=`5`（无长按手势；无游戏/语音助手/ANC动作） | — | `getInfo`，回包 Tag `01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:206`）；RTouchInfoModel（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:487`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1372`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `0E 00` / `0E 02` | 开=`00`、关=`02` | — | `getInfo`，回包 Tag `07` | 静态 | RTouchInfoModel（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:504`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:879`） |
| 面板容器（主页） | 结构 | — | 容器（内含寻机、触控设置） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:243`） |
| 更多设置入口 | 结构 | — | 二级页面入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:252`） |
| 面板容器（主页） | 结构 | — | 容器（内含更多设置入口） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:286`） |
| 提示音音量 | `setDingLevel` | `5C <level>` | 界面 1–5 级 = 线值 `01`–`05` | — | `getInfo`，回包 Tag `2E` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:338`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:697`） |
| 面板容器（二级页） | 结构 | — | 容器（内含提示音音量） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:382`） |
| 空间音频 | `setSpatialAudioSwitchOn`、`setSpatialAudioSwitchOff` | `33 01` / `33 00` | 开=`01`、关=`00` | — | `getInfo`，回包 Tag `33` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:391`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1886`） |
| 面板容器（二级页） | 结构 | — | 容器（内含空间音频） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:419`） |

说明：
1. **触控手势与动作**：`RTouchInfoModel` 显式配置了 3 个手势档位 `[TouchCtlType.one, TouchCtlType.two, TouchCtlType.three]`（单击、双击、三击），未配置 `TouchCtlType.long`（长按）。可选动作列表配置为 6 项（`touch-none(0)`、`touch-play-pause(1)`、`touch-pre-music(2)`、`touch-next-music(3)`、`touch-up-volume(4)`、`touch-down-volume(5)`），未包含 `touch-game(6)`、`touch-assistant(7)` 或 `touch-anc-control(8)`。因此共享 Sender 中的 `setLeftTouchLong` 与 `setRightTouchLong` 不会被本型号 UI 调用。
2. **多点连接**：`DeviceMulDevicePanelItemWidgetModel` 传入参数 `audioModeIsMulDeviceValue=4`、`audioModeIsNotMulDeviceValue=0`。开启发送 `2B 04`，关闭发送 `2B 00`（复用 `setAudioAacSbc`，切回 AAC/SBC 模式）。
3. **空间音频**：`DeviceSpatialSwitchPanelItemWidgetModel` 通过 `DeviceSpatialSwitchIntent.setSpatialSwitch` 绑定 `setSpatialAudioSwitchOn`（`33 01`）与 `setSpatialAudioSwitchOff`（`33 00`）；Sender 中的 `setSpatialAudioModeHead` 与 `setSpatialAudioModeOff` 分别为此二者的别名。
4. **二级页面**：提示音音量与空间音频控件挂载在 `field_2b`（二级页面列表），由主页的“更多设置”（`RDeviceMoreItemWidgetModel`）入口调出。

## 接收字段

SportFeel 直接使用族默认的 `RsCommonReceiver` 和 `RsCommonReceiveHandler`，接收分派逻辑与全族共用实现一致。本型号在各 Tag 上的取值与消费差异如下：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `01` | 按键映射 | 6 个十进制切片 `11,13)`、`[15,17)`、`[19,21)`、`[31,33)`、`[35,37)`、`[39,41)`；本型号仅配置单击、双击、三击（无长按），动作码限 0–5 | 静态 | [RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:119`） |
| `07` | 触控开关 | `value == '0'` 为关，其余为开；更新触控总开关状态 | 静态 | RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1512`） |
| `0C` | 电量 | byte8–9 左耳、byte10–11 右耳、byte12–13 充电盒；16 进制解析；仅消费左、右电量，界面无充电盒展示 | 静态 | RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`）；RsCommonReceiveHandler（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:380`） |
| `0D` | 固件版本 | `input9,14)` 3 段 hex 数值以空格拼接为版本字符串 | 静态 | [RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:808`）；RsCommonReceiveHandler（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:10`） |
| `0E` | 游戏模式 | `value == '1'` 为开，其余为关；更新游戏模式开关状态 | 静态 | RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:710`） |
| `2B` | 多点连接 | 十进制解析数值，`4` 表示多点连接开启，`0` 表示多点关闭（切回 AAC/SBC） | 静态 | F8Receiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:882`） |
| `2E` | 提示音音量 | 十进制解析数值 1–5，更新提示音音量档位 | 静态 | F8Receiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:507`） |
| `33` | 空间音频 | `value == '1'` 为开，其余为关；更新空间音频开关状态 | 静态 | RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:612`） |
| `08`、`09`、`2A`、`2C`、`2D` | 佩戴检测、降噪模式、EQ 预设、ANC 等级、通透等级 | 共享分派存在并由 `getInfo` 查询，但本型号未配置相应控件，界面不展示 | 静态 | RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`）；配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:101`） |

## 未实现与未知

以下缺口的查证范围为 `SportfreeDeviceConfig`、`RsCommonSender`、`RsCommonReceiver`、`RsCommonReceiveHandler`、UI 控件交互实现以及 `analysis/` 下的机器索引；没有 SportFeel 实机样本。

- **无实机验证**：本型号没有任何实机连接抓包或通知样例，所有帧格式与取值均归纳自 Dart AOT 静态反汇编；设备端是否真正实现并接受各控制命令属于未知。
- **`0E` 命令码复用**：Sender 中 `0E 00` 同时代表“触控总开关开”与“游戏模式关”，`0E 01` 为“游戏模式开”，`0E 02` 为“触控总开关关”。耳机固件如何在单一字节中区分这两种状态变更未知，可能存在开关竞态或固件端特殊解析逻辑。
- **`2B` 命令码复用**：`2B 04` 代表开启多点连接，而关闭多点连接发送 `2B 00`（切回 AAC/SBC 编码）。如果耳机工作在其它音频编码下，关闭多点连接是否会强制变更编码未知。
- **触控手势限制**：配置中显式排除了长按手势与游戏/语音助手/ANC 动作选项。硬件触控传感器是否支持长按未知；App 界面不提供长按绑定入口。
- **接收字符串物理布局**：`RsCommonReceiver.process` 按照固定字符下标切片解析 Tag 与参数，未校验帧头、长度、校验和，也未处理字节对齐与分包。通知在底层 BLE/SPP 通道中是否包裹外层报文或包含空格前缀未知。
- **充电盒电量**：Handler 中包含充电盒电量解析逻辑（如果字节为 `0xFF` 则记录无充电盒日志），但配置中未分配充电盒展示槽；设备端是否通过回包上报盒电量未知。
- **寻机无读回状态**：寻机命令（`5E 01`–`5E 04`）为动作触发型命令，Receiver 中未定义 Tag `5E`，耳机是否响铃及响铃状态无法通过查询读回。
- **固件门槛缺失**：所有控件模型均未传入版本门槛整数，静态上在所有固件版本下均展示全部已配置控件；设备低版本固件对高级功能（如空间音频、多点连接）的兼容门槛未知。

## OTA

SportFeel 在 App 配置中没有配置 OTA 路由：
- `SportfreeDeviceConfig` 未调用 `normalBleOtaServiceConfig`，也未配置 OTA SPP UUID（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:532`））。
- 控件列表中未添加 `DeviceOtaPanelItemWidgetModel`，用户界面没有固件升级入口（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:101`））。
- `otaType` 保持基类默认值（静态分析中未见显式分配）。

App 层面本型号不支持通过蓝牙协议进行 OTA 固件更新。OpenRose 不实现 OTA，本节仅作记录。
