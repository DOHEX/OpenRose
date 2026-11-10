# OpenFeel 控制协议

配置类 `OpenfreeDeviceConfig`（cid 538），枚举 `OPENFREE`（序号 22），内部工程名 `OpenFree`。分析基线为 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），属于 [RS Common 协议族](../families/rs-common.md)（注意不是 V2 协议族的 OpenFeel MK2）。由于没有 OpenFree / OpenFeel 实机，以下结论全部基于 Flutter AOT 静态反编译分析，设备端行为与命令接受情况未在硬件上验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| OpenFeel (LDAC 分支) | `0b0a0a0b` | `OpenFree` | `OpenFeel` | 静态 | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:10`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:330`） |
| OpenFeel (LHDC 分支) | `0b0a0a0c` | `OpenFree` | `OpenFeel` | 静态 | 同上 |
| OpenFeel (双高清/全触控分支) | `0b0a0a0d` | `OpenFree` | `OpenFeel` | 静态 | 同上 |

零售名称为 `OpenFeel`，内部工程名为 `OpenFree`，CMIIT 型号核准代码为 `26J51U5AR748`（构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:10`））。未见其他零售别名。

### 分支差异与代码选择机制

App 在扫描广播中匹配规范化 hex 前缀（`0b0a0a0b`、`0b0a0a0c`、`0b0a0a0d`）。连接建立后，`OpenfreeDeviceConfig` 的 `homeWidgetModels`（音频面板构造）与 `touchInfo`（触控配置）通过读取当前连接设备的 `deviceIdentifier` 字符串的前 8 个字符（`substring(0, 8)`）来动态选择硬件分支的能力：

1. **音频编码分支差异**（homeWidgetModels（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:456`））：
   基础编码列表初始化为 `[aacSbc, lc3, mulDevice]`（对应模型值 `0`、`3`、`7`）。
   - 若前缀等于 `"0b0a0a0b"`：在索引 1 调用 `insert(1, ldac)`，最终可选编码为 `[aacSbc, ldac, lc3, mulDevice]`。
   - 若前缀等于 `"0b0a0a0c"`：在索引 1 调用 `insert(1, lhdc)`，最终可选编码为 `[aacSbc, lhdc, lc3, mulDevice]`。
   - 若前缀等于 `"0b0a0a0d"`：在索引 1 调用 `insertAll(1, [ldac, lhdc])`，最终可选编码为 `[aacSbc, ldac, lhdc, lc3, mulDevice]`。
   - 若前缀不属于上述三者（或标识为空/长度小于 8）：不执行插入，回退到基础列表 `[aacSbc, lc3, mulDevice]`。

