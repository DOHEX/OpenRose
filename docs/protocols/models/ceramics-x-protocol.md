# CERAMICS-X 控制协议

CERAMICS-X 与 CERAMICS-X-7034 分别使用 `CeramicsXDeviceConfig` 和 `CeramicsX7034DeviceConfig`，都属于 [F8 协议族](../families/f8.md)。分析基线为 RoseLink 3.8.0（versionCode 5310），APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。没有实机连接、广播或通知抓包；以下结论全部是静态分析。两者共享展示名 `CERAMICS-X`，但连接配置和配置类不同，不能合并识别分支。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| CERAMICS-X | `18840109` | `CERAMICS-X` | CERAMICS-X | 静态 | ceramics_x_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:10`） |
| CERAMICS-X-7034 | `18840112` | `CERAMICS-X-7034` | CERAMICS-X | 静态 | ceramics_x_7034_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:10`） |

系统设备名里可能出现的零售名称都是 `CERAMICS-X`；`CERAMICS-X-7034` 是内部 name，不是第二个展示名。两者的 CMIIT 型号核准代码都是 `24J51R2V9918`（X 构造器（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:20`）、7034 构造器（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:20`））。7034 不是 UI 变体：它的 SPP 配置与 X 不同，必须保留独立识别分支（注册表（`rose_reverse/analysis/model_registry.json`））。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | CERAMICS-X：否（`"false"`）；CERAMICS-X-7034：是（`"true"`） | 静态 | X 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:549`）；7034 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:547`） |
| 控制 SPP UUID | CERAMICS-X：—；CERAMICS-X-7034：`0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | 同上 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |
| OTA Service / 写 / 读 | 两者均为 `00007033-0000-1000-8000-00805f9b34fb` / `00002001-…` / `00002002-…` | 静态 | OTA 默认配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`） |
| OTA 路由 UUID | 两者均为 `00007034-0000-1000-8000-00805f9b34fb` | 静态 | X 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:549`）；7034 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:547`） |

