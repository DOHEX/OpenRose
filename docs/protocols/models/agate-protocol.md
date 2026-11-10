# AGATE / AGATE X 控制协议

AGATE 与 AGATE X 分别使用 `AgateDeviceConfig` 和 `AgateXDeviceConfig`，都属于 [F8 协议族](../families/f8.md)。分析基线为 RoseLink 3.8.0（versionCode 5310），APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`；APK 归档证据见 REA 检查（`rose_reverse/analysis/rea-apk-inspection.json`），Evidence ID `ev_cf7262dd02af6ee04bf97c9934c76316bead5da966f22f1761221cf57423be3a`，协议语义来自反汇编与分派表，不来自归档检查。两款型号都没有实机连接、广播抓包或通知抓包；以下结论全部是静态分析，型号差异在 App 配置层，设备端行为未验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| AGATE | `18840505` | `agate` | AGATE | 静态 | agate_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:10`） |
| AGATE X | `18840506` | `agatex` | AGATE X | 静态 | agate_x_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:10`） |

系统设备名里可能出现的零售名称是 `AGATE` 和 `AGATE X`；内部 name 分别是 `agate`、`agatex`。没有发现已知旧名。AGATE X 构造器另有 CMIIT 型号核准代码 `25J51R2V3265`，它不是设备名，也不进入 F8 帧（agate_x_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:20`））。识别只使用规范化 hex 前缀；AGATE 与 AGATE X 仅最后一位 `05`/`06` 不同（注册表（`rose_reverse/analysis/model_registry.json`））。

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

