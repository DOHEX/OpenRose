# EXPLORER 控制协议

EXPLORER 使用 `ExplorerDeviceConfig`，属于 [F8 协议族](../families/f8.md)。分析基线为 RoseLink 3.8.0（versionCode 5310），APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。没有实机连接、广播或通知抓包；以下结论全部是静态分析。EXPLORER 是 F8 中唯一有专用 Sender 和专用接收处理器的型号。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| EXPLORER | `18840107` | `EXPLORER` | EXPLORER | 静态 | explorer_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:10`） |

系统设备名里可能出现的零售名称是 `EXPLORER`；内部 name 相同。没有发现已知旧名。CMIIT 型号核准代码是 `25J5151G1183`（构造器（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:10`）），它不进入 F8 帧。

EXPLORER 覆写了 `getInfoFromRawData`：先调用基类，再在规范化 hex 长度 ≥24 字符时取字符 22–23 写入 `color`（EarObject 字段 `0x27`）。`normalColorImageConfig` 在该字段为空时回退 `"01"`（覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:658`）；回退（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1127`））。这是 F8 中唯一解析广播颜色字段的配置；没有抓包确认广播实际携带该字段。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | `0000fdb3-0000-1000-8000-00805f9b34fb` | 静态 | base_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1030`） |
| 写特征 | `0000ff16-0000-1000-8000-00805f9b34fb` | 静态 | 同上 |
| 通知特征 | `0000ff17-0000-1000-8000-00805f9b34fb` | 静态 | 同上 |
| SPP 是否允许 | 是（`sppSupport="true"`） | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:715`） |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | 同上 |
| 广播中的经典地址 | F8 基类分支把两路输入的字符 10–21 解析为 `btAddress`；无抓包确认广播携带 | 推断 | base_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:124`） |
| 写入间隔 | 默认 100 ms；BleConnector + OHOS 为 350 ms | 静态 | connect_manager.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:467`） |
| OTA Service / 写 / 读 | `00007033-0000-1000-8000-00805f9b34fb` / `00002001-…` / `00002002-…` | 静态 | OTA 默认配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`） |
| OTA 路由 UUID | `00007034-0000-1000-8000-00805f9b34fb` | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:715`） |

`otaType` 是 `wq`。SPP 只有在平台支持、未强制 BLE、音频 Profile 不是 LE Audio 时才可能被选中；`otaUuid` 不是控制 Service，控制仍使用 `fdb3/ff16/ff17`（传输分析（`rose_reverse/analysis/ceramics_transport.md`））。

## 初始化

EXPLORER 的配置 cid 为 541，`sendInitCommands` 分派索引为 `4096 + 541 + 0xd8ba = 60119`，目标 `0x8e1d8c` `ExplorerDeviceConfig::sendInitCommands`（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60120`））。函数体就是本型号自己的覆写，依次发送 12 条查询（explorer_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:773`））：

```text
00 27 01 00 01 01   getBattery
00 27 01 00 01 0C   getAnc
00 27 01 00 01 04   getEq
00 27 01 00 01 08   getGameMode
00 27 01 00 01 0B   getAudioLdac
00 27 01 00 01 02   getVersion
02 27 01 00 01 05   getKey
00 27 01 00 01 09   getEarDetection
00 27 01 00 01 13   getDingLevel
00 27 01 00 01 11   getAncLevel
00 27 01 00 01 16   getTouch
00 27 01 00 01 0A   getEarLanguage
```