7034 的 `sppSupport="true"` 只在平台支持 SPP、未强制 BLE、音频 Profile 不是 LE Audio 时才可能走 SPP；静态配置不等于实际 bearer（传输分析（`rose_reverse/analysis/ceramics_transport.md`））。其余默认项的具体取值见 [F8 默认传输](../families/f8.md#默认传输)。

## 初始化

| 型号 | 配置 cid | 分派表索引 | 目标 |
|---|---:|---:|---|
| CERAMICS-X | 542 | 60120 | `0x8e1d8c` `ExplorerDeviceConfig::sendInitCommands` |
| CERAMICS-X-7034 | 543 | 60121 | `0x8e1d8c` `ExplorerDeviceConfig::sendInitCommands` |

两者都没有自己的 `sendInitCommands` 覆写；分派表把两个槽都指向 EXPLORER 的共享实现（X 行（`rose_reverse/analysis/i7_dispatch/table.tsv:60121`）、7034 行（`rose_reverse/analysis/i7_dispatch/table.tsv:60122`）），因此连接后发送 12 条查询，比 CERAMICS 多一条 `getEarLanguage`（explorer_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:773`））：

```text
00 27 01 00 01 01   getBattery
00 27 01 00 01 0C   getAnc
00 27 01 00 01 04   getEq
00 27 01 00 01 08   getGameMode
00 27 01 00 01 0B   getAudioLdac
00 27 01 00 01 02   getVersion
02 27 01 00 01 05   getKey
00 27 01 00 01 09   getEarDetection
00 27 01 00 01 13   getDingLevel
00 27 01 00 01 11   getAncLevel
00 27 01 00 01 16   getTouch
00 27 01 00 01 0A   getEarLanguage
```

这是对旧分析“X/7034 初始化未闭合”的静态修正；实际执行仍受 `GlobalController` 的连接类型/USB 早退条件影响（global_controller.dart（`rose_reverse/analysis/blutter/asm/roselink/lib/global_controller.dart:2134`））。两组型号的初始化字节相同，不能用初始化区分 X 与 7034。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左、右、盒三槽 | 静态 | X 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:113`）；7034 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:113`） |
| 回包字段 | byte7 左、byte8 右、byte9 盒；bit7 为左/右充电位 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1639`） |
| 本型号消费方式 | 左、右按 0–100 显示百分比；盒值另有处理，特定值表示“没有充电仓数据” | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:818`） |

电量查询是 `00 27 01 00 01 01`，回包 byte5=`01`。`RBatteryData` 的盒字段在处理器中被单独检查；日志文本是“没有充电仓数据”，但该哨兵值与 byte9 的精确对应受 Dart Smi 表示影响，标为推断。左、右低 7 位是否就是百分比仍需实机确认。

## 设置命令

### CERAMICS-X

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右、盒三槽 | — | `getBattery`，回包 byte5=`01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:111`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeWind`、`setNoiseModeNormal`、`setNoiseModeTrans` | `00 2C 01 00 01 VV` | ANC=`01`、抗风=`00`、关闭=`03`、通透=`02` | — | `getAnc`，回包 byte5=`0C` | 静态 | 降噪条（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:129`）；字符串分派（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:3018`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:544`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `00 20 01 00 02 00 VV` | POP=`01`、HIFI=`00`、ROCK=`02` | — | `getEq`，回包 byte5=`04` | 静态 | EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:160`） |
| 面板容器 | — | — | 内含游戏/多点/音频/触控/语言 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:196`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00 25 01 00 01 01` / `00 25 01 00 01 00` | 开=`01`、关=`00` | — | `getGameMode`，回包 byte5=`08` | 静态 | 游戏面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:205`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `00 2B 01 00 01 00` / `00 2B 01 00 01 02` | 开=`00`、关=`02`（关即 AAC/SBC） | — | `getAudioLdac`，回包 byte5=`0B` | 推断 | 多点面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:218`）；委托（`rose_reverse/analysis/ceramics_f8_protocol.md`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioMulDevice` | `00 2B 01 00 01 02` / `… 01` / `… 00` | AAC/SBC=`02`、LDAC=`01`、多点=`00` | — | `getAudioLdac`，回包 byte5=`0B` | 静态 | 音频面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:243`）；setAudioProtocol（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_audio_panel_item.dart:2263`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `00 22 01 00 03 SS 01 0A` | `SS`：左单/右单=`01`/`02`、左双/右双=`03`/`04`、左三/右三=`05`/`06`、左长/右长=`07`/`08`；动作码 0、2、3、4、5、6、7、9 | — | `getKey`，回包 byte5=`05` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:263`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `00 33 01 00 01 01` / `00 33 01 00 01 00` | 开=`01`、关=`00` | — | `getTouch`，回包 byte5=`16` | 推断 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1067`） |
| 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish` | `00 29 01 00 01 00` / `… 01` | 中文=`00`、英文=`01` | — | `getEarLanguage`，回包 byte5=`0A` | 推断 | 语言项（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:272`） |
| 星芒业务入口 | 范围外 | — | 云端业务 | — | — | 静态 | 入口（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:331`） |
| OTA | 范围外 | — | `wq`，`7033/2001/2002` | — | — | 静态 | OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:371`） |

`RDeviceComponentNoiseBarWidgetModel` 的 ANC/通透等级显示 flag 在 X 与 7034 上都是 false（等级/自适应 flag 结论（`rose_reverse/analysis/legacy_capabilities.md:58`））；因此两个型号都不列“降噪等级”设置行。接收 Tag `11`/`12` 仍能解析，见“接收字段”；协议可解析不等于界面开放等级控件。

### CERAMICS-X-7034

设置命令与 CERAMICS-X 相同：同一 `F8Sender`、同一组控件、同一初始化目标，差异只在连接配置。7034 的 `DeviceMulDevicePanelItemWidgetModel` 构造调用缺少 `needResetValueWhenCancel` 命名参数（X 有、7034 没有）；这影响取消/回退逻辑，不改变线上字节。`DeviceAudioPanelItemWidgetModel` 的命名参数与 X 相同。实现时不要因为字节相同就把两个分支合并成一个 transport 配置。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右、盒三槽 | — | `getBattery`，回包 byte5=`01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:111`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeWind`、`setNoiseModeNormal`、`setNoiseModeTrans` | `00 2C 01 00 01 VV` | ANC=`01`、抗风=`00`、关闭=`03`、通透=`02` | — | `getAnc`，回包 byte5=`0C` | 静态 | 降噪条（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:129`）；字符串分派（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:3018`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `00 20 01 00 02 00 VV` | POP=`01`、HIFI=`00`、ROCK=`02` | — | `getEq`，回包 byte5=`04` | 静态 | EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:160`） |
| 面板容器 | — | — | 内含游戏/多点/音频/触控/语言 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:196`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00 25 01 00 01 01` / `00 25 01 00 01 00` | 开=`01`、关=`00` | — | `getGameMode`，回包 byte5=`08` | 静态 | 游戏面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:205`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `00 2B 01 00 01 00` / `00 2B 01 00 01 02` | 开=`00`、关=`02`（关即 AAC/SBC） | — | `getAudioLdac`，回包 byte5=`0B` | 推断 | 多点面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:218`）；参数差异（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:238`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioMulDevice` | `00 2B 01 00 01 02` / `… 01` / `… 00` | AAC/SBC=`02`、LDAC=`01`、多点=`00` | — | `getAudioLdac`，回包 byte5=`0B` | 静态 | 音频面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:241`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `00 22 01 00 03 SS 01 0A` | 左单/右单=`01`/`02`、左双/右双=`03`/`04`、左三/右三=`05`/`06`、左长/右长=`07`/`08`；动作码 0、2、3、4、5、6、7、9 | — | `getKey`，回包 byte5=`05` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:261`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `00 33 01 00 01 01` / `00 33 01 00 01 00` | 开=`01`、关=`00` | — | `getTouch`，回包 byte5=`16` | 推断 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1067`） |
| 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish` | `00 29 01 00 01 00` / `… 01` | 中文=`00`、英文=`01` | — | `getEarLanguage`，回包 byte5=`0A` | 推断 | 语言项（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:270`） |
| 星芒业务入口 | 范围外 | — | 云端业务 | — | — | 静态 | 入口（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:329`） |
| OTA | 范围外 | — | `wq`，`7033/2001/2002` | — | — | 静态 | OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:369`） |

## 接收字段

CERAMICS-X 与 7034 都使用 `CeramicsXReceiveHandler`（cid 429）。除电量外，其处理与 AGATE/CERAMICS 的 `AgateReceiveHandler` 不同：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `01` | 电量 | 左、右、盒三槽；byte9 特定值表示“没有充电仓数据” | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:818`） |
| `02` | 固件版本 | byte9–10 按 hex 解析后转字符串 | 静态 | rs_common_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:10`） |
| `04` | EQ 当前值 | 线值 `0`→控制器 `2`；`1`→`0`；`2`→`4`；其他不更新 | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:634`） |
| `05` | 按键绑定 | 处理器不用 F8Receiver 解析出的 6 个值，而是按字符索引 `[19,31,43,55,25,37,49,61]` 从整帧取 8 个动作（byte9/15/21/27/12/18/24/30 的低 nibble） | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:385`） |
| `08` | 游戏模式 | byte7 精确等于 `"01"` 为开 | 静态 | headfree_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:635`） |
| `09` | 佩戴检测 | 由 `EarfreeI5ReceiveHandler` 实现；本型号没有佩戴面板 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:607`） |
| `0A` | 提示音语言 | 由 `RsCommonV2ReceiveHandler` 实现 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:13798`） |
| `0B` | 音频模式 | 原始 `0`→控制器 `7`（多点）、`1`→`1`（LDAC）、`2`→`0`（AAC/SBC） | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:308`） |
| `0C` | 降噪模式 | 原始 `0`→控制器 `4`、`1`→`6`、`2`→`2`、`6`→`0`；其他记录警告 | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:464`） |
| `11` / `12` | ANC / 通透等级 | 原始 `0`→`10`、`1`→`6`、`2`→`2`；其他记录警告。处理器可解析，但 X/7034 的等级显示 flag 为 false，界面未开放等级控件 | 静态 | ceramics_x_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart:10`） |
| `13` | 提示音音量 | 由 `EarfreeI5ReceiveHandler` 实现；本型号没有提示音面板 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:522`） |
| `16` | 触控开关 | 处理器落到统一 thrower；回包被连接层跳过 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:576`） |

`05` 的 8 个索引要求约 31 字节的帧，比 AGATE 的 6 值解析长得多；设备按键回包实际长度未知。语言有对应面板与回包处理器；佩戴、提示音虽有处理器，但 X 没有对应面板，不能据此增加型号能力。`0C` 的原始值到控制器值（`0`→`4`、`1`→`6`、`2`→`2`、`6`→`0`）与 `setNoiseMode` 的本地状态赋值（anc→`6`、wind→`4`、normal→`0`、trans→`2`）一致。

## 未实现与未知

- **降噪模式设备端语义**：App 侧分派已闭合（`setNoiseMode @0x75770c` 按字符串 `anc`/`wind`/`normal`/`trans` 调用 `RCommander.setNoiseModeAnc/Wind/Normal/Trans`，见 分派（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:3018`）），但设备是否把线值 `00`/`01`/`02`/`03` 解释为对应模式未知。
- **降噪等级映射**：接收原始值 `10/6/2` 与界面 `1/3/5` 的对应未闭合；等级在 X/7034 上没有开放控件，只有接收解析；第三方实现应保留原始值。
- **按键 8 值结构**：处理器按字符索引读取 8 个动作，但设备回包长度、填充和缺失值行为未知；F8Receiver 自身的 6 值解析对 X 不适用。
- **触控开关**：回调是 thrower，`getTouch` 回包不会更新状态。
- **多点面板参数差异**：X 与 7034 的 `DeviceMulDevicePanelItemWidgetModel` 命名参数不同（7034 缺少 `needResetValueWhenCancel`），取消/回退逻辑不能共用一套实现；音频面板参数相同，线上字节也相同。
- **SPP 实际使用**：7034 的 `sppSupport="true"` 只是配置；平台、全局偏好和音频 Profile 都可能让它仍走 BLE。
- **无实机**：没有固件版本、广播、通知或写入抓包；所有设备行为未知。

查证范围：两个配置类、`f8_sender.dart`、`f8_receiver.dart`、`CeramicsXReceiveHandler`、`analysis/i7_dispatch/table.tsv` 及 `analysis/ceramics_f8_protocol.md` / `ceramics_transport.md`；没有实机记录可闭合设备行为。

## OTA

CERAMICS-X 与 CERAMICS-X-7034 的 `otaType` 都是 `wq`，调用 `normalBleOtaServiceConfig`（X 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:549`）、7034 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:547`））。OTA 面板没有写 `sinceVersion`，因此没有固件门槛（X OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:371`）、7034 OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:369`））。两者与 [WQ 方案](../ota/wq.md) 的 F8 组没有差异：通道与 UUID、字节格式、传输窗口与状态机见该文档；OTA Service/写/读与路由 UUID 已列在“传输”表。OpenRose 不实现 OTA，此节只作记录。
