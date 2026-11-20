# KY01 控制协议

RoseLink 3.8.0（5310）的配置类为 `Ky01DeviceConfig`。静态核对的收发路径为 [RS Common V2](../families/rs-common-v2.md) 的 `RsCommonV2Sender`、`RsCommonV2Receiver` 和 `RsCommonV2ReceiveHandler`。实机收发与设置持久化未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| KY01 | `0a0b0a0d` | `ky01` | `KY01` | 静态 | 识别前缀（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:20`）、型号注册表（`rose_reverse/analysis/model_registry.json`） |

以 `KY01` 作为设备名称中的型号词。广播按前缀识别。

## 传输

| 项 | 值 | 状态 | 出处 |
| --- | --- | --- | --- |
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认 | 同族 | 同族 |
| 控制 SPP UUID | 同族默认 | 同族 | 同族 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| OTA SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:585`） |

[默认传输](../families/rs-common-v2.md#默认传输)列出 GATT 三个 UUID、SPP 选择条件、广播中经典地址的偏移与发送间隔。型号控制 SPP 服务见SPP 服务（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:585`）。

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

| 型号 | 配置 cid | GDT 表项 | 目标 | 查询 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- |
| KY01 | 518 | 60096 | `0x8e2444` | `getInfo` | 静态 | 初始化分派（`rose_reverse/analysis/i7_dispatch/table.tsv:60097`） |

初始化闭包只调用 `getInfo`。Sender 从序号 0 开始，命令体与 35 字节完整查询帧见[初始化与查询](../families/rs-common-v2.md#初始化与查询)。发送端序号（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:44`）。

## 电量

显示左右耳与充电仓，字段 `0C` 中左右耳各占一个字节。仓电量取第三字节，须按入盒状态判断本次仓读数是否有效。见电量槽枚举（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:125`）（静态）及[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存)。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 左右耳与充电仓 | — | `getInfo` → `0C` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:122`） |
| 均衡器预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2A vv` | POP=01、HIFI=00、ROCK=02 | — | `getInfo` → `2A` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:140`）、[收发映射](../families/rs-common-v2.md#eq) |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0E vv` | 开=01、关=00 | — | `getInfo` → `0E` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:185`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 vv` | 开=01、关=00 | — | `getInfo` → `32` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:198`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | 左单／双／三击及长按=01..04、右耳对应=11..14。动作依次为无=00、播放暂停=01、上一曲=02、下一曲=03、音量加=04、音量减=05、游戏模式=06、语音助手=07、降噪轮换=08 | — | `getInfo` → `01` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:243`）、动作列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:506`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | `07 vv` | 开=00、关=01 | — | `getInfo` → `07` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:243`） |
| 提示音音量 | `setDingLevel` | `2E vv` | 1..5 档=01..05，关闭选项隐藏 | — | `getInfo` → `2E` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:414`）、[关闭选项条件](../families/rs-common-v2.md#全族已知的坑) |
| 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish` | `31 vv` | 中文=00、英文=01 | — | `FA 31` → `31` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:252`）、语言设置与查询（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:824`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2F vv` | 左=01、右=02、双耳=03、停止=04 | — | 通知 `2F`（单耳或双耳事件） | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:314`）、[接收事件](../families/rs-common-v2.md#寻机) |
| 设置面板 | 结构 | — | 分组容器 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:176`） |
| 更多设置 | 结构 | — | 进入更多设置 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:265`） |
| OTA | 范围外 | — | AB | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:323`） |

语言弹窗提供中文和英文，两条回调均调用 `DeviceEarLanguageIntent.setEarLanguage`。在 AOT 地址 `0xb90890` 和 `0xb908e8`，两个分支分别调用中文 `0x75a4bc` 和英文 `0x75a454`。共用命令派发给该型号的 V2 Sender。弹窗与选项（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/devic_ear_lang_panel_item.dart:102`）、中文和英文入口（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:2644`）、AOT 函数地址（`rose_reverse/analysis/blutter/ida_script/addNames.py:11418`）。

KY01 的动作列表允许把降噪轮换绑到触控手势，配置没有直接选降噪模式的面板。

## 接收字段

字段格式同[族文档](../families/rs-common-v2.md#字段表)。左右耳和仓按电量节的条件展示。预设回报 Tag `2A` 为单字节时，00/01/02 表示 HIFI/POP/ROCK。双字节格式首个 00/01/02 对应 POP/HIFI/ROCK。

## 未实现与未知

- OpenRose 可读取寻机事件。发送响铃及停止的操作尚未开放。`2F` 的单侧事件只能确认对应一侧的变化。
- 真机资料未知。现有依据是 APK 与其 AOT 代码，还缺本型号的广播、BLE / SPP 连接、实际设置回包和断电后持久化记录。

## OTA

静态路由为 [AB](../ota/ab.md)，OTA 面板没有设置固件门槛。OpenRose 未实现固件升级。型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:323`）。
