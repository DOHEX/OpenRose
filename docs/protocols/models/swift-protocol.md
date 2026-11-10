# SWIFT 控制协议

配置类 `SwiftDeviceConfig`（cid 534），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common 协议族](../families/rs-common.md)。本协议为初代旧版 SWIFT（RS Common）控制协议，不同于使用 RS Common V2 协议族的 [Swift 2 控制协议](swift2-protocol.md)（配置类 `Swift2DeviceConfig`、识别前缀 `5999064c`、支持 SPP、带帧和校验与 AB OTA）。商品 SWIFT 为骨传导耳机（程序分类 `head` 单体形态）。没有 SWIFT 实机连接、广播抓包或通知抓包记录；以下结论全部来自 Flutter AOT 静态反汇编分析，设置命令是否被真实设备接受在实机测试前未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售骨传导 | `0b0b0a0b` | `swift` | SWIFT | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:23`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:345`） |

系统设备名里可能出现的零售名称是 `SWIFT`，内部名是 `swift`。构造函数中记录了 CMIIT 型号核准代码 `2023DP10823`（构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:21`）），用于 App 内部信息展示，不参与广播识别，也不进入协议数据帧。识别按广播 `manufacturerData` 规范化十六进制字符串匹配，前缀为 8 字符 `0b0b0a0b`（注册表（`rose_reverse/analysis/model_registry.json`））。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认（否；`sppSupport="false"`） | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:361`） |
| 控制 SPP UUID | 同族默认（未配置） | 静态 | 同上 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |

