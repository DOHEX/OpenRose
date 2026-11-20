# SpeakFeel [speakfree] 控制协议

`SpeakfreeDeviceConfig` 属于[RS Common V2](../families/rs-common-v2.md)。基线为 RoseLink 3.8.0（5310）。型号配置、界面和命令均为静态证据，未进行实机验证。

3.8.6（5495）的[注册表与选项索引](../supported-devices-3.8.6.json)仍含此配置。新版个性化与 Windows APO 入口属于应用／系统功能。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| SPEAKFREE | `0b0f0a0b` | `speakfree` | SpeakFeel | 静态 | 型号构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:10`）、设备名称（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:557`） |

RoseLink 显示名称为 SpeakFeel，系统配对名称未实测。SpeakFeel 的两个硬件分支共用零售名称，须用广播前缀区分。

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

控制 SPP 使用 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。本型号的 UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:561`）、[族文档](../families/rs-common-v2.md#默认传输)。

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

GDT 表项 60084 → `0x8e2444`，发送 `getInfo`。分派目标（`rose_reverse/analysis/i7_dispatch/table.tsv:60085`）。Sender 初始序号为 0，见Sender 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:49`）。批量读取帧（合成，序号 0）如下。

`ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。[族文档](../families/rs-common-v2.md#初始化与查询)。

## 电量

单体（复用仓槽）。Tag `0C` 的第 3 字节承载单体读数。静态依据为电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:127`）和[族文档](../families/rs-common-v2.md#电池与仓缓存)。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 单体（复用仓槽） | — | getInfo（0C） | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:127`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2a mode` | POP=01、HIFI=00、ROCK=02 | — | FA 2A → 2A | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:145`）、界面值到 Sender 的分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_session.dart:97`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e n` | 开=01、关=00 | — | getInfo → 0E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:190`）、setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3252`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 n` | 开=01、关=00 | — | getInfo → 32 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:203`）、setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:5009`） |
| 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish` | `31 n` | 中文=00、英文=01 | — | FA 31 → 31 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:283`）、setEarLanguageChinese（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:865`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioAptx`、`setAudioAptxHd`、`setAudioAptxAdaptive` | `2b n` | AAC/SBC=00、LDAC=01、aptX=04、aptX HD=05、aptX Adaptive=06 | — | getInfo → 2B | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:252`）、选项回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_audio_panel_item.dart:2529`）、发送字节目录（`rose_reverse/analysis/i7_sender_commands.json:1`） |
| 寻机 | `setFindDeviceBoth`、`setFindOff` | `2f n` | 单体开始=03、停止=04 | — | getInfo / 通知 2F | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:274`）、TWS 与单体分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:276`）、setFindDeviceBoth（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1207`） |
| 提示音 | `setDingLevel` | `2e n` | 1..5 档=01..05 | — | getInfo → 2E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:441`）、关闭选项（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:825`）、档位编码（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） |
| 功能分组 | 结构 | — | 控件容器 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:181`） |
| 更多设置 | 结构 | — | 页面入口 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:296`） |
| 固件更新 | 范围外 | — | 见 OTA 节 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:348`） |

音频面板将 aptX 系列选项分派给各自的 Sender 方法。原生回调核对（`rose_reverse/analysis/v2-spatial-and-microphone.md:7`）。

## 接收字段

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 0C | 单体电量 | 仓槽 1..100 时更新百分比与仓槽标志 | 静态 | 单体读数（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/speakfree_receive_handler.dart:201`） |
| 2B | 音频模式 | 显式接纳 0、1、4、5、6，其他值不更新控制器 | 静态 | 接收映射（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/speakfree_receive_handler.dart:26`） |

## 未实现与未知

- 未取得该分支的设备样本。真实广播、SPP 配对、BLE / SPP 首次连接、设置效果及持久化均未知。协议基线来自 RoseLink 静态证据。
- 仅凭不含硬件信息的零售名称无法判断分支，采用厂商广播前缀。

## OTA

面板存在，`otaType=none`；实际路由未知。SPP OTA 配置写入与控制相同的 UUID。面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:348`）、UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:563`）。

OpenRose 不实现固件升级。
