# RS Common V2 协议族

RoseLink 3.8.0（versionCode 5310，包名 `cn.ikaile.ruoshui.client`，APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）中 28 个耳机配置使用 RS Common V2。本文收录这一族共用的广播识别、BLE/SPP 传输、发送帧、设置命令、接收解析和客户端状态建议；型号文档只写与本文不同的取值。本文的结论都来自静态分析，没有任何一款 RS Common V2 耳机做过实机验证；字节样例均为合成，不是蓝牙抓包。

范围是耳机控制与通知协议。BLE/SPP 的 BES OTA 是独立方案，不在本文展开（见 [BES 方案文档](../ota/bes.md)）。

## 全族共用

28 个耳机配置的构造函数都实例化 `RsCommonV2Sender` 和 `RsCommonV2Receiver`（例：BudsFeel MK2 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:47`）），所以帧格式、命令编码和接收拆包属于协议族，型号只决定开放哪些控件、取哪些值。接收字段的处理由 ReceiveHandler 负责：26 个配置用 `RsCommonV2ReceiveHandler`，FIRST ANGEL 用 `FirstAngleReceiveHandler`，SpeakFeel [speakfree] 用 `SpeakfreeReceiveHandler`，这两款的字段处理需要单独核对。

`i7_sender_commands.json` 和 `i7_receiver_layouts.json` 虽然以 i7 命名，内容是整个族的 Sender 与 Receiver。前者用 `applicability` 区分 `i7_configured`（i7 界面实际调用）和 `shared_sender_only_not_proven_i7`（只在 Sender 中存在）。目录没有收录 Sender 的全部方法（例如 `setEqBassPlus`、`setLeftTapTwo`）；目录里查不到的方法，以 rs_common_v2_sender.dart（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart`） 为准。

