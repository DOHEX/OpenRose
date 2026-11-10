# Swift 2 控制协议

配置类 `Swift2DeviceConfig`（cid 505，继承 `BaseDeviceConfig`，不是 `RsCommonV2DeviceConfig` 的子类），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common V2 协议族](../families/rs-common-v2.md)。Sender 为 `RsCommonV2Sender`，Receiver 为 `RsCommonV2Receiver`，handler 为 `RsCommonV2ReceiveHandler`（class id 421）。早期型号 `SWIFT`（配置类 `SwiftDeviceConfig`，RS Common 协议族，非 V2）采用完全不同的帧协议与独立文档 [swift-protocol.md](swift-protocol.md)，两者不可混淆。商品踏浪 Swift 2 为骨传导耳机（App 中分类字符串为 `"head"`，不能仅据此推断佩戴形态）。没有 Swift 2 实机记录：以下均为静态分析，未连接耳机、未抓取蓝牙流量，也没有执行 OTA；设备是否接受各设置未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售骨传导 | `5999064c` | `swift2` | `SWIFT 2` | 静态 | 构造:23（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:23`）、定制:529（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:529`） |

零售名 `SWIFT 2`（商品名亦称踏浪 Swift 2），内部名 `swift2`；识别按规范化 manufacturer 数据前缀 `5999064c`。CMIIT ID 为 `26J51U5A2752`（构造:20（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:20`））。广播识别和规范化规则见族文档 [广播识别](../families/rs-common-v2.md#广播识别)。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认 | 同族 | 同族 |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | customConfigEarObject:541（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:541`） |
| 广播中的经典地址 | `1` | 静态 | customConfigEarObject:538（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:538`） |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| OTA SPP UUID | `0cf12d32-fac3-4553-bd80-d6832e7b3931` | 静态 | customConfigEarObject:545（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:545`） |
| BLE OTA 服务 | `0000ff12-0000-1000-8000-00805f9b34fb`（`ff12`） | 静态 | normalBleOtaServiceConfig:1235（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1235`） |
| BLE OTA 写特征 | `0000ff15-0000-1000-8000-00805f9b34fb`（`ff15`） | 静态 | normalBleOtaServiceConfig:1239（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1239`） |
| BLE OTA 通知特征 | `0000ff14-0000-1000-8000-00805f9b34fb`（`ff14`） | 静态 | normalBleOtaServiceConfig:1243（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1243`） |

同族默认的取值与证据见 [族文档的默认传输表](../families/rs-common-v2.md#默认传输)。Swift 2 配置中允许 SPP（`isSupportSpp="true"`，定制:574（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:574`）），指定控制 SPP UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`，独立 OTA SPP UUID 为 `0cf12d32-fac3-4553-bd80-d6832e7b3931`。通过 `normalBleOtaServiceConfig` 分配 AB 方案的 16 位 BLE OTA UUID（`ff12`/`ff15`/`ff14`）。控制通道选择、MTU、订阅与发送队列规则见族文档。

## 初始化

| 分派 | 表项 | 目标 | 发送的查询 | 状态 | 出处 |
|---|---|---|---|---|---|
| GDT+0xd8ba，cid 505 | 60083（`table.tsv` 第 60084 行） | `0x8e2444` | 仅 `getInfo` | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60084`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`） |

目标闭包只调用 `RCommander.getInfo`，不发送自定义 EQ 或其他补读查询。`getInfo` 的 29 个功能 ID 和完整 35 字节帧见族文档 [初始化与查询](../families/rs-common-v2.md#初始化与查询)。查询包含族级通用功能 ID，不代表 Swift 2 支持全部项目。

## 电量

| 布局 | 槽 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 单体（复用仓槽） | `charge` | 仅配置单槽 `charge`，共享 `0C` 字段第 3 字节；百分比与显示规则同族，left/right 槽在界面上不展示 | 静态 | BatteryType:141（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:141`）、[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存) |

