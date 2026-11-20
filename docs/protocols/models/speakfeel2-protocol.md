# SPEAKFEEL [speakfeel2] 控制协议

`Speakfeel2DeviceConfig` 属于[RS Common V2](../families/rs-common-v2.md)。基线为 RoseLink 3.8.0（5310）。型号配置、界面和命令均为静态证据，未进行实机验证。

3.8.6（5495）的[注册表与选项索引](../supported-devices-3.8.6.json)仍含此配置。新版个性化与 Windows APO 入口属于应用／系统功能。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| SPEAKFEEL2 | `0b0e3ce6` | `speakfeel2` | SPEAKFEEL | 静态 | 型号构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:10`）、设备名称（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:655`） |

RoseLink 显示名称为 SPEAKFEEL，系统配对名称未实测。SpeakFeel 的两个硬件分支共用零售名称，须用广播前缀区分。

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

控制 SPP 使用 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。本型号的 UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:659`）、[族文档](../families/rs-common-v2.md#默认传输)。

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

GDT 表项 60085 → `0x8e2444`，发送 `getInfo`。分派目标（`rose_reverse/analysis/i7_dispatch/table.tsv:60086`）。Sender 初始序号为 0，见Sender 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:44`）。批量读取帧（合成，序号 0）如下。

`ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。[族文档](../families/rs-common-v2.md#初始化与查询)。

## 电量

单体（复用仓槽）。Tag `0C` 的第 3 字节承载单体读数。静态依据为电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:122`）和[族文档](../families/rs-common-v2.md#电池与仓缓存)。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 单体（复用仓槽） | — | getInfo（0C） | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:122`） |
| 低音增强 | `setEqBassPlus` | `2a 05 n` | 两档 n=00 / 01；字段为未装箱的档位总数 2 | — | FA 2A → 2A 05 n | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:140`）、档位总数（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:142`）、编码（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4044`） |
| 增益 | `setGainLevel` | `45 n` | 低=00、中=01、高=02 | — | FA 45 → 45 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:153`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 听力保护 | `setHearingProtection` | `46 n` | 关=00，75/80/85/90/95/100 dB=01..06 | — | getInfo → 46 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:162`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e n` | 开=01、关=00 | — | getInfo → 0E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:171`）、setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3252`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 n` | 开=01、关=00 | — | getInfo → 32 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:184`）、setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:5009`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc` | `2b n` | AAC/SBC=00、LDAC=01、LHDC=02 | — | getInfo → 2B | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:235`）、选项回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_audio_panel_item.dart:2529`）、发送字节目录（`rose_reverse/analysis/i7_sender_commands.json:1`） |
| 独立麦克风激活 | `setIndependentMicModeActivateOn`、`setIndependentMicModeActivateOff` | `49 00 n` | 激活=01、停用=00 | — | getInfo → 49 第一字节 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:203`）、激活开关（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/independent_mic_mode_page.dart:716`）、setIndependentMicModeActivateOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3082`） |
| 独立麦克风模式 | `setIndependentMicModeOn`、`setIndependentMicModeOff` | `49 01 n` | 开=01、关=00；需先激活 | — | getInfo → 49 第二字节 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:203`）、模式开关的激活条件（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/independent_mic_mode_page.dart:511`）、setIndependentMicModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3168`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | `07 n` | 开=00、关=01 | — | getInfo → 07 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:257`）、setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） |
| 触控绑定 | `setLeftTouchOne 等八个按键方法` | `01 slot action` | 左 01..04、右 11..14；动作 00 无、01 播放/暂停、02 上一首、03 下一首、04 音量+、05 音量−、06 游戏模式、07 语音助手 | — | getInfo → 01 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:257`）、[族文档](../families/rs-common-v2.md#触摸动作) |
| 寻机 | `setFindDeviceBoth`、`setFindOff` | `2f n` | 单体开始=03、停止=04 | — | getInfo / 通知 2F | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:266`）、TWS 与单体分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:276`）、setFindDeviceBoth（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1207`） |
| 提示音 | `setDingLevel` | `2e n` | 1..5 档=01..05、关闭=00 | — | getInfo → 2E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:462`）、关闭选项（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:825`）、档位编码（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） |
| 功能分组 | 结构 | — | 控件容器 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:226`） |
| 更多设置 | 结构 | — | 页面入口 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:275`） |
| 固件更新 | 范围外 | — | 见 OTA 节 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:327`） |

麦克风激活与模式开关为两组命令，界面要求先激活，再允许更改模式。原生回调核对（`rose_reverse/analysis/v2-spatial-and-microphone.md:7`）。

## 接收字段

49 的两字节通知是完整状态快照：第一字节表示激活、第二字节表示模式，均须为 00 或 01。未激活时模式回读为关。四、六字节的 49 通知另作扩展固件数据处理。低音档位由 2A 05 n 回读。49 分派（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:2095`）、两标志解析（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:12968`）、[族文档](../families/rs-common-v2.md#接收帧与字段)。

## 未实现与未知

- 未取得该分支的设备样本。真实广播、SPP 配对、BLE / SPP 首次连接、设置效果及持久化均未知。协议基线来自 RoseLink 静态证据。
- 仅凭不含硬件信息的零售名称无法判断分支，采用厂商广播前缀。

## OTA

路由 AB，面板无固件门槛，SPP OTA 复用控制 UUID。另配置 AB123A USB-HID。升级面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:327`）、UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:661`）、[AB 方案](../ota/ab.md)。
USB HID 配置覆写返回单项列表，字段值为 `0x38ff / 0xdfba / 0xff66`。usbHidConfigs（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:700`）、配置对象（`rose_reverse/analysis/blutter/objs.txt:1238`）。

OpenRose 不实现固件升级。
