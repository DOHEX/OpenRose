# AB OTA 方案

APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）。AB 是 App 的 OTA 路由之一：`DeviceOtaType.ab` 的枚举值为 0（枚举（`rose_reverse/analysis/blutter/objs.txt:39881`）），`DeviceOtaController.initOtaManager` 把枚举索引 0 分派到 `AbOta`（分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3538`） `0xb45d64`），所有 `otaType=ab` 的型号共用同一套帧封装、分块切片、ACK 超时重试和状态机。**本仓库没有实机 OTA 记录，完整固件未下载、未执行刷机**；以下除另注外均为静态分析。

| 型号 | 路由 | 门槛 | 出处 |
|---|---|---|---|
| OpenFeel MK2（RS Common V2，cid 524） | `ab`；BLE OTA 服务 `0000ff12-0000-1000-8000-00805f9b34fb`，写特征 `0000ff15-…`，通知特征 `0000ff14-…`；SPP OTA UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`（复用控制 SPP UUID）；OTA 面板 `needCheckWwsMode=true`（默认），双耳出仓前置门槛（失败报错 Code 10000） | —（OTA 面板未写 `sinceVersion`） | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:401`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:660`） |
| SWIFT 2（RS Common V2，cid 520） | `ab`；BLE 同上（`ff12/ff15/ff14`）；独立 SPP OTA UUID `0cf12d32-fac3-4553-bd80-d6832e7b3931`（与控制 SPP UUID `0cf12d31-…` 分离）；OTA 面板显式写 `needCheckWwsMode=false`，无出仓门槛 | —（OTA 面板未写 `sinceVersion`） | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:284`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:524`） |
| Cambrian（RS Common，cid 470） | `ab`；BLE OTA 显式写 `ff12/ff15/ff14`；SPP OTA UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`（复用控制 SPP UUID）；头戴单体（`earType="head"`），`checkIsTws()` 为 false，`checkWws()` 恒真，无出仓门槛 | —（OTA 面板未写 `sinceVersion`） | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:375`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:686`） |

RS Common V2 配置中路由 AB 的还有 Astro X、Coral、KY01、Magician、OpenFeel L、SpeakFeel 2、Zircon、Ceramics MK2、Ceramics MK2 [8912F]、First Angel、Moka、Furina 等（v2_capabilities.json（`rose_reverse/analysis/v2_capabilities.json`））。上述型号的 `DeviceOtaPanelItemWidgetModel` 均未传 `sinceVersion`，因此本方案没有可解码的固件版本门槛（静态，默认参数（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:5432`））。`needCheckWwsMode` 默认值为 `true`（默认参数（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:5330`） `0x67e0c8`）：头戴型号 SWIFT 2 在面板中显式覆写为 `false`；Cambrian 未覆写但因 `earType="head"` 判定非 TWS，出仓检查直接跳过。

## 通道与 UUID

AB 方案支持 BLE、经典蓝牙 SPP 与 USB-HID 三种传输通道。

| 通道 | 目标服务 / 描述符 | 写特征 / 端口 | 读 / 通知特征 | 状态 | 出处 |
|---|---|---|---|---|---|
| BLE OTA | `0000ff12-0000-1000-8000-00805f9b34fb`（16 位 `ff12`） | `0000ff15-0000-1000-8000-00805f9b34fb`（16 位 `ff15`） | `0000ff14-0000-1000-8000-00805f9b34fb`（16 位 `ff14`） | 静态 | 默认配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1230`）、Cambrian 定制（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:710`） |
| SPP OTA（SWIFT 2） | — | `0cf12d32-fac3-4553-bd80-d6832e7b3931`（独立 OTA RFCOMM UUID） | 同写特征 | 静态 | SWIFT 2 定制（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:545`） `0x82611c` |
| SPP OTA（OpenFeel MK2 / Cambrian 等） | — | `0cf12d31-fac3-4553-bd80-d6832e7b3931`（复用控制 SPP UUID） | 同写特征 | 静态 | MK2 定制（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:694`）、Cambrian 定制（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:706`） |
| USB-HID | 报告 ID `_usbHidReportId` | USB-HID 写入 | USB-HID 接收 | 静态 | HID 端口（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:1015`） `0xabfba0` |

### 通道特征与连接逻辑

1. **BLE 通道**：`normalBleOtaServiceConfig` 为 RS Common V2 分支配置 `ff12/ff15/ff14`。`_subscriptionCharacteristics`（订阅（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:2674`） `0xe232d4`）监听 `ff14` 通知，并将通知通过 EventBus 转发至 `"ble-callback-ota"` 事件供 `AbOta.init` 消费。
2. **SPP 通道 UUID 差异**：SWIFT 2 的 `customConfigEarObject` 将控制 SPP UUID（`field_33`）设为 `0cf12d31-…`，但将 OTA SPP UUID（`field_37`）单独设为 `0cf12d32-fac3-4553-bd80-d6832e7b3931`（末字节前一位为 `2`）；而 OpenFeel MK2 与 Cambrian 将 `field_33` 与 `field_37` 均设为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`，复用控制通道 UUID。
3. **回包前缀过滤**：`_decodeOtaNotifyHex`（过滤（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:9262`） `0xe27998`）在非 USB 模式下处理通知：
   - 若十六进制串以 `"dd474d"` 开头（6 个十六进制字符，即 3 字节 `\xddGM`），执行 `substring(6)` 剥离前 3 字节后再交由状态机解析。
   - 若以 `"dd"` 开头，保留原十六进制串。
   - 其余前缀视作非法，返回 `null` 丢弃。USB 模式直接透传原串。
