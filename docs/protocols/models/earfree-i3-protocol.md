# EarFree-i3 控制协议

配置类 `EarfreeI3DeviceConfig`（cid 502），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [ZT 协议族](../families/zt.md)。没有 i3 实机记录；以下结论均为静态分析，设置命令是否被设备接受未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `f10203`、`010203` | `earfree-i3` | `EarFree-i3` | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:10`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:592`） |

配置中的显示名是 `EarFree-i3`，内部名是 `earfree-i3`。配置没有 CMIIT ID。两个识别前缀都按广播 `manufacturerData` 规范化后的最长前缀匹配；i3 使用 6 位前缀，匹配顺序仍在 12/8/6 位中完成（identifyDevice（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`））。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认（否；i3 配置 `sppSupport="false"`） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:634`） |
| 控制 SPP UUID | 同族默认（未配置） | 静态 | 同上 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认（i3 未单独核对） | 同族 | 同族 |
| OTA 服务 | `65786365-6c70-6f69-6e74-2e636f6d0000` | 静态 | normalBleOtaServiceConfig（0x824cf8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`） |
| OTA 写入/通知特征 | `65786365-6c70-6f69-6e74-2e636f6d0001` | 静态 | 同上 |

i3 的 `customConfigEarObject` 调用了 `normalBleOtaServiceConfig`，OTA 类型为 WQ（构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:30`））；BLE 服务/特征沿用 ZT 默认值。

## 初始化

i3 的初始化只有 8 条查询，没有 `getVersion`、佩戴检测、ANC/通透等级、提示音等级、降噪轮换或寻机查询。

| 次序 | App 方法 | 完整 TX hex | 读回 Tag | 状态 | 出处 |
|---:|---|---|---|---|---|
| 1 | `getInfo` | `08 EE 00 00 00 01 01 0A 00 02` | `0101` | 静态 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`） |
| 2 | `getAnc` | `08 EE 00 00 00 06 02 0A 00 08` | `0602` | 静态 | getAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:801`） |
| 3 | `getEq` | `08 EE 00 00 00 02 01 0A 00 03` | `0201` | 静态 | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2731`） |
| 4 | `getGameMode` | `08 EE 00 00 00 06 03 0A 00 09` | `0603` | 静态 | getGameMode（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2392`） |
| 5 | `getAudioLdac` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 静态 | getAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`） |
| 6 | `getKey` | `08 EE 00 00 00 04 01 0A 00 05` | `0401` | 静态 | getKey（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1936`） |
| 7 | `getTouch` | `08 EE 00 00 00 0A 01 0A 00 0B` | `0a01` | 静态 | getTouch（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1407`） |
| 8 | `getHideSearch` | `08 EE 00 00 00 0B 01 0A 00 0C` | `0b01` | 静态 | Zt219Sender::getHideSearch（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4942`） |

初始化顺序证据：sendInitCommands（0x8e2498）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:649`）；分派表项 60080（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60080`））。

## 电量

