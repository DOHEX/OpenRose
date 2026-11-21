# Cambrian S 控制协议

RoseLink 3.8.6（5495）为该型号注册 `CambrianSDeviceConfig`，使用 [RS Common](../families/rs-common.md) 的 Sender、Receiver，以及 `CambrianReceiveHandler`。本文记录 APK 静态行为，实机读写未知。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| Cambrian S | `0b0c2b46` | `cambrian-s` | ROSE CAMBRIAN S | 静态 | 型号配置（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:20`） |

设备名称使用 ROSE CAMBRIAN S。广播前缀用于区分 Cambrian 和 Cambrian S。

## 传输

| 项 | 值 | 状态 | 出处 |
| --- | --- | --- | --- |
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 是 | 静态 | 型号配置（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:128`） |
| 控制 SPP UUID | 同族默认 | 同族 | 同族 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| OTA BLE 服务 / 写 / 通知 | `FF12 / FF15 / FF14` | 静态 | OTA 配置（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:135`） |
| OTA SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | UUID（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:130`） |

普通控制的参数见[默认传输](../families/rs-common.md#默认传输)。本型号显式允许 SPP 并写入控制 UUID。型号服务（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:130`）。

## 初始化

配置 cid 579 的分派目标是 `0x819ca4`。先发送 `getInfo` 查询帧（以下按静态代码生成）。sendInitCommands（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:263`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2163`）。

```text
FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA
```

随后延迟 500 ms，再经 `0x81a53c` 发送一次相同的 `getInfo`。延时调用（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:303`）、Duration 500000 微秒（`rose_reverse/analysis/3.8.6/blutter/objs.txt:51646`）。

## 电量

单体电量复用仓槽，取 `0C` 第三个电量字节。显示槽的配置为静态。电量枚举（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:369`）。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 见电量节 | — | getInfo → `0C` | 静态 | 电量枚举（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:369`） |
| 降噪模式 | setNoiseModeAnc / Normal / Trans / Wind | `12 vv` | ANC=02、标准=04、通透=06、抗风=08 | — | getInfo → `09` | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:384`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:645`） |
| 通透档位 | setTransLevel | `5A vv` | 1/2/3 档=01/02/03 | — | getInfo → `2D` | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:384`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:330`） |
| EQ 预设 | setEqHifi / Pop / Rock | `2A vv` | HIFI=00、POP=01、ROCK=02 | — | getInfo → `2A` | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:417`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2000`） |
| 音频模式 | setAudioAacSbc / Ldac | `2B vv` | AAC/SBC=00、LDAC=01 | — | getInfo → `2B` | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:498`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1616`） |
| 游戏模式 | setGameModeOn / Off | `0E vv` | 开=01、关=00 | — | getInfo → `0E` | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:462`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1742`） |
| 多点连接 | setConnectMulOn / Off | `2B vv` | 开=04、关=00，关闭后为 AAC/SBC | — | getInfo → `2B` | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:475`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2108`） |
| 触控绑定 | setLeftTouchOne / Two / Three、setRightTouchOne / Two / Three | `01 SS 0A` | 左单/双/三=01/02/03、右=11/12/13，A=0 无、1 播放暂停、2 上一曲、3 下一曲、4 音量加、5 音量减 | — | getInfo → `01` | 静态 | touchInfo（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:177`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1376`） |
| 触控总开关 | setTouchOn / Off | `0E vv` | 开=00、关=02 | — | getInfo → `07` | 静态 | touchInfo（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:177`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:883`） |
| 提示音音量 | setDingLevel | `5C vv` | 1..5 档=01..05 | — | getInfo → `2E` | 静态 | moreControlWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:237`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:700`） |
| 寻机 | setFindDeviceLeft / Right / Both / setFindOff | `5E vv` | 整机启动=03、停止=04 | — | — | 静态 | 型号控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:518`）、Sender（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:195`） |
| 设置面板 | 结构 | — | 页面入口 | — | — | 静态 | 控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:453`） |
| 更多设置 | 结构 | — | 页面入口 | — | — | 静态 | 控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:536`） |
| 星芒业务 | 范围外 | — | 云端业务 | — | — | 静态 | 入口（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:594`） |
| OTA | 范围外 | — | 见 OTA 节 | — | — | 静态 | 入口（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:635`） |

共用页尾还包含本地个性化和 Windows APO 入口，由宿主处理。homeFooterWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:3389`）。

左右两组绑定的界面标签为「音量−」和「音量＋」。触控信息中的字段分别引用 `left_volume_down` 和 `right_volume_up`。标签（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:209`）。

## 接收字段

解码同[族文档](../families/rs-common.md)。CambrianReceiveHandler 将 `0C` 第三槽映射为单体电量。 `2B` 值 4 表示多点，值 0 表示 AAC/SBC。

## 未实现与未知

- 寻机命令只有单向写入，当前接收器没有可确认响铃设置结果的字段。
- 本型号仍待实机验证广播原文、连接初始化、读回和设置持久化。

## OTA

型号选择 [AB OTA](../ota/ab.md)，配置没有设置版本门槛。升级属独立传输，OpenRose 未实现。OTA 类型（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_s_device_config.dart:26`）、AB 枚举（`rose_reverse/analysis/3.8.6/blutter/objs.txt:42067`）。
