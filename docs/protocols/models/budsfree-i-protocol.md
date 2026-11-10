# BudsFree i 控制协议

BudsFree i 使用配置类 `BudsfreeDeviceConfig`（cid 540），属于 [RS Common 协议族](../families/rs-common.md)。分析基线为 RoseLink 3.8.0（versionCode 5310，APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）。BudsFree i 没有实机连接、广播抓包或通知抓包记录；以下结论全部来自静态分析与反汇编，设备端行为未验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `12cc3e4c` | `budsfree` | `BudsFree i` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:23`） |

系统设备名中的零售名称为 `BudsFree i`，内部名（`earObjectName`）为 `budsfree`，枚举为 `BUDSFREE`（序号 17）。未发现已知旧名。配置类另包含 CMIIT 设备型号核准代码 `24J51R2VC019`（budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:20`）），仅作为硬件核准标识展示，不参与广播识别或控制协议帧构造。广播识别使用规范化后的 4 字节十六进制前缀 `12cc3e4c`，在统一扫描器中按最长前缀规则匹配（注册表（`rose_reverse/analysis/model_registry.json:500`））。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认（否；`sppSupport="false"`） | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:413`） |
| 控制 SPP UUID | 同族默认（未配置） | 静态 | 同上 |
| 广播中的经典地址 | `rawData.substring(16, 28)`（要求长度 ≥27） | 静态 | BaseDeviceConfig.getInfoFromRawData（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:277`） |
| 写入间隔 | 同族默认 | 同族 | 同族 |

BudsFree i 不支持 SPP 控制通道（`sppSupport="false"`），未配置控制 SPP UUID，控制通道仅走 BLE（budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:413`））。各默认项的具体 UUID 见 [RS Common 默认传输](../families/rs-common.md#默认传输)。

广播字段解析存在型号特例：通用 RS Common 设备要求广播 `rawData` 长度 ≥22 并提取字符 `[10, 22)` 作为经典蓝牙 MAC，而 BudsFree i 在 `BaseDeviceConfig.getInfoFromRawData` 中有独立判断分支，显式要求 `rawData` 长度 ≥27（0x1b），并截取字符 `[16, 28)`，调用 `to2()` 格式化为用冒号分隔的 6 字节 MAC 字符串（赋值给 `bleAddress` 与 `btAddress`）；长度不足 27 时输出警告日志 `[rs_common] BUDSFREE rawData 解析错误` 并放弃解析（base_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:277`））。

## 初始化

| 型号 | 配置 cid | 分派表索引 | 目标 |
|---|---:|---:|---|
| BudsFree i | 540 | 60118 | `0x8e1f00` `BudsfreeDeviceConfig::sendInitCommands` |

分派表目标见 table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60119`）。连接成功后，`0x8e1f00`（budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:431`））依次执行两条查询命令：

```text
1. getInfo():
   ff 00 0f fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 33 aa
2. 追加自定义查询帧:
   ff 05 08 fa 2a 09 0c 0e 07 01 2b aa
```

### 追加帧与 SS=05 语义解析

