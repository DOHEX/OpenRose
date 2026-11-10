# Cambrian 控制协议

Cambrian 使用 `CambrianDeviceConfig`（配置 cid 539），属于 [RS Common 协议族](../families/rs-common.md)。分析基线为 RoseLink 3.8.0（versionCode 5310），APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`；APK 归档证据见 REA 检查（`rose_reverse/analysis/rea-apk-inspection.json`），Evidence ID `ev_cf7262dd02af6ee04bf97c9934c76316bead5da966f22f1761221cf57423be3a`。该型号没有实机连接、广播抓包或通知抓包；本文结论全部来自静态反汇编分析，设备端实际行为未验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
|---|---|---|---|---|---|
| Cambrian | `0b0c0a0c` | `cambrian` | Cambrian | 静态 | cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:10`） |

系统设备名里出现的零售名称为 `Cambrian`，内部 name 为 `cambrian`，形态为头戴式（`ear_type: "head"`，cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:701`））。构造器中包含 CMIIT 型号核准代码 `25J5151GL088`（cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:20`））。识别仅使用规范化 hex 前缀 `0b0c0a0c`（注册表（`rose_reverse/analysis/model_registry.json`））。未发现已知旧名或额外硬件分支。

## 传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | `true`（允许） | 静态 | cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:704`） |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:706`） |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |

