# Jasmine 控制协议

配置类 `JasmineDeviceConfig`（cid 519，继承 `BaseDeviceConfig`，不是 `RsCommonV2DeviceConfig` 的子类），APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common V2 协议族](../families/rs-common-v2.md)。Sender 为 `RsCommonV2Sender`，Receiver 为 `RsCommonV2Receiver`，handler 为 `RsCommonV2ReceiveHandler`（cid 421）。没有 Jasmine 实机记录：以下均为 Flutter AOT 静态反汇编分析，未连接该型号真实耳机，未抓包，也没有执行 OTA；设备是否接受各设置未知。商品“茉莉”与 Jasmine 的对应关系仅由名称推测，尚未获得直接证据。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| 零售 TWS | `0b0a0a11` | `jasmine` | `Jasmine` | 静态 | 构造:23（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:23`）、定制:532（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:532`） |

零售名 `Jasmine`，内部名 `jasmine`，枚举定义为 `JASMINE`（序号 29，对象池（`rose_reverse/analysis/blutter/objs.txt:39875`））。识别使用规范化 manufacturer 数据的前 8 个十六进制字符 `0b0a0a11`，不按设备名匹配。配置写入 CMIIT ID 为 `25J5151GM751`（构造:20（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:20`））。商品“茉莉”与 Jasmine 的对应关系仅由英文直译推测，在 APK 提取资源与二进制字串中未发现“茉莉”直接关联证据，仍属未确认。

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
| OTA 通道 / UUID | 无 | 静态 | 定制:530（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:530`） |

