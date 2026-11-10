# CERAMICS 控制协议

CERAMICS 使用 `CeramicsDeviceConfig`，属于 [F8 协议族](../families/f8.md)。分析基线为 RoseLink 3.8.0（versionCode 5310），APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。没有实机连接、广播或通知抓包；以下结论全部是静态分析。CERAMICS-MK2、CERAMICS-U 等型号属于 RS Common V2 族，不在本文范围。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| CERAMICS | `18840100`、`18840103` | `ceramics` | CERAMICS | 静态 | ceramics_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:10`） |

系统设备名里可能出现的零售名称是 `CERAMICS`；内部 name 为 `ceramics`。没有发现已知旧名。两个识别前缀走同一配置分支，规范化为 hex 后按 8 字符前缀匹配；它们不是 F8 帧的命令或型号字节（注册表（`rose_reverse/analysis/model_registry.json`））。

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

CERAMICS 没有覆写控制 GATT，`sppSupport` 是 `"false"`，也没有 SPP UUID，控制通道静态上只走 BLE（customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:344`））。各默认项的具体取值见 [F8 默认传输](../families/f8.md#默认传输)。

## 初始化

CERAMICS 的配置 cid 为 544，`sendInitCommands` 分派索引为 `4096 + 544 + 0xd8ba = 60122`，目标 `0x8e18b0` `CeramicsDeviceConfig::sendInitCommands`（table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60123`））。该方法就是本型号自己的覆写，依次发送 11 条查询（ceramics_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:380`））：

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
```

11 条全部是查询，没有设置写入。初始化是否真正执行仍受 `GlobalController` 的连接类型/USB 早退条件影响（global_controller.dart（`rose_reverse/analysis/blutter/asm/roselink/lib/global_controller.dart:2134`））。回包处理与 AGATE 相同（同一个 `AgateReceiveHandler`）：6 类有处理器，5 类落到 thrower。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左、右；没有充电盒槽 | 静态 | ceramics_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:101`） |
| 回包字段 | byte7 左、byte8 右、byte9 第三路；bit7 为充电位 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1639`） |
| 本型号消费方式 | 只写左、右两个字符串与充电布尔；box 槽被忽略 | 静态 | budsfree_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/budsfree_receive_handler.dart:10`） |

电量查询是 `00 27 01 00 01 01`，回包 byte5=`01`。CERAMICS 的处理器与 AGATE 相同，因此盒电量即使出现在 byte9 也不会显示。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右两槽 | — | `getBattery`，回包 byte5=`01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:101`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `00 20 01 00 02 00 VV` | POP=`01`、HIFI=`00`、ROCK=`02` | — | `getEq`，回包 byte5=`04` | 静态 | EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:119`）；分派（`rose_reverse/analysis/agate_models_findings.md`） |
| 面板容器 | — | — | 内含 EQ/游戏/触控 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:155`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00 25 01 00 01 01` / `00 25 01 00 01 00` | 开=`01`、关=`00` | — | `getGameMode`，回包 byte5=`08` | 静态 | 游戏面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:164`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchLong` | `00 22 01 00 03 SS 01 0A` | 左单/右单=`01`/`02`、左双/右双=`03`/`04`、左长/右长=`07`/`08`；动作码 0、2、3、4、5、6、7、9 | — | `getKey`，回包 byte5=`05` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:284`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `00 33 01 00 01 01` / `00 33 01 00 01 00` | 开=`01`、关=`00` | — | `getTouch`，回包 byte5=`16` | 推断 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1067`） |

CERAMICS 的 EQ 列表是全局 `POP(0)`、`HIFI(1)`、`ROCK(2)`，与 `DeviceEqIntent::setEq` 的 0/1/2 → Pop/Hifi/Rock 分派对齐，因此线值可以直接按标签理解。触控模型使用 `RTouchInfoModel.ceramicsXTouchList`（8 个动作）和 `typeOTL`（单/双/长）；共享 Sender 还有三击方法，但本型号没有 `three` 手势。动作列表包含 `touch-anc-control(9)`，但 CERAMICS 没有 ANC 面板，不能据此推断硬件 ANC 能力。

## 接收字段

CERAMICS 的接收处理器是 `AgateReceiveHandler`（cid 431，配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:85`）），与 AGATE、AGATE X 完全相同。逐 Tag 行为按 [AGATE 文档](agate-protocol.md#接收字段) 的“接收字段”表执行；本型号的具体差异只有：

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `01` | 电量 | 只有左、右两个显示槽；byte9 被解析但不显示 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1639`） |
| `04` | EQ 当前值 | 线值 `0`→控制器 `2`；`1`、`2`→控制器 `0`；POP 与 ROCK 在接收侧不可区分 | 静态 | agate_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/agate_receive_handler.dart:10`） |
| `05` | 按键绑定 | 6 个低 nibble，顺序 byte7、9、11、8、10、12 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1271`） |
| `08` | 游戏模式 | byte7 精确等于 `"01"` 为开 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1173`） |
| `09`、`0A`、`0C`、`11`、`12`、`13`、`16` | 佩戴、语言、ANC、等级、提示音、触控开关 | 落到统一 thrower，回包被连接层跳过 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:524`） |

## 未实现与未知

- **EQ 回读**：POP 与 ROCK 的线值 1、2 都写入同一个控制器值 0；UI 是否能重新高亮正确项未闭合。
- **触控动作 9（ANC control）**：配置提供该动作，但本型号没有 ANC 面板；硬件响应未知。
- **触控开关**：回调是 thrower，`getTouch` 回包不会更新状态；开关控件是否暴露也未核对。
- **与 AGATE 相同的未知**：`0x27`/`0x28` 语义、`LEN` 宽度、按键 nibble 语义、固件版本格式、写入方式与 MTU；本型号没有额外证据，见 [AGATE 文档](agate-protocol.md#未实现与未知)。
- **无实机**：没有固件版本、广播、通知或写入抓包；设备行为全部未知。

查证范围：`CeramicsDeviceConfig`、`f8_sender.dart`、`f8_receiver.dart`、`AgateReceiveHandler`、`analysis/i7_dispatch/table.tsv` 及 `analysis/ceramics_f8_protocol.md` 等 F8 笔记；没有实机记录可闭合设备行为。

## OTA

CERAMICS 的 `otaType` 是 `none`：没有 OTA 路由，也没有固件门槛。没有 OTA UUID，也没有调用 `normalBleOtaServiceConfig`（customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:344`））。OpenRose 不实现 OTA；此处仅记录 App 配置中没有可用的 OTA 路由。