这 12 条与 CERAMICS-X/7034 相同，因为后两者的初始化槽也指向本实现。全部是查询，没有设置写入；实际执行受 `GlobalController` 的连接类型/USB 早退条件影响（global_controller.dart（`rose_reverse/analysis/blutter/asm/roselink/lib/global_controller.dart:2134`））。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左、右、盒三槽 | 静态 | explorer_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:111`） |
| 回包字段 | byte7 左、byte8 右、byte9 盒；bit7 为左/右充电位 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1639`） |
| 本型号消费方式 | 左、右、盒三路显示；盒数据有“没有充电仓数据”哨兵 | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:818`） |

电量查询是 `00 27 01 00 01 01`，回包 byte5=`01`。EXPLORER 的接收处理器继承 `CeramicsXReceiveHandler`，电量处理与 CERAMICS-X 相同。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右、盒三槽 | — | `getBattery`，回包 byte5=`01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:111`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans` | `00 2C 01 00 01 VV` | ANC=`01`、关闭=`00`（`ExplorerSender` 把 `setNoiseModeNormal` 委托给 `setNoiseModeWind`）、通透=`02` | — | `getAnc`，回包 byte5=`0C` | 静态 | 降噪条（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:129`）；Sender 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/sender/explorer_sender.dart:8`） |
| 降噪等级 | `setAncLevel`、`setTransLevel` | `00 30 01 00 01 0M` / `00 31 01 00 01 0M` | 低=`2`、中=`1`、高=`0`（Sender 把界面 1/3/5 映射到 2/1/0） | — | `getAncLevel`/`getAncTransLevel`，回包 byte5=`11`/`12` | 推断 | 等级条（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:1907`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `00 20 01 00 02 00 VV` | ROCK=`02`、HIFI=`00`、POP=`01` | — | `getEq`，回包 byte5=`04` | 静态 | EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:162`）；分派（`rose_reverse/analysis/agate_models_findings.md`） |
| 面板容器 | — | — | 内含游戏/多点/音频/寻机/触控/更多/语言 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:198`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00 25 01 00 01 01` / `00 25 01 00 01 00` | 开=`01`、关=`00` | — | `getGameMode`，回包 byte5=`08` | 静态 | 游戏面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:207`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `00 2B 01 00 01 00` / `00 2B 01 00 01 02` | 开=`00`、关=`02`（关即 AAC/SBC） | — | `getAudioLdac`，回包 byte5=`0B` | 推断 | 多点面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:220`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioMulDevice` | `00 2B 01 00 01 02` / `… 01` / `… 00` | AAC/SBC=`02`、LDAC=`01`、多点=`00` | — | `getAudioLdac`，回包 byte5=`0B` | 静态 | 音频面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:245`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `00 2A 01 00 01 NN` | 左=`01`、右=`02`、双=`03`、关=`00` | — | 无查询；接收是事件 | 静态 | 寻机项（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:265`）；调用（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart`） |
| 触控绑定 | 8 个左右单/双/三/长方法 | `00 22 01 00 03 SS 01 0A` | `SS`：左单/右单=`01`/`02`、左双/右双=`03`/`04`、左三/右三=`05`/`06`、左长/右长=`07`/`08`；动作码 0、2、3、4、5、6、7、9 | — | `getKey`，回包 byte5=`05` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:274`） |
| 更多设置入口 | — | — | 容器 | — | — | 静态 | 更多项（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:283`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `00 33 01 00 01 01` / `00 33 01 00 01 00` | 开=`01`、关=`00` | — | `getTouch`，回包 byte5=`16` | 推断 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1067`） |
| 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish` | `00 29 01 00 01 00` / `… 01` | 中文=`00`、英文=`01` | — | `getEarLanguage`，回包 byte5=`0A` | 推断 | 语言项（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:292`） |
| 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | `00 26 01 00 01 01` / `00 26 01 00 01 00` | 开=`01`、关=`00` | — | `getEarDetection`，回包 byte5=`09` | 静态 | 检测面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:450`） |
| 提示音音量 | `setDingLevel` | `00 32 01 00 01 0N` | 档位=`N`（十进制个位；界面档位上限未核对） | — | `getDingLevel`，回包 byte5=`13` | 推断 | 提示音面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:501`） |
| OTA | 范围外 | — | `wq`，`7033/2001/2002` | — | — | 静态 | OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:357`） |

EXPLORER 的 EQ 使用专属对象键 `eq_custom_ROCK_01(2)`、`eq_custom_HIFI_01(1)`、`eq_custom_POPS_01(0)`，与 AGATE 的 `Rock(0)/HIFI(1)` 不是同一组常量；不能把 AGATE 的标签→线值映射套过来。降噪模式只有 ANC/关闭/通透三个 UI 项，但“关闭”实际走抗风命令 `2C 00`；抗风没有独立 UI 项。

## 接收字段