`0101`，左/右/盒三槽，偏移与 i5 相同：byte11 左、byte12 右、byte13/14 左右充电、byte15 盒，`FF` 表示盒数据缺失。i3 使用 `Zt219ReceiveHandler`，它继承 `EarfreeI5ReceiveHandler`，电量处理直接复用 i5 的 `batteryDataReceived`；i3 自己没有电量实机样本。详见 [族文档接收 Tag](../families/zt.md#接收-tag-与处理) 和 [族文档电量](../families/zt.md#电量)。

## 设置命令

BODY 从 group 开始，长度和校验按族文档发送帧规则生成。i3 的设置写入没有实机证据，状态均为静态；设备是否接受在实测前未知。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | `getInfo` | `01 01` | 左/右/盒 | — | `0101` | 静态 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans` | `06 82` | ANC=`01 00 00`；普通=`00 00 01`；通透=`00 01 00` | — | `0602` | 静态 | Zt219Sender::setNoiseModeAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:5171`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `02 81` | POP=`00`、HIFI=`01`、ROCK=`02` | — | `0201` | 静态 | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2664`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `06 83` | 开=`00`、关=`01` | — | `0603` | 静态 | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2325`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `08 81` | 开=`00`、关=`01`；关闭同时切 LDAC | — | `0801` | 静态 | setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2932`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac` | `08 81` | AAC/SBC=`00`、LDAC=`01` | — | `0801` | 静态 | setAudioAacSbc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2131`） |
| 触摸绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `04 81` | `[side][gesture][action]`；side 左=`00` 右=`01`；gesture 单=`00` 双=`01` 三=`02`；action 见下 | — | `0401` | 静态 | setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1878`） |
| 触摸功能 | `setTouchOn`、`setTouchOff` | `0A 81` | 开=`00`、关=`01` | — | `0a01` | 静态 | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1340`） |
| 隐藏搜索 | `setHideSearchOn`、`setHideSearchOff` | `0B 81` | 开=`01`、关=`00` | — | `0b01` | 静态 | Zt219Sender::setHideSearchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4873`） |
| OTA | 范围外 | — | — | — | — | 未知 | OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:321`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:193`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:266`） |

i3 触摸动作列表只有 6 项：`00` 音量增加、`01` 音量降低、`02` 上一曲、`03` 下一曲、`05` 播放/暂停、`06` 无功能。没有语音助手和 ANC 轮换动作；手势列表只有单击/双击/三击，没有长按（触控列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:550`）、对象池（`rose_reverse/analysis/blutter/objs.txt:1027`））。

## 接收字段

i3 与族文档不同的处理只有 `0b01` 隐藏搜索：`_handleHideSearch` 把回包交给 `Zt219ReceiveHandler.hideSearchReceived`，后者把值写入控制器 `field_37`。其余 Tag 由继承的 `EarfreeI5ReceiveHandler` 处理，字段布局同 i5；但 i3 初始化不查询 `0d01`、`0c01`、`0607`、`0604`、`0605`、`0e03`，这些 Tag 即使被设备主动上报，也没有 i3 实机证据。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0b01` | 隐藏搜索 | payload byte9 的 hex 字符串等于 `00` 时控制器值为 true，否则为 false；该值传入界面开关。设置方法开=`01`、关=`00`，与 UI 回读极性不对称 | 静态 | 分派取值（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:646`）、等号与回调（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:749`）、字符串等号分派（`rose_reverse/analysis/i7_dispatch/table.tsv:4191`）、控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:1785`） |
| `0101`、`0201`、`0401`、`0602`、`0603`、`0801`、`0a01` | 电量、EQ、按键、降噪、游戏、音频、触摸 | 同 i5/族文档 | 静态 | [族文档](../families/zt.md#接收-tag-与处理) |
| `0d01`、`0c01`、`0604`、`0605`、`0607`、`0e03` | 版本、佩戴、等级、轮换、提示音 | 共享分派存在，但 i3 初始化不查询，未实测 | 未知 | process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`） |

## 未实现与未知

以下缺口的查证范围是 i3 配置类、`ZtSender`/`Zt219Sender`、`ZTReceiver`/`Zt219ReceiveHandler` 和现有索引；没有 i3 实机样本。

- 没有 i3 实机记录；所有设置写入、持久化、响铃范围和 OTA 都未知。
- i3 降噪设置是 3 字节 payload，和 i5/HeadFeel 的 4 字节格式不同；`0602` 回包是否仍为 5 字节尾部、第五字节含义都没有 i3 样本。
- `setNoiseModeWind` 只是 `setNoiseModeNormal` 的别名，配置的 `NoiseType` 列表也没有抗风；i3 的抗风能力不能从 Sender 方法名推断。
- i3 没有语音助手和 ANC 轮换触控动作，也没有长按手势；不要套用 i5 的 8 动作表。
- i3 的触控左右槽位到实际耳机的映射没有实机确认。
- i3 初始化不读版本、佩戴、等级、轮换、提示音和寻机；共享 Receiver 虽能解析这些 Tag，不能据此增加型号能力。
- 隐藏搜索写入方法开=`01`、关=`00`，而回包 byte9=`00` 时官方开关为 true；回包与写入存在不对称，设备是否按此返回以及实际可发现性未实测。其他字节官方均显示 false，第三方可保留未知原始值。
- `getInfoFromRawData` 的 ZT 分支会把 rawData 片段格式化成地址样式，但未证明是经典地址或连接地址；连接使用系统扫描对象地址。
- OTA 为 WQ，OTA 面板带 `hasEnglish`，但 i3 没有 i5 那样的云端元数据验证或完整 OTA 流程研究；不要把 i5 的 `keyId`、固件版本或状态机直接套到 i3。

## OTA

i3 配置的 OTA 类型是 WQ，使用与 i5 相同的 OTA GATT 服务/特征（见“传输”表），OTA 面板设置 `hasEnglish=true`，没有写固件版本门槛（配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:330`））。静态上支持通过 WQ 选择语言固件；没有 i3 的 OTA 元数据请求、资源下载或刷机验证，trackingName、固件版本和服务端记录都未核对，不要套用 i5 的 `keyId`。字节格式、传输窗口与 ACK、状态机见 [WQ 方案文档](../ota/wq.md)。OpenRose 不实现 OTA，本节只作记录。
