# BudsFeel MK2 控制协议

配置类 `BudsfeelMk2DeviceConfig`（cid 530，继承 `BaseDeviceConfig`，不是 `RsCommonV2DeviceConfig` 的子类），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common V2 协议族](../families/rs-common-v2.md)。Sender 为 `RsCommonV2Sender`，Receiver 为 `RsCommonV2Receiver`，handler 为 `RsCommonV2ReceiveHandler`（cid 421）。没有 BudsFeel MK2 实机记录：以下均为静态分析，未连接耳机、未抓包，亦未执行 OTA；设备是否接受各设置未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `0b150a0b` | `budsfeel_mk2` | `BudsFeel MK2` | 静态 | 构造:10（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:10`）、定制:198（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:198`） |

零售名 `BudsFeel MK2`，内部名 `budsfeel_mk2`；识别按规范化 manufacturer 数据的前 8 个十六进制字符 `0b150a0b`，协议标识为 `rs_common_v2`，设备类型为 `tws`（定制:231（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:231`））。CMIIT ID 为 `26J51U5AX128`（构造:20（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:20`））。前缀用于静态匹配，非实机广播样本。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认 | 同族 | 同族 |
| 控制 SPP UUID | 同族默认 | 同族 | 同族 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| BLE OTA 服务 | `0000AE00-0000-1000-8000-00805F9B34FB` | 静态 | 定制:240（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:240`） |
| BLE OTA 写特征 | `0000AE01-0000-1000-8000-00805F9B34FB` | 静态 | 定制:243（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:243`） |
| BLE OTA 通知特征 | `0000AE02-0000-1000-8000-00805F9B34FB` | 静态 | 定制:246（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:246`） |
| OTA SPP UUID | `00001101-0000-1000-8000-00805F9B34FB` | 静态 | 定制:249（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:249`） |