2. **触控动作列表分支差异**（touchInfo（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:130`））：
   触控手势槽位全分支固定为 `typeOTT`（touch_info.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:39`）），即包含单击（`one`）、双击（`two`）、三击（`three`）3 个槽位，无长按（`long`）。
   - 若前缀精确等于 `"0b0a0a0d"`：使用 `RTouchInfoModel.rsCommonTouchList`，包含 6 项动作（`List<IntValueModel>(6)`）：无（`0`）、播放/暂停（`1`）、上一曲（`2`）、下一曲（`3`）、音量+（`4`）、音量-（`5`）。
   - 若前缀不等于 `"0b0a0a0d"`（包括 `"0b0a0a0b"`、`"0b0a0a0c"` 及异常回退）：使用 `List<IntValueModel>(5)`，仅包含 5 项动作（播放/暂停、上一曲、下一曲、音量+、音量-），缺少“无（0）”动作选项。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认（否；`sppSupport="false"`） | 静态 | openfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:347`） |
| 控制 SPP UUID | 同族默认（未配置） | 静态 | 同上 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |

`OpenfreeDeviceConfig.customConfigEarObject` 将 `sppSupport` 设置为字符串 `"false"`，未调用 `normalBleOtaServiceConfig`，未配置控制 SPP UUID 或 OTA SPP UUID，协议类型为 `"rs-common"`，耳型为 `"tws"`（customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:330`））。所有控制数据全部通过 BLE 传输，默认特征 UUID 详见 [RS Common 默认传输](../families/rs-common.md#默认传输)。

## 初始化

| 型号 | 配置 cid | 分派表索引 | 目标函数 |
|---|---:|---:|---|
| OpenFeel | 538 | 60116 | `0x8e20e0` `OpenfreeDeviceConfig::sendInitCommands` |

分派表项见 table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60117`）。`sendInitCommands`（openfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:366`））依次发送 2 条查询指令：

1. 共享批量查询 `getInfo`：
```text
FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA
```
BODY: `FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33`。依次查询 14 个功能 ID（按键、触控开关、电量、版本、游戏模式、EQ、音频模式等）。

2. 本型号专属额外查询 `FA 2B`：
```text
FF 00 02 FA 2B AA
```
BODY: `FA 2B`。在 `getInfo` 之后下发，专门用于刷新并确认耳机当前的音频编码及多点连接状态（对应 Tag `2B`）。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 左耳、右耳、充电盒三槽（`left`、`right`、`charge`） | 静态 | openfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:630`） |
| 回包字段 | byte7–8 左耳、byte9–10 右耳、byte11–12 盒；左右耳最高位（bit7）为入仓标志 | 静态 | rs_common_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1013`） |
| 本型号消费方式 | 沿用 `RsCommonReceiveHandler.batteryDataReceived`，左、右、盒三槽均写入控制器状态并在界面展示 | 静态 | rs_common_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:379`） |

电量由 `getInfo` 中的 `0C` 触发读回，回包 Tag 为 `0C`。`RDeviceComponentDisplayBarWidgetModel` 配置了 `left`, `right`, `charge` 三个槽位；`OpenfreeReceiveHandler` 未重写电量方法，直接沿用父类 `RsCommonReceiveHandler.batteryDataReceived`，App 界面展示左右耳与充电盒的电量。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 左耳、右耳、充电盒三槽 | — | `getInfo`（含 `0C`），回包 Tag `0C` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:630`） |
| EQ 预设 | `setEqPop`、`setEqHifi` | `2A 01` / `2A 00` | BASS=`2A 01`（模型值 0 → `setEqPop`）、BALANCE=`2A 00`（模型值 1 → `setEqHifi`） | — | `getInfo`（含 `2A`），回包 Tag `2A` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:659`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1996`） |
| 面板容器 | — | — | 容器，内含 EQ 预设控件 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:714`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0E 01` / `0E 00` | 开=`0E 01`、关=`0E 00` | — | `getInfo`（含 `0E`），回包 Tag `0E` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:723`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1738`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `2B 04` / `2B 00` | 开=`2B 04`、关=`2B 00`（关多点同时将音频模式切回 AAC/SBC） | — | `getInfo` 与 `FA 2B`，回包 Tag `2B` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:739`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:2211`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLc3`、`setAudioMulDevice` | `2B 00` / `2B 01` / `2B 02` / `2B 03` / `2B 04` | AAC/SBC=`2B 00`、LC3=`2B 03`、多设备=`2B 04`（全分支）；LDAC=`2B 01`（`0b0a0a0b`、`0b0a0a0d` 分支）；LHDC=`2B 02`（`0b0a0a0c`、`0b0a0a0d` 分支） | — | `getInfo` 与 `FA 2B`，回包 Tag `2B` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:776`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1612`） |
| 触控绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `01 SS 0A` | 槽位 `SS`：左单/双/三=`01`/`02`/`03`、右单/双/三=`11`/`12`/`13`（无长按）；动作 `0A`：无=`0`（仅 `0b0a0a0d` 分支）、播暂=`1`、上一曲=`2`、下一曲=`3`、音量+=`4`、音量-=`5`（全分支） | — | `getInfo`（含 `01`），回包 Tag `01` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:130`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1372`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `0E 00` / `0E 02` | 开=`0E 00`、关=`0E 02` | — | `getInfo`（含 `07`），回包 Tag `07` | 推断 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:803`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:879`） |

说明：
- OpenFree 没有配置降噪面板（`RDeviceComponentNoiseBarWidgetModel`），没有 ANC 相关控制。
- EQ 预设模型中仅配置两项：BASS（模型值 0）与 BALANCE（模型值 1）。在 UI 分派逻辑中，模型值 0 对应调用 `setEqPop`（BODY `2A 01`），模型值 1 对应调用 `setEqHifi`（BODY `2A 00`）。
- 触控绑定通过 `RsCommonSender` 的槽位方法生成，参数通过十进制数字拼接为 `01 SS 0A`。
- 所有控件构造均未设置固件版本门槛（`EarVersionCheck.checkPass` 未限制或为 null），所有命令在静态上对全版本生效。

## 接收字段

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `2B` | 音频模式与多点 | `OpenfreeReceiveHandler.audioModeReceived` 专属重写：线值 `0`→控制器 `0`（AAC/SBC）、`1`→`1`（LDAC）、`2`→`2`（LHDC）、`3`→`3`（LC3）、`4`→`7`（多设备 `mulDevice`）；其余越界值输出警告日志，不更新状态 | 静态 | openfree_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/openfree_receive_handler.dart:10`） |
| `2A` | EQ 预设当前值 | 沿用 `RsCommonReceiveHandler.eqDataReceived`：线值 `0`→控制器 `1`（BALANCE）、`1`→控制器 `0`（BASS）、`2`→控制器 `2`（ROCK，本型号未开放）；其余越界报警 | 静态 | rs_common_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:238`） |
| `0C` | 电量 | 沿用 `RsCommonReceiveHandler.batteryDataReceived`：byte7–8 左耳、byte9–10 右耳、byte11–12 盒；左右最高位为入仓标志；三槽均展示 | 静态 | rs_common_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/rs_common_receive_handler.dart:379`） |
| `01`、`07`、`0D`、`0E` | 按键绑定、触控开关、固件版本、游戏模式 | 沿用族文档默认处理逻辑，与 [RS Common 协议族文档](../families/rs-common.md) 一致 | 静态 | rs_common_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:278`） |
| `08`、`09`、`2C`、`2D`、`2E`、`33` | 佩戴、ANC、等级、空间音频 | 虽有族解析器处理，但本型号未配置对应 UI 控件，界面不展示对应状态 | 静态 | openfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:456`） |

## 未实现与未知

- **实机验证缺失**：未连接 OpenFree / OpenFeel 实机，所有帧格式、参数映射与硬件分支逻辑均来自 RoseLink 3.8.0 静态反编译分析；设备端是否真正接受这些设置指令及回包格式在实机测试前未知。
- **触控总开关在界面的具体呈现**：`setTouchOn`/`setTouchOff`（BODY `0E 00`/`0E 02`）在 `RsCommonSender` 中已证实存在并与族内规范一致，但在 OpenFree 触控页面上是否以独立 Switch 呈现还是内嵌在页面中，未在静态代码中穷尽视图树。
- **广播中的经典地址提取**：`BaseDeviceConfig.getInfoFromRawData` 的 rs_common 分支提取广播 rawData 字符 `10, 22)` 作为经典地址，真实耳机的广播包结构与有效性需实机验证。

## OTA

OpenFree 的 `otaType` 为默认值 `none`（未覆写）。`customConfigEarObject` 中未调用 `normalBleOtaServiceConfig`，未配置 OTA 服务或特征 UUID，`homeWidgetModels` 亦未挂载 `DeviceOtaPanelItemWidgetModel`（[配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:330`））。App 静态配置中 OpenFree 没有可用的 OTA 路由。OpenRose 不实现 OTA，本节仅作记录。