4. **重复通知去重**：`process`（去重（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:2901`） `0xe23530`）检查通知内容是否与上次相同；若相同且**不是 `0x90` 帧**，记录警告 `ab ota receive duplicate skipped` 并丢弃。`0x90` 作为分块确认与状态轮询，豁免去重。

## 字节格式

发送帧由 `constructData`（帧构造（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:818`） `0xabf974`）统一定义：

```text
[Opcode: 1B] [Seq: 1B] [Payload: NB...]
```

其中 `Seq` 为单字节循环递增计数器：每次构造帧时取 `field_b & 0xff` 写入帧中，随后更新 `field_b = (field_b + 1) & 0xff`。

### 命令与应答 Opcode

| Opcode | 方向 | 阶段 / 功能 | Payload 结构与说明 | 出处 |
|---|:---:|---|---|---|
| 固定识别码 | TX | 启动握手 | 固定 7 字节：`CC AA 55 EE 12 19 E4`。不带 Opcode 与 Seq，直写通道，随后延时 200 ms | sendOtaIdentification（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:1073`） `0xabfc34` |
| `0x89` | RX | Dongle 透传外层 | 用于无线麦 Dongle 模式外层包解包，内嵌实际数据 | Dongle 解包（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:3882`） `0xe24008` |
| `0x8A` | RX | GUID 查询应答 | 携带 target 与 16 字节 `deviceGuid`；校验 target 必须与目标匹配，否则报错 `GUID-target-mismatch`；成功后发送 32 字节固件 GUID | GUID 应答（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:3235`） `0xe238fc` |
| `0x8B` | RX | GUID 校验应答 | **不是分块 ACK**。状态字节为 1 表示 GUID 匹配，日志打印 `GUID 匹配，进入 AB 版本查询`，触发 `0x91` 版本查询；不为 1 则报错 `Code[GUID-mismatch]` 终止升级 | GUID 结果（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:3665`） `0xe23da8` |
| `0x91`（Type 1） | TX | 查询版本 | Payload：`[0x01, 0x00]`（查询设备端当前固件版本文本） | 版本查询（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:3792`） `0xe23f08` |
| `0x91`（Type 2） | TX | 查询升级参数 | Payload 长 7 字节：`[0x02, ver_lo, ver_hi, md5_0, md5_1, md5_2, md5_3]`。总帧长 9 字节 | getInfoUpdate（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:8711`） `0xe273ac` |
| `0x92` | TX | 查询全量信息 | `sendGetAllInfo` 发送固定 Payload：`[0x01, 0x00, 0x03, 0x00, 0x04, 0x00, 0x06, 0x00]`，总帧长 10 字节。设备回包由 `processTLVData` 解析 | sendGetAllInfo（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:2029`） `0xac0c74` |
| `0xA0` | TX | 固件分块切片发送 | Payload：按 `effectivePacketSize` 切分的固件切片数据 | _senOtaStartAsync（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:6232`） `0xe2594c` |
| `0x90` | RX | **分块 ACK 与状态上报** | 格式为 `type`、`length`、`value`。包含 `status` 字节，携带 `sentOffsetBytes`、`totalBytes` 与 `percent` | processOtaMessage（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4586`） `0xe247b0` |

### 关键字段与算法细节

- **固件 MD5 前缀**：`_otaFirmwareMd5Prefix4`（MD5截取（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:8862`） `0xe27540`）先将固件 hex 文本转为字节数组，通过标准 `crypto.md5.convert` 计算 MD5 哈希，并截取前 4 字节（`md5[0..3]`）拼入 `0x91` Type 2 帧中；若固件内容为空或未初始化，则填入 4 字节 `FF FF FF FF`。
- **Type 2 参数解析**：设备对 `0x91` Type 2 的回包携带 4 个升级核心参数（解析（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:5250`） `0xe24ee8`）：`startAddress`（起始烧录地址）、`blockSize`（单块大小）、`packetSize`（设备建议包长）与 `allowedUpdate`（允许升级标志）。

## 传输窗口、分段与 ACK

升级数据传输按两级分片进行：外层为 **Block（块）**，内层为 **Packet（切片包）**。

### 切片长度与自适应调整

1. 单个 Block 大小由设备在 `0x91` Type 2 回包中的 `blockSize` 指定。
2. 每个 Block 被切分为若干个 `effectivePacketSize`（`field_9b`）的切片包（包长计算（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:6298`） `0xe259fc`）：
   - USB 模式固定为 59 字节。
   - BLE 模式：取 `min(MTU - 3, reported_packetSize)`，且上限不超过 `MTU - 10`。
   - SPP 模式：若设备未上报则默认 240 字节（`0xf0`）。