同族默认的取值与证据见 [族文档的默认传输表](../families/rs-common-v2.md#默认传输)。BudsFeel MK2 在 `customConfigEarObject` 中显式写入 `sppSupport="true"` 与控制 SPP UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`（与同族默认一致），并写入专属的杰理（JL）OTA BLE 服务/特征与 SPP UUID。普通 BLE 通道选择、512 MTU 请求、通知订阅与 100 ms（OHOS 350 ms）写入间隔队列见族文档 [传输选择](../families/rs-common-v2.md#传输选择) 与 [通道与连接](../families/rs-common-v2.md#通道与连接)。OTA 写入走专用队列（普通 BLE 写入间隔 25 ms，OHOS 50 ms，ConnectManager.writeOta:13186（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:13186`））。

## 初始化

| 分派 | 表项 | 目标 | 发送的查询 | 状态 | 出处 |
|---|---|---|---|---|---|
| GDT+0xd8ba，cid 530 | 60108（`table.tsv` 第 60109 行） | `0x8e2444` | 仅 `getInfo` | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60109`）、补核（`rose_reverse/analysis/i7_dispatch_findings.md`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`） |

目标函数只调用 `RCommander.getInfo`，不查询自定义 EQ `3E`；AOT 将共享闭包标成 SportFree 匿名 closure，不代表 BudsFeel MK2 使用 SportFree 配置。`getInfo` 的 29 个 ID 和完整 35 字节帧见族文档 [初始化与查询](../families/rs-common-v2.md#初始化与查询)。查询含 29 项通用功能 ID，不能据此推断型号支持全部功能；35 字节帧超过默认 ATT MTU 23 的有效载荷，须按 BLE 协商后的容量发送。

## 电量

| 布局 | 槽 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 左右耳与仓 | `left`、`right`、`charge` | 共享 `0C` 字段的三个字节；百分比、入仓 flag、仓缓存与新鲜度规则同族 | 静态 | BatteryType:425（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:425`）、[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存) |

电量展示控件配置左、右、仓三槽（`RDeviceComponentDisplayBarWidgetModel`，配置:422（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:422`））。仓 byte 的 bit7 只作为 flag 保存，不能据此称为“充电中”；仓电量有效性取决于入仓耳读数，规则同族。

## 设置命令

BudsFeel MK2 配置的每个控件一行。BODY 只写命令体，完整帧的 `FF SS LL BODY CC AA` 封装、序号和校验见族文档 [发送帧](../families/rs-common-v2.md#发送帧)。除星芒 AI 业务入口外，命令体和配置都是静态事实；设备是否接受仍未知。“读回”列的 `getInfo` 表示该 ID 在共享批量查询中，`—` 表示无对应查询或未实现。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 左、右、仓三槽 | — | `getInfo`（`0c`） | 静态 | 显示控件:422（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:422`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind` | `09 01`、`09 02`、`09 03`、`09 04` | ANC=`01`；普通=`02`；通透=`03`；抗风=`04` | — | `getInfo`（`09`） | 静态 | 控件:440（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:440`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 降噪轮换 | `setAncLine` | `36 [ANC][抗风][普通][通透]` | 每项 `00/01`；四项全部可选 | — | `getInfo`（`36`） | 静态 | 控件:333（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:333`）、[族轮换](../families/rs-common-v2.md#anc-轮换) |
| 低音增强 | `setEqBassPlus` | `2a 05 value` | 0..4 档=`00..04`（UI 呈现 5 段，对应 0..4） | — | `getInfo`（`2a`） | 静态 | 控件:471（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:471`）、setEqBassPlus:4044（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4044`）、接收与控制器:5980（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/rs_common_v2_receive_handler.dart:5980`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e 01`、`0e 00` | 开=`01`、关=`00` | — | `getInfo`（`0e`） | 静态 | 控件:484（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:484`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 01`、`32 00` | 开=`01`、关=`00` | — | `getInfo`（`32`） | 静态 | 控件:497（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:497`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLc3` | `2b 00`、`2b 01`、`2b 03` | AAC/SBC=`00`、LDAC=`01`、LC3=`03`（未配置 LHDC） | — | `getInfo`（`2b`） | 静态 | 控件:583（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:583`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `07 00`、`07 01` | 开=`00`、关=`01` | — | `getInfo`（`07`） | 静态 | 控件:605（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:605`）、setTouchOn:2420（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） |
| 触控按键 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | 左耳 `01/02/03/04`、右耳 `11/12/13/14`；action 0 无、1 播放暂停、2 上一首、3 下一首、4 音量+、5 音量−、6 游戏、7 语音助手、8 ANC 循环 | — | `getInfo`（`01`） | 静态 | 控件:605（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:605`）、[族触摸动作](../families/rs-common-v2.md#触摸动作) |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2f 01`、`2f 02`、`2f 03`、`2f 04` | 左=`01`、右=`02`、双耳=`03`、停=`04` | — | `getInfo`（`2f`）；回包含 05/06 单耳停止 | 静态 | 控件:663（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:663`）、[族寻机](../families/rs-common-v2.md#寻机) |
| 提示音 | `setDingLevel` | `2e value` | 1..5=`01..05`（未配置关闭选项） | — | `getInfo`（`2e`） | 静态 | 控件:280（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:280`）、setDingLevel:2014（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） |
| 面板容器 | 结构 | — | 选项面板容器 | — | — | 静态 | 面板:324（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:324`） |
| 更多设置入口 | 结构 | — | 更多设置入口 | — | — | 静态 | 入口:614（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:614`） |
| 星芒业务入口 | 范围外 | — | AI 业务入口容器 | 300 | — | 静态 | 入口:542（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:542`） |
| OTA | 范围外 | — | 见 [OTA](#ota) | 200 | — | 静态 | OTA 面板:672（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:672`） |

门槛为 Dart Smi 右移一位后的整数。OTA 面板 `sinceVersion=400`（Smi 0x190 >> 1 = 200）；星芒业务入口容器 `sinceVersion=600`（Smi 0x258 >> 1 = 300）。提示音控件配置 `field_23=false`（`hasCloseOption=false`），未设置 `field_27` 固件门槛，与 i7（304 门槛下提供关闭）不同，仅提供 1..5 档。

低音增强（`setEqBassPlus`）为本型号特有控件（`RDeviceEqBassPlusItemWidgetModel`，配置:471（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:471`））：
1. 控件设置 `field_f = 5`（5 档），构建 `RHintMessageBoxItemByLevel`，以 1..5 段显示，配置偏移量 `field_23 = -1`（构建:1722（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:1722`））。点击第 $i$ 段（$1 \le i \le 5$）时计算 $i + (-1)$，向回调传入 `0..4` 档位。
2. 回调执行 `setEqBass(level)`（0x752524（`rose_reverse/analysis/extracted/lib/arm64-v8a/libapp.so`）），调用 `RCommander.setEqBassPlus(level)`（0x75264c（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:944`）），进入 `RsCommonV2Sender.setEqBassPlus(value)`（0xc9d048（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4044`））。
3. Sender 校验 `0 <= value <= 4`，构造 BODY 为 `2A 05 value`（3 字节）。
4. 读回机制：族查询 `getInfo` 包含 `2A`；接收端 `RsCommonV2ReceiveHandler.eqDataReceived`（0xe35f14（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/rs_common_v2_receive_handler.dart:5980`））收到 Tag `2A` 且长度 $\ge 4$ 字符（2 字节）时，若第 1 字节为 `05`，则解析第 2 字节并写入 `deviceController.field_4f`（低音增强档位 RxInt），供 UI 响应式显示。

与 i7 相比，BudsFeel MK2 **未启用**自定义 EQ 界面（`customConfigEarObject` 及面板中无 EQ 模式切换及频段滑块控件），亦**未启用** ANC 或通透细分强度档位条（`RDeviceComponentNoiseBarWidgetModel` 中 `field_13`、`field_1b`、`field_1f` 均为 false，无档位条滑块）；音频模式仅开放 AAC/SBC、LDAC、LC3 三项，无 LHDC。

## 接收字段

所有 Tag 的处理与取值同族文档 [字段表](../families/rs-common-v2.md#字段表)，没有 BudsFeel MK2 专属的 ReceiveHandler 分支；本型号使用共享 `RsCommonV2ReceiveHandler`（cid 421）。

本型号相关细节：
- Tag `2A`：接收 2 字节且首字节为 `05` 时，次字节由 `RsCommonV2ReceiveHandler.eqDataReceived`（handler:5980（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/rs_common_v2_receive_handler.dart:5980`））解析为低音增强档位并更新 `deviceController.field_4f`，供低音增强控件响应展示。
- Tag `0C`：包含左、右耳与仓三槽电量，仓 bit7 为入仓/充电标志，同族默认。
- Tag `09`：包含降噪模式（ANC/普通/通透/抗风），同族默认。
- Tag `0D`：解析固件版本号，用于与 OTA 面板门槛 200 进行比对。
- 未配置自定义 EQ 控件，因此 Tag `3E`（自定义 EQ 10 频段数据）在官方 App 流程中未被请求或展示。

## 未实现与未知

- 没有 BudsFeel MK2 实机记录：所有设置写入、持久化确认、回包时序、广播原文和 SPP 配对/SDP 均未验证；静态分析结论不代表设备确认。
- 广播仅有规范化规则的静态推导（前缀 `0b150a0b`），没有实机广播报文样本；BR/EDR 经典地址与 BLE 地址一致性未实测。
- `0C` 仓 byte 的 bit7 语义、仓电量何时有效以及入仓触发机制同族文档，仍无实机抓包样本。
- 低音增强：固件是否接受 `2A 05 value` 并生效、以及固件是否会在 `getInfo` 或通知中主动上报 `2A 05 value` 待实机抓包确认。
- 自定义 EQ 与档位调节：App 未为该型号配置自定义 EQ 界面及 ANC/通透细分档位；属于静态未配置，不代表芯片硬件不支持。
- 星芒 AI 业务入口门槛为 `sinceVersion=300`，内层交互为云端 SDK 业务，非基础耳机控制，命令与通信细节未知。
- 杰理（JL）OTA 底层线协议：RCSP 帧结构、命令 opcode、分包分段、固件包加密/签名及校验机制封装在闭源原生库中，在 Flutter AOT 中不可见；未获取到该型号固件更新包样本。

## OTA

BudsFeel MK2 的 OTA 路由为杰理（`DeviceOtaType.jieli`，枚举索引 4），OTA 面板固件版本门槛为 200（`sinceVersion=400`，Smi 0x190 >> 1），面板配置 `needCheckWwsMode=true`。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `jieli` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:30`）、OTA 面板:681（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:681`） |
| 固件门槛 | 200 | 静态 | OTA 面板:675（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:675`） |
| needCheckWwsMode | `true` | 静态 | OTA 面板:677（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:677`） |
| BLE OTA 服务 UUID | `0000AE00-0000-1000-8000-00805F9B34FB` | 静态 | 定制:240（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:240`） |
| BLE OTA 写特征 | `0000AE01-0000-1000-8000-00805F9B34FB` | 静态 | 定制:243（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:243`） |
| BLE OTA 通知特征 | `0000AE02-0000-1000-8000-00805F9B34FB` | 静态 | 定制:246（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:246`） |
| OTA SPP UUID | `00001101-0000-1000-8000-00805F9B34FB` | 静态 | 定制:249（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:249`） |
| MethodChannel | `com.jieli.jl_ota/methods` | 静态 | MethodChannel:6640（`rose_reverse/analysis/blutter/objs.txt:6640`） |
| 方案配置参数 | `useAuth=true`、`mtu=20`、`supportNewReconnectWay=true` | 静态 | JlOtaOptions:2602（`rose_reverse/analysis/blutter/objs.txt:2602`）、toMap:378（`rose_reverse/analysis/blutter/asm/jl_ota/src/jl_ota_event.dart:378`） |

### 方案机制（Flutter AOT 证据）

1. **编排与通道初始化**：升级计划类为 `JieliOta`（jieli.dart:8（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/jieli.dart:8`））。进入升级时通过 MethodChannel `com.jieli.jl_ota/methods` 调用原生 SDK 方法：
   - `configure`：下发 `JlOtaOptions` 配置（`useAuth: true`、`mtu: 20`、`timeoutMs: null`、`supportNewReconnectWay: true`，配置对象:2602（`rose_reverse/analysis/blutter/objs.txt:2602`）、configure:361（`rose_reverse/analysis/blutter/asm/jl_ota/src/jl_ota.dart:361`））。
   - `bindDevice`：绑定目标设备参数（`deviceId`、`deviceName`、`bleAddress`、`mtu: 20`，bindDevice:271（`rose_reverse/analysis/blutter/asm/jl_ota/src/jl_ota.dart:271`））。
   - `startOta`：传入 `deviceId` 与固件字节数组启动流程（startOta:125（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/jieli.dart:125`））。
2. **数据中继传输**：
   - **发送方向**：原生 JL SDK 产生待发字节，通过事件流抛出 `JlOtaEventType.outgoingData`（枚举值 0）；`JieliOta._writeOutgoingData`（2854（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/jieli.dart:2854`））捕获后调用 `ConnectManager.writeOta`（13065（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:13065`）），经 `RQueueMamager` 专用队列（间隔 25 ms，OHOS 50 ms）写入 BLE OTA 特征 `0000AE01-...`。
   - **接收方向**：底层 BLE 在 OTA 通知特征 `0000AE02-...` 收到设备数据时，通过全局 EventBus 广播 `"ble-callback-ota"`；`JieliOta._subscribeTransport`（968（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/jieli.dart:968`））监听并将十六进制字符串转换为 `Uint8List`，调用 `JlOta.handleIncomingData`（3087（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/jieli.dart:3087`））喂回原生 SDK。
3. **事件状态机**：原生 SDK 通过事件流向 Flutter 上报 `JlOtaEventType`（枚举定义:969（`rose_reverse/analysis/blutter/asm/jl_ota/src/jl_ota_event.dart:969`））：
   - `0: outgoingData`（待发送协议数据包）
   - `1: otaState`（升级进度与状态更新，包括准备、传输中、校验、成功/失败）
   - `2: connection`（连接状态：`connected`、`connecting`、`disconnected`）
   - `3: deviceInfo`（读取到的杰理设备固件与芯片信息）
   - `4: auth`（认证事件）
   - `5: rcspInit`（杰理 RCSP 协议初始化事件）
   - `6: reconnectRequired`（设备复位后触发 `completeReconnect` 流程）
   - `7: error`、`8: log`、`9: unknown`
4. **未解决与未知**：
   - JL OTA/RCSP 协议的内层帧格式（帧头、命令字 opcode、分片序号、CRC 校验等）全部由闭源原生库（`libjl_ota.so`）封装，在 Dart 反汇编中不可见。
   - 设备认证（`auth`）握手协议的线格式与密钥未知。
   - OTA SPP UUID（`00001101-...`）在 Android/桌面平台是否曾被启用未见调用代码。
   - 双耳配对同步模式（`needCheckWwsMode`）的主从角色切换与重连时序未做实机验证。