同族默认的取值与证据见 [族文档的默认传输表](../families/rs-common-v2.md#默认传输)。Jasmine 在 `customConfigEarObject` 中写入控制 SPP UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`，`sppAllowed` 为 `true`，设备类型为 `tws`。配置中未写入任何 OTA SPP 或 BLE OTA UUID。控制通道选择、MTU、通知订阅与写完成队列规则（普通 100 ms，OHOS 350 ms）同族文档。

## 初始化

| 分派 | 表项 | 目标 | 发送的查询 | 状态 | 出处 |
|---|---|---|---|---|---|
| GDT+0xd8ba，cid 519 | 60097（`table.tsv` 第 60098 行） | `0x8e2444` | 仅 `getInfo` | 静态 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60098`）、补核（`rose_reverse/analysis/i7_dispatch_findings.md`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`） |

目标函数为共享闭包 `0x8e2444`，仅调用 `RCommander.getInfo`，不额外查询自定义 EQ 或其他扩展项。`getInfo` 发送 35 字节的完整查询帧：

```text
FF 00 1E FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 2F 31 32 33 36 37 38 39 3A 3B 3C 3D 3F 45 46 49 E9 AA
```

该帧包含 29 个功能 ID（涵盖电量 `0C`、游戏模式 `0E`、提示音 `2E`、寻机 `2F`、提示音语言 `31`、多点连接 `32` 等）。共享查询发送某功能 ID 不代表 Jasmine 硬件支持全部对应功能，详见族文档 [初始化与查询](../families/rs-common-v2.md#初始化与查询)。

## 电量

| 布局 | 槽 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| 仅左右耳 | `left`、`right` | 仅展示左右耳两槽，无充电仓槽；左右耳百分比与入仓状态规则同族 | 静态 | BatteryType:129（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:129`）、[电池与仓缓存](../families/rs-common-v2.md#电池与仓缓存) |

Jasmine 在 `RDeviceComponentDisplayBarWidgetModel` 中仅传入 `[BatteryType.left, BatteryType.right]`（[pp+0xb298]），不包含 `BatteryType.charge`。公共接收器的 `0C` 字段虽然固定解析 3 个字节（左耳、右耳、仓），但在 Jasmine 界面上仅展示左右耳电量，不显示仓电量。左右耳百分比（低 7 位，1..100）与入仓标志（bit7）处理逻辑同族文档。

## 设置命令

Jasmine 配置的每个控件各占一行。BODY 只写命令体，完整帧按 `FF SS LL BODY CC AA` 封装，初始序号为 0（构造:48（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:48`）），规则见族文档 [发送帧](../families/rs-common-v2.md#发送帧)。除实机生效未知外，命令体与配置参数均为静态事实。“读回”列的 `getInfo` 表示该 ID 在批量初始化查询中，最终状态以通知回包为准；`—` 表示无对应读回方法。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | 结构 | — | 左、右两槽 | — | `getInfo`（`0c`） | 静态 | 显示控件:127（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:127`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0e 01`、`0e 00` | 开=`01`、关=`00` | — | `getInfo`（`0e`） | 静态 | 控件:145（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:145`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `32 01`、`32 00` | 开=`01`、关=`00` | — | `getInfo`（`32`） | 静态 | 控件:158（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:158`）、[族命令](../families/rs-common-v2.md#设置命令) |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `2f 01`、`2f 02`、`2f 03`、`2f 04` | 左=`01`、右=`02`、双耳=`03`、停=`04` | — | `getInfo`（`2f`）；回包含 05/06 单耳停止 | 静态 | 控件:203（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:203`）、[族寻机](../families/rs-common-v2.md#寻机) |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `07 00`、`07 01` | 开=`00`、关=`01` | — | `getInfo`（`07`） | 静态 | 控件:212（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:212`）、setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） |
| 触控按键 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong` | `01 slot action` | 左耳 `01/02/03/04`、右耳 `11/12/13/14`；action 0 无、1 播放暂停、2 上一首、3 下一首、4 音量+、5 音量−、6 游戏、7 语音助手 | — | `getInfo`（`01`） | 静态 | 控件:212（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:212`）、动作列表:491（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:491`）、[族触摸动作](../families/rs-common-v2.md#触摸动作) |
| 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish` | `31 00`、`31 01` | 中文=`00`、英文=`01` | — | `getEarLanguage`（`fa 31`）/ `getInfo`（`31`） | 静态 | 控件:221（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:221`）、弹窗交互:348（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/devic_ear_lang_panel_item.dart:348`）、Sender:824（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:824`） |
| 提示音 | `setDingLevel` | `2e value` | 1..5=`01..05`（关闭选项 `00` 被显式禁用） | — | `getInfo`（`2e`） | 静态 | 控件:376（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:376`）、关闭禁用:392（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:392`）、setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） |
| 面板容器 | 结构 | — | 容器 | — | — | 静态 | 配置:194（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:194`） |
| 更多设置入口 | 结构 | — | 入口（更多设置页包含提示音设置） | — | — | 静态 | 配置:234（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:234`） |
| OTA | 范围外 | — | 见 [OTA](#ota) | — | — | 静态 | 配置:286（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:286`）、OTA控制器:3330（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3330`） |

Jasmine 配置的所有控件均无固件门槛限制（`—`）。

差异与细节说明：
- **提示音音量与关闭选项**：Jasmine 在配置 `DeviceDingLevelPanelItemWidgetModel` 时，将 `field_23` 显式设为 `false`（构造:392（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:392`）），且未设置 `field_27`。在 `DeviceDingLevelPanelItem` 中，`field_23=false` 直接使 `_canShowSupportClose` 判断分支短路返回 `false`（逻辑:825（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:825`）），因此界面不提供“关闭（`00`）”选项，仅提供 1..5 档（`01..05`），且 1..5 档不受任何固件版本门槛约束。弹窗打开时调用 `RCommander.getDingLevel()`（`0x8e1a74`），但 `RsCommonV2Sender` 并未实现单项查询方法，实际依赖 `getInfo`（含 `2E`）与通知回包同步。
- **提示音语言**：Jasmine 配置了独立语言选择控件 `RDeviceLangItemWidgetModel`（配置:221（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:221`））。点击后弹出 `DevicEarLangPanelItem`，选项包含中文（`ota-chinese`）与英文（`ota-english`）。选择中文时调用 `RCommander.setEarLanguageChinese()` 发送 `31 00`（Sender:865（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:865`）），选择英文时调用 `RCommander.setEarLanguageEnglish()` 发送 `31 01`（Sender:824（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:824`））。单项查询方法为 `RsCommonV2Sender.getEarLanguage`，发送 `FA 31`（Sender:904（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:904`）），同时 `getInfo` 亦包含 `31`。
- **触控动作列表**：Jasmine 配置了 4 种触摸手势（单击、双击、三击、长按），左右耳各 4 槽（左 `01..04`、右 `11..14`）。动作列表引用 `rsCommonV2TouchListWithoutAnc`（构造:469（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:469`）），包含 8 项动作（0 无、1 播放暂停、2 上一首、3 下一首、4 音量+、5 音量−、6 游戏模式、7 语音助手）。因本型号无 ANC 硬件，列表中未包含动作 8（降噪切换）。
- **页面结构布局**：主卡片列表 `field_2b` 依次放置电量条、第 1 分组面板（游戏模式、多点连接）、第 2 分组面板（寻机、触控设置、提示音语言、更多设置入口）、第 3 分组面板（OTA 面板）。提示音控件放置于“更多设置”列表 `field_2f` 中（构造:454（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:454`））。

## 接收字段

所有 Tag 的处理与解析取值遵循族文档 [字段表](../families/rs-common-v2.md#字段表)，Jasmine 使用共享 `RsCommonV2ReceiveHandler`（cid 421），没有型号专属的 ReceiveHandler 分支。

与同族其他型号的主要差异：
1. `0C` 电池：显示配置仅定义 `left` 和 `right`，界面不展示仓电量（见 [电量](#电量)）；
2. `31` 提示音语言：Jasmine 配置了语言选择控件，接收字段解析得到的 `earLanguage`（0 为中文，1 为英文）会直接更新到界面控制器（ReceiveHandler:5058（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/rs_common_v2_receive_handler.dart:5058`）），不同于未配置语言界面的型号（如 i7）；
3. 共享 receiver 支持的 `09`（降噪模式）、`2A`/`3E`（EQ）、`2B`（音频编码）、`08`（佩戴检测）等字段，Jasmine 均未配置相应界面控件，接收到对应报文时不触发 UI 响应。

## 未实现与未知

- 没有 Jasmine 实机记录：所有写入命令、设备回包、持久化行为、广播原文和 SPP 配对均未在真实耳机上验证；状态为静态的分析不能代替设备确认。
- 商品中文名映射未确认：商品“茉莉”与 Jasmine 的对应关系仅由英文直译推测，在 APK 资源（`assets/`）和二进制字串中未发现直接关联证据。
- 广播数据只有规范化规则的静态推导，没有抓取到真实广播报文；经典地址字段未做实测。
- `0C` 电池字段：尽管 App 界面仅配置展示左右耳电量，底层硬件在 `0C` 报文中是否上报第 3 字节（仓电量）以及数值有效性未知。
- 未配置的功能：ANC（`09`、`2C`、`2D`、`36`）、EQ 预设与自定义 EQ（`2A`、`3E`）、佩戴检测（`08`）、音频编码切换（`2B`）、增益与听力保护（`45`、`46`）等均未在 Jasmine 配置中启用。“App 未配置”为静态事实，不代表耳机硬件物理缺少相关芯片能力。
- 硬件升级能力：App 端配置为 `DeviceOtaType.none`，页面进入即退出，仅表明该版本客户端未提供升级入口，硬件是否具备底层固件升级能力未知。

## OTA

Jasmine 的 OTA 类型在配置中指定为 `none`（`DeviceOtaType.none`，枚举索引 5，对象池（`rose_reverse/analysis/blutter/objs.txt:39890`））。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `none` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:30`）、OTA面板:294（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:294`） |
| 门槛 | — | 静态 | 无门槛（不可用） |
| 控制器动作 | 直接返回 Null，不初始化 OTA 管理器 | 静态 | OTA控制器:3330（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3330`） |

用户在 App 中触发 OTA 页面时，`DeviceOtaController.enterPage`（`0xb45b30`）检查传入的 `otaType`，若为 `DeviceOtaType.none` 则直接退出返回 `Null`（控制器:3336（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3336`）），不会调用 `initOtaManager`，亦不加载任何固件下载或传输逻辑。OpenRose 不实现 OTA，本节仅作静态记录。