SWIFT 的 `customConfigEarObject` 将 `sppSupport` 显式设为字符串 `"false"`，且未配置 SPP UUID，普通控制静态上只走 BLE 通道。具体默认值见 [RS Common 默认传输](../families/rs-common.md#默认传输)。配置未调用 `normalBleOtaServiceConfig`，没有 OTA 通道。

## 初始化

| 型号 | 配置 cid | 分派表索引 | 目标 |
|---|---:|---:|---|
| SWIFT | 534 | 60112 | `0x8e2444` `SportfreeDeviceConfig::<anonymous closure>` |

分派表索引计算为 `0x1000 + 0xd8ba + cid` = 60112，指向 `SportfreeDeviceConfig` 的共享初始化闭包（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60113`）、闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`））。该闭包只调用一次 `RCommander.getInfo`，下发全族共享的批量查询帧（19 字节）：

```text
FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA
```

出处：getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`）。该查询包含 14 个功能 ID（按键 `01`、触控 `07`、EQ `08`、LDAC `09`、降噪等级 `0C`、固件版本 `0D`、游戏/触控 `0E`、降噪模式 `12`、EQ预设 `2A`、音频模式 `2B`、提示音语言 `2C`、佩戴检测 `2D`、提示音音量 `2E`、空间音频 `33`）。SWIFT 作为单体骨传导，App 界面并未开放降噪、EQ、音频模式及佩戴检测等功能，查询存在不代表设备端具备对应能力。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 单体（复用充电仓槽，仅配置单个 `BatteryType.charge`） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:105`）、对象池（`rose_reverse/analysis/blutter/objs.txt:40296`） |
| 回包字段 | `RsCommonReceiver._handleBatteryData` 按字符切片解析出 3 个电量值，第 3 个切片为盒电量 | 静态 | _handleBatteryData（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`） |
| 本型号消费方式 | `SwiftReceiveHandler.batteryDataReceived` 只读取第 3 个电量（仓槽），写入 `deviceController.field_27`（`caseBattery`），`field_2f`（`caseCharging`）置 `false`；忽略左耳与右耳槽 | 静态 | swift_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/swift_receive_handler.dart:10`） |

SWIFT 为单体骨传导耳机（`head` 形态），App 仅分配了包含一个元素的电量展示列表 `List<BatteryType>(1) [BatteryType.charge]`（[pp+0xace0]）。在接收端，`SwiftReceiveHandler` 重写了 `batteryDataReceived`：
1. 仅解构 `RBatteryData` 偏移 0x17 处的数值（即输入串字符 `12,14)` 解析出的第三路仓电量整数）。
2. 若该值处于有效范围 $1 \le \text{value} \le 100$，则格式化为 `"${value}%"` 存入 `deviceController.field_27`（仓电量观察者），同时将 `field_2f`（仓充电状态）置为 `false`，并调用 `LocalStorage.saveCaseBattery` 存入本地缓存。
3. 若该值无效（$\le 0$ 或 $> 100$），则将 `field_27` 置为 `"-"`，并尝试异步调用 `LocalStorage.getCaseBattery` 读取历史缓存恢复显示。
4. 控制器的左耳（`field_1f`）、右耳（`field_23`）电量字符串及左右耳充电布尔值（`field_2b`、`field_33`）在此 Handler 中完全不被修改，保持初始的 `"-"` 与 `false`。

## 设置命令

完整发送帧为 `FF SS LL BODY AA`，`SS` 固定为 `00`，无校验和。SWIFT 在 App 配置中开放的控件全部位于主界面 `homeWidgetModels`，`moreWidgetModels` 为空列表。设置写入均为静态推断，未获实机验证。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | `getInfo` | `FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33` | 单体电量（复用仓槽） | — | 字符 `[12,14)`（电量） | 静态 | [getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2104`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | 开=`0E 01`、关=`0E 00` | 开=`1`、关=`0` | — | Tag `0E`（`input9,10) == '1'`） | 静态 | [setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1738`）、setGameModeOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1685`）、UI 调用（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_game_panel_item.dart:191`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | 左=`5E 01`、右=`5E 02`、双耳=`5E 03`、关=`5E 04` | 左=`1`、右=`2`、双耳=`3`、关=`4` | — | — | 静态 | setFindDevice（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:195`）、UI 调用（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:636`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | 开=`0E 00`、关=`0E 02` | 开=`0`、关=`2` | — | Tag `07`（`input9,10) == '0'`） | 静态 | [setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:934`）、setTouchOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:879`） |
| 按键映射（左耳单击） | `setLeftTouchOne` | `01 01 0<action>` | 动作取值 `0`–`5`（见下） | — | Tag `01`（字符 `11,13)`） | 静态 | [setLeftTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1372`） |
| 按键映射（左耳双击） | `setLeftTouchTwo` | `01 02 0<action>` | 动作取值 `0`–`5`（见下） | — | Tag `01`（字符 `15,17)`） | 静态 | [setLeftTouchTwo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1317`） |
| 按键映射（左耳三击） | `setLeftTouchThree` | `01 03 0<action>` | 动作取值 `0`–`5`（见下） | — | Tag `01`（字符 `19,21)`） | 静态 | [setLeftTouchThree（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1262`） |
| 按键映射（右耳单击） | `setRightTouchOne` | `01 11 0<action>` | 动作取值 `0`–`5`（见下） | — | Tag `01`（字符 `31,33)`） | 静态 | [setRightTouchOne（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1152`） |
| 按键映射（右耳双击） | `setRightTouchTwo` | `01 12 0<action>` | 动作取值 `0`–`5`（见下） | — | Tag `01`（字符 `35,37)`） | 静态 | [setRightTouchTwo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1097`） |
| 按键映射（右耳三击） | `setRightTouchThree` | `01 13 0<action>` | 动作取值 `0`–`5`（见下） | — | Tag `01`（字符 `39,41)`） | 静态 | [setRightTouchThree（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1042`） |
| 面板容器（游戏模式） | 结构 | — | 容器（包含游戏模式） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:158`） |
| 面板容器（寻机与触控） | 结构 | — | 容器（包含寻机与触控设置） | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:213`） |

按键映射参数细节：
1. **动作取值**：SWIFT 绑定的 `RTouchInfoModel.rsCommonTouchList`（[pp+0xb568]、touch_info.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:52`））包含 6 项动作：
   - 无功能（`touch-none`）：线值 `0`
   - 播放/暂停（`touch-play-pause`）：线值 `1`
   - 上一曲（`touch-pre-music`）：线值 `2`
   - 下一曲（`touch-next-music`）：线值 `3`
   - 音量加（`touch-up-volume`）：线值 `4`
   - 音量减（`touch-down-volume`）：线值 `5`
   未配置语音助手（`7`）、游戏模式切换（`6`）或降噪控制（`8`）。
2. **槽位取值**：SWIFT 绑定的 `RTouchInfoModel.typeOTT`（[pp+0xb560]、touch_info.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:44`））仅包含 3 个手势档位：单击（`one`）、双击（`two`）、三击（`three`）。未配置长按（`long`），因此 Sender 的 `setLeftTouchLong` 与 `setRightTouchLong` 在本型号界面中不可用。
3. **固件门槛**：所有控件均未设置固件版本门槛（`threshold = —`）。
4. **读回说明**：寻机命令（`5E`）下发后，Receiver 中没有对应的回显 Tag 解析，读回为 `—`。触控开关（`0E 00`/`02`）与游戏模式（`0E 01`/`00`）复用相同的命令字节 `0E`，但回包解析中 Tag `0E` 仅作为游戏状态分派，触控开关状态由 Tag `07` 回显。

## 接收字段

SWIFT 与族文档的核心差异在于电量消费方式，其余字段解析与族文档共享 Receiver 一致。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 电量 | 字符 `8,10)` 左、`[10,12)` 右、`[12,14)` 盒 | 本型号由 `SwiftReceiveHandler` 独占重写，只消费第三路（`[12,14)`）作为单体电量写入 `caseBattery`，左右耳槽保持未设置状态 | 静态 | [swift_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/swift_receive_handler.dart:10`） |
| `0E` | 游戏模式 | 输入串 `9,10) == '1'` 判定开，其余判定关；写入游戏模式控制器 | 静态 | [rs_common_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`） |
| `01` | 按键映射 | 输入串解析 6 个切片（`11,13)`、`[15,17)`、`[19,21)`、`[31,33)`、`[35,37)`、`[39,41)`），分别对应左右耳的单/双/三击动作 | 静态 | 同上 |
| `07` | 触控开关 | 输入串 `[9,10)` 与 `'0'` 比较判定开/关 | 静态 | 同上 |
| `08`、`09`、`0C`、`0D`、`12`、`2A`、`2B`、`2C`、`2D`、`2E`、`33` | EQ、音频、版本、降噪、语言、佩戴、音量、空间音频等 | 共享 Receiver 中定义了分派逻辑，但 SWIFT 未配置对应控件界面，属未开放能力的冗余解析 | 静态 | 同上 |

## 未实现与未知

以下缺口的查证范围包括 `SwiftDeviceConfig`、`SwiftReceiveHandler`、`RsCommonSender`、`RsCommonReceiver`、`DeviceController` 及相关 UI 控件代码，无 SWIFT 实机样本：
1. **实机验证缺失**：所有结论来自反汇编静态分析，未抓取蓝牙控制流量，设备端对各命令的实际接受和回包情况未验证。
2. **寻机读回**：`setFindDevice` 属于单向下发指令，Receiver 中未找到对应的回包 Tag 解析分支，设备是否回包及状态如何回显未知。
3. **单体物理键映射逻辑**：SWIFT 为单体骨传导耳机，但 App 仍配置了左耳与右耳共 6 个按键槽位；设备端物理按键究竟映射到左耳槽位、右耳槽位还是两者生效其一，需实机验证。
4. **批量初始化冗余**：初始化共享闭包调用 `getInfo` 发送 14 个功能 ID 查询，包含了 SWIFT 界面未提供的降噪、EQ、音频模式等，设备端对此类查询是返回默认值还是忽略未知。
5. **通知串底层封包格式**：`RsCommonReceiver.process` 依赖整串的字符下标（如 `[6,8)`、`[9,10)`、`[12,14)`），BLE ATT 载荷如何转换为该字符串（是否包含前后缀、分隔符或空格）需实机抓包证实。

## OTA

SWIFT 没有 OTA 路由。`customConfigEarObject` 未配置 `otaType`，未配置控制/OTA SPP UUID，未调用 `normalBleOtaServiceConfig`（[swift_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:343`））；App 的 `SwiftDeviceConfig` 也未分配 `DeviceOtaPanelItemWidgetModel`，`moreWidgetModels` 为空列表。静态上 App 未为 SWIFT 提供任何固件升级功能。OpenRose 不实现 OTA，本节仅作记录。