3. **写入异常自适应**：在 BLE 传输中，若底层拋出包含 `dataLen:\s*(\d+)\s*>\s*max:\s*(\d+)` 的写入异常，App 正则捕获 `max` 数值，动态将 `effectivePacketSize` 缩小至 `max`，并以新尺寸重试当前切片（异常捕获（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:7112`） `0xe2631c`）。

### 写入间隔与队列

分片写入由 `ConnectManager.writeOta` 提交至 `RQueueMamager::send`（发送队列（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:13186`） `0xabe4c0`）：
- BLE 通道（Connector `0x18c`）：分包间隔 `gapAfterMs` 强制设为 **50 ms**。
- SPP 通道与 USB-HID 通道：分包间隔 `gapAfterMs` 设为 **25 ms**。
- 只有无线麦 Dongle 路由在 `_usesPamBleGuidBulkWrite` 为 true 时直写 `BleConnector.writeOta`；普通耳机（OpenFeel MK2、SWIFT 2、Cambrian）均走 `ConnectManager.writeOta` 队列控制。

### Block ACK 与重试上限（3 次 vs 6 次）

在分块传输中，切片之间没有逐包 ACK，一个 Block 内的所有切片连续发出后，启动 Block ACK 确认机制：

- 发送完全部切片后，启动 **5.0 秒定时器**（`_scheduleBlockAckTimeout`，微秒值 `0x4c4b40=5000000`，定时器（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:7420`） `0xe26640`、5 秒对象（`rose_reverse/analysis/blutter/objs.txt:49140`））等待设备上报 Opcode `0x90`。
- **重试上限判定**：
  - 超时日志模板中写有 `"retry={count}/6"`（日志模板（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:7621`） `0xe26840`），因而容易被误读为最大重试 6 次。
  - 但代码逻辑判断严格为 `cmp x1, #3; b.ge #0xe2695c`（重试分支（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:7637`） `0xe26868`）：当 `retryCount < 3` 时递增重试计数并重新从 `0xA0` 发送当前 Block；当 `retryCount >= 3` 时直接跳转调用 `_onOtaBlockAckGiveUp`（放弃升级（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:7741`） `0xe2697c`）终止 OTA。
  - **结论：实际重试上限为 3 次**，日志串中的 `/6` 是未对齐的打印残留。