第 2 条命令由 `BudsfreeDeviceConfig::sendInitCommands` 直接分配长度为 12 的整型列表并调用 `_sendTransformed` 发送（budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:465`））：

1. **帧格式与序号槽位**：RS Common 帧结构为 `FF SS LL BODY AA`。全族所有常规命令与 OpenFree 的追加初始化帧中，序号字节 `SS` 均为 `00`（RS Common 未启用类似 V2 的累加计数器，`transform` 直接原样返回输入列表）。BudsFree i 在第 2 帧将第 2 字节（偏移 1）硬编码写入 Smi `10`（解码为 `0x05`），是全族唯一见到的非零序号字节。
2. **接收端与设备端影响**：`RsCommonReceiver.process` 解析回包时直接截取字符 `6, 8)` 作为功能 Tag，完全不校验、不读取、也不匹配序号字节（[rs_common_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:337`））。固件端是否要求特定序号未知（推断该帧可能源自开发调试期间捕获的第 5 条命令日志或测试脚本硬编码）。
3. **BODY 查询内容**：BODY 为 `FA 2A 09 0C 0E 07 01 2B`，共 8 字节，由查询指令 `FA` 紧跟 7 个 ID 构成：
   - `2A`：EQ 预设模式
   - `09`：降噪（ANC）模式
   - `0C`：电量数据
   - `0E`：游戏模式
   - `07`：触控开关状态
   - `01`：触控按键映射
   - `2B`：音频编码模式
   这 7 个 ID 严格对应 BudsFree i 在 App 界面所启用的 7 项控制能力，剔除了 `getInfo` 中包含但本型号不支持的 `08`（佩戴检测）、`12`、`2C`（ANC 调节深度）、`2D`（通透等级）、`2E`（提示音量）、`33`（空间音频）。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左耳、右耳；无充电盒槽 | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:105`）；objs.txt List<BatteryType>(2)（`rose_reverse/analysis/blutter/objs.txt:35927`） |
| 回包字段 | `Tag=0C`；字符 `8,10)` 左耳、`[10,12)` 右耳、`[12,14)` 盒；各字节最高位为充电标志，低 7 位为电量百分比 | 静态 | [RsCommonReceiver._handleBatteryData（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`） |
| 本型号消费方式 | 只消费左耳、右耳电量与充电状态；盒电量字段被忽略不显示 | 静态 | BudsfreeReceiveHandler.batteryDataReceived（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/budsfree_receive_handler.dart:10`） |

`BudsfreeDeviceConfig` 在电量展示控件 `RDeviceComponentDisplayBarWidgetModel` 中仅配置了 `BatteryType.left` 与 `BatteryType.right` 两个槽位（budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:105`））。`BudsfreeReceiveHandler` 继承 `RsCommonReceiveHandler` 且仅重写了 `batteryDataReceived` 方法：解析 `RBatteryData.field_7`（左）和 `field_f`（右），有效范围 1–100 显示为 `"N%"`，无效显示 `"-"`，分别写入控制器左耳字符串（`field_1f`）与右耳字符串（`field_23`），并将充电布尔写入 `field_37[0]` 与 `field_37[1]`；充电盒电量 `field_17` 与充电位 `field_27` 被显式忽略（budsfree_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/budsfree_receive_handler.dart:10`））。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | `getInfo` | `0C` | 左/右 | — | Tag `0C` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:101`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans` | `12 02`、`12 04`、`12 06` | 降噪=`12 02`、普通=`12 04`、通透=`12 06` | — | Tag `09` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:122`）、rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:477`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2A 01`、`2A 00`、`2A 02` | POP=`2A 01`、HIFI=`2A 00`、ROCK=`2A 02` | — | Tag `2A` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:153`）、rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:588`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0E 01`、`0E 00` | 开启=`0E 01`、关闭=`0E 00` | — | Tag `0E` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:196`）、rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:441`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac` | `2B 00`、`2B 01` | AAC/SBC=`2B 00`、LDAC=`2B 01` | — | Tag `2B` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:221`）、rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:315`） |
| 触摸按键映射 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `01 <slot> 0<action>` | slot: 左单=`00`, 左双=`01`, 左三=`02`, 右单=`03`, 右双=`04`, 右三=`05`; action: 无功能=`00`, 播放/暂停=`01`, 上一曲=`02`, 下一曲=`03`, 音量加=`05`, 音量减=`06` | — | Tag `01` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:351`）、rs_common_sender_commands.json（`rose_reverse/analysis/rs_common_sender_commands.json:775`） |
| 触控开关 | `setTouchOn`、`setTouchOff` | `0E 00`、`0E 02` | 开启=`0E 00`、关闭=`0E 02` | — | Tag `07` | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:368`）、rs_common_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:232`） |
| 面板容器 | — | — | 容器 | — | — | 静态 | budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:187`） |

各控件参数配置细节说明：
1. **降噪模式**：`RDeviceComponentNoiseBarWidgetModel` 配置的 `NoiseType` 列表长度为 3（`[anc, normal, trans]`，出处 `[pp+0xb388]`），不包含抗风噪 `wind`；降噪深度调节开关（`field_13`、`field_17`、`field_1b`、`field_1f`）全部传入 `false`，不提供降噪或通透等级调节（`setAncLevel`/`setTransLevel`）。
2. **EQ 预设**：`DeviceEqPanelItemWidgetModel` 配置的预设列表长度为 3（`POP`=`00`, `HIFI`=`01`, `ROCK`=`02`，出处 `[pp+0xb350]`），未配置自定义 EQ（`customSinceVersion` 未传，即 `hasCustom=false`）。
3. **音频模式**：`DeviceAudioPanelItemWidgetModel` 配置的 `DeviceAudioType` 列表长度为 2（`[aacSbc, ldac]`，出处 `[pp+0xad50]`），不支持 LHDC、LC3 或双设备多点连接（`field_17=false`）。
4. **触控按键**：`RTouchInfoModel` 采用 `typeOTT` 模板（出处 `[pp+0xb558]`），槽位 `TouchCtlType` 列表为 `[one, two, three]`（单/双/三击），不提供长按 `long`；动作选项 `IntValueModel` 包含 6 项（`touch-none`=`0`, `touch-play-pause`=`1`, `touch-pre-music`=`2`, `touch-next-music`=`3`, `touch-up-volume`=`5`, `touch-down-volume`=`6`，出处 `[pp+0xb5f0]`），不包含语音助手（4）与降噪切换（7）。

