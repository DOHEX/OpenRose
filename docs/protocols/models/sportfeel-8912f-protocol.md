# SportFeel 8912F 控制协议

RoseLink 3.8.6（5495）的 `Sportfeel8912fDeviceConfig` 使用 [RS Common V2](../families/rs-common-v2.md)。型号配置、对象池和收发方法均为静态证据，尚无实机记录。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| BHE-0003 | `5d2f61f0` | sportfeel-8912f | SportFeel | 静态 | 构造器（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:10`）、customConfigEarObject（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:104`） |

SportFeel 名称也用于早期 RS Common 分支。扫描时由广播前缀选择协议。

## 传输

| 项 | 值 | 状态 | 出处 |
| --- | --- | --- | --- |
| BLE 服务 | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |
| 写特征 | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |
| 通知特征 | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |
| SPP 是否允许 | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |
| 控制 SPP UUID | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |
| 广播中的经典地址 | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |
| 写入间隔 | 同族默认 | 同族 | [默认传输](../families/rs-common-v2.md#默认传输) |

## 初始化

分派表第 86540 项（cid 542）落到 `0x81a53c`，仅调用 `getInfo`。anonymous closure（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:222`）、getInfo（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4912`）。3.8.6 的 Commander 根据本型号面板生成 `FA` 查询。序号 00 的完整合成帧为 `ff000bfa0d0c2a0e322b2f01072e17aa`。字段顺序及分派证据见[版本对照](../3.8.6-model-delta.md)。

## 电量

左右耳与充电盒。Tag `0C` 共三个字节，字段解释同族，按型号布局显示电量。静态依据见 homeWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:334`）。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 游戏模式 | `setGameModeOn/Off` | `0E nn` | 关闭 00、开启 01 | — | FA 0E → 0E | 静态 | homeWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:334`）、setGameModeOn（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3518`） |
| 多点连接 | `setConnectMulOn/Off` | `32 nn` | 关闭 00、开启 01 | — | FA 32 → 32 | 静态 | homeWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:334`）、setConnectMulOn（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4871`） |
| 提示音音量 | `setDingLevel` | `2E nn` | 0..5，00 静音 | — | FA 2E → 2E | 静态 | moreControlWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:238`）、setDingLevel（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1748`） |
| EQ 预设 | `setEqHifi` | `2A nn` | HIFI=00 | — | FA 2A → 2A | 静态 | homeWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:334`）、setEqHifi（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4709`） |
| 音频模式 | `setAudioAacSbc / setAudioLdac` | `2B nn` | AAC/SBC=00、LDAC=01 | — | FA 2B → 2B | 静态 | homeWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:334`）、setAudioLdac（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2657`） |
| 触控开关 | `setTouchOn/Off` | `07 nn` | 开启 00、关闭 01 | — | FA 07 → 07 | 静态 | touchInfo（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:168`） |
| 触控绑定 | `setLeftTouchOne…setRightTouchLong` | `01 ss aa` | 左 01..04、右 11..14 依次单击、双击、三击、长按。动作 00 无、01 播放暂停、02 上一首、03 下一首、04 音量加、05 音量减、06 游戏、07 语音 | — | FA 01 → 01 | 静态 | touchInfo（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:168`）、setLeftTouchOne（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2409`） |
| 寻机 | `setFindDeviceLeft/Right/Both、setFindOff` | `2F nn` | 01 左、02 右、03 双耳、04 停止 | — | 事件 2F，未证实能查询完整响铃状态 | 静态 | homeWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:334`）、setFindDeviceLeft（`rose_reverse/analysis/3.8.6/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1035`） |

## 接收字段

同族文档。电量按上述布局取值，提示音仅开放 0..5 档，触控与 EQ 使用本型号枚举。具体配置与复用方法见 [3.8.6 清单](../supported-devices-3.8.6.json)。

## 未实现与未知

- 寻机只呈现设备事件。尚无可确认完整响铃状态的独立查询，保留写入缺口。
- 主页分组、更多设置、外观和 Windows APO 入口属于应用或系统服务。homeFooterWidgetModels（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:3389`）。

## OTA

AB。OpenRose 的常规控制不写升级服务。型号构造器（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/sportfeel_8912f_device_config.dart:10`）。 [AB 研究](../ota/ab.md)。
