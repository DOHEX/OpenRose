# ZT 协议族

RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）中有 3 个耳机配置使用 ZT：EarFree-i3、EarFeel-i5、HeadFeel。三者在构造时都实例化 `ZTReceiver` 和 `ZtSender` 的子类，共用广播识别、`08 EE` 发送帧、初始化分派、接收分派与大部分设置命令。i5 已在 Linux 上完成单副设备的 BLE 初始化读取实机验证；i3、HeadFeel 以及全部设置写入都没有实机证据。本文是整理 ZT 型号的入口，逐字节细节仍保留在 [EarFeel-i5 文档](../models/earfeel-i5-protocol.md) 的详细证据章节。

## 全族共用

三个配置的构造都分配 `ZTReceiver`；发送器按型号分为 `Zt219Sender`（i3）、`Zt229Sender`（i5）、`Zt231Sender`（HeadFeel）。接收处理由 ReceiveHandler 完成：i3 用 `Zt219ReceiveHandler`，它继承 `EarfreeI5ReceiveHandler` 并只增加 `hideSearchReceived`；i5 用 `EarfreeI5ReceiveHandler`；HeadFeel 用 `HeadfreeReceiveHandler`。因此同一 Tag 在不同型号上的字段处理可能不同，尤其电量、提示音等级、增益、自定义 EQ 和多点名称列表。