- 并发保护：若上一个 Block 的发送操作仍在进行（`field_67 == false`），新触发的超时回调或发送请求会被直接跳过（并发保护（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:7500`） `0xe26700`、`0xe25604`）。

### 0x90 状态码与 STATE_ERR_SEQ (0x40)

设备通过 Opcode `0x90` 回复分块状态，格式由 `processOtaMessage` 处理（0x90处理（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4586`） `0xe247b0`）：

| 状态码 | 含义 | App 处理动作 | 出处 |
|:---:|---|---|---|
| `0x00` | 块确认成功 | 取消 5.0 s 定时器，重置重试计数器与 0x40 单次标志；更新 `sentOffsetBytes`、`totalBytes` 与 `percent`；若未发完全量数据则调用 `_senOtaStartAsync` 推进下一 Block | 0x00 成功（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4619`） `0xe24818` |
| `0x40` | `STATE_ERR_SEQ`（序号错误） | **单次重发保护**：检查 `field_83`。若首次出现（`field_83=false`），置 `field_83=true`，重置序号并重发当前 Block 一次（打印 `ab ota: 0x90 STATE_ERR_SEQ (0x40), resend current block once`）；若再次出现（`field_83=true`），弹窗报错 `update-fail-restart \n Error: [0x40 repeat]` 并终止 | 0x40 逻辑（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4855`） `0xe24aac`、单次分支（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:5003`） `0xe24c34` |
| `0xFF` | 最终升级成功 | 调用 `_onOtaSuccess`，进入完成提示与重连流程 | 0xFF 成功（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4753`） `0xe24990` |
| `0x80` | 设备升级失败 | 取消定时器，弹窗提示 `update-fail-restart \n Code[TWS-80]` 并终止 | 0x80 失败（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4759`） `0xe249a4` |
| `0xFD` / `0xFE` | 设备流控（暂停 / 继续） | 记录警告 `ab ota device pause(0xFD)/continue(0xFE) not handled:`，App 未做挂起处理 | 流控日志（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:4837`） `0xe24a78` |
| 其他状态 | 异常状态码 | `0x01`、`0x02`、`0x03`、`0x0B`、`0x41` 取消定时器；其余弹出 `Error: [...]` 提示 | 异常处理（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:5026`） `0xe24c70` |

## 状态机

```text
[握手阶段]
  App 发送固定识别码 CC AA 55 EE 12 19 E4
  └─ 延时 200 ms
[GUID 协商]
  App 查询 target 与 GUID
  └─ 设备回复 0x8A (校验 target 匹配，保存 deviceGuid)
  └─ App 发送 32 字节固件 GUID
  └─ 设备回复 0x8B (status==1 表示 GUID 匹配；否则 Code[GUID-mismatch] 终止)
[参数查询与出仓门槛]
  双耳出仓门槛检查 (若 needCheckWwsMode=true 且为 TWS，失败报错 Code[10000])
  └─ App 发送 0x91 Type 1 (查询设备当前版本文本)
  └─ App 发送 0x91 Type 2 (携带版本号与固件 MD5 前 4 字节，获取 startAddress, blockSize, packetSize)
  └─ App 发送 0x92 (全量信息查询 sendGetAllInfo)
[分块传输循环]
  App 发送 0xA0 分块切片数据 (间隔 50 ms BLE / 25 ms SPP)
  └─ 启动 5.0 s 定时器等待 0x90
  ├─ 收到 0x90 status=0x00 ──> 推进下一 Block
  ├─ 收到 0x90 status=0x40 ──> 单次重发当前 Block (重复则报 0x40 repeat 终止)
  └─ 5.0 s 超时未应答 ──> 重传当前 Block (上限 3 次，第 4 次超时触发 giveUp 终止)
[升级完成与重连]
  全量发送完毕且设备回报最终成功 (0x90 status=0xFF)
  └─ 弹窗提示升级成功
  └─ 蓝牙延时 1.0 s / USB 延时 0 s
  └─ 调用 OtaReconnect.reconnect 主动重新连接设备
