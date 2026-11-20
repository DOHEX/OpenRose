# Astro X 控制协议

`AstroXDeviceConfig` 属于[RS Common V2](../families/rs-common-v2.md)。基线为 RoseLink 3.8.0（5310）。型号配置、界面和命令均为静态证据，未进行实机验证。

3.8.6（5495）的[注册表与选项索引](../supported-devices-3.8.6.json)仍含此配置。新版个性化与 Windows APO 入口属于应用／系统功能。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| ASTROX | `0b0e0a0b` | `astrox` | Astro X | 静态 | 型号构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:10`）、设备名称（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:374`） |

RoseLink 显示名称为 Astro X，系统配对名称未实测。

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

控制 SPP 使用 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。本型号的 UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:378`）、[族文档](../families/rs-common-v2.md#默认传输)。

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

GDT 表项 60111 → `0x8e21b0`，发送 `getInfo`。分派目标（`rose_reverse/analysis/i7_dispatch/table.tsv:60112`）。Sender 初始序号为 0，见Sender 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:49`）。批量读取帧（合成，序号 0）如下。

`ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。[族文档](../families/rs-common-v2.md#初始化与查询)。

本型号随后同步本地时间。BODY 为 `35 世纪 年份后两位 月 日 时 分 秒`，每项按普通单字节整数编码。例如 2026-11-20 09:05:08 的 BODY 为 `35 14 1a 0b 14 09 05 08`（合成）。getInfo、setTime 顺序（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:437`）、日期拆分和编码（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:134`）。

## 电量

左、右、仓。Tag `0C` 的三字节分别承载左右耳和仓电量。静态依据为电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:505`）和[族文档](../families/rs-common-v2.md#电池与仓缓存)。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 左、右、仓 | — | getInfo（0C） | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:505`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2a mode` | BASS=01、BALANCE=00、BASS+=02 | — | FA 2A → 2A | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:551`）、界面值到 Sender 的分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_session.dart:97`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 增益 | `setGainLevel` | `45 n` | 低=00、中=01、高=02 | 152 | FA 45 → 45 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:608`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 听力保护 | `setHearingProtection` | `46 n` | 关=00，75/80/85/90/95/100 dB=01..06 | 152 | getInfo → 46 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:630`）、[族文档](../families/rs-common-v2.md#设置命令) |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e n` | 开=01、关=00 | — | getInfo → 0E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:654`）、setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3252`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 n` | 开=01、关=00 | — | getInfo → 32 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:667`）、setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:5009`） |
| 佩戴检测 | `setEarDetectionOn`、`setEarDetectionOff` | `08 n` | 开=00、关=01 | — | getInfo → 08 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:127`）、setEarDetectionOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2114`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc` | `2b n` | AAC/SBC=00、LDAC=01、LHDC=02 | — | getInfo → 2B | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:818`）、选项回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_audio_panel_item.dart:2529`）、发送字节目录（`rose_reverse/analysis/i7_sender_commands.json:1`） |
| 耳道自适应 | `setEarAdaptiveOn`、`setEarAdaptiveOff` | `3a n` | 开=01、关=00 | — | getInfo → 3A | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:198`）、自适应开关（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:135`）、setEarAdaptiveOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1773`） |
| 空间模式 | `setSpatialAudioModeOff`、`setSpatialAudioModeHead`、`setSpatialAudioModeFixed` | `38 n` | 关闭=00、头部跟踪=01、固定=02 | — | getInfo → 38 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:533`）、空间模式按钮（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_spatial_audio.dart:709`）、setSpatialAudioModeOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3455`） |
| 空间音频内容模式 | `setSpatialAudioMusicModeTV`、`setSpatialAudioMusicModeMusic`、`setSpatialAudioMusicModeMovie`、`setSpatialAudioMusicModeGame` | `3d n` | 电视=00、音乐=01、电影=02、游戏=03 | — | getInfo → 3D | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:533`）、内容选择（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_spatial_audio.dart:203`）、setSpatialAudioMusicModeTV（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3416`） |
| 轻拍开关 | `setTapOn`、`setTapOff` | `3b n` | 开=01、关=00 | — | getInfo → 3B | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:879`）、开关（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/tap.dart:2127`）、setTapOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2192`） |
| 轻拍绑定 | `setLeftTapTwo`、`setLeftTapThree`、`setRightTapTwo`、`setRightTapThree` | `3c slot action` | 左双击 / 三击=02 / 03，右双击 / 三击=12 / 13。动作值：00 无、01 播放/暂停、02 上一首、03 下一首、04 音量+、05 音量−、06 游戏模式、07 语音助手 | — | getInfo → 3C | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:879`）、四个按键回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_tap_control_screen.dart:243`）、设置轻拍（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2233`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | `07 n` | 开=00、关=01 | — | getInfo → 07 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:838`）、setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） |
| 触控绑定 | `setLeftTouchOne 等八个按键方法` | `01 slot action` | 左 01..04、右 11..14；动作 00 无、01 播放/暂停、02 上一首、03 下一首、04 音量+、05 音量−、06 游戏模式、07 语音助手 | — | getInfo → 01 | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:838`）、[族文档](../families/rs-common-v2.md#触摸动作) |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2f n` | 左=01、右=02、双耳=03、停止=04 | — | getInfo / 通知 2F | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:847`）、TWS 与单体分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_find.dart:276`）、setFindDeviceBoth（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1207`） |
| 提示音 | `setDingLevel` | `2e n` | 1..5 档=01..05 | — | getInfo → 2E | 静态 | 型号控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:142`）、关闭选项（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:825`）、档位编码（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） |
| 功能分组 | 结构 | — | 控件容器 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:189`） |
| 星芒业务 | 范围外 | — | 云端业务入口 | 170 | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:724`） |
| 更多设置 | 结构 | — | 页面入口 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:856`） |
| 固件更新 | 范围外 | — | 见 OTA 节 | — | — | 静态 | 型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:926`） |

开启头部跟踪或固定模式后，若耳道自适应未开启，另发 `3A 01`。关闭耳道自适应时，若空间模式仍开启，另发 `38 00`。空间模式关闭时隐藏内容模式选择。原生回调核对（`rose_reverse/analysis/v2-spatial-and-microphone.md:7`）。

## 接收字段

同族文档。实际可用项见上表。[族文档](../families/rs-common-v2.md#字段表)。

轻拍开关和按键表与触控分别上报。3B 只接受一个字节（00 关、01 开）；3C 将槽位和动作按字节对解析，每个槽位都取到后才发布完整按键表。3C 接收（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:14973`）、3B 接收（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:15404`）。轻拍页面从 本型号 touchInfo（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/normal_ear_tap_control_screen.dart:521`） 取动作表，双击和三击为左右两侧各自的手势。 3.8.6 保留此行为，见轻拍页（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/modules/device_control/view/normal_ear_tap_control_screen.dart:673`）及接收器四个必需槽位（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:19457`）。

## 未实现与未知

- 耳塞贴合测试：已检查全部 RS Common V2 配置，只有通用调试配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/rs_common_v2_device_config.dart:1760`）实例化独立测试入口。Astro X 的耳道自适应控件（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ear_adaptive_panel_item.dart:171`）只切换自适应及其关联的空间模式，未发现从该型号 UI 发起单次贴合测试的调用。3.8.6 同样只有通用配置（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/rs_common_v2_device_config.dart:4199`）实例化贴合测试入口。OpenRose 不开放该测试。

- 未取得该分支的设备样本。真实广播、SPP 配对、BLE / SPP 首次连接、设置效果及持久化均未知。协议基线来自 RoseLink 静态证据。
- 空间面板使用 38 和 3D 两个选择器。未在这两个回调内发现 33 空间总开关、39 头部手势命令；星芒业务独立于耳机控制。

## OTA

路由 AB，面板无固件门槛，SPP OTA 复用控制 UUID。升级面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:926`）、UUID（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:380`）、[AB 方案](../ota/ab.md)。

OpenRose 不实现固件升级。
