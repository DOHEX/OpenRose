# Luminous Patamon / Tailmon / Gabumon / Agumon 控制协议

RoseLink 3.8.0（5310）为本组配置分配 `LuminousPatamonDeviceConfig`、`LuminousTailmonDeviceConfig`、`LuminousGabumonDeviceConfig`、`LuminousAgumonDeviceConfig`。静态核对的收发路径为 [RS Common V2](../families/rs-common-v2.md) 的 `RsCommonV2Sender`、`RsCommonV2Receiver` 和 `RsCommonV2ReceiveHandler`。实机收发与设置持久化未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| Patamon | `0b0ae916` | `luminous_patamon` | `Patamon` | 静态 | 识别前缀（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:23`）、型号注册表（`rose_reverse/analysis/model_registry.json`） |
| Tailmon | `0b0a941f` | `luminous_tailmon` | `Tailmon` | 静态 | 识别前缀（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_tailmon_device_config.dart:23`）、型号注册表（`rose_reverse/analysis/model_registry.json`） |
| Gabumon | `0b0a2aed` | `luminous_gabumon` | `Gabumon` | 静态 | 识别前缀（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_gabumon_device_config.dart:23`）、型号注册表（`rose_reverse/analysis/model_registry.json`） |
| Agumon | `0b0a34fd` | `luminous_agumon` | `Agumon` | 静态 | 识别前缀（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_agumon_device_config.dart:23`）、型号注册表（`rose_reverse/analysis/model_registry.json`） |

以 `Patamon`、`Tailmon`、`Gabumon`、`Agumon` 作为设备名称中的型号词。广播按前缀识别。

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

[默认传输](../families/rs-common-v2.md#默认传输)列出 GATT 三个 UUID、SPP 选择条件、广播中经典地址的偏移与发送间隔。型号控制 SPP 服务见Patamon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:484`）、Tailmon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_tailmon_device_config.dart:484`）、Gabumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_gabumon_device_config.dart:484`）、Agumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_agumon_device_config.dart:484`）。

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

| 型号 | 配置 cid | GDT 表项 | 目标 | 查询 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- |
| Patamon | 515 | 60093 | `0x8e2444` | `getInfo` | 静态 | 初始化分派（`rose_reverse/analysis/i7_dispatch/table.tsv:60094`） |
| Tailmon | 514 | 60092 | `0x8e2444` | `getInfo` | 静态 | 初始化分派（`rose_reverse/analysis/i7_dispatch/table.tsv:60093`） |
| Gabumon | 516 | 60094 | `0x8e2444` | `getInfo` | 静态 | 初始化分派（`rose_reverse/analysis/i7_dispatch/table.tsv:60095`） |
| Agumon | 517 | 60095 | `0x8e2444` | `getInfo` | 静态 | 初始化分派（`rose_reverse/analysis/i7_dispatch/table.tsv:60096`） |

初始化闭包只调用 `getInfo`。Sender 从序号 0 开始，命令体与 35 字节完整查询帧见[初始化与查询](../families/rs-common-v2.md#初始化与查询)。Patamon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:44`）、Tailmon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_tailmon_device_config.dart:44`）、Gabumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_gabumon_device_config.dart:44`）、Agumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_agumon_device_config.dart:44`）。

## 电量

仅显示左右耳，字段 `0C` 中左右耳各占一个字节。显示配置没有仓电量槽。见Patamon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:136`）、Tailmon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_tailmon_device_config.dart:136`）、Gabumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_gabumon_device_config.dart:136`）、Agumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_agumon_device_config.dart:136`）（静态）及[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存)。

## 设置命令

四款的控件、槽位和选项相同，下面以 Patamon 的控件行为定位共用表。其余三款的设置面板分别见Tailmon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_tailmon_device_config.dart:151`）、Gabumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_gabumon_device_config.dart:151`）、Agumon（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_agumon_device_config.dart:151`）。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 仅左右耳 | — | `getInfo` → `0C` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:133`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0E vv` | 开=01、关=00 | — | `getInfo` → `0E` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:151`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 vv` | 开=01、关=00 | — | `getInfo` → `32` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:164`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | 左单／双／三击及长按=01..04、右耳对应=11..14。动作依次为无=00、播放暂停=01、上一曲=02、下一曲=03、音量加=04、音量减=05、游戏模式=06、语音助手=07 | — | `getInfo` → `01` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:209`）、动作列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:408`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | `07 vv` | 开=00、关=01 | — | `getInfo` → `07` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:209`） |
| 提示音音量 | `setDingLevel` | `2E vv` | 1..5 档=01..05，关闭选项隐藏 | — | `getInfo` → `2E` | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:316`）、[关闭选项条件](../families/rs-common-v2.md#全族已知的坑) |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2F vv` | 左=01、右=02、双耳=03、停止=04 | — | 通知 `2F`（单耳或双耳事件） | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:218`）、[接收事件](../families/rs-common-v2.md#寻机) |
| 设置面板 | 结构 | — | 分组容器 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:200`） |
| 更多设置 | 结构 | — | 进入更多设置 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:227`） |

## 接收字段

字段格式同[族文档](../families/rs-common-v2.md#字段表)。仅左右耳的布局不向用户发布第 3 字节的仓电量。

## 未实现与未知

- OpenRose 可读取寻机事件。发送响铃及停止的操作尚未开放。`2F` 的单侧事件只能确认对应一侧的变化。
- 真机资料未知。现有依据是 APK 与其 AOT 代码，还缺本型号的广播、BLE / SPP 连接、实际设置回包和断电后持久化记录。
- 四款显示配置都只列左右耳，未提供 ANC、EQ 或音频模式选择。

## OTA

本版本型号配置没有 OTA 入口（静态）。见[逐型号能力表](../00-supported-models-and-capabilities.md#rs-common-v2逐型号能力)。