```

- **出仓门槛校验（Code 10000）**：在发送 `0xA0` 数据之前，若 `needCheckWwsMode == true`，调用 `CommonUtil.checkWws`（出仓检查（`rose_reverse/analysis/blutter/asm/roselink/lib/common_util.dart:126`） `0x749fd0`）。对 TWS 型号（如 OpenFeel MK2），若耳机在仓内或未佩戴出仓，打印 `[ota][ab] 双耳出仓校验不通过，无法进入ota流程！` 并弹窗 `update-fail-restart \n Code[10000]` 终止升级。头戴耳机（如 SWIFT 2、Cambrian）不产生此拦截。
- **升级后重连**：传输成功触发 `_onOtaSuccess`（重连（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart:5479`） `0xe2515c`）：在非 USB 模式下等待 1.0 秒（`Duration@db0781`，微秒值 `0xf4240=1000000`），调用 `OtaReconnect.reconnect` 主动发起重连；若设备对象在成功时刻已被清空，记录 `[ota-reconnect] OTA 成功时当前设备为空，无法主动重连` 并跳过。

## 固件来源与完整性

| 项 | 行为 | 状态 | 出处 |
|---|---|---|---|
| 下载链 | 与 WQ / BES 方案完全共用：`DeviceOtaController.getOtaContent`（`field_57`）→ `DeviceService` → `ApiDeviceService` → `StaticResourceClient.getText`；通过 HTTP GET 拉取 hex 纯文本保存在 `latestData` | 静态 | 控制器入口（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1666`） `0xac01e0`、HTTP客户端（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:642`） |
| 完整性 hook | `compareOtaDataWithEncodeData` 依然恒真返回 `true`，未在此处执行服务端 `encodeData` 的真实校验 | 静态 | 校验hook（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1723`） `0xac05a0` |
| 元数据请求 | `POST /app/ota/v2/get-by-key-id-and-tag`，入参 `keyId`、`pid=""`、`tag=1`、`clientVersion="5310"`、`lang`，`token=false` | 静态 | 元数据API（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:476`） `0xacc6d4` |
| keyId 映射 | 取自 `trackingName`：OpenFeel MK2 为 `openfeel_mk2`，SWIFT 2 为 `swift2`，Cambrian 为 `cambrian` | 静态 | MK2（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:665`）、SWIFT 2（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:530`）、Cambrian（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/object_info.dart:6`） |
| 服务端样本 | 当前 [OTA 更新包链接](../ota-package-links.md) 中仅收录了 i5（WQ）与 i7（BES）的真实响应样本；**本仓库尚未抓取并验证 AB 型号（OpenFeel MK2、SWIFT 2、Cambrian）的实际服务端固件包** | 未知 | [OTA更新包链接](../ota-package-links.md) |

## 未知与缺口

- **实机抓包缺口**：本方案全部事实由反汇编静态推断，尚无任意一款 AB 型号（OpenFeel MK2 / SWIFT 2 / Cambrian）的蓝牙空中抓包数据，设备端对 `CC AA 55 EE 12 19 E4` 标识码、`0x91` 查询及 `0xA0` 数据的底层接收逻辑未验证。
- **流控响应缺失**：设备上报 `0xFD`（暂停）与 `0xFE`（继续）流控信号时，App 仅打印日志未暂停发送队列；真实耳机若频繁流控是否会导致丢包或断连未知。
- **断点续传恢复**：代码中保存有已确认偏移量 `sentOffsetBytes`，但在连接意外断开后设备端是否支持继续写入未经验证。

## 维护

本文件收录所有 `otaType=ab` 型号共用的 OTA 机制。新增型号先核对配置类中的 `otaType`、`needCheckWwsMode`、`sinceVersion`、SPP OTA UUID 及 BLE 特征，再在本文件型号表加一行；型号文档的 `## OTA` 章节只保留本型号的路由、门槛及与本文的不同之处（如 SPP UUID 是否复用、是否有出仓门槛），不重复通用帧格式、分块窗口与状态机。