控制通道支持 BLE 与 SPP；默认项的具体取值见 [RS Common 默认传输](../families/rs-common.md#默认传输)。
OTA 通道在 `customConfigEarObject` 中单独配置：BLE OTA 服务 `ff12`、写特征 `ff15`、通知特征 `ff14`；SPP OTA UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`（cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:706`））。

## 初始化

| 型号 | 配置 cid | 分派表索引 | 目标 |
|---|---:|---:|---|
| Cambrian | 539 | 60117 | `0x8e2064` `CambrianDeviceConfig::sendInitCommands` |

分派表项见 table.tsv（`rose_reverse/analysis/i7_dispatch/table.tsv:60118`）。`sendInitCommands` 具有两阶段延迟初始化机制（cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:748`））：
1. 立即调用 `RCommander.getInfo()` 发送全族共享查询帧：
```text
FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA
```
2. 启动 500 ms 延迟定时器（`Duration(microseconds: 500000)`，对象池（`rose_reverse/analysis/blutter/objs.txt:40003`）），调用共享闭包 `0x8e2444`（sportfree_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:574`））再次下发一次相同的 `getInfo()` 查询帧。

## 电量

| 项目 | 内容 | 状态 | 出处 |
|---|---|---|---|
| 电量槽布局 | 单体（头戴式，复用仓槽展示） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:110`） |
| 回包字段 | byte8–9 为第 1 路原始电量；byte10–11 为第 2 路；byte12–13 为第 3 路 | 静态 | rs_common_receiver.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_receiver.dart:1033`） |
| 本型号消费方式 | 只消费 `list[0]`（byte8–9），写入控制器仓电量槽 `chargeBattery`，充电状态固定设为 `false`；同步调用 `localStorage.saveCaseBattery` 写入持久化缓存；数值不在 1–100% 范围时回退为 `"-"` 并从本地缓存读取 | 静态 | cambrian_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/cambrian_receive_handler.dart:140`） |

Cambrian 为单体头戴式耳机（`head`），App 在 `RDeviceComponentDisplayBarWidgetModel` 中仅配置了 `BatteryType.charge` 单槽（cambrian_device_config.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:110`））。`CambrianReceiveHandler.batteryDataReceived` 仅取第 1 路数据并将其格式化为百分比字符串写入仓电量展示位，忽略左耳、右耳槽位。

## 设置命令

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
|---|---|---|---|---|---|---|---|
| 电量展示 | — | — | 单电量（复用仓槽） | — | `getInfo`，回包 Tag `0C` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:107`） |
| 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind` | `12 02` / `12 04` / `12 06` / `12 08` | 降噪=`12 02`、关闭=`12 04`、通透=`12 06`、抗风=`12 08` | — | `getInfo`，回包 Tag `09`（`1`=降噪、`2`=关闭、`3`=通透、`4`=抗风） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:125`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:477`） |
| 通透等级 | `setTransLevel` | `5A LL` | 1级=`5A 01`、2级=`5A 02`、3级=`5A 03` | — | `getInfo`，回包 Tag `2D` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:146`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:329`） |
| EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock` | `2A 01` / `2A 00` / `2A 02` | POP=`2A 01`、HIFI=`2A 00`、ROCK=`2A 02` | — | `getInfo`，回包 Tag `2A`（线值 `0`=HIFI、`1`=POP、`2`=ROCK） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:161`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1941`） |
| 面板容器 | — | — | 容器，内含游戏/多点/音频/寻机/触控/更多 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:194`） |
| 游戏模式 | `setGameModeOn`、`setGameModeOff` | `0E 01` / `0E 00` | 开=`0E 01`、关=`0E 00` | — | `getInfo`，回包 Tag `0E`（`"1"`=开、`"0"`=关） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:203`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1685`） |
| 多点连接 | `setConnectMulOn`、`setConnectMulOff` | `2B 04` / `2B 00` | 开=`2B 04`、关=`2B 00` | — | `getInfo`，回包 Tag `2B`（`4`=开、`0`或`1`=关） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:216`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1665`） |
| 音频模式 | `setAudioAacSbc`、`setAudioLdac` | `2B 00` / `2B 01` | AAC/SBC=`2B 00`、LDAC=`2B 01` | — | `getInfo`，回包 Tag `2B`（`0`=AAC/SBC、`1`=LDAC） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:239`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1557`） |
| 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | `5E 01` / `5E 02` / `5E 03` / `5E 04` | 左耳=`5E 01`、右耳=`5E 02`、双耳=`5E 03`、关=`5E 04` | — | —（单向响铃事件，无状态 Tag） | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:259`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:173`） |
| 触控按键绑定 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree` | `01 SS 0A` | 槽位左单/双/三=`01`/`02`/`03`、右单/双/三=`11`/`12`/`13`；动作无/播暂/上一曲/下一曲/音量+/音量-=`0`/`1`/`2`/`3`/`4`/`5` | — | `getInfo`，回包 Tag `01` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:600`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:1042`） |
| 触控总开关 | `setTouchOn`、`setTouchOff` | `0E 00` / `0E 02` | 开=`0E 00`、关=`0E 02` | — | `getInfo`，回包 Tag `07` | 静态 | 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:670`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:879`） |
| 更多设置入口 | — | — | 容器，内含星芒入口/OTA/提示音音量 | — | — | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:277`） |
| 星芒业务入口 | — | — | 云端星芒业务跳转入口，不走蓝牙协议 | — | — | 未知 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:335`） |
| OTA 入口 | — | — | 独立 AB OTA 方案入口 | — | — | 未知 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:376`） |
| 提示音音量 | `setDingLevel` | `5C LL` | 1..5 级=`5C 01`..`5C 05` | — | `getInfo`，回包 Tag `2E` | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:467`）；Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_sender.dart:697`） |

## 接收字段

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
|---|---|---|---|---|
| `0C` | 电量 | 覆写处理：仅取第 1 路原始值（byte8–9），转十进制百分比字符串存入仓槽，充电标志固定设为 `false` 并写入缓存；左右槽被忽略 | 静态 | cambrian_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/cambrian_receive_handler.dart:140`） |
| `2B` | 音频/多点模式 | 覆写处理：线值 `0` 对应 AAC/SBC（关闭多点）、`1` 对应 LDAC（关闭多点）、`4` 对应开启多点；忽略 `2`（LHDC）和 `3`（LC3） | 静态 | cambrian_receive_handler.dart（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common/cambrian_receive_handler.dart:10`） |
| `01`、`07`、`08`、`09`、`0D`、`0E`、`2A`、`2C`、`2D`、`2E`、`33` | 按键、触摸开关、佩戴、降噪、版本、游戏、EQ、降噪等级、通透等级、提示音、空间音频 | 同族默认：由 `RsCommonReceiver` 和 `RsCommonReceiveHandler` 默认处理 | 静态 | [rs-common.md](../families/rs-common.md#全族共用) |

## 未实现与未知

- **降噪等级与通透等级控制分离**：Cambrian 配置的降噪栏开启了通透 3 级调节（`field_17 = true`，取值 low/medium/high），但降噪等级调节处于禁用状态（`field_13 = false`）；虽然 Sender 存在 `setAncLevel`（`58`）方法，但界面不向 Cambrian 用户暴露降噪强度调节。
- **触控手势范围**：Cambrian 覆写 `touchInfo` 采用 `typeOTT`（One、Two、Three），界面仅提供单击、双击、三击手势，不包含长按（Long）；按键动作仅支持 6 项基础音频控制（无功能、播放暂停、上一首、下一首、音量+、音量-），不支持切换降噪等动作。
- **按键标签与物理映射**：触控配置中定义了左耳默认标签 `"left_volume_down"`（音量-）与右耳默认标签 `"right_volume_up"`（音量+），但左右声道的实际按键位置与设备物理响应未经实机验证。
- **音频编码与多点互斥状态**：发送 `setConnectMulOn`（`2B 04`）开启多点后，`setConnectMulOff`（`2B 00`）会同时将音频模式复位为 AAC/SBC；在 LDAC 打开时开启多点的设备端抢占与仲裁逻辑在无实机情况下未知。
- **无字节级证据控件**：星芒业务入口为云端服务跳转界面，OTA 为独立协议，两者均无控制帧字节证据，在“设置命令”表中状态标为“未知”。
- **无实机**：无真实耳机抓包、广播日志或回包样本；所有设备端是否支持与状态一致性均为未知。

查证范围：`CambrianDeviceConfig`、`CambrianReceiveHandler`、`RsCommonSender`、`RsCommonReceiver`、`AbOta`、`ConnectManager`、`BleConnector`、`BtConnector` 及 `model_registry.json`。

## OTA

Cambrian 配置的 OTA 方案为 AB（`DeviceOtaType.ab`，构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:30`）），由 `AbOta` 执行升级。OTA 方案通用的通道定义、帧格式、分块窗口与 ACK、状态机见 [AB OTA 方案](../ota/ab.md)。OpenRose 不实现 OTA，此节只作记录。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| 路由 | `ab` | 静态 | 构造:30（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:30`）、OTA 面板:375（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:375`） |
| 固件门槛 | 无（面板未配置 `sinceVersion`） | 静态 | OTA 面板:379（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:379`） |
| 前置条件 | 无出仓门槛（头戴耳机 `earType="head"`，`checkIsTws()` 为 false，`checkWws()` 恒真） | 静态（代码逻辑） | 定制:701（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:701`）、出仓检查（`rose_reverse/analysis/blutter/asm/roselink/lib/common_util.dart:138`） |
| BLE OTA 服务 | `0000ff12-0000-1000-8000-00805f9b34fb`（16 位 `ff12`） | 静态 | 定制:711（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:711`） |
| BLE OTA 写特征 | `0000ff15-0000-1000-8000-00805f9b34fb`（16 位 `ff15`） | 静态 | 定制:715（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:715`） |
| BLE OTA 通知特征 | `0000ff14-0000-1000-8000-00805f9b34fb`（16 位 `ff14`） | 静态 | 定制:718（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:718`） |
| SPP OTA UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931`（复用控制 SPP UUID） | 静态 | 定制:706（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:706`） |
| 元数据 keyId | `cambrian` | 静态 | 对象信息（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/object_info.dart:6`） |