| 环节 | 结论 | 细节与证据 |
|---|---|---|
| 广播识别 | 取 BLE 广播的 `manufacturerData`：company ID 按小端排序拼接，再拼接各条 payload；用规范化 hex 的前 12、8、6 位依次查型号表，最长前缀优先。i3 使用 6 位前缀，i5/HeadFeel 使用 12 位前缀 | [i5 §2.1](../models/earfeel-i5-protocol.md#21-广播识别)、identifyDevice（0x90a2ac）（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`） |
| 广播字段 | 未发现 ZT 专用的扩展广播类型字段；`BaseDeviceConfig.getInfoFromRawData` 的 zt 分支会把 rawData 片段经 `to2` 格式化成地址样式并写入 `field_4b`/`field_4f`，但用途和与经典地址的对应关系未证明。连接应使用系统扫描对象的 remote id/address | getInfoFromRawData（0x748ddc）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:30`）、[i5 §2.1](../models/earfeel-i5-protocol.md#21-广播识别) |
| 控制通道选择 | i3/i5 配置 `sppSupport="false"`，只用 BLE；HeadFeel 配置允许 SPP，控制 SPP UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。是否实际走 SPP 还取决于系统是否暴露该 SPP 服务和连接管理器的选择 | i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:634`）、i5 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:773`）、HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:742`） |
| 控制特征 | 默认 BLE 控制服务 `011bf5da-0000-1000-8000-00805f9b34fb`，写特征 `00007777-0000-1000-8000-00805f9b34fb`，通知特征 `00008888-0000-1000-8000-00805f9b34fb`。HeadFeel 的 SPP 配置不改变这组 BLE 特征 | normalBleServiceConfig（0x74990c）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、[i5 §2.2](../models/earfeel-i5-protocol.md#22-gatt-uuid-与写入属性) |
| 发送帧 | `08 EE 00 00 00 \| group \| operation \| 总长度 LE16 \| payload \| 和校验`；总长度包含最后的校验字节，校验为前面所有字节之和的低 8 位。`ZtSender.transform` 原样返回，不再封装 | transform（0xdfaaf4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2926`）、checkSum（0x795b24）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:189`）、[i5 §3](../models/earfeel-i5-protocol.md#3-常规发送帧) |
| 初始化 | `GlobalController` 经 GDT+0xd8ba 按配置 cid 分派到各型号 `sendInitCommands`；没有全族统一的初始化函数。分派表项：HeadFeel 60078、i5 60079、i3 60080 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60078`）、i3 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:649`）、i5 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:788`）、HeadFeel 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:761`） |
| 命令编码 | 全族共用的设置/读取方法在 `ZtSender`；i3 覆盖噪声模式与 HideSearch，i5 覆盖寻机停止、ANC/通透等级与提示音等级，HeadFeel 覆盖语言、增益、音频、寻机停止、提示音、自定义 EQ、电量与多点名称列表 | ZtSender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`）、Zt219Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4804`）、Zt229Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4347`）、Zt231Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3002`） |
| 接收解析 | `ZTReceiver.process` 把通知转成无空格 hex，取 `hex[10:14]`（byte5/6）作 Tag，payload 从 byte9 开始；未验证 RX 固定前缀、长度、末字节校验或 CRC。已恢复的是字段偏移解析，不是完整帧校验器 | process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`）、[i5 §6.1](../models/earfeel-i5-protocol.md#61-分派和验证边界) |
| 客户端状态建议 | 底层 write 完成不等于设备确认；设置后串行读取对应状态并比较字段。没有业务事务 id，不要用“下一个回包”机械匹配；未知枚举保留 raw，不自动降级为关 | [i5 §7](../models/earfeel-i5-protocol.md#7-客户端状态同步与异常处理) |

### 接收 Tag 与处理

| Tag | 字段/处理 | 共享范围 | 状态 | 出处 |
|---|---|---|---|---|
| `0101` | i3/i5 电量：byte11 左、byte12 右、byte13/14 左右充电、byte15 盒，`FF` 表示盒数据缺失 | i3、i5 | 实机（i5 初始化读取） | _handleBatteryData（0xe53748）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3326`） |
| `0103` | HeadFeel 单体电量：只更新 `charge` 槽，左右固定 `FF` | HeadFeel | 静态 | _handleHeadfreeBatteryData（0xe535f4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`）、HeadfreeReceiveHandler.batteryDataReceived（0xe41a88）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:1189`） |
| `0d01` | 固件版本：byte9..11 各按 hex 转整数后拼接十进制文本（例 `9A 0B 0C` → `101112`） | 全族 | 实机（i5 `125`） | _handleFirmwareVersion（0xe533cc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2987`） |
| `0201` | EQ 预设：byte9 低 nibble `0..3` 映射 POP/HIFI/ROCK/LIGHT（按型号列表） | 全族 | 实机（i5 HIFI） | _handleEQData（0xe532c8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2889`） |
| `0202` | 自定义 EQ 数据；接收后交 `customEqDataReceived` | HeadFeel | 静态 | _handleCustomEqData（0xe531e0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2800`） |
| `0203` | 增益等级：wire `1..3` 交给 handler，handler 减 1 后映射 UI `0..2` | HeadFeel | 静态 | _handleGainLevel（0xe530bc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2690`）、HeadfreeReceiveHandler.gainLevelReceived（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:175`） |
| `0401` | 触摸绑定：byte9..16 低 nibble 按左右/手势槽位读出，交 `keyDataReceived` | 全族 | 实机（i5） | _handleKeyData（0xe52df8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2418`） |
| `0602` | 降噪模式：byte9 到结尾精确匹配 5 字节尾部；第五字节语义未证明 | 全族 | 实机（i5 抗风） | _handleANCData（0xe52914）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1939`） |
| `0603` | 游戏模式：byte9 低 nibble `0` 开、`1` 关 | 全族 | 实机（i5 关） | _handleGameMode（0xe52cf4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2321`） |
| `0604` | 通透等级：byte9 完整字节 `0/1/2` → UI `1/3/5` | i5、HeadFeel | 实机（i5 1 档） | _handleTransparencyLevel（0xe52724）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1751`） |
| `0605` | 降噪轮换：byte9..12 低 nibble 依次为普通/通透/抗风/ANC 是否参与 | i5、HeadFeel | 实机（i5） | _handleAncList（0xe5201c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1087`） |
| `0607` | ANC 等级：byte9 完整字节 `0/1/2` → UI `1/3/5` | i5 | 实机（i5 5 档） | _handleANCLevel（0xe5281c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1845`） |
| `0801` | 音频/多点：byte9 低 nibble `0` AAC/SBC+多点、`1` LDAC+单点；i5/i3 共用 | i3、i5 | 实机（i5 LDAC） | _handleConnectionMode（0xe52af0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2127`） |
| `0802` | 多点连接状态；byte9 低 nibble `1` 开、`0` 关 | HeadFeel | 静态 | _handleMultiDevice（0xe51f14）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:989`） |
| `0803` | 多点设备名称列表 | HeadFeel | 静态 | _handleMultiDeviceNameList（0xe51d1c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:789`） |
| `0a01` | 触摸功能：byte9 低 nibble `0` 开、`1` 关 | 全族 | 实机（i5 开） | _handleTouchControl（0xe52414）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1455`） |
| `0b01` | 隐藏搜索：i3 专用；处理 `hideSearchReceived` | i3 | 静态 | _handleHideSearch（0xe51c14）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:691`） |
| `0c01` | 佩戴检测功能：byte9 低 nibble `0` 开、`1` 关 | i5、HeadFeel | 实机（i5 关） | _handleInEarDetection（0xe52bf0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2224`） |
| `0e02` | 寻机：byte9 方向 `00` 左、`01` 右、`02` 双耳；byte10 状态 | i5、HeadFeel（HeadFeel 未见 `findMeReceived`） | 静态 | _handleEarFind（0xe52340）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1372`） |
| `0e03` | 提示音等级：i5 handler 对 byte9 加 1；HeadFeel handler 直接取值 | i5、HeadFeel | 实机（i5 2 档） | _handlePromptToneLevel（0xe5261c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1651`）、_handlePromptToneLevelHeadfree（0xe52518）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1552`） |
| `0c00`、`0e06`、`0b00` | 共享分派中的额外分支，分别组合调用等级/提示音处理；适用型号和触发条件未核清 | 未定 | 未知 | process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`） |

命令键不是 `0x80` 清除后的读写对应关系；设置和查询使用不同 operation，不要把设置回包自动当成查询 Tag 的确认。

## 按型号变化

| 项 | 去哪里查 |
|---|---|
| 识别前缀、SPP 是否允许、控制/OTA 的 SPP UUID、CMIIT | model_registry.json（`rose_reverse/analysis/model_registry.json`）；逐型号文档的“识别/传输”表有摘录和行号 |
| 配置类 cid、覆写方法、显示名与内部名 | 各配置类构造函数与 `customConfigEarObject`：i3 592/613/617/634，i5 737/763/767/773，HeadFeel 702/732/736/742/744/746 |
| 初始化顺序与目标 | 各配置的 `sendInitCommands`；分派表项 60078–60080 |
| 控件与取值 | 各配置类中 `Allocate*WidgetModel` 的调用；噪声/EQ/音频/触控列表由 `pp.txt` 对象池解引用，逐型号文档取值表 |
| 噪声 payload 宽度与寻机停止 operation | i3 覆盖为 3 字节 payload，i5/HeadFeel 4 字节；i5 停止 `0E 82 03 00`，HeadFeel 停止 `0E 02 03 00` |
| 电量 Tag 与槽位 | i3/i5 `0101` 左/右/盒；HeadFeel `0103` 单体复用 charge |
| 提示音等级语义 | i5 `0E 83` + UI−1；HeadFeel `0E 03` + UI 原值；接收加 1 规则也不同 |
| 固件门槛 | HeadFeel 自定义 EQ 与增益 `0x104`（Dart Smi，实际 130）；其他型号没有配置门槛 |
| OTA 类型与语言 | i3/i5 为 WQ，i3/i5 的 OTA 面板有 `hasEnglish`；HeadFeel 为 BES，无语言开关 |

## 控件与命令

控件模型类定义在 `roselink/rose_bluetooth/config/widget_model.dart`，界面共享，经 `RCommander` 调用当前配置的 Sender。下表的“Sender 方法”只写方法名；写“结构”表示容器或展示控件，写“范围外”表示 OTA 或云端业务入口，写“—”表示没有找到对应方法。“按方法名推断”表示 Sender 有名称相符的方法，但控件实际是否调用、参数如何取值尚未逐型号核对。

`analysis/model_brief.py` 已注册 `zt` 并会读取本表，但它只按控件类名套用全族方法列表，不按型号过滤：例如 i3 简报会列出 `setEqLight` 和长按触控，而 i3 配置没有这些入口；HeadFeel 简报也会列出 `setEqLight`，但 HeadFeel 配置没有 LIGHT 预设。工具输出只能当作候选列表，型号能力以型号文档的“设置命令”表为准，不能声称工具已验证族映射。

| 控件类 | 功能 | Sender 方法 | 说明 |
|---|---|---|---|
| `RDeviceComponentDisplayBarWidgetModel` | 电量展示 | 结构 | 电量槽取型号配置的 `BatteryType`；i3/i5 左/右/盒，HeadFeel 单体复用 charge |
| `RDeviceComponentNoiseBarWidgetModel` | 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeWind`、`setNoiseModeTrans` | 可选模式取 `NoiseType`；i3 列表无抗风且发送 3 字节 payload，i5/HeadFeel 为 4 字节；控件调用按方法名推断 |
| `DeviceEqPanelItemWidgetModel` | EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight` | 预设取 `DeviceEQTypeModel`；i3 无 LIGHT，i5 有 LIGHT，HeadFeel 另有自定义曲线（`setEqCustom`、`setEqCustomData`、`getCustomEq`）；控件调用按方法名推断 |
| `DeviceOptionPanelWidgetModel` | 面板容器 | 结构 | |
| `DeviceGamePanelItemWidgetModel` | 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00` 开、`01` 关；按方法名推断 |
| `DeviceMulDevicePanelItemWidgetModel` | 多点连接 | `setConnectMulOn`、`setConnectMulOff` | i3/i5 与音频模式耦合（`08 81`），HeadFeel 独立 `08 82`；i5 与 LDAC 互锁；按方法名推断 |
| `DeviceAudioPanelItemWidgetModel` | 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLdacLhdc` | i3/i5 仅 AAC/SBC、LDAC；HeadFeel 另有 LHDC、LDAC+LHDC；按方法名推断 |
| `RDeviceTouchSettingItemWidgetModel` | 触控 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong`、`setTouchOn`、`setTouchOff` | i3 仅单/双/三击、6 动作；i5 单/双/三/长、8 动作；HeadFeel 两键、8 动作；槽位/动作取值见各型号文档；控件调用按方法名推断 |
| `RDeviceFindItemWidgetModel` | 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | i5/HeadFeel 有入口；`setFindDeviceBoth` 原样调用左耳；i5 停止 `0E82`，HeadFeel 停止 `0E02`；按方法名推断 |
| `RDeviceMoreItemWidgetModel` | 更多设置入口 | 结构 | |
| `RDeviceStarburstBusinessEntryItemWidgetModel` | 星芒业务入口 | 范围外 | 云端业务，不是耳机控制协议 |
| `DeviceOtaPanelItemWidgetModel` | OTA | 范围外 | i3/i5 WQ，HeadFeel BES；独立通道 |
| `DeviceDetectionSwitchPanelItemWidgetModel` | 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | i5 有入口；HeadFeel 有接收处理但无设置入口；i3 无；按方法名推断 |
| `DeviceDingLevelPanelItemWidgetModel` | 提示音等级 | `setDingLevel` | i5 UI1..5→线值 00..04；HeadFeel UI1..5→线值 01..05；i3 无入口；按方法名推断 |
| `DeviceAncLinePanelItemWidgetModel` | 降噪轮换 | `setAncLine` | i5/HeadFeel；线序普通/通透/抗风/ANC；按方法名推断 |
| `RDeviceGainLevelItemWidgetModel` | 增益 | `setGainLevel`、`getGainLevel` | HeadFeel 专用；版本门槛 130，线值 01..03；按方法名推断 |
| `RDeviceHideSearchItemWidgetModel` | 隐藏搜索 | `setHideSearchOn`、`setHideSearchOff`、`getHideSearch` | i3 专用；Tag `0B01`，不是寻机；按方法名推断 |

## 全族已知的坑

- i3 的降噪设置是 3 字节 payload（`ANC=01 00 00`、`trans=00 01 00`、`normal=00 00 01`），不能复用 i5/HeadFeel 的 4 字节格式（Zt219Sender::setNoiseModeAnc（0x81a3fc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:5171`））。
- i3 的 `setNoiseModeWind` 直接调用 `setNoiseModeNormal`，配置的 `NoiseType` 列表也没有抗风；不要把“Sender 有 wind 方法”当成 i3 支持抗风（Zt219Sender::setNoiseModeWind（0x815fbc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:5151`）、i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:126`））。
- i3 的 HideSearch 是可见性控制，Tag `0B01`；不要把它当寻机 `0E02` 使用（i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:416`））。
- HeadFeel 的 `setFindOff` 发送 `0E 02 03 00`，而它的左/右寻机发送 `0E 82`；i5 的停止是 `0E 82 03 00`。这是 App 静态差异，设备是否接受未实测（Zt231Sender::setFindOff（0x7b399c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3205`）、Zt229Sender::setFindOff（0x7b394c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4349`））。
- i5 提示音设置用 `0E 83`，UI `1..5` 发送 `00..04`，接收 `0E 03` 后加 1；HeadFeel 设置用 `0E 03`，UI `1..5` 原样发送，接收不加 1。两型号不能共用同一个 setDing 实现（Zt229Sender::setDingLevel（0x8232d4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4663`）、Zt231Sender::setDingLevel（0x823394）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3537`）、接收处理（0xe52518/0xe5261c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1552`））。
- 等级命令的线值可能不一致：i5 的 `Zt229Sender` 覆盖 `setAncLevel`/`setTransLevel`，把 UI `1/3/5` 映射为 `00/01/02`；HeadFeel 继承基类，若 UI 也传 `1/3/5` 则发送 `01/02/03`，而 `HeadfreeReceiveHandler` 把回包 `00/01/02` 映射为 UI `1/3/5`。i3 没有等级 UI。HeadFeel UI 实际传参和写入/读回是否同一线值需实机确认（基类 setAncLevel（0x80b8e4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:426`）、Zt229Sender::setAncLevel（0x80b81c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523`）、Headfree 等级处理（0xdffcf8/0xe2f694）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:265`））。
- i3/i5 的 `0101` 电量是左/右/盒三槽；HeadFeel 的 `0103` 是单体复用 charge 槽。不要把 `getInfo` 的 `0101` 回包套到 HeadFeel，也不要用 `getBattery` 的 `0103` 解析 i5。
- `0602` 降噪模式回包按 byte9 开始的全部尾部精确匹配，第五字节性质未证明；不能用“第一个非零 flag”替代精确匹配（[i5 §6.3](../models/earfeel-i5-protocol.md#63-降噪模式尾部的精确匹配)）。
- i5/i3 的 `getConnectMul`/`setConnectMulOff` 走 `08 01` 或 `08 81 01`，与音频模式耦合；HeadFeel 的 `getConnectMul` 是 `08 02`，`setConnectMulOn/Off` 是 `08 82 01/00`，两者不能互换（Zt231Sender::getConnectMul（0xc77ea8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3950`））。
- 所有 ZT 型号的接收路径都没有验证 RX 固定前缀、长度字段和末字节校验；未知 Tag 直接忽略，不报错（process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`））。
- i5 只在 2026-10-18 验证了初始化读取；设置写入、持久化、寻机响铃和 OTA 都未实测（i5 实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。
- HeadFeel 初始化调用 `getLoseLoss`，但 `ZtSender`/`Zt231Sender` 中没有同名实现，分派表中也没有对应函数；该查询的帧和用途未知（HeadFeel 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:864`）、RCommander.getLoseLoss（0x8e29d4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:3883`））。
- i3/i5 是 WQ OTA，HeadFeel 是 BES OTA；OTA UUID、会话和固件格式都不同，不能把 i5 的 WQ 状态机套到 HeadFeel（i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:321`）、i5 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`）、HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`））。
- HeadFeel 的自定义 EQ 与增益门槛在配置里是 tagged Smi `0x104`，实际值 130；不要写成 260（HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:169`））。

## 维护

目前没有覆盖 ZT 全族的机器可读 Sender/Receiver 目录；[earfeel-i5-protocol.json](../models/earfeel-i5-protocol.json) 只描述 i5。本文的帧表和接收表是人工维护的族级依据，新增 ZT 型号时不要把它当成已生成的全族目录。

整理 ZT 型号时，先在本文补充全族共用事实，再在型号文档写差异。控件与命令的对应关系只维护本文的“控件与命令”表；`model_brief.py` 会读取本表，但不会按型号过滤，新增型号时仍要人工核对配置类和 Sender 调用点。新发现的接收 Tag 或字段处理差异写入本文的接收表和型号文档的“接收字段”。修改 EarFeel-i5 文档时保留其详细证据章节和既有锚点。
