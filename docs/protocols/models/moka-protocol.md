# MOKA 控制协议

`MokaDeviceConfig` 属于[RS Common V2](../families/rs-common-v2.md)。基线为 RoseLink 3.8.0（5310）。型号配置、界面和命令均为静态证据，未进行实机验证。

3.8.6（5495）的[注册表与选项索引](../supported-devices-3.8.6.json)仍含此配置。新版个性化与 Windows APO 入口属于应用／系统功能。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| MOKA | `68f5b8eb` | `moka` | MOKA | 静态 | 型号构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:12`）、设备名称（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:635`） |

RoseLink 显示名称为 MOKA，系统配对名称未实测。

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

控制 SPP 使用 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。本型号的 UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:639`）、[族文档](../families/rs-common-v2.md#默认传输)。

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

GDT 表项 60089 → `0x8e2444`，发送 `getInfo`。分派目标（`rose_reverse/analysis/i7_dispatch/table.tsv:60090`）。Sender 初始序号为 0，见Sender 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:46`）。批量读取帧（合成，序号 0）如下。

`ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。[族文档](../families/rs-common-v2.md#初始化与查询)。

## 电量

单体（复用仓槽）。Tag `0C` 的第 3 字节承载单体读数。静态依据为电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:144`）和[族文档](../families/rs-common-v2.md#电池与仓缓存)。

新版预设与历史配置的差异见下表。新版 EQ 选项（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:406`）、标签和控件值（`rose_reverse/analysis/3.8.6/blutter/objs.txt:2130`）。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 单体（复用仓槽） | — | getInfo（0C） | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:144`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2a mode` | 3.8.6 POP=01、HIFI=00、ROCK=02；3.8.0 仅 HIFI | — | FA 2A → 2A | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:162`）、界面值到 Sender 的分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_session.dart:97`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 自定义 EQ | `setEqCustom`、`setEqCustomData` | `2a 04；3e + 10 字节` | 10 段 −6..6；负数为 80 按位或绝对值 | — | FA 2A / FA 3E → 2A / 3E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:162`）、自定义预设的写入顺序（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_session.dart:446`）、[族文档](../families/rs-common-v2.md#自定义-eq) |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e n` | 开=01、关=00 | — | getInfo → 0E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:211`）、setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3252`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 n` | 开=01、关=00 | — | getInfo → 32 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:224`）、setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:5009`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac` | `2b n` | AAC/SBC=00、LDAC=01 | — | getInfo → 2B | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:269`）、选项回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_audio_panel_item.dart:2529`）、发送字节目录（`rose_reverse/analysis/i7_sender_commands.json:1`） |
| 提示音 | `setDingLevel` | `2e n` | 1..5 档=01..05 | — | getInfo → 2E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:437`）、关闭选项（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:825`）、档位编码（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） |
| 功能分组 | 结构 | — | 控件容器 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:202`） |
| 更多设置 | 结构 | — | 页面入口 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:291`） |
| 固件更新 | 范围外 | — | 见 OTA 节 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:337`） |

3.8.6 增加扬声器组网入口，固件门槛 127。该功能涉及多个目标、组状态和从机列表，OpenRose 未接入。组网入口（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:519`）、组状态及选择命令（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2737`）。

## 接收字段

同族文档。实际可用项见上表。[族文档](../families/rs-common-v2.md#字段表)。

## 未实现与未知

- 未取得该分支的设备样本。真实广播、SPP 配对、BLE / SPP 首次连接、设置效果及持久化均未知。协议基线来自 RoseLink 静态证据。
- `_sendAfter` 为游戏开关两条命令登记回调，开启和关闭之后都补读 EQ（FA 2A），OpenRose 收到游戏状态变化后据此刷新均衡器读数。游戏模式开关回调（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_function.dart:14`）、回调登记（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:532`）、共享回调（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:597`）。

## OTA

路由 AB，面板无固件门槛，SPP OTA 复用控制 UUID。另配置 AB123A USB-HID。升级面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:337`）、UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:641`）、[AB 方案](../ota/ab.md)。

OpenRose 不实现固件升级。