Swift 2 作为单体骨传导耳机，在 `RDeviceComponentDisplayBarWidgetModel` 中仅配置了 `[BatteryType.charge]`（构造:141（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:141`））。硬件通过 `0C` 回包的第 3 字节上报单体电量；App 界面直接读取该槽并展示为整机电量，不显示左右耳电量。仓 byte 的 bit7 在单体耳机上的语义仍是未知。

## 设置命令

Swift 2 配置的每个控件一行。BODY 只写命令体，完整帧的 `FF SS LL BODY CC AA` 封装、序号和校验见族文档 [发送帧](../families/rs-common-v2.md#发送帧)。除 OTA 外，命令体和配置均为静态事实；设备是否接受仍未知。“读回”列的 `getInfo` 表示该 ID 在共享批量查询中，最终状态以通知回包为准，`—` 表示没有查询方法。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 单体（仓槽） | — | `getInfo`（`0c`） | 静态 | 显示控件:138（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:138`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e 01`、`0e 00` | 开=`01`、关=`00` | — | `getInfo`（`0e`） | 静态 | 控件:156（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:156`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 01`、`32 00` | 开=`01`、关=`00` | — | `getInfo`（`32`） | 静态 | 控件:169（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:169`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `07 00`、`07 01` | 开=`00`、关=`01` | — | `getInfo`（`07`） | 静态 | 控件:214（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:214`）、setTouchOn:2073（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:2073`）、setTouchOff:2056（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:2056`） |
| 按键映射（功能 -） | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong` | `01 slot action` | slot 单击/双击/三击/长按=`01/02/03/04`；action 无/播暂/上一首/下一首/音量+/音量-/游戏/语音助手=`00..07` | — | `getInfo`（`01`） | 静态 | 控件:214（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:214`）、动作列表:485（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:485`）、按键闭包:2703（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:2703`） |
| 按键映射（功能 +） | `setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | slot 单击/双击/三击/长按=`11/12/13/14`；action 取值同上（`00..07`，无 ANC 选项） | — | `getInfo`（`01`） | 静态 | 控件:214（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:214`）、动作列表:485（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:485`）、按键闭包:2403（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:2403`） |
| 提示音音量 | `setDingLevel` | `2e value` | 1..5=`01..05`（无关闭选项） | — | `getInfo`（`2e`） | 静态 | 控件:370（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:370`）、关闭禁用:386（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:386`） |
| 寻机 | `setFindDeviceBoth`、`setFindOff` | `2f 03`、`2f 04` | 寻机=`03`、停止=`04`（UI 单按钮单体逻辑，不使用 `01/02`） | — | `getInfo`（`2f`） | 静态 | 控件:269（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:269`）、非TWS分支:276（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:276`）、寻机闭包:682（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:682`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置:205（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:205`） |
| 更多设置入口 | 结构 | — | 入口 | — | — | 静态 | 配置:223（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:223`） |
| OTA | 范围外 | — | 见 [OTA](#ota) | — | — | 静态 | 配置:278（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:278`）、[AB 方案](#ota) |

按键映射解析：Swift 2 的佩戴分类为 `"head"`，`CommonUtil.checkIsTws()` 返回 `false`（checkIsTws:300（`rose_reverse/analysis/blutter/asm/roselink/lib/common_util.dart:300`））。在按键设置组件 `RTouchCtl` 中，非 TWS 耳机的两个配置分组标题被本地化为 `"left-sub"`（功能 -，文本:3745（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:3745`））与 `"right-add"`（功能 +，文本:3773（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:3773`）），分别对应骨传导耳机机身上的物理“减键/功能键”与“加键/功能键”。底层发送命令复用族级左/右耳 slot 编码：功能 - 使用左耳 slot `01..04`，功能 + 使用右耳 slot `11..14`。可选动作采用 `RTouchInfoModel.rsCommonV2TouchListWithoutAnc`（列表:463（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:463`）），仅提供 8 项操作（`00..07`），不包含 ANC 轮换（`08`）。

寻机解析：在寻机页面 `NormalEarFindPageState` 中，因 `checkIsTws()` 为 `false`，页面只构建单个寻机按钮（分支:276（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:276`））。设备类型判定非初代 `SWIFT`（判定:1044（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:1044`）），开启寻机时直接发送 `setFindDeviceBoth`（`2f 03`，闭包:682（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:682`）），停止寻机时发送 `setFindOff`（`2f 04`，闭包:636（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:636`）），不触发单耳寻机命令 `01/02`。

提示音解析：`DeviceDingLevelPanelItemWidgetModel` 在 Swift 2 构造中显式将 `field_23` 置为 `false`（构造:386（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:386`）），使得组件的 `_canShowSupportClose` 判定直接短路为 `false`（判定:825（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:825`））。因此 Swift 2 界面上不展示“关闭”（`00`）选项，档位固定为 1..5（`01..05`），且无固件版本门槛（未传 `field_27`）。

## 接收字段

所有 Tag 的处理与取值同族文档 [字段表](../families/rs-common-v2.md#字段表)，没有 Swift 2 专属 Handler 分支；Swift 2 使用共享 `RsCommonV2ReceiveHandler`（class id 421）。各字段本型号差异如下：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0C` | 电量 | 共享解析 3 字节，Swift 2 为单体耳机，仅有效使用第 3 字节（`charge` 槽），左右耳槽界面不展示 | 静态 | [电量分析](#电量)、[族电池](../families/rs-common-v2.md#电池与仓缓存) |
| `2F` | 寻机状态 | 共享解析双耳寻机状态，Swift 2 单体寻机通过 `03` 触发，回包 `04` 视为停止 | 静态 | [寻机分析](#设置命令)、[族寻机](../families/rs-common-v2.md#寻机) |

未在 App 中配置对应控件的字段（如 `09` 降噪、`2A`/`3E` EQ、`2B` 音频模式、`2C`/`2D` 降噪等级、`45` 增益、`46` 听力保护等）虽然共享 Receiver 能解析，但 Swift 2 配置中没有对应 UI 与 Sender 触发路径。

## 未实现与未知

- 没有 Swift 2 实机记录：设置写入、持久化时序、单体广播原文和经典蓝牙 SPP 配对链路均未通过硬件验证。
- 物理按键与槽位映射：App 界面将 `slot 01..04`（左）与 `slot 11..14`（右）分别命名为“功能 -”与“功能 +”，实际骨传导耳机物理按键（如音量减键/加键）的长按、单击硬件触发与该逻辑槽位的精确对应关系需实机确认。
- 单体电量 flag：`0C` 仓 byte 的 bit7 在单体耳机充电时是否置位，以及单体低电/充电动画表现未经硬件验证。
- 寻机单体硬件响应：Swift 2 发送 `2f 03` 后单体发声单元的具体蜂鸣音调与自动超时停止逻辑未实机捕获。
- 未配置功能：ANC 降噪、EQ 调节、音频编解码切换（AAC/SBC/LDAC/LC3）、耳道检测与提示音语言切换在 App 中均未配置；“App 未配置”为静态事实，不能断言固件硬件绝对不支持。

## OTA

Swift 2 的 OTA 路由为 AB（`DeviceOtaType.ab`，构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:30`）），由 `AbOta` 执行升级。OTA 方案通用的通道定义、帧格式、分块窗口与 ACK、状态机见 [AB OTA 方案](../ota/ab.md)。OpenRose 不实现 OTA，此节只作记录。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `ab` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:30`）、OTA 面板:284（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:284`） |
| 固件门槛 | 无（面板未配置 `sinceVersion`） | 静态 | OTA 面板:285（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:285`） |
| 前置条件 | 无出仓门槛（头戴耳机，面板显式配置 `needCheckWwsMode=false`） | 静态 | OTA 面板:284（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:284`） |
| BLE OTA 服务 | `0000ff12-0000-1000-8000-00805f9b34fb`（16 位 `ff12`） | 静态 | 配置:1230（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1230`） |
| BLE OTA 写特征 | `0000ff15-0000-1000-8000-00805f9b34fb`（16 位 `ff15`） | 静态 | 配置:1232（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1232`） |
| BLE OTA 通知特征 | `0000ff14-0000-1000-8000-00805f9b34fb`（16 位 `ff14`） | 静态 | 配置:1236（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1236`） |
| SPP OTA UUID | `0cf12d32-fac3-4553-bd80-d6832e7b3931`（独立 OTA SPP UUID，不同于控制 SPP UUID `0cf12d31-…`） | 静态 | 定制:545（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:545`） |
| 元数据 keyId | `swift2` | 静态 | 定制:530（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:530`） |