## 接收字段

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0c` | 电量 | 仅更新左、右两耳电量与充电标志，忽略充电盒字段 | 静态 | BudsfreeReceiveHandler.batteryDataReceived（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/budsfree_receive_handler.dart:10`） |
| `01`、`07`、`09`、`0d`、`0e`、`2a`、`2b` | 按键映射、触控开关、降噪模式、固件版本、游戏模式、EQ 预设、音频模式 | 同族文档（`RsCommonReceiveHandler` / `F8Receiver` 默认处理；其中 `2b` 经 GDT 分派至共享音频模式更新） | 静态 | [族文档](../families/rs-common.md#全族共用)、table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:725`） |
| `08`、`2c`、`2d`、`2e`、`33` | 佩戴检测、降噪等级、通透等级、提示音等级、空间音频 | 本型号未配置对应控件，初始化未查询，设备上报行为未测试 | 未知 | RsCommonReceiver（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`） |

## 未实现与未知

1. **设备端实机响应未知**：本协议全部基于 RoseLink 3.8.0 逆向工程静态分析，没有 BudsFree i 实体耳机进行抓包验证。设备是否接受写入帧、是否按预期切换模式、以及状态回包的时序均为未知。已查阅：`budsfree_device_config.dart`、`budsfree_receive_handler.dart`、`rs_common_sender.dart`、`rs_common_receiver.dart`。
2. **初始化追加帧序号 SS=05 固件依赖未知**：`sendInitCommands` 中第 2 帧硬编码 `SS=05`，而 App 端接收处理完全忽略该序号。固件端是否要求此特定序号才能返回对应的 7 项数据未知。已查阅：`budsfree_device_config.dart:476`、`rs_common_receiver.dart:278`。
3. **触控长按手势（Long Tap）未配置**：虽然 `RsCommonSender` 存在 `setLeftTouchLong` 与 `setRightTouchLong` 编码，但 BudsFree i 配置中 `TouchCtlType` 仅声明单/双/三击，未开放长按手势配置。已查阅：`budsfree_device_config.dart:352`、`objs.txt:40275`。
4. **触控动作缺少语音助手与降噪切换**：动作候选列表仅有 6 项，不提供语音助手（4）与降噪模式切换（7）绑定。已查阅：`budsfree_device_config.dart:357`、`objs.txt:1027`。
5. **不支持多点连接与寻机**：未配置 `DeviceMulDevicePanelItemWidgetModel` 与 `RDeviceFindItemWidgetModel`。已查阅：`budsfree_device_config.dart`。
6. **不支持抗风噪与降噪深度调节**：`NoiseType` 仅开放 ANC、Normal、Trans，不支持 Wind；降噪与通透等级调节相关控件全部禁用。已查阅：`budsfree_device_config.dart:128`。
7. **充电盒电量不可见**：`BudsfreeReceiveHandler` 显式忽略充电盒电量与充电状态，App 仅能显示左右两只耳机的独立电量。已查阅：`budsfree_receive_handler.dart:10`。

## OTA

BudsFree i 的 `otaType` 为 `none`（model_registry.json（`rose_reverse/analysis/model_registry.json:500`））。`BudsfreeDeviceConfig` 未配置 `DeviceOtaPanelItemWidgetModel` 控件，在 `customConfigEarObject` 中未调用 `normalBleOtaServiceConfig`，未配置控制 SPP 或 OTA SPP UUID，`sppSupport="false"`（budsfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:413`））。

App 端对 BudsFree i 未开放任何 OTA 路由，无固件门槛。OpenRose 不实现 OTA，本节仅作记录。