EXPLORER 的处理器是 `ExplorerReceiveHandler`（cid 430，`extends CeramicsXReceiveHandler`），只覆写 `ancDataReceived`（explorer_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/explorer_receive_handler.dart:8`））。其余 Tag 的处理继承 CERAMICS-X：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `01` | 电量 | 左、右、盒三槽；盒哨兵表示无数据 | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:818`） |
| `02` | 固件版本 | byte9–10 按 hex 解析后转字符串 | 静态 | rs_common_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:10`） |
| `04` | EQ 当前值 | 线值 `0`→控制器 `2`；`1`→`0`；`2`→`4` | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:634`） |
| `05` | 按键绑定 | 按字符索引 `[19,31,43,55,25,37,49,61]` 从整帧取 8 个动作 | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:385`） |
| `08` | 游戏模式 | byte7 精确等于 `"01"` 为开 | 静态 | headfree_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:635`） |
| `09` | 佩戴检测 | 由 `EarfreeI5ReceiveHandler` 实现，本型号有对应面板 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:608`） |
| `0A` | 提示音语言 | 由 `RsCommonV2ReceiveHandler` 实现 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:13799`） |
| `0B` | 音频模式 | 原始 `0`→`7`（多点）、`1`→`1`（LDAC）、`2`→`0`（AAC/SBC） | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:308`） |
| `0C` | 降噪模式 | **本型号覆写**：原始 `0`→控制器 `0`、`1`→`6`、`2`→`2`；没有 `6`→`0` 分支 | 静态 | explorer_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/explorer_receive_handler.dart:10`） |
| `11` / `12` | ANC / 通透等级 | 原始 `0`→`10`、`1`→`6`、`2`→`2` | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:10`） |
| `13` | 提示音音量 | 由 `EarfreeI5ReceiveHandler` 实现，本型号有对应面板 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:523`） |
| `16` | 触控开关 | 由 `EarfreeI5ReceiveHandler` 实现（与 X 不同，X 是 thrower） | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:577`） |

降噪回包的核心差异：EXPLORER 的原始 `0` 映射到控制器 `0`，CERAMICS-X 的原始 `0` 映射到 `4`；EXPLORER 不识别原始 `6`。由于 `setNoiseModeNormal` 实际发的是抗风帧 `2C 00`，界面“关闭”与设备原始值 0 的对应关系仍需实机确认。

## 未实现与未知

- **降噪模式语义**：UI 只有 ANC/关闭/通透，但“关闭”发送 `2C 00`（`setNoiseModeWind`）；设备是否把该值解释为关闭未知。回包原始值与 UI 标签的映射也未闭合。
- **EQ 标签/线值**：ROCK/HIFI/POP 的模型值与 `setEqRock/Hifi/Pop` 对齐，但设备端预设语义未验证。
- **按键 8 值结构**：索引读取 8 个动作，设备回包长度和缺失值行为未知。
- **提示音档位**：`DeviceDingLevelPanelItemWidgetModel` 的档位范围未逐项核对；`setDingLevel` 只接受单个十进制位。
- **寻机**：只有发送命令，没有状态查询；接收是事件，是否响铃/停止的时序未验证。
- **SPP 实际使用**：配置允许且带 UUID，但平台、全局偏好和音频 Profile 都可能让它仍走 BLE。
- **广播颜色字段**：`getInfoFromRawData` 的字符 22–23 解析已核对，但广播是否携带、颜色值如何映射到资源未验证。
- **OTA**：配置为 `wq`，没有恢复升级协议；OpenRose 不实现 OTA。
- **无实机**：没有固件版本、广播、通知或写入抓包；所有设备行为未知。

查证范围：`ExplorerDeviceConfig`、`ExplorerSender`、`f8_sender.dart`、`f8_receiver.dart`、`ExplorerReceiveHandler`、`analysis/i7_dispatch/table.tsv` 及 F8 发送/接收笔记；没有实机记录可闭合设备行为。

## OTA

EXPLORER 的 `otaType` 是 `wq`，调用 `normalBleOtaServiceConfig`，OTA Service/写/读为 `00007033-…` / `00002001-…` / `00002002-…`，`otaUuid` 为 `00007034-…`（customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:715`））。这是 App 的 OTA 路由配置，不是已恢复的升级协议；OpenRose 不实现 OTA，此节只作记录。