两款都没有覆写控制 GATT，`sppSupport` 都是字符串 `"false"`，也没有 SPP UUID，控制通道静态上只走 BLE（AGATE 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:404`）、AGATE X 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:382`））。各默认项的具体取值见 [F8 默认传输](../families/f8.md#默认传输)。

## 初始化

| 型号 | 配置 cid | 分派表索引 | 目标 |
|---|---:|---:|---|
| AGATE | 546 | 60124 | `0x8e18b0` `CeramicsDeviceConfig::sendInitCommands` |
| AGATE X | 545 | 60123 | `0x8e18b0` `CeramicsDeviceConfig::sendInitCommands` |

分派表两项目标相同，见 AGATE 行（`rose_reverse/analysis/i7_dispatch/table.tsv:60125`）、AGATE X 行（`rose_reverse/analysis/i7_dispatch/table.tsv:60124`）。`0x8e18b0` 依次发送 11 条查询（ceramics_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:380`））：

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

AGATE X 没有游戏面板，但仍会发送 `getGameMode`；查询存在不等于界面提供该功能。实际执行还受 `GlobalController` 的连接类型/USB 早退条件影响（global_controller.dart（`rose_reverse/analysis/blutter/asm/roselink/lib/global_controller.dart:2134`））。初始化查询的回包处理见“接收字段”：11 条中只有 6 类有非抛异常处理器。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左、右；没有充电盒槽 | 静态 | AGATE 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:138`）；AGATE X 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:143`） |
| 回包字段 | byte7 左、byte8 右、byte9 第三路；bit7 为充电位，低 7 位为原始电量 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1639`） |
| 本型号消费方式 | 只写左、右两个字符串与充电布尔；box 槽被忽略 | 静态 | budsfree_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/budsfree_receive_handler.dart:10`） |

电量查询是 `00 27 01 00 01 01`，回包 byte5=`01`。`F8Receiver` 把 byte9 也解析进 `RBatteryData`，但 AGATE/AGATE X 的处理器只使用左、右两个槽，因此 App 不显示盒电量。低 7 位是否就是 0–100 百分比需要实机确认。

## 设置命令

### AGATE

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右两槽 | — | `getBattery`，回包 byte5=`01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:163`） |
| EQ 预设 | `setEqPop`、`setEqHifi` | `00 20 01 00 02 00 VV` | Rock=`01`（模型值 0 → `setEqPop`）、HIFI=`00`（模型值 1 → `setEqHifi`） | — | `getEq`，回包 byte5=`04` | 静态 | EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:179`）；分派（`rose_reverse/analysis/agate_models_findings.md`） |
| 面板容器 | — | — | 容器，内含 EQ/游戏/触控 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:215`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00 25 01 00 01 01` / `00 25 01 00 01 00` | 开=`01`、关=`00` | — | `getGameMode`，回包 byte5=`08` | 静态 | 游戏面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:224`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1951`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchLong` | `00 22 01 00 03 SS 01 0A` | 左单/右单=`01`/`02`、左双/右双=`03`/`04`、左长/右长=`07`/`08`；动作码 0、2、3、4、5、6、7、9 | — | `getKey`，回包 byte5=`05` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:361`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1224`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `00 33 01 00 01 01` / `00 33 01 00 01 00` | 开=`01`、关=`00` | — | `getTouch`，回包 byte5=`16` | 推断 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1067`） |

AGATE 的触控动作列表有 8 项，包含 `touch-anc-control(9)`。这只能说明配置提供该动作选项，不能证明硬件有 ANC 模式；AGATE 没有 ANC 面板。

### AGATE X

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左、右两槽 | — | `getBattery`，回包 byte5=`01` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:168`） |
| EQ 预设 | `setEqPop`、`setEqHifi` | `00 20 01 00 02 00 VV` | Rock=`01`（模型值 0 → `setEqPop`）、POP=`00`（模型值 1 → `setEqHifi`） | — | `getEq`，回包 byte5=`04` | 静态 | EQ 列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:184`）；分派（`rose_reverse/analysis/agate_models_findings.md`） |
| 面板容器 | — | — | 容器，内含 EQ/触控 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:220`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchLong` | `00 22 01 00 03 SS 01 0A` | 左右单/双/长同上；动作码 0、2、3、4、5、6、7（无 ANC control） | — | `getKey`，回包 byte5=`05` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:345`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `00 33 01 00 01 01` / `00 33 01 00 01 00` | 开=`01`、关=`00` | — | `getTouch`，回包 byte5=`16` | 推断 | Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/f8_sender.dart:1067`） |

AGATE X 没有 `DeviceGamePanelItemWidgetModel`，因此没有用户可见的游戏模式开关；初始化仍会查询，回包仍会写入共享状态。两款配置的 Sender 相同，同一方法产生相同字节。

## 接收字段

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `01` | 电量 | byte7 左、byte8 右；bit7 充电；盒字段被忽略 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1639`） |
| `02` | 固件版本 | byte9–10 的 4 个 hex 字符按 radix 16 解析成整数，再转字符串 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1524`） |
| `04` | EQ 当前值 | 线值 `0`→控制器 `2`；`1`、`2`→控制器 `0`；其他值不更新 | 静态 | agate_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/f8/agate_receive_handler.dart:10`） |
| `05` | 按键绑定 | 6 个低 nibble，顺序为 byte7、9、11、8、10、12；写入左右触控列表 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1271`） |
| `08` | 游戏模式 | byte7 精确等于 `"01"` 为开，其他为关 | 静态 | f8_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/f8_receiver.dart:1173`） |
| `0B` | 音频/连接模式 | byte7 按十进制解析后写入 `audioMode` | 静态 | earfree_i5_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:278`） |
| `09`、`0A`、`0C`、`11`、`12`、`13`、`16` | 佩戴、语言、ANC、等级、提示音、触控开关 | 处理器落到统一 thrower；回包被连接层当作解析异常跳过，不更新状态 | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:524`）；接收分析（`rose_reverse/analysis/agate_receive_findings.md`） |

`F8Receiver` 接受 byte1 为 `27` 或 `28`，两者没有分支差异。按键回包的 6 个值到左右/手势槽位的最终顺序是从发送模板推断的，未用回包验证。

## 未实现与未知

- **EQ 标签与线值**：静态调用路径显示 AGATE 的 `Rock(0)` 发 `01`、`HIFI(1)` 发 `00`；AGATE X 的 `Rock(0)` 发 `01`、`POP(1)` 发 `00`。设备是否把这些线值解释为标签所示预设未知；不要按标签名直接映射。两款配置的 EQ 列表都只有 2 项（AGATE 列表（`rose_reverse/analysis/blutter/pp.txt:8841`）、AGATE X 列表（`rose_reverse/analysis/blutter/pp.txt:8829`）），共享 `F8Sender.setEqRock` 不会被配置选中。
- **游戏模式在 AGATE X 上的实际能力**：没有 UI 入口，但初始化查询和回包处理存在；不能判断固件是否支持，也不能判断用户是否可控制。
- **触控动作 9（ANC control）**：AGATE 配置提供该选项，但 AGATE 没有 ANC 面板；硬件是否响应未知。
- **触控开关回包**：AGATE/AGATE X 的 `touchSwitchReceived` 是 thrower；`getTouch` 回包会被跳过。触控开关控件是否真的出现在界面上也未逐项核对。
- **`0x27`/`0x28` 语义**：接收端不区分，可能是查询回复/主动通知或成功/失败；未知。
- **`LEN` 编码宽度**：单字节 byte4 还是 byte3–4 的 16 位大端，静态无法区分；所有已见 LEN≤3。
- **按键 nibble 语义**：6 个位置已知，但“哪一侧/哪个手势”的最终映射未经回包验证。
- **固件版本格式**：F8 把 byte9–10 解析为 hex 整数再转十进制字符串；UI 如何分段/补零未知。
- **写入方式与 MTU**：write-with/without-response、MTU、100 ms 间隔是否满足设备，均未验证。
- **CMIIT getter**：AGATE X 的配置字符串与 UI 行支持 CMIIT ID 语义，但 getter 方法体被 AOT 去重，未从类转储直接读出。
- **无实机**：没有固件版本、广播、通知或写入抓包；上述所有设备行为都是未知。

查证范围：本型号的两个配置类、`f8_sender.dart`、`f8_receiver.dart`、`AgateReceiveHandler`、`analysis/i7_dispatch/table.tsv` 及 `analysis/` 下的 AGATE 发送/接收笔记；没有实机记录可闭合设备行为。

## OTA

AGATE 与 AGATE X 的 `otaType` 都是 `none`：没有 OTA 路由，也没有固件门槛。`customConfigEarObject` 没有调用 `normalBleOtaServiceConfig`，也没有 OTA UUID（AGATE 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:404`）、AGATE X 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:382`））。OpenRose 不实现 OTA；此处仅记录 App 配置中没有可用的 OTA 路由。