| 环节 | 结论 | 细节与证据 |
|---|---|---|
| 广播识别 | manufacturer 数据先拼所有 company ID（小端）再拼 payload；按前 12 → 8 → 6 位查前缀表 | [广播识别](#广播识别) |
| 广播字段 | `getInfoFromRawData` 的 rs_common_v2 分支：设备类型、BR/EDR 标志、地址偏移 | [广播识别](#广播识别) |
| 控制通道选择 | `ConnectManager._shouldUseSppControl`：SPP 可用、型号允许、未强制 BLE、非 LE Audio 时走 SPP | [传输选择](#传输选择) |
| BLE 控制特征 | 默认 `00fe` / `00f1` / `00f2`；SPP UUID 由型号的 `customConfigEarObject` 写入 | [默认传输](#默认传输)、[通道与连接](#通道与连接) |
| 发送帧 | `FF SS LL BODY CC AA`，`CC` 为前缀字节和的低 8 位 | [发送帧](#发送帧) |
| 初始化 | `GlobalController` 经 GDT+0xd8ba 按配置 cid 分派；已核对的共享目标 `0x8e2444` 只调用 `getInfo` | [初始化与查询](#初始化与查询)、分派补核（`rose_reverse/analysis/i7_dispatch_findings.md`） |
| 命令编码 | `RsCommonV2Sender` 各方法 | [设置命令](#设置命令)；机器目录 i7_sender_commands.json（`rose_reverse/analysis/i7_sender_commands.json`） |
| 接收解析 | `DD HH [L T V...] CS AA`、两种校验、分包重组、各 Tag | [接收帧与字段](#接收帧与字段)；机器布局 i7_receiver_layouts.json（`rose_reverse/analysis/i7_receiver_layouts.json`） |
| 客户端状态建议 | 写入完成与设备确认分开、会话级序号、电量缓存与新鲜度 | [客户端状态](#客户端状态) |

### 默认传输

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | `000000fe-0000-1000-8000-00805f9b34fb`（配置存 `00fe`） | 静态 | normalBleServiceConfig:1066（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1066`） |
| 写特征 | `000000f1-0000-1000-8000-00805f9b34fb`（配置存 `00f1`） | 静态 | normalBleServiceConfig:1069（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1069`） |
| 通知特征 | `000000f2-0000-1000-8000-00805f9b34fb`（配置存 `00f2`） | 静态 | normalBleServiceConfig:1072（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1072`） |
| SPP 是否允许 | 按型号（本族默认 `true`）；28 个产品配置均为 `true`，由型号配置写入 | 静态 | model_registry.json（`rose_reverse/analysis/model_registry.json`） 的 `spp_support` |
| 控制 SPP UUID | `0cf12d31-fac3-4553-bd80-d6832e7b3931`；28 个配置写同一值 | 静态 | 型号定制:1052（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1052`）、model_registry.json（`rose_reverse/analysis/model_registry.json`） |
| 广播中的经典地址 | 规范化 hex `H` 的 `H[22:34]`：6 个字节按原序用 `:` 分隔；`bleAddress` 与 `btAddress` 取同一子串 | 静态 | V2 广播解析:451（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:451`）、MAC 格式化:685（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:685`） |
| 写入间隔 | 普通 BLE 100 ms；OHOS BLE 350 ms；均在 GATT write Future 完成后计 | 静态 | ConnectManager.write:386（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:386`）、队列笔记（`rose_reverse/analysis/i5_queue_protocol.md`） |

### 广播识别

共用 scanner `_advertisementDataToHexString @0xa0daa8` 把 manufacturer keys 数值排序；先拼所有 keys 的 2 字节小端，再拼依此顺序的所有 payload，最后转 lowercase hex。不是 key/payload 逐项交错。

`DeviceConfig.identifyDevice @0x90a2ac` 先尝试前 12 hex chars、再前 8 chars、最后前 6 chars 的表匹配；例：i7 用前 8 chars `b8e5290f`。通用配置 RS_COMMON_V2（前缀 `0a0b0a0c`）受开发调试过滤规则约束（0xa0d2c4（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:1061`））：未开启 `isDeviceDebug` 与 `agreeApp` 时 App 直接过滤丢弃并输出“未开启开发者调试模式，基础协议/非正式版设备被过滤”，并非未识别设备的通用兜底。单个 manufacturer 项的静态推导样例是 key/company `0xe5b8`，payload 开始 `29 0f`；此例不是实机广告。
Base `getInfoFromRawData @0x748ddc` 的 rs_common_v2 分支，对规范化后的 hex H：

| 条件/偏移 | 解释 |
|---|---|
| `len(H)>=22`；`H[12:14]` | broadcastDeviceType；01→tws、04→mic、其余→head |
| `H[14:16]=="01"` | brEdr="true"；其余="false" |
| `len(H)>=34`；`H[22:34]` | bleAddress/btAddress；6 个字节按原序用 `:` 分隔，未见颠倒；`to2` 只分组不转换大小写 |

field `4b=bleAddress`、`4f=btAddress`，实际字段名称见 EarObject.toMap。BLE 连接仍使用 OS 扫描对象的 address/remoteId；SPP 可使用广播的 BR/EDR 地址，不能用 discovery ID 当 MAC。广播 H 中 bytes 4..5、8..10 的语义此路径未读，不补猜测。

- manufacturer 规范化:3737（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/scanner/r_ble_scanner.dart:3737`）
- 前缀识别:191（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`）
- V2 广播解析:451 起，0x749298（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:451`）
- MAC 格式化:685，0x74953c（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:685`）

### 传输选择

`ConnectManager._shouldUseSppControl @0xa009fc`：平台 SPP 可用且该型号 `sppSupport=true`、全局没有强制 BLE，音频 Profile 非 LE Audio 才选 BtConnector。`isSppOperatingSystem @0x9ecba0` 包含 android、windows、ohos、openharmony、harmonyos、harmony。LE Audio 情况由 `A2dpManager.prefersBleControl` 决定，不能直接根据 UI 选了 LC3 就断言连接已进入 LE Audio。

普通 BLE 流程：connect、discoverServices、匹配 00fe/00f1/00f2、注册 onValueReceived、setNotifyValue(true)，writeWithoutResponse 属性优先，否则有响应写；请求 MTU 512。普通队列等待 write Future 完成后间隔 100 ms；OHOS BLE（connector class id `0x18c`）间隔 350 ms。**业务应答不是 GATT write Future**。SPP 将同一 V2 控制字节帧发往自己的 socket；BES OTA 则独立 socket/channel。

- 控制选择:4614，0xa009fc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:4614`）
- SPP 操作系统与强制 BLE/LE Audio 条件:95，0x9ecb04（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/src/roselink_platform.dart:95`）
- SPP 操作系统名单:136（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/src/roselink_platform.dart:136`）
- BtConnector SPP 连接:41，0x9f4270；控制 UUID 取对象 field33（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/bt_connector.dart:41`）
- A2dpManager.prefersBleControl:364（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/a2dp_manager.dart:364`）
- BLE 请求 MTU:9320，0xe555b4（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:9320`）
- 写入间隔 100/350 ms:386，0x67fa58（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:386`）

### 通道与连接

| 通道 | 服务/连接 UUID | 写 | 读/通知 |
|---|---|---|---|
| BLE 正常控制 | `000000fe-0000-1000-8000-00805f9b34fb` | `000000f1-0000-1000-8000-00805f9b34fb` | `000000f2-0000-1000-8000-00805f9b34fb` |
| SPP 正常控制 | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | RFCOMM/socket byte stream | 同 socket 读取 |

BLE/SPP 的 BES OTA 通道不是本族控制通道，见 [BES 方案文档](../ota/bes.md)。

1. 扫描并按 manufacturer 前缀匹配，保存系统 remote id。SPP 通过系统配对/SDP 服务发现连接其专用 UUID；不能把 BLE 的 00FE 服务或型号前缀当 RFCOMM 通道号。
2. BLE 连接后发现实际服务/特征，核对 write 属性。先订阅 00F2 并完成 CCCD 设置，再发送查询。
3. 默认公共 BLE 层优先无响应写，尝试请求 MTU 512；不支持无响应写时用有响应写。SPP 直接写同一完整 RS V2 报文，接收时作为字节流重组。
4. 普通控制共用 FIFO，底层 write 完成后留 100 ms；OHOS BLE 特定分支 350 ms。这里没有统一业务 ACK 关联或自动重发。
5. 分别表示 transport_connected、notifications_subscribed、protocol_received、每个字段的 loaded/freshness；断开时清空序号所属会话、分包 buffer 和待处理查询。

`getInfo` 整帧 35 字节，比 ATT MTU 23 下的 20 字节值容量大，所需普通 ATT MTU 至少 38。不要将它任意拆成两个独立 write 而宣称设备已经支持 TX 拼接。优先使用协商后的可写容量；参考 BLE 例子在容量不足时，将 FA 的 ID 列表分成多条完整 FA 查询帧。这是从 App 观察到的 FA 多 ID/单 ID 查询归纳的客户端降级策略，设备接纳仍需验证。每条新逻辑查询使用新的序号和校验。

本族正常单值设置 7 字节，触摸设置 8 字节，ANC 循环 10 字节，自定义 EQ 数据 16 字节。没有额外业务帧分片参数出现在这些命令里。普通控制序列和 BES OTA 序列分别维护。

通道证据：型号定制:1024（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1024`）、默认 BLE 配置:993（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、SPP 选择:4614（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:4614`）、普通写属性:5921（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:5921`）、公共队列（`rose_reverse/analysis/i5_queue_protocol.md`）。本研究没有发现正常控制的自定义登录 token/密码握手；系统配对要求仍取决于设备/平台，不能据此声明所有固件均无需配对。

### 发送帧

`FF SS LL BODY... CC AA`。`SS` 为 1 字节序号，`RsCommonV2Sender` 构造时为 0（i7 配置同）；每次 transform 使用当前值再加 1，下一次若超过 255 先归零。`LL=len(BODY)`，包括命令及参数，不包括帧头、序号、长度、校验与结尾；全帧长 `LL+5`。`CC=sum(FF SS LL BODY...) & 0xff`，并非 CRC。尾字节固定 `AA`。

证据：transform:4750（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4750`）（0xdfa838）、Sender 初值:49（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:49`）（0x681848）、CommandUtil.checkSum:189（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:189`） 与 加法 reducer:454（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:454`）。

以下完整帧样例全使用 `SS=00`，实际串行发送时必须使用当前序号并重新求和。把同一完整样例反复写入会重复序号。初始化查询长 35 字节，超过默认 ATT MTU 23 的单次值容量，须按 BLE transport 的能力处理；普通单值设置只有 7 字节，自定义 EQ 16 字节。

```python
def pack(body, seq):
    if not 0 <= seq <= 255 or not 1 <= len(body) <= 255:
        raise ValueError("invalid frame fields")
    raw = bytes([0xff, seq, len(body)]) + bytes(body)
    return raw + bytes([sum(raw) & 0xff, 0xaa])
```

### 初始化与查询

`getInfo` 返回 BODY：

```text
fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49
```

对应第一次写入：

```text
ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa
```

这是一条共享的批量查询，包含 29 个功能 ID，不能用它推断型号支持所有对应功能。FA 是已观察到的普通查询前缀：`FA 2A` 查询 EQ 模式，`FA 45` 查询增益，`FA 3E` 查询自定义 EQ。`getInfo` 没有查询 `3E`，想获取当前自定义曲线须另发 `FA 3E`。`getEarMac` 特殊为 `FE 34`。对其它已知 ID 拼接 `FA ID` 属于由现有查询方法归纳的构造方法，没有找到每个 ID 对应独立 App getter。

官方初始化：`GlobalController` 的 GDT+0xd8ba 按配置 cid 分派；已核对的共享目标 `0x8e2444` 仅调用 `RCommander.getInfo`。AOT 输出把该共享代码标为 Sportfree 匿名 closure，并不表示型号关系。以 i7 为例：cid 523 → 表 index 60101 → `0x8e2444`。因此官方初始化只包含 `getInfo`，**不含自定义 EQ 数据 3E**。客户端补发 `getCustomEq` 填充当前曲线是新增的客户端选择，有意与官方初始化区分。GDT 证据（`rose_reverse/analysis/i7_dispatch_findings.md`）

版本缺失时的重试：`GlobalController._retryVersionQuery`（global_controller.dart（`rose_reverse/analysis/blutter/asm/roselink/lib/global_controller.dart:2859`），0x9fbbb4）对协议为 `rs_common` 或 `rs_common_v2` 的配置同样直接调用 `RCommander.getInfo`（0x8e2004）。静态，重试的触发条件与间隔未逐项核对。

### 设置命令

| 方法 | BODY | seq=00 完整帧 | 证据 |
|---|---|---|---|
| `setFindOff` | `2f 04` | `ff 00 02 2f 04 34 aa` | setFindOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1166`） / 0x7b38f4 |
| `setFindDeviceBoth` | `2f 03` | `ff 00 02 2f 03 33 aa` | setFindDeviceBoth（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1207`） / 0x7b3b64 |
| `setFindDeviceRight` | `2f 02` | `ff 00 02 2f 02 32 aa` | setFindDeviceRight（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1283`） / 0x7bb13c |
| `setFindDeviceLeft` | `2f 01` | `ff 00 02 2f 01 31 aa` | setFindDeviceLeft（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1305`） / 0x7bb284 |
| `setAncAutoDynamicOn` | `09 07` | `ff 00 02 09 07 11 aa` | setAncAutoDynamicOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1368`） / 0x80a748 |
| `setAncAutoOn` | `09 05` | `ff 00 02 09 05 0f aa` | setAncAutoOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1409`） / 0x80a7a0 |
| `setNoiseModeTrans` | `09 03` | `ff 00 02 09 03 0d aa` | setNoiseModeTrans（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1570`） / 0x80d8c0 |
| `setNoiseModeNormal` | `09 02` | `ff 00 02 09 02 0c aa` | setNoiseModeNormal（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1611`） / 0x815834 |
| `setNoiseModeWind` | `09 04` | `ff 00 02 09 04 0e aa` | setNoiseModeWind（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1652`） / 0x815f64 |
| `setNoiseModeAnc` | `09 01` | `ff 00 02 09 01 0b aa` | setNoiseModeAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1693`） / 0x81a3a4 |
| `getGainLevel` | `fa 45` | `ff 00 02 fa 45 40 aa` | getGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1973`） / 0x822c3c |
| `setEarDetectionOff` | `08 01` | `ff 00 02 08 01 0a aa` | setEarDetectionOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2073`） / 0x826944 |
| `setEarDetectionOn` | `08 00` | `ff 00 02 08 00 09 aa` | setEarDetectionOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2114`） / 0x8284f8 |
| `setTouchOff` | `07 01` | `ff 00 02 07 01 09 aa` | setTouchOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2379`） / 0x8e87cc |
| `setTouchOn` | `07 00` | `ff 00 02 07 00 08 aa` | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） / 0x90fd08 |
| `setAudioLc3` | `2b 03` | `ff 00 02 2b 03 2f aa` | setAudioLc3（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2840`） / 0xc6dd34 |
| `setAudioLhdc` | `2b 02` | `ff 00 02 2b 02 2e aa` | setAudioLhdc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2881`） / 0xc6de70 |
| `setAudioLdac` | `2b 01` | `ff 00 02 2b 01 2d aa` | setAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2922`） / 0xc6e0ac |
| `setAudioAacSbc` | `2b 00` | `ff 00 02 2b 00 2c aa` | setAudioAacSbc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2963`） / 0xc6e274 |
| `setConnectMulOff` | `32 00` | `ff 00 02 32 00 33 aa` | setConnectMulOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3002`） / 0xc77cbc |
| `setGameModeOff` | `0e 00` | `ff 00 02 0e 00 0f aa` | setGameModeOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3213`） / 0xc78c24 |
| `setGameModeOn` | `0e 01` | `ff 00 02 0e 01 10 aa` | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3252`） / 0xc78dec |
| `getCustomEq` | `fa 3e` | `ff 00 02 fa 3e 39 aa` | getCustomEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3962`） / 0xc87f48 |
| `setEqCustom` | `2a 04` | `ff 00 02 2a 04 2f aa` | setEqCustom（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4003`） / 0xc87fd4 |
| `setEqEap` | `2a 06` | `ff 00 02 2a 06 31 aa` | setEqEap（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4312`） / 0xcb322c |
| `setEqLight` | `2a 03` | `ff 00 02 2a 03 2e aa` | setEqLight（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4353`） / 0xcbfc20 |
| `setEqRock` | `2a 02` | `ff 00 02 2a 02 2d aa` | setEqRock（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4394`） / 0xcbfdf0 |
| `setEqHifi` | `2a 00` | `ff 00 02 2a 00 2b aa` | setEqHifi（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4435`） / 0xcd3c40 |
| `setEqPop` | `2a 01` | `ff 00 02 2a 01 2c aa` | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4474`） / 0xdf57a0 |
| `getEq` | `fa 2a` | `ff 00 02 fa 2a 25 aa` | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4515`） / 0xdfa0b8 |
| `getEarMac` | `fe 34` | `ff 00 02 fe 34 33 aa` | getEarMac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4556`） / 0xdfa274 |
| `getInfo` | `fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49` | `ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa` | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4597`） / 0xdfa670 |
| `setConnectMulOn` | `32 01` | `ff 00 02 32 01 34 aa` | setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:5009`） / 0xe342e8 |
| `setEarLanguageChinese` | `31 00` | `ff 00 02 31 00 32 aa` | setEarLanguageChinese（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:865`） / 0x78ed0c |
| `setEarLanguageEnglish` | `31 01` | `ff 00 02 31 01 33 aa` | setEarLanguageEnglish（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:824`） / 0x78eb48 |
| `getEarLanguage` | `fa 31` | `ff 00 02 fa 31 2c aa` | getEarLanguage（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:904`） / 0x7956ec |
| `setEqBassPlus` | `2a 05 value` | `ff 00 03 2a 05 value CC aa` | setEqBassPlus（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4044`） / 0xc9d048，value 0..4 |

ANC 发送线值为 `09 01` ANC、`09 02` 普通、`09 03` 通透、`09 04` 抗风、`09 05` 自适应、`09 07` 动态自适应。共享的 `09 06` 是 `setDeepAncOn`；型号可以未配置深度 ANC UI（i7 未配置），不应据此增加能力。接收研究已用 UI 互证 RX 05 自适应、06 深度、07 动态自适应与 TX 一致；对应 App 内部状态 4、5、6 不是 NoiseType 枚举 0..3 之一。

EQ 线值 `2A 00` HIFI、`2A 01` POP、`2A 02` ROCK、`2A 03` LIGHT、`2A 04` custom、`2A 06` EAP。型号 UI 的取值映射取该型号的 `DeviceEQTypeModel`；i7 的配置是 POP=0/HIFI=1，而 Sender 的线值是 POP=1/HIFI=0，因此设置时要把 UI 值交换后再写，其他型号要各自核对。接收单字节 `2A` 的 `00/01` 另有显示层互换（见[字段表](#字段表)）。音频设置 `2B 00/01/02/03` 分别 AAC/SBC、LDAC、LHDC、LC3；型号配置只列它开放的项。多点是独立命令 `32 00/01`，与 i5 的 codec/多点耦合协议不同。游戏 `0E 00/01` 是关闭/开启；入耳与触摸开关则是反向极性 `08 00/01`、`07 00/01`，00 开、01 关。

寻机 `2F 01` 左，`2F 02` 右，`2F 03` 双耳，`2F 04` 停止；双耳是独立命令，回包含单耳停止事件。

#### 动态参数命令

| BODY | Sender 接受值 | 语义 | 证据 |
|---|---|---|---|
| `2c value` | `[1, 2, 3, 4, 5]` | ANC 等级 | setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1510`） / 0x80b788 |
| `2d value` | `[1, 2, 3, 4, 5]` | 通透等级 | setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1450`） / 0x80b30c |
| `2e value` | `[0, 1, 2, 3, 4, 5]` | 提示音 1..5；0 是关闭入口，是否开放由型号的固件门槛决定 | setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） / 0x823184 |
| `45 value` | `[0, 1, 2]` | 增益低/中/高三档 0/1/2，无具体 dB 量化证据 | setGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1914`） / 0x82189c |
| `46 value` | `[0, 1, 2, 3, 4, 5, 6]` | 听力保护 0 关，1..6 显示 75/80/85/90/95/100 dB（共享滑块的 UI 阈值标签，未测量 SPL） | setHearingProtection（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1855`） / 0x820f64 |
| `2f value` | `[1, 2, 3, 4]` | 1 左、2 右、3 双耳、4 停 | setFindDevice（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1229`） / 0x7b3b94 |

这些函数直接把参数放入 1 字节，没有 i5 那种 1/3/5→0/1/2 的等级换算。值越界时返回空列表，RCommander 的正常路径不会生成控制帧。增益线值 0/1/2 只代表 3 档，建议显示档位 1/2/3 并保留 raw；没有具体增益 dB 对应。听力保护线值 0 关闭，1..6 对应 UI 阈值 75/80/85/90/95/100 dB。提示音线值 1..5 直接对应档位，0 是关闭；0 的 UI 是否显示由型号配置 `DeviceDingLevelPanelItemWidgetModel` 的 `field_23`（`supportClose`）决定（若为 false 则隐藏 0 关闭选项并忽略固件门槛 `field_27`；若为 true 则需固件版本达到 `field_27` 门槛，如 i7 的 304）；Sender 没有独立的 `getDingLevel`，仅随 `getInfo`（2E）批量查询。
#### ANC 轮换

`36 [ANC] [wind] [normal] [transparency]`，四标志 true=1/false=0。线上顺序与型号 UI 列表顺序不同。Sender 参数线序是 r2、r6、r3、r5；公共 UI caller 的列表 0→r3、1→r5、2→r6、3→r2，列表顺序由型号定义（i7 模型 `pp+b218` 按 normal/trans/wind/ANC 定义）。

证据：setAncLine:945（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:945`）；公共 UI caller:798（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_anc_line_panel_item.dart:798`）；型号配置示例:873（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:873`）；模型枚举（`rose_reverse/analysis/blutter/objs.txt:1849`）。另用 `llvm-objdump -D --triple=aarch64 --start-address=0x756cb8 --stop-address=0x756f08` 可恢复 Blutter 省略的 mixin：r3 加入 PP1a298 normal，r5 加入 PP11fb8 trans，r6 加入 PP11fc0 wind，r2 加入 PP11fc8 ANC，互相吻合。

#### 触摸动作

`01 slot action`：左耳单击/双击/三击/长按 slot 为 `01/02/03/04`，右耳为 `11/12/13/14`。动作码由型号的 `IntValueModel` 给出；i7 配置是 0 无、1 播放暂停、2 上一首、3 下一首、4 音量+、5 音量−、6 游戏模式、7 语音助手、8 ANC 循环。Sender 不校验 action，型号配置的取值范围决定客户端可选项；这个 action 码表与 i5 完全不同。
证据：_setTouchKey:2482（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2482`） 与其 8 个 wrapper；值模型（`rose_reverse/analysis/blutter/objs.txt:998`） 及 `pp+b228`。触控动作候选列表分两类：全功能列表 `rsCommonV2TouchList`（9 项，含 8 降噪切换）与无降噪列表 `rsCommonV2TouchListWithoutAnc`（8 项，0..7）；开放式/无 ANC 型号（OpenFeel MK2、Swift 2、Jasmine 等）配置后者。非 TWS（单体/头戴）型号（`CommonUtil.checkIsTws()` 为 false，如 Swift 2）在界面上将左右两组按键重命名为“功能 −”（左耳槽位 `01..04`）与“功能 +”（右耳槽位 `11..14`）。

#### 自定义 EQ

先 `2A 04` 进入 custom，再 `3E b0..b9` 下发十个增益值，App 在正常队列中分两次写入。每个 double 先 `.toInt()` 向 0 截断；正数写绝对值，负数写 `0x80|abs(value)`。它是符号位加绝对值编码，不是有符号补码。例：−6=86、−1=81、0=00、+6=06；−0.5 截断 0 所以写 00。

共享 EQ 面板的频段标签依次 20、100、300、500、1k、2k、3k、5k、8k、15k；纵轴 −6..+6、步长 1。这些是界面标签，没有滤波器 Q/类型参数，也没有实测中心频率证据。Sender 自身未检查列表长度，客户端应按面板的十项构造并限制 −6..+6 整数值，以免产生意外数据。

```text
ff 00 0b 3e 86 83 81 00 01 02 03 04 05 06 e7 aa
```

上例对应 −6、−3、−1、0、1、2、3、4、5、6，seq=00。

证据：setEqCustomData:3656（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3656`）、intToEqList:3735（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3735`）、double 截断:3929（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3929`）；EQ 轴及频段:2404（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2404`）；setEqCustom 后发数据:450（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_session.dart:450`）。

应用曲线：先发送 `2A 04` 进入 custom，再发送 `3E` 加 10 个 gain，两条不同序号的完整帧；最后分别读取 EQ 模式和曲线。命名 preset 的保存/删除发生在 App 本地 session/服务中，不能把本地名字或数据库 id 直接写入耳机报文。原 App 恢复内建模式时有先写 custom 零曲线再恢复目标模式的路径，不代表所有内建模式必须经过零曲线才能切换。

#### 自动 ANC 关闭

自动模式没有独立 ON/OFF 命令，使用正常降噪模式设置：auto on=`09 05`；dynamic on=`09 07`。关闭时共享控件直接发送 fallback 控制 body，并把本地 noise 状态立即写成 ANC；最终状态以 09 通知回包为准。动态自动 ANC 的 fallback 由共享 widget 模型硬编码为 `09 04`（抗风）；普通自动 ANC 的 fallback 来自型号构造时传入的列表，i7 配置也是 `09 04`。客户端若希望关闭后回 ANC，可以显式选择 ANC，这是客户端行为选择，不能称为原 App 发送命令。

证据：动态自动 ANC 模型:4124（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:4124`）；auto 状态判定:48（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_auto_noise_anc_panel_item.dart:48`）；dynamic 状态判定:48（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_auto_noise_anc_dynamic_panel_item.dart:48`）；关闭行为 native 补核（`rose_reverse/analysis/i7_config_ota_findings.md`）。

#### 门槛

固件门槛使用 App 版本文本转十进制整数的比较，不是系统 App 版本，也不是标准 semantic version。版本回包的编码见接收章节；例如回包 V=`01 05 02` 形成文本 `152`，这个整数与其它门槛值在同一比较域里，不是版本号分段。门槛值由型号配置给出，型号文档列出各自取值；共享 Sender 有某方法不代表型号固件接受它。提示音关闭选项门槛：`DeviceDingLevelPanelItemWidgetModel.field_23`（`supportClose`）为 false 时直接隐藏关闭选项（00）并不读 `field_27`（Jasmine、Swift 2、BudsFeel MK2 均为 false）；为 true 时比较固件版本与 `field_27`（i7 为 304）。
### 接收帧与字段

`RsCommonV2Receiver` + `RsCommonV2ReceiveHandler`（handler class id 421）走 `_process` → `CommandUtil.splitData` → 去头尾 → `_processDataContext` → `_processLTV` → 各字段 handler。

#### 帧格式与校验

正常接收格式：

```text
DD HH [L T V...] [L T V...] ... CS AA
```

`HH` 是第二头字节，接收路径没有校验它的具体值或解释其语义。L 为单字节长度，含 T 和 V，不含 L 自身：`L = 1 + len(V)`。LTV 的总长为 `L+1`。这里没有整体长度字段，也未发现事务序号或请求/响应 ACK 编号。

`CS` 是 AA 之前一个字节。`_isValidPacket` 接受两个算法：

```text
CS == sum(frame[0:-2]) & 0xFF
或
CS == sum(frame[2:-2]) & 0xFF
```

第一种含两字节头，第二种仅含 LTV 内容。App 对两个计算分支都调用 `CommandUtil.checkSum`。若两个都不符，整个候选包被拒绝。

`splitData` 用 LTV 的长度跳过各记录，抵达 CS+AA 后校验，故不能简单 `split(AA)`：V 中可以包含 AA。它会扫描输入中的 `dd` 并提取多个完整帧；候选解析失败时从候选 DD 后一个字节继续查找。扫描使用 hex 字符串 indexOf，未见显式字节对齐筛选，第三方实现宜限制在字节边界。

接收 `_hasPrefix` 容许 DD 或 FF，但 `splitData` 和 `_isValidPacket` 只认 DD。因此孤立的 `FF ... AA` 虽会走完整帧分支，最终找不到任何可解析帧。这是原 App 静态路径差异，应记录，不应声称官方已经完整接受 FF 响应。

完整帧解析把前 2 字节、末尾 AA 去掉，保留 CS 在 context 尾部。每个 LTV 按长度取完后，通常仅余 CS；长度小于 2 字节时循环退出，CS 不当成字段。

L=00 有兼容特例：`_parseLTVPacket` 如果发现最终 AA，直接返回从当前帧头到输入尾的字符串，绕过 `_isValidPacket`。然后 `_processDataContext` 把有效 L 算作 `(remaining_hex_chars - 4)/2`，即 `remaining_bytes - 2`；这使当前 T 的 V 覆盖余下内容（除最后 CS）。它因此只能可靠表达该帧余下的最后一个字段，不能继续识别里面的其他 LTV。第三方实现可保留此兼容格式，但应仍自行校验 CS，并记录零长度特例使用情况。

Type FE 在 `_processDataContext` 中直接跳过，既不更新状态也不做 received 标记。尚未从此路径解释它的协议语义。

#### 分包与容错

- 输入先转换为字符串并去空格；实际字符应为小写 hex（比较 DD/DD 等是小写字符串比较）。
- 若输入有 DD/FF 前缀并以 AA 结束，直接走完整帧集合解析，未消费之前暂存 buffer。
- 若输入有前缀但末尾不是 AA，作为首段保存；已有未完成 buffer 则放弃旧 buffer，用新首段替换。
- 无前缀的中间段只有在 buffer 已有 DD/FF 前缀时才追加。
- 无前缀且以 AA 结束的尾段追加到 buffer，递归 `_process`，然后清空 buffer。
- 该 receiver 中未见分包超时或 buffer 容量限制。第三方实现宜在断开时清空、限制长度并对未完成帧设置超时。
- LTV 长度不足或非法长度使当前剩余 context 停止解析；已成功更新的较早字段不回滚。
- `_processLTV` 对单项 handler 异常做 catch，日志后返回 false，下一条 LTV 可以继续。未知 type 原样日志并返回 false。
- receiver 构造时 `Map<RsCommonCommandEnum,bool>` 的各项为 false；字段成功处理后相应项才被标 true。这是已收到字段记录，不是硬件能力广告。一个帧返回 true 表示至少一项被成功处理，不是设置操作的显式 ACK。

#### 字段表

字段宽度中“至少”表示 App 只读取前若干字节而忽略后续。默认十进制解析表示将 hex 文本传给 `int.parse` 而不指定 radix，属于原 App 的处理方式；它对正常 00..09 可用，对 0A 等含字母的值抛异常。

| Type | 字段 | V 格式与语义 | App 行为 |
|---|---|---|---|
| 01 | 触控按键 | `(id,action)` byte pairs；至少含所有 01/02/03/04/11/12/13/14 | 缺 id 或长度不是 pair 对齐则整条字段失败；重复 id 后出现者覆盖 |
| 07 | 触控总开关 | 精确 `00` 开，`01` 关 | 其他值不更新 |
| 08 | 佩戴检测开关 | 精确 `00` 开，`01` 关 | 其他值不更新 |
| 09 | 降噪模式 | `01` ANC、`02` 普通、`03` 通透、`04` 抗风、`05` 自动 ANC、`06` 深度 ANC、`07` 动态 ANC | 映射 controller 3/0/1/2/4/5/6；未知值不更新；型号可未启用深度 ANC UI（i7 未配置） |
| 0C | 左/右/仓电量 | 至少 3 bytes；每 byte 低 7 位电量，bit7 独立 flag | 左/右 flag 为入仓；仓 flag 语义尚不明；FF 为特殊无效值 |
| 0D | 固件版本 | 至少 3 bytes，三个无符号 byte | 每 byte 按 hex 解析为十进制，然后无分隔符拼接成字符串；额外字节忽略 |
| 0E | 游戏模式 | 精确 `01` 开，`00` 关 | 其他值不更新 |
| 2A | EQ 模式 / 低音增强 | 1 byte（模式）或 2 bytes（首字节 05 时次字节为低音增强等级 0..4） | 两个格式的 00/01 映射不同；首字节 05 时次字节存入 `deviceController.field_4f`（详见[EQ](#eq)） |
| 2B | 音频模式 | 通常 1 byte，整个 hex 值按默认十进制 parse | 原始整数交 audioModeReceived，无范围检查 |
| 2C | ANC 等级 | 通常 1 byte，默认十进制 parse | 原值存 controller，无加 1 或 1/3/5 重映射 |
| 2D | 通透等级 | 通常 1 byte，默认十进制 parse | 原值存 controller，无重映射 |
| 2E | 提示音等级 | 通常 1 byte，默认十进制 parse | 原值交 dingLevelReceived，无重映射 |
| 2F | 寻机状态 | 精确 1..6，详见[寻机](#寻机) | 单耳事件只更新对应耳，另一耳沿用旧状态 |
| 32 | 多点连接 | 精确 `01` 开，`00` 关 | 其他值不更新 |
| 36 | ANC 切换循环 | 至少 4 bytes，顺序 ANC/抗风/普通/通透 | 等于 1 表示加入对应 mode；handler 只追加，未见 clear |
| 3E | 自定义 EQ 增益 | 任意长度 byte 数组，sign-magnitude | bit7 为负号、低 7 位绝对值；没有小数缩放 |
| 45 | 增益等级 | 精确 1 byte，范围 0..2 | 超范围不更新 |
| 46 | 听力保护 | 精确 1 byte，范围 0..6 | 超范围不更新 |
| 31 | 提示音语言 | 1 byte，默认十进制 | 0 中文、1 英文，写入 `earLanguage` |

#### 按键

`01/02/03/04` = 左耳单击/双击/三击/长按，`11/12/13/14` = 右耳同四种操作。pair 可以任意排列，receiver 通过 Map 汇总后按此固定序列输出。

i7 配置的 action 值是：0 无功能、1 播放暂停、2 上一曲、3 下一曲、4 音量加、5 音量减、6 游戏模式、7 语音助手、8 降噪切换。receiver 对 action 数字本身不做范围检查。第三方客户端应保留未知 action，不把未知值强行变成“无功能”。

#### EQ

原 App 的 `_handleEQData` 有两个分支：

```text
len(V)==1: 00→controller1, 01→controller0, other→原值
len(V)==2: controller=V[0]，不交换00/01
```
处理 2 byte 且首字节为 05 时，`eqDataReceived`（0xe35f14（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/rs_common_v2/rs_common_v2_receive_handler.dart:5980`））将 V[1]（第 2 字节）解析为低音增强等级并存入 `deviceController.field_4f`，供 UI 响应式显示；型号配置给出档位数（如 BudsFeel MK2 5 档，OpenFeel MK2 3 档）。注意：`analysis/i7_receiver_layouts.json` 将此字段标为 `custom_slot`，系机器分析误标，实际为低音增强（`setEqBassPlus`，BODY `2A 05 value`）。

显示名取型号的 `DeviceEQTypeModel`；i7 配置的 controller 值是 0 POPS / 1 HIFI / 2 ROCK / 3 LIGHT / 6 EAP，5 为 custom，因此按 i7 配置显示，单 byte 00 为 HIFI、单 byte 01 为 POPS；二 byte 00 为 POPS、二 byte 01 为 HIFI。该差异不应根据 TX 命令表自动统一，客户端应该保留值长度和 raw 值。
`3E` 调用 `EqData.fromFormat`，依次将每 byte 解为 `-(b&0x7F)`（bit7=1）或 `b&0x7F`（bit7=0），再转 double。它没有解析槽位编号，没有验证必须 10 个频段，也没有频点表；频点/槽位范围应来自共享面板及型号配置。0x80 为负零，实际 decode 为 0。

#### 电池与仓缓存

receiver 日志明确写左/右最高 bit 为“入仓 L/R”，因此可确定 `left_in_case`、`right_in_case`。仓的 bit7 只是传入 `RBatteryData.field_27` 的 boolean，尚不能独立确定“充电中”。百分比为低 7 位，FF 会得到 127，应当保留为无效/未取得。

`RsCommonV2ReceiveHandler.batteryDataReceived` 总是先更新左右入仓状态。左右百分比只有 1..100 显示，0 或 >100 显示“-”；不要把 App 显示规则误当成 0% 必不可能。

仓电量须 1..100，且至少一个入仓耳不是 FF，才被视为当前有效仓数据：

```text
case_pct in1..100 and
 ((left_in_case and raw_left!=FF) or (right_in_case and raw_right!=FF))
```

满足时写仓百分比/flag 并保存本地缓存，标示非缓存；不满足时 `_showCachedCaseBattery` 读取历史值而非展示当前帧的仓 byte。因此第三方状态最好包含 raw/current/cached/freshness 字段，避免把上次仓电量伪装为刚取得。非 TWS 单体耳机（`CommonUtil.checkIsTws()` 为 false，如 Swift 2）仅配置 `[BatteryType.charge]`，界面直接读取第 3 字节（仓槽）作为整机单体电量展示，不展示左右耳电量。
#### 寻机

- 01 仅将左耳 true；02 仅将右耳 true。
- 03 把左右和 both 三个状态都置 true。
- 04 把三个状态都置 false。
- 05 仅左耳 false；06 仅右耳 false。
- 其余值无更新。

这段是事件更新逻辑，不保证每条通知都描述完整双耳状态。例如先 03 再 05 后，官方 both 状态仍为 true；客户端可以保留原始事件，并以左右当前状态自行计算“双方均在响”，同时标明这是客户端自己的派生值。非 TWS 单体耳机（`CommonUtil.checkIsTws()` 为 false，如 Swift 2）界面仅构建单个寻机按钮，开启发送 `setFindDeviceBoth`（`2f 03`），停止发送 `setFindOff`（`2f 04`），不使用单耳 `01/02` 命令。
#### 模式 05/06/07 语义

`NoiseType` 枚举只有普通/通透/抗风/ANC 四项，不能从它推断 controller 4..6。确认来自对应 UI：自动 ANC 控件比较 `value()==Smi8`，即 controller4；深度 ANC 控件比较 `value()==Smi0xA`，即 controller5，并绑定 setDeepAnc；动态 ANC 控件比较 `value()==Smi0xC`，即 controller6。故接收 05/06/07 确为 auto/deep/dynamic，与发送代码语义一致。型号配置缺少 deep UI 不影响共享 receiver 识别其值。

#### 共享扩展边界

`31` 语言为默认十进制 parse 值后直接写 `earLanguage`；型号可以没有语言 UI（i7 无）。

`34` 要求 12 bytes，前 6 bytes mainMac，后 6 bytes subMac，以原顺序用冒号连接；没有反转，也不是已确定的 left/right。`35` 忽略 V，调用 timeSyncReceived。

`33` 空间音效、`37` 陀螺仪、`38` 空间模式、`39` 头部动作、`3A` 耳自适应、`3B/3C` 拍击控制、`3D` 空间音乐模式、`3F` dongle 固件、`47` 耳塞贴合测试、`48` AI、`49` 扩展固件/独立麦克风、`50..79` 麦克风系列、`E0/E1/E3/E4/40/42` Opus/星闪 SDK、`F0` 主从切换等都属于共享 receiver 的额外分支。没有型号配置或 init/query 覆盖的项目不可认作该型号实际支持。

#### 接收证据索引

完整通知研究与 51 条证据（`rose_reverse/analysis/i7_receiver_findings.md`） 和 机器字段布局（`rose_reverse/analysis/i7_receiver_layouts.json`） 保留各字段具体函数/地址/长度与原 App 更新逻辑。主分派：RsCommonV2Receiver.process:739（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:739`），拆包：CommandUtil.splitData:831（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:831`），校验：CommandUtil._isValidPacket:1553（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:1553`）。所有偏移从 0 开始，区间为半开；LTV 表描述的是 V，不含帧头/长度/类型/CS/AA。

### 客户端状态

- 序号从 0 递增并绕回，属于发送封装；RX 第二头字节 HH 仍是 opaque。不能仅凭发送有 seq 就认定它是可关联的业务 ACK id。
- `write_complete` 与 `state_confirmed` 分开。设置后读取相应字段，等待实际通知比较值；不能用原 App 乐观 UI 状态代替硬件确认。
- 对同字段串行设置/确认，缓存最近回包时间；客户端自行配置查询超时与有限查询重试。100 ms 仅是写间隔。寻机与 OTA 不使用普通查询重试策略。
- 电池状态同时保留 raw、入仓 flag、当前仓数据可用性。历史仓电量可以显示，但必须带 cached/freshness 标识；bit7 仓 flag 只保留 raw，不擅自称为充电中。
- 寻机是局部事件：先双耳启动后仅停左耳，官方 both 标志不会自动清除。客户端可按左右实际标志重新派生 both，需注明这是自己的状态规则。
- 轮换回包应替换当前的 4 个 flag，而不是沿用原 App handler 不断 append 且未见 clear 的列表累积行为。
- 21 类恢复字段中 31 语言、34 地址、35 时间同步等有共享用途说明；未知 type、扩展 SDK 和未启用能力保存 raw。查询 getInfo 含某 ID 不证明它能启用对应硬件功能。
- 新会话清空未发队列、buffer 和所有待确认请求。参考 encoder 是会话实例，从 0 开始；不要把文档里 seq 00 的完整样例反复固定发送。

第三方实现可以比原 App 更严格：按字节边界扫描、限制 buffer、用 AA 候选加 LTV/CS 验证；L00 也检查 CS；截断/非法 LTV 时拒绝候选整帧，而不是保留已更新的较早字段；cycle flag 保留 unknown，而不是只把等于 1 的项加入；FE 标记为 ignored。这些是实现选择，不是设备要求。

## 按型号变化

| 项 | 去哪里查 |
|---|---|
| 识别前缀、SPP 是否允许、控制与 OTA 的 SPP UUID、CMIIT | model_registry.json（`rose_reverse/analysis/model_registry.json`） |
| 配置类 cid、覆写的方法 | Blutter 配置类文件头部的 `// class id:` 与方法列表 |
| 初始化目标 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv`） 第 `0x1000 + 0xd8ba + cid` 项 |
| 开放的控件（型号能力） | 配置类中 `Allocate*WidgetModel` 的调用；v2_capabilities.json（`rose_reverse/analysis/v2_capabilities.json`） 的 `capabilities` 已按控件汇总并附行号 |
| 控件取值 | `v2_capabilities.json`：`NoiseType`、`DeviceAudioType`、`DeviceEQTypeModel`、`TouchCtlType`、`IntValueModel`（触控动作）、`BatteryType` / `battery_types`、`noise_levels`（ANC / 通透档位） |
| 固件门槛 | 自定义 EQ、OTA 的门槛已收入 `v2_capabilities.json`；其他控件的门槛写在控件构造参数里，是 Dart Smi，需右移 1 位（见 v2_capabilities.md（`rose_reverse/analysis/v2_capabilities.md`）） |

analysis/model_brief.py（`rose_reverse/analysis/model_brief.py`） 以型号名为参数，把上表各项和下面的控件对应关系汇总成一份简报，并列出索引回答不了的缺口。

## 控件与命令

控件模型类定义在 `roselink/rose_bluetooth/config/widget_model.dart`，对应界面在 `roselink/modules/device_control/widget`，全族共用，经 `RCommander` 调用当前配置的 Sender；取值由该型号的枚举决定。`model_brief.py` 读取本表：第三列只写方法名；写“结构”表示容器或展示控件，写“范围外”表示 OTA 或云端业务入口，写“—”表示尚未找到对应的 Sender 方法。

| 控件类 | 功能 | Sender 方法 | 说明 |
|---|---|---|---|
| `RDeviceComponentNoiseBarWidgetModel` | 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeTrans`、`setNoiseModeWind` | 可选模式取 `NoiseType` |
| `RDeviceComponentOnlyNoiseBarWidgetModel` | 降噪开关 | `setNoiseModeAnc`、`setNoiseModeNormal` | 按方法名推断，i7 没有该控件 |
| `DeviceAutoNoiseAncPanelItemWidgetModel` | 自动 ANC | `setAncAutoOn` | 关闭时发送型号配置的 fallback；i7 为 `09 04`（抗风），见[自动 ANC 关闭](#自动-anc-关闭) |
| `DeviceAutoNoiseAncDynamicPanelItemWidgetModel` | 动态 ANC | `setAncAutoDynamicOn` | 关闭时共享控件硬编码发送 `09 04`，见[自动 ANC 关闭](#自动-anc-关闭) |
| `DeviceLimitNoiseAncPanelItemWidgetModel` | 深度 ANC | `setDeepAncOn` | 接收 `09 06` 已由界面互证 |
| `DeviceAncLinePanelItemWidgetModel` | 降噪轮换 | `setAncLine` | 线序 ANC / 抗风 / 普通 / 通透，与界面顺序不同 |
| `RDeviceTouchSettingItemWidgetModel` | 触控 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong`、`setTouchOn`、`setTouchOff` | 槽位取 `TouchCtlType`，动作取 `IntValueModel`；目录中合并记为 `touch_key` |
| `DeviceGamePanelItemWidgetModel` | 游戏模式 | `setGameModeOn`、`setGameModeOff` | `01` 开 |
| `DeviceMulDevicePanelItemWidgetModel` | 多点连接 | `setConnectMulOn`、`setConnectMulOff` | 独立命令，不与音频模式耦合 |
| `DeviceAudioPanelItemWidgetModel` | 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLc3`、`setAudioAptx`、`setAudioAptxHd`、`setAudioAptxAdaptive` | 可选项取 `DeviceAudioType`，其线值即 `2B` 参数 |
| `DeviceDingLevelPanelItemWidgetModel` | 提示音 | `setDingLevel` | 0（静音）在 i7 上有固件门槛 304 |
| `DeviceDetectionSwitchPanelItemWidgetModel` | 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | `00` 开 |
| `RDeviceFindItemWidgetModel` | 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | 接收是事件，不是完整状态 |
| `DeviceEqPanelItemWidgetModel` | EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight`、`setEqEap`、`setEqGame1`、`setEqGame2`、`setEqGame3`、`setEqGame4`、`setEqGame5` | 预设取 `DeviceEQTypeModel`；`hasCustom` 时另有自定义曲线（`setEqCustom`、`setEqCustomData`、`getCustomEq`） |
| `RDeviceEqBassPlusItemWidgetModel` | 低音增强 | `setEqBassPlus` | Sender 接受 0–4 |
| `RDeviceGainLevelItemWidgetModel` | 增益 | `setGainLevel`、`getGainLevel` | i7 门槛 213 |
| `RDeviceHearingProtectionItemWidgetModel` | 听力保护 | `setHearingProtection` | i7 门槛 213 |
| `RDeviceSpatialAudioBarWidgetModel` | 空间音频 | `setSpatialAudioSwitchOn`、`setSpatialAudioSwitchOff`、`setSpatialAudioModeHead`、`setSpatialAudioModeFixed`、`setSpatialAudioModeOff`、`setSpatialAudioMusicModeMusic`、`setSpatialAudioMusicModeMovie`、`setSpatialAudioMusicModeGame`、`setSpatialAudioMusicModeTV`、`setHeadMoveModeOn`、`setHeadMoveModeOff` | 按方法名推断，控件实际调用哪些未核对 |
| `DeviceEarAdaptivePanelItemWidgetModel` | 耳道自适应 | `setEarAdaptiveOn`、`setEarAdaptiveOff`、`setEarTipFitTest` | 按方法名推断 |
| `RDeviceTapControlItemWidgetModel` | 轻拍控制 | `setTapOn`、`setTapOff`、`setLeftTapTwo`、`setLeftTapThree`、`setRightTapTwo`、`setRightTapThree` | 按方法名推断 |
| `RDeviceLangItemWidgetModel` | 提示音语言 | `setEarLanguageChinese`、`setEarLanguageEnglish`、`getEarLanguage` | 中文 `31 00`、英文 `31 01`，查询 `fa 31`，回包 Tag 31 |
| `DeviceIndependentMicModePanelItemWidgetModel` | 独立麦克风 | `setIndependentMicModeOn`、`setIndependentMicModeOff`、`setIndependentMicModeActivateOn`、`setIndependentMicModeActivateOff` | 按方法名推断 |
| `RDeviceRecordItemWidgetModel` | 录音 | — | Sender 中没有录音方法 |
| `RDevicePersonalizationItemWidgetModel` | 个性化 | — | 尚未分析 |
| `DeviceOtaPanelItemWidgetModel` | OTA | 范围外 | OTA 是独立协议，路由见 `v2_capabilities.json` 的 `ota` |
| `RDeviceStarburstBusinessEntryItemWidgetModel` | 星芒业务入口 | 范围外 | 云端业务，不是耳机控制协议 |
| `RDeviceComponentDisplayBarWidgetModel` | 电量展示 | 结构 | 电量槽取 `battery_types` |
| `DeviceOptionPanelWidgetModel` | 面板容器 | 结构 | |
| `RDeviceMoreItemWidgetModel` | 更多设置入口 | 结构 | |

“按方法名推断”的行只说明 Sender 有名称相符的方法，控件是否调用、调用哪些、参数如何取值，都要在控件类中核对后才能写进型号文档。

## 全族已知的坑

- `getInfo` 整帧 35 字节，超过默认 ATT MTU 23 的单次写入容量（[发送帧](#发送帧)）。
- 接收 `2A` 为单字节时 `00` / `01` 与控制器值互换，双字节时不换；显示名称取该型号的 `DeviceEQTypeModel`（[EQ](#eq)）。
- 接收 `2B`–`2E` 把十六进制文本按十进制解析，`0A` 及以上会解析失败（[字段表](#字段表)）。
- 极性不统一：`07`、`08` 是 `00` 开；`0E`、`32` 是 `01` 开（[设置命令](#设置命令)）。
- Sender 收到越界参数时返回空列表，不会生成帧（[动态参数命令](#动态参数命令)）。
- `0C` 中的仓电量只有在至少一只入仓耳机读数有效时才是当前值，否则 App 显示缓存值（[电池与仓缓存](#电池与仓缓存)）。
- `getInfo` 查询包含 29 个 ID，只能说明 Sender 会问，不能据此给型号增加能力（[初始化与查询](#初始化与查询)）。
- 开发者调试过滤：`DeviceConfig`（0xa0d2c4（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:1061`））在未开启调试模式（`isDeviceDebug` 与 `agreeApp`）时直接过滤丢弃 `RS_COMMON_V2`（前缀 `0a0b0a0c`）设备并输出“未开启开发者调试模式，基础协议/非正式版设备被过滤”，此前缀不是未识别设备的通用兜底。
- 提示音控件关闭选项与固件门槛：`DeviceDingLevelPanelItemWidgetModel` 的 `field_23`（`supportClose`）若为 false（Jasmine、Swift 2、BudsFeel MK2 等）则完全隐藏 00 关闭选项并忽略固件门槛 `field_27`；仅当 `field_23=true` 时才按 `field_27` 进行固件门槛校验（i7 为 304）。
- 接收 `2A` 双字节首字节 05 语义修正：当 Tag 2A 为双字节且首字节为 05 时，次字节为低音增强等级（`setEqBassPlus`），写入 `deviceController.field_4f`；`analysis/i7_receiver_layouts.json` 将其误标为 `custom_slot`，在此予以勘误。

## 维护

整理型号时发现的新的全族事实，写回本文对应的表格或列表，并在“细节与证据”中注明出处。控件与命令的对应关系只在本文维护，`model_brief.py` 从这里读取。
