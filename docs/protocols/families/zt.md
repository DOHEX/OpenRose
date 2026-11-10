# ZT 协议族

RoseLink 3.8.0（versionCode 5310，包名 `cn.ikaile.ruoshui.client`，APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）中有 3 个耳机配置使用 ZT：EarFree-i3、EarFeel-i5、HeadFeel。三者在构造时都实例化 `ZTReceiver` 和 `ZtSender` 的子类，共用广播识别、`08 EE` 发送帧、初始化分派、接收分派与大部分设置命令。i5 已在 Linux 上完成单副设备的 BLE 连接和 13 条初始化读取实机验证（实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`）、读取记录（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl`））；i3、HeadFeel 以及全部设置写入都没有实机证据。除特别注明外，字节级结论来自 RoseLink 3.8.0 的 ARM64 Flutter AOT 静态代码；常规控制已整理到发现、GATT、发送帧、参数、通知解析和状态同步层，可据此编写第三方控制客户端。

本文收录全族共用的逐字节细节和客户端实现建议；型号特有的取值、控件与接收字段差异见各型号文档：[EarFree-i3](../models/earfree-i3-protocol.md)、[EarFeel-i5](../models/earfeel-i5-protocol.md)、[HeadFeel](../models/headfeel-protocol.md)。

## 全族共用

三个配置的构造都分配 `ZTReceiver`；发送器按型号分为 `Zt219Sender`（i3）、`Zt229Sender`（i5）、`Zt231Sender`（HeadFeel）。接收处理由 ReceiveHandler 完成：i3 用 `Zt219ReceiveHandler`，它继承 `EarfreeI5ReceiveHandler` 并只增加 `hideSearchReceived`；i5 用 `EarfreeI5ReceiveHandler`；HeadFeel 用 `HeadfreeReceiveHandler`。因此同一 Tag 在不同型号上的字段处理可能不同，尤其电量、提示音等级、增益、自定义 EQ 和多点名称列表。

| 环节 | 结论 | 细节与证据 |
|---|---|---|
| 广播识别 | 取 BLE 广播的 `manufacturerData`：company ID 按小端排序拼接，再拼接各条 payload；用规范化 hex 的前 12、8、6 位依次查型号表，最长前缀优先。i3 使用 6 位前缀，i5/HeadFeel 使用 12 位前缀 | [广播识别](#广播识别)、identifyDevice（0x90a2ac）（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`） |
| 广播字段 | 未发现 ZT 专用的扩展广播类型字段；`BaseDeviceConfig.getInfoFromRawData` 的 zt 分支会把 rawData 片段经 `to2` 格式化成地址样式并写入 `field_4b`/`field_4f`，但用途和与经典地址的对应关系未证明。连接应使用系统扫描对象的 remote id/address | getInfoFromRawData（0x748ddc）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:30`）、[广播识别](#广播识别) |
| 控制通道选择 | i3/i5 配置 `sppSupport="false"`，只用 BLE；HeadFeel 配置允许 SPP，控制 SPP UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。是否实际走 SPP 还取决于系统是否暴露该 SPP 服务和连接管理器的选择 | i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:634`）、i5 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:773`）、HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:742`） |
| 控制特征 | 默认 BLE 控制服务 `011bf5da-0000-1000-8000-00805f9b34fb`，写特征 `00007777-0000-1000-8000-00805f9b34fb`，通知特征 `00008888-0000-1000-8000-00805f9b34fb`。HeadFeel 的 SPP 配置不改变这组 BLE 特征 | normalBleServiceConfig（0x74990c）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、[默认传输](#默认传输) |
| 发送帧 | `08 EE 00 00 00 \| group \| operation \| 总长度 LE16 \| payload \| 和校验`；总长度包含最后的校验字节，校验为前面所有字节之和的低 8 位。`ZtSender.transform` 原样返回，不再封装 | transform（0xdfaaf4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2926`）、checkSum（0x795b24）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:189`）、[发送帧](#发送帧) |
| 初始化 | `GlobalController` 经 GDT+0xd8ba 按配置 cid 分派到各型号 `sendInitCommands`；没有全族统一的初始化函数。分派表项：HeadFeel 60078、i5 60079、i3 60080 | 分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60078`）、i3 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:649`）、i5 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:788`）、HeadFeel 初始化（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:761`） |
| 命令编码 | 全族共用的设置/读取方法在 `ZtSender`；i3 覆盖噪声模式与 HideSearch，i5 覆盖寻机停止、ANC/通透等级与提示音等级，HeadFeel 覆盖语言、增益、音频、寻机停止、提示音、自定义 EQ、电量与多点名称列表 | ZtSender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`）、Zt219Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4804`）、Zt229Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4347`）、Zt231Sender（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3002`） |
| 接收解析 | `ZTReceiver.process` 把通知转成无空格 hex，取 `hex[10:14]`（byte5/6）作 Tag，payload 从 byte9 开始；未验证 RX 固定前缀、长度、末字节校验或 CRC。已恢复的是字段偏移解析，不是完整帧校验器 | process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`）、[分派和验证边界](#分派和验证边界) |
| 客户端状态建议 | 底层 write 完成不等于设备确认；设置后串行读取对应状态并比较字段。没有业务事务 id，不要用“下一个回包”机械匹配；未知枚举保留 raw，不自动降级为关 | [客户端状态同步与异常处理](#客户端状态同步与异常处理) |

### 默认传输

型号文档的“传输”表与本表相同的行写“同族默认”；SPP 两行按型号变化，型号文档只给出本型号的取值。

| 项 | 值 | 状态 | 出处 |
|---|---|---|---|
| BLE 服务 | `011bf5da-0000-1000-8000-00805f9b34fb` | 静态 | normalBleServiceConfig（0x74990c）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）；i5 实机发现服务见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`） |
| 写特征 | `00007777-0000-1000-8000-00805f9b34fb` | 静态 | 同上；i5 实机发现服务见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`） |
| 通知特征 | `00008888-0000-1000-8000-00805f9b34fb` | 静态 | 同上；i5 实机发现服务见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`） |
| SPP 是否允许 | 默认否（i3/i5，`sppSupport="false"`）；HeadFeel 为是（`"true"`） | 静态 | i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:634`）、i5 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:773`）、HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:742`） |
| 控制 SPP UUID | 默认未配置（i3/i5）；HeadFeel 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 静态 | HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:744`） |
| 广播中的经典地址 | 未解析；连接使用系统扫描对象的 remote id/address | 未知 | [广播识别](#广播识别)、[连接与写入](#连接与写入) |
| 写入间隔 | 正常 100 ms；OHOS 分支 350 ms | 静态 | ConnectManager.write（0x67fa58）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:386`）、RQueueMamager.send（0x67fb8c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/r_queue_mamager.dart:10`） |

100/350 ms 是 App 公共发送队列参数，不是设备回复超时，也不是型号固件要求；i3/HeadFeel 未单独核对。i5 的普通控制报文最长 14 字节（降噪模式和轮换列表）；HeadFeel 的自定义 EQ 数据帧更长（22 字节），不能假设全族最大 14 字节。App 公共 BLE 层尝试请求 MTU 512，协商结果由设备和系统决定。UUID 由配置里的 32/16 位值 `011bf5da`、`7777`、`8888` 经 Bluetooth 标准基 UUID 扩展，不能把 32 位服务值截成 16 位；匹配时忽略大小写（Guid.str128（0xa257ec）（`rose_reverse/analysis/blutter/asm/flutter_blue_plus_platform_interface/src/guid.dart:322`））。写特征支持 `writeWithoutResponse` 时使用无响应写，否则使用有响应写；GATT 写响应不等同业务状态回包。OTA 是独立通道（i3/i5 为 WQ，HeadFeel 为 BES），不在普通控制特征上传输。

写入属性与 MTU 证据：属性决定写入方式（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:5921`）、writeWithoutResponse 字段（`rose_reverse/analysis/blutter/asm/flutter_blue_plus/flutter_blue_plus.dart:12072`）、MTU 请求值 512（`rose_reverse/analysis/blutter/asm/flutter_blue_plus/flutter_blue_plus.dart:11300`）。

### 广播识别

App 使用 BLE 广播的 `manufacturerData`，不是按可修改的设备名识别。规范化算法如下，结果转小写；将前 12、8、6 个 hex 字符依次与设备表匹配，最长前缀优先：

```python
ids = sorted(manufacturer_data)
raw = (b"".join(i.to_bytes(2, "little") for i in ids)
       + b"".join(bytes(manufacturer_data[i]) for i in ids))
# 取 raw.hex() 的前 12/8/6 位依次匹配型号表，最长前缀优先
```

单个 manufacturer 项时，company id `0x5352` 序列化为 `52 53`，该项 payload 前四字节为 `00 01 00 01`（i5 前缀 `525300010001` 的匹配例）。这是按 App 标识和规范化算法推导的匹配例，**不是实机抓到的广播**。存在多项时 App 将所有 company id 放在所有 payload 之前，不能改为逐项交错拼接。保存系统扫描对象的 remote id/address 用于连接；不要用型号前缀或广播中的其他疑似 MAC 字段替换它。

发现证据：manufacturerData 规范化（0xa0daa8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/scanner/r_ble_scanner.dart:3737`）、identifyDevice 前缀匹配（0x90a2ac）（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`）。

### 连接与写入

1. 让耳机保持唤醒，扫描并匹配广播；用户指定系统设备标识时，仍须在连接后核对服务与特征。
2. 连接 BLE，发现服务，取得本服务内的 `7777`、`8888` 特征。
3. **先订阅 `8888` 通知并完成 CCCD 设置，再发送查询**。底层系统需要配对时通过系统配对流程完成；没有发现 ZT 自定义密码、会话 token 或加密握手（i5 实机连接即完成），不能据此断言所有固件都不要求系统配对。
4. 以单一队列按型号配置的初始化顺序发送查询。原 App 在本地 write 完成后留 **100 ms** 间隔；OHOS 特定分支为 350 ms。没有统一业务 ACK 等待和自动重发。i5 的 13 条查询见 [初始化和读取](#初始化和读取)；i3 为 8 条、HeadFeel 为 17 条，以各型号文档为准。
5. 将通知应用到逐字段状态缓存。BLE 已连接、订阅已成功、协议已收到可解析回包、各字段已加载，应分别表示。
6. 断连后清空待发命令和查询等待，重连重新发现/订阅并读取状态。重新查询即可建立当前状态，无须复用旧会话。

原 App 控制队列是 FIFO：`await write → gap → next`。所有查询和设置共用队列，常规控制按一条完整帧一次 GATT write 发送。**100 ms 是发送间隔，不是设备回复超时**。在这条接收路径中没有看到跨通知重组或按接收长度拼包；先按一次通知解析一次报文，若实机出现拆包，应先抓包确认接收帧边界。

**Linux 双模连接实测补充（i5，2026-10-18）**：系统已有经典蓝牙配对时，第一次连接可能只建立经典链路，表现为“已连接”但没有 GATT 服务。客户端在空服务表或明确的 GATT `NotConnected` 错误下补连 BLE，并等待控制特征发布；首条只读查询用于进一步确认链路。指定地址时可复用已配对、型号匹配的 i5 缓存对象；正常 BLE 连接不重复补连。配对记录不需删除，UUID 也不需修改。实际记录与验证范围见 实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`）。

连接/通知证据：订阅通知（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:8709`）、通知字节转 hex 后送 Receiver（0xe55164）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:8997`）、发送队列笔记（`rose_reverse/analysis/i5_queue_protocol.md`）。

### 发送帧

本节所有字节偏移从 **0** 开始，区间为左闭右开；hex 以两个字符表示一个字节。`group/op` 是按报文位置命名，未确认厂商字段名称。

| 偏移 | 字节数 | 发送值/含义 |
|---|---:|---|
| 0 | 1 | 固定 `08` |
| 1 | 1 | 固定 `EE` |
| 2–4 | 3 | 固定 `00 00 00`；语义未确定，保持原值 |
| 5 | 1 | group |
| 6 | 1 | operation |
| 7–8 | 2 | **全帧总字节数，小端**，包含最后的校验字节 |
| 9..N−2 | 可变 | payload |
| N−1 | 1 | 前面所有字节之和的低 8 位 |

```python
body = bytes.fromhex("08 ee 00 00 00") + bytes([group, op])
body += (10 + len(payload)).to_bytes(2, "little") + payload
packet = body + bytes([sum(body) & 0xff])
```

无 payload 查询为 10 字节；单字节设置为 11 字节；寻机 12 字节；触摸绑定 13 字节；i5/HeadFeel 的降噪模式和轮换列表为 14 字节，i3 的降噪为 13 字节；HeadFeel 的自定义 EQ 数据帧为 22 字节（BODY `02 82` + 10 字节，见 [HeadFeel 文档](../models/headfeel-protocol.md#设置命令)）。`ZtSender.transform` 原样返回，不再增加其他封装。这里只确认 **TX 校验**，不得把公式未经验证地套到 RX 或 OTA。

证据：transform（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2926`）、CommandUtil.checkSum（0x795b24）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:189`）；固定帧逐字节提取见 原始命令常量（`rose_reverse/analysis/i5_fixed_sender_commands.json`）。

### 初始化和读取

`GlobalController` 经 GDT+0xd8ba 按配置 cid 分派到各型号 `sendInitCommands`；没有全族统一的初始化函数。分派表项：HeadFeel 60078、i5 60079、i3 60080（分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60078`））。各型号初始化都是按配置顺序发送一组读取查询，共同查询的帧相同，型号只是选用不同的子集和顺序。

下表是 i5 的 13 条初始化顺序（唯一完成实机读回的型号，作为 worked example）；同一组查询帧由各型号按自己的 `sendInitCommands` 选用，i3/HeadFeel 的同名查询只有静态证据。电量随 `getInfo` 返回，无须套用 HeadFeel 的 `getBattery=0103`。

| 次序 | App 方法 | 完整 TX hex | 读回 Tag | 使用型号 | 状态 | 出处 |
|---:|---|---|---|---|---|---|
| 1 | `getInfo` | `08 EE 00 00 00 01 01 0A 00 02` | `0101` | 全族 | 实机 | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2861`）、实机 L3（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:3`） |
| 2 | `getAnc` | `08 EE 00 00 00 06 02 0A 00 08` | `0602` | 全族 | 实机 | getAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:801`）、实机 L6（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:6`） |
| 3 | `getEq` | `08 EE 00 00 00 02 01 0A 00 03` | `0201` | 全族 | 实机 | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2731`）、实机 L8（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:8`） |
| 4 | `getGameMode` | `08 EE 00 00 00 06 03 0A 00 09` | `0603` | 全族 | 实机 | getGameMode（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2392`）、实机 L10（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:10`） |
| 5 | `getAudioLdac` | `08 EE 00 00 00 08 01 0A 00 09` | `0801` | 全族 | 实机 | getAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2151`）、实机 L12（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:12`） |
| 6 | `getVersion` | `08 EE 00 00 00 0D 01 0A 00 0E` | `0d01` | i5、HeadFeel | 实机 | getVersion（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2796`）、实机 L15（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:15`） |
| 7 | `getKey` | `08 EE 00 00 00 04 01 0A 00 05` | `0401` | 全族 | 实机 | getKey（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1936`）、实机 L17（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:17`） |
| 8 | `getTouch` | `08 EE 00 00 00 0A 01 0A 00 0B` | `0a01` | 全族 | 实机 | getTouch（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1407`）、实机 L19（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:19`） |
| 9 | `getAncList` | `08 EE 00 00 00 06 05 0A 00 0B` | `0605` | i5、HeadFeel | 实机 | getAncList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:866`）、实机 L22（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:22`） |
| 10 | `getEarDetection` | `08 EE 00 00 00 0C 01 0A 00 0D` | `0c01` | i5 | 实机 | getEarDetection（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1206`）、实机 L23（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:23`） |
| 11 | `getAncLevel` | `08 EE 00 00 00 06 07 0A 00 0D` | `0607` | i5 | 实机 | getAncLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4598`）、实机 L25（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:25`） |
| 12 | `getAncTransLevel` | `08 EE 00 00 00 06 04 0A 00 0A` | `0604` | i5、HeadFeel | 实机 | getAncTransLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4458`）、实机 L27（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:27`） |
| 13 | `getDingLevel` | `08 EE 00 00 00 0E 03 0A 00 11` | `0e03` | i5、HeadFeel | 实机 | getDingLevel（Zt229Sender）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4736`）、实机 L29（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:29`） |

`getConnectMul` 在 i5 的父类中调用 `getAudioLdac`，仍发送 `08 01`，不是 HeadFeel 的独立 `08 02` 查询。i3 不发送上表的 `getVersion`、`getAncList`、`getEarDetection`、`getAncLevel`、`getAncTransLevel`、`getDingLevel`，另有 `getHideSearch`；HeadFeel 不发送 `getEarDetection`、`getAncLevel`，另有 `getCustomEq`、`getGainLevel`、`getBattery`、`getConnectMul`、`getMulDeviceNameList` 等查询，其初始化的 `getLoseLoss`（0x8e29d4（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:3883`））经选择器 `0xf495` 与 `Zt231Sender`（cid 402）分派至 GDT 表项 67111，复用 `ZtSender::getAudioLdac`（0xc6e364（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3749`））的代码，实际发送 `08 EE 00 00 00 08 01 0A 00 09`（读回 Tag `0801`）。初始化顺序证据：i5 sendInitCommands（0x8e25cc）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:788`）、i3 sendInitCommands（0x8e2498）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:649`）、HeadFeel sendInitCommands（0x8e27b0）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:761`）、分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60078`）。

### 设置命令与参数

BODY 从 group 开始，长度按[发送帧](#发送帧)规则自动生成。设置返回在同一 `8888` 通知通道；已证实的状态分派使用读取操作码，例如 `0602`，**不要自动把收到的 op 清除 `0x80` 后当成已确认状态**。

下表是全族 `ZtSender` 的设置帧模板；“型号”列说明该命令在哪些型号上存在或使用，具体取值子集以各型号文档的“设置命令”表为准。i5 的设置写入没有实机证据，状态均为静态。

| 功能 | group/op | payload | 字节数 | 读取确认 | 型号 | 状态 | 证据 |
|---|---|---|---:|---|---|---|---|
| 降噪模式 | `06 82` | ANC `01 00 00 00`；普通 `00 01 00 00`；抗风 `00 00 01 00`；通透 `00 00 00 01` | 14 | `getAnc` | i5、HeadFeel（i3 为 3 字节） | 静态 | 模式四分支（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:501`） |
| EQ | `02 81` | `00` POP，`01` HIFI，`02` ROCK，`03` LIGHT | 11 | `getEq` | 全族（LIGHT 仅 i5） | 静态 | EQ（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2457`） |
| 游戏模式 | `06 83` | `00` 开，`01` 关 | 11 | `getGameMode` | 全族 | 静态 | 游戏开关（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2256`） |
| 佩戴检测功能 | `0C 81` | `00` 开，`01` 关 | 11 | `getEarDetection` | i5（HeadFeel 仅接收） | 静态 | 检测开关（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1070`） |
| 触摸功能 | `0A 81` | `00` 开，`01` 关 | 11 | `getTouch` | 全族 | 静态 | 触摸开关（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1271`） |
| 音频模式 / 多点 | `08 81` | `00` AAC/SBC 且多点开；`01` LDAC 且多点关 | 11 | `getAudioLdac` | i3、i5（HeadFeel 用 `08 82`） | 静态 | AAC 路径（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2131`）、关多点→LDAC（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2216`） |
| ANC 等级 | `06 87` | `00/01/02` → UI `1/3/5` | 11 | `getAncLevel` | i5 | 静态 | Zt229Sender::setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523`） |
| 通透等级 | `06 84` | `00/01/02` → UI `1/3/5` | 11 | `getAncTransLevel` | i5、HeadFeel（传参未核） | 静态 | Zt229Sender::setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4383`） |
| 提示音音量 | `0E 83` | `00..04` → UI `1..5` | 11 | `getDingLevel` | i5（HeadFeel 用 `0E 03`） | 静态 | Zt229Sender::setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4663`） |
| 触摸绑定 | `04 81` | `[side][gesture][action]`，见下表 | 13 | `getKey` | 全族（手势/动作子集不同） | 静态 | 八种绑定方法（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:1472`） |
| 降噪轮换列表 | `06 85` | `[普通][通透][抗风][ANC]`，每项 `00/01` | 14 | `getAncList` | i5、HeadFeel | 静态 | setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:10`） |
| 寻机 | `0E 82` | 左/原 App 双耳 `00 01`；右 `01 01`；停止 `03 00` | 12 | `0e02` 通知，无独立查询 | i5、HeadFeel（HeadFeel 停止 `0E 02`） | 静态 | 双耳别名（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:120`）、左耳（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:208`）、i5 停止（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4349`） |

#### 触摸绑定

`side=00` 左、`01` 右；`gesture=00` 单击、`01` 双击、`02` 三击、`03` 长按。长按只有 i5 的触控列表提供；HeadFeel 两键没有长按，i3 只有单/双/三击。动作值是共享编码，各型号提供的子集不同：

| action | 功能 | 参考代码名称 |
|---|---|---|
| `00` | 音量增加 | `volume_up` |
| `01` | 音量降低 | `volume_down` |
| `02` | 上一曲 | `previous_track` |
| `03` | 下一曲 | `next_track` |
| `04` | 语音助手 | `voice_assistant` |
| `05` | 播放/暂停 | `play_pause` |
| `06` | 无功能 | `none` |
| `07` | 降噪模式轮换 | `noise_cycle` |

这是**设置触摸动作的绑定**，不是让客户端立即播放/调音量的命令。i3 没有 `04` 语音助手和 `07` ANC 轮换；HeadFeel 的两键经 `_RTouchCtlListState`（0xa52888/0xa528e0（`rose_reverse/analysis/blutter/asm/roselink/screen/common/component/touch.dart:4599`））映射：电源键（`click-button-power`）由 `_buildLeftItem` 映射至 `side=00`（调用 `setLeftTouch*`），ANC 键（`ANC`）由 `_buildRightItem` 映射至 `side=01`（调用 `setRightTouch*`）。动作列表证据：i5 动作列表（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/touch_info.dart:60`）、HeadFeel 触控配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:589`）。

#### 音频、多点和寻机

音频与多点耦合由 i5 的 UI 配置明确规定：`useAudioModeNotMulDeviceMode=true`，多点对应音频值 `0`，非多点对应 `1`。`setAudioAacSbc` 调用父类 `setConnectMulOn`；父类 `setConnectMulOff` 直接调用 AOT 中标作 `Zt231Sender.setAudioLdac` 的共享函数，生成 `01`。这里的类名是去重后的静态标签，不代表该命令只适用 HeadFeel。没有单独解析 `RCommander.setAudioLdac` 的 GDT 项；这里使用已确认的继承调用路径和具体帧，不把它说成动态运行验证。证据：i5 多点配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:221`）、父类关闭多点（0xc77d10）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:2216`）、共享 LDAC 数据包（0xc6e104）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3749`）。

`setFindDeviceBoth` 原样调用 `setFindDeviceLeft`。接收器虽能解析方向 `02` 的双耳状态，**这不能证明发送 `02 01` 可启动双耳寻机**。参考代码复现原 App 的别名，不虚构新的双耳启动命令。i5 停止命令的方向值是 `03`；接收器处理的方向值为 `00/01/02`，不能假定停止回包也一定是 `03 00`。HeadFeel 的停止改用 `0E 02`，见[全族已知的坑](#全族已知的坑)。

#### 合成 TX 示例

以下为合成 TX 示例（按发送帧规则构造，取自 App 静态帧值）；ANC UI 5 级和停止寻机是 i5 的线值：

```text
# ANC
08 EE 00 00 00 06 82 0E 00 01 00 00 00 8D
# LDAC（同时关闭多点）
08 EE 00 00 00 08 81 0B 00 01 8B
# ANC UI 5级
08 EE 00 00 00 06 87 0B 00 02 90
# 左耳双击 → 播放/暂停
08 EE 00 00 00 04 81 0D 00 00 01 05 8E
# 仅普通、ANC 参加轮换
08 EE 00 00 00 06 85 0E 00 01 00 00 01 91
# 停止寻机
08 EE 00 00 00 0E 82 0C 00 03 00 95
```

### 通知解析

#### 分派和验证边界

接收器将一帧转为无空格 hex；使用 `hex[10:14]`，即 **byte5/6**，作命令键。普通 payload 从 byte9 开始。**已恢复的 RX 是字段偏移解析，不是已证明的完整帧校验器**：该路径未验证 RX 固定前缀、byte7/8 长度、末字节求和或 CRC。不要让 TX 构帧规则筛掉可能合法的 RX，也不要将这些未知字段当成序号或 ACK。

i5 2026-10-18 的实机读取验证了 13 个 Tag 的分派和字段解析（读取记录（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl`））；其余分支只有静态证据。收到通知后保存原始字节、命令、时间，再按下表检查最小长度并解码。表中的最小长度是为安全读取字段而定义，不是已证明的设备帧总长度；长度未知、命令未知、枚举未知时保存原始数据并报告 unknown。原 App 有些分支会默认显示“关闭”；第三方实现应保留 unknown，避免把异常包当成可靠状态。

| 命令键 | 字段读取 | 解释 | 最小解析字节数 |
|---|---|---|---:|
| `0101` | byte11、12、15 | 左、右、盒电量；完整布局见[电量](#电量) | 16 |
| `0d01` | byte9、10、11 **低 nibble** | 各自按 hex 转整数，再将十进制文本无分隔拼接；byte12 不参与最终版本文本 | 13（原 App 长度保护） |
| `0201` | byte9 **低 nibble** | `0/1/2/3` → POP/HIFI/ROCK/LIGHT | 10 |
| `0401` | byte9..16 **低 nibble** | 左单/双/三/长，然后右单/双/三/长；动作值见[触摸绑定](#触摸绑定) | 17 |
| `0602` | byte9 到通知结尾 | 整段精确匹配，见[降噪模式尾部的精确匹配](#降噪模式尾部的精确匹配) | 14 |
| `0603` | byte9 **低 nibble** | `0` 游戏开，`1` 关 | 10 |
| `0604` | byte9 **完整字节** | `0/1/2` 通透 UI `1/3/5` | 10 |
| `0605` | byte9..12 **低 nibble** | 普通/通透/抗风/ANC 是否参加轮换 | 13 |
| `0607` | byte9 **完整字节** | `0/1/2` ANC UI `1/3/5` | 10 |
| `0801` | byte9 **低 nibble** | `0` AAC/SBC + 多点；`1` LDAC + 单点 | 10 |
| `0a01` | byte9 **低 nibble** | `0` 触摸功能开，`1` 关 | 10 |
| `0c01` | byte9 **低 nibble** | `0` 佩戴检测功能开，`1` 关 | 10 |
| `0e02` | byte9/10 **完整字节** | 寻机方向和状态；见[固件版本与寻机](#固件版本与寻机) | 11 |
| `0e03` | byte9 **低 nibble** | `0..4` 加 1 → 提示音 UI `1..5` | 10 |

共享 Receiver 还有 `0802` 分支：byte9 低 nibble `1` 表示多点开、`0` 关；i5 初始化不查询它，因此只能作为额外兼容解析，不能据此复制 HeadFeel 的独立多点协议。共享 `0103`、`0202/0203`、`0803`、`0e06`、`0b01` 等分支也不自动构成某个型号的能力。

各型号 ReceiveHandler 均继承自 `RFunctionLostReceiveHandler`（r_receive_handler.dart#L1091（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/r_receive_handler.dart:1091`））；未实现的接收回调默认抛出 `RFunctionLostException`（0xe41b40（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/r_receive_handler.dart:1098`）），由底层 `BleConnector`（0xe553a0（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:9203`））捕获并记录日志 `[connect][ble] 接收数据解析异常，跳过当前数据:`。例如 HeadFeel 未实现 `0e02`（`findMeReceived`），收到后即抛出异常并由 BleConnector 捕获跳过；而 HeadFeel 的 `0803`（多点名称列表）在分派表中直接指向空返回桩（0xe593f0（`rose_reverse/analysis/i7_dispatch/table.tsv`））。

证据：process 分派（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`）、触摸 8 槽读取（0xe52df8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2418`）、8 项映射到左右 4 槽（0x92c0a4）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/r_receiver_tool.dart:434`）。

#### 电量

`0101` 通知至少需要 byte15。下表是 i5/i3 的左/右/盒三槽解释（静态；i5 实机回包已验证）；HeadFeel 用 `0103` 单体电量（0xe535f4（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`）），其 `substring` 立即数参数为 tagged Smi（`0x28` 即 40，右移得 20；`0x12` 即 18），截取字符 `[18,20)` 对应 payload byte 0（报文第 9 字节）存入 `charge` 槽，左右耳固定为 `0xFF`，`leftInCase`/`rightInCase` 设为 `true`，`caseCharging` 设为 `false`（见 [HeadFeel 文档](../models/headfeel-protocol.md#接收字段)）。

| 偏移 | App 解释 |
|---|---|
| 9–10 | 本次电量处理函数未消费；保持 raw |
| 11 | 左耳电量，按完整字节无符号整数读取 |
| 12 | 右耳电量 |
| 13 | 左耳充电状态，`00=false`，其他=true |
| 14 | 右耳充电状态，`00=false`，其他=true |
| 15 | 盒电量，`FF` 表示没有盒数据 |

原 App 对左/右只显示 `1..100`，`0` 或超范围显示 `-`。盒值 `FF` 不更新盒显示；有盒数据时，还需任意一耳的充电标志为 true 且值在 `1..100` 内，才显示并缓存盒电量。第三方可以保留 `raw_percent`、`display_percent` 和 `case_present`，避免把无数据直接写成 0%。这两个充电 bool 已由 UI 的 `chargingState` 和充电图标用途确认；是否也表示“在仓且已充满”等实际状态边界仍须实测。

实机抓取（i5，2026-10-18）：`09 ff 00 00 01 01 01 11 00 00 01 64 62 00 00 ff e2`，对应左 100%、右 98%、盒缺失；`FF` 表示盒电量未提供。此样本不建立所有固件 RX 前缀、长度或校验的通用规则（实机 L3（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:3`）、实机验证记录（`rose_reverse/analysis/i5_hardware_validation.md`））。

证据：电量偏移（0xe53748）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3326`）、i5 电量显示逻辑（0xe41678）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:698`）、chargingState 充电图标（`rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_display.dart:2615`）。

#### 降噪模式尾部的精确匹配

原 App 匹配 **byte9 开始的全部剩余数据**，包含下表第五个字节。第五字节的字段性质尚未证明，不称为校验字节。i5/HeadFeel 的 payload 为 4 字节；i3 为 3 字节，回包尾部是否有第五字节没有样本（[i3 文档](../models/earfree-i3-protocol.md#未实现与未知)）。

| 全部尾部 hex | 模式 | App 内部模式值 |
|---|---|---:|
| `01 00 00 00 20` | ANC | 3 |
| `00 01 00 00 20` | 普通/关闭 | 0 |
| `00 00 01 00 20` | 抗风 | 2 |
| `00 00 00 01 20` | 通透 | 1 |
| `02 00 00 00 21` | auto，共享解析分支；i5 UI 没有该模式 | 4 |

i5 实机抗风回包命中 `00 00 01 00 20`（实机 L6（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:6`））。未知尾部原 App 记录警告并退回普通模式。第三方应显示未知并保留整帧，避免误报。不能仅用“payload 首个非零 flag”代替原 App 的匹配而宣称完全等价。证据：_handleANCData（0xe52914）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1939`）。

#### 固件版本与寻机

版本例：byte9..11 为 `9A 0B 0C` 时，低 nibble 得到整数 `[10,11,12]`，原 App 版本文本是 **`101112`**，不是 `ABC`、`10.11.12` 或完整三字节整数。应同时保留这三个值和原始报文。证据：_handleFirmwareVersion（0xe533cc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2987`）。

寻机通知 `0e02` 的 byte9 是方向 `00` 左、`01` 右、`02` 双耳；byte10 是状态 `00/01`。i5 的 handler 对 UI 左右标志执行如下规则（静态），`00 00` 最后还有一次全部置 false 的特例：

| 通知 payload | 左 finding | 右 finding |
|---|---:|---:|
| `00 01` | true | false |
| `00 00` | false | false（末尾特例覆盖） |
| `01 01` | false | true |
| `01 00` | true | false |
| `02 01` | true | true |
| `02 00` | false | false |

这个交叉赋值是 App 行为；实际硬件是否同时切换另一耳响铃仍未验证。方向 `03` 未在此 handler 分支中处理。HeadFeel 的 handler 没有 `findMeReceived`，寻机回包是否更新状态未核。证据：findMeReceived（0xd15fa8）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart:10`）。

等级 `0/1/2→1/3/5` 的共享转换证据：通透档位转换（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:265`）、ANC 档位转换（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:414`）。这些是 AOT 去重后输出的函数标签；i5 的 Sender 参数映射与共享 Receiver 单字节分支也独立支持相同线值。降噪轮换顺序还由 i5 的 4 个模型对象（`rose_reverse/analysis/blutter/objs.txt:1849`） 和 UI 发送顺序（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_anc_line_panel_item.dart:798`） 交叉核对。

### 接收 Tag 与处理

| Tag | 字段/处理 | 共享范围 | 状态 | 出处 |
|---|---|---|---|---|
| `0101` | i3/i5 电量：byte11 左、byte12 右、byte13/14 左右充电、byte15 盒，`FF` 表示盒数据缺失 | i3、i5 | 实机 | _handleBatteryData（0xe53748）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3326`）、i5 实机 L3（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:3`） |
| `0103` | HeadFeel 单体电量：substring(18,20)（payload byte 0，即报文第 9 字节）写入 charge 槽，左右固定 0xFF，入仓 true，充电 false | HeadFeel | 静态 | _handleHeadfreeBatteryData（0xe535f4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:3192`）、HeadfreeReceiveHandler.batteryDataReceived（0xe41a88）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:1189`） |
| `0d01` | 固件版本：byte9..11 的低 nibble 各按 hex 转整数后拼接十进制文本（例 `9A 0B 0C` → `101112`） | 全族 | 实机 | _handleFirmwareVersion（0xe533cc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2987`）、i5 实机 L15（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:15`） |
| `0201` | EQ 预设：byte9 低 nibble `0..3` 映射 POP/HIFI/ROCK/LIGHT（按型号列表） | 全族 | 实机 | _handleEQData（0xe532c8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2889`）、i5 实机 L8（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:8`） |
| `0202` | 自定义 EQ 数据；接收后交 `customEqDataReceived` | HeadFeel | 静态 | _handleCustomEqData（0xe531e0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2800`） |
| `0203` | 增益等级：wire `1..3` 交给 handler，handler 减 1 后映射 UI `0..2` | HeadFeel | 静态 | _handleGainLevel（0xe530bc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2690`）、HeadfreeReceiveHandler.gainLevelReceived（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:175`） |
| `0401` | 触摸绑定：byte9..16 低 nibble 按左右/手势槽位读出，交 `keyDataReceived` | 全族 | 实机 | _handleKeyData（0xe52df8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2418`）、i5 实机 L17（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:17`） |
| `0602` | 降噪模式：byte9 到结尾精确匹配 5 字节尾部；第五字节语义未证明 | 全族 | 实机 | _handleANCData（0xe52914）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1939`）、i5 实机 L6（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:6`） |
| `0603` | 游戏模式：byte9 低 nibble `0` 开、`1` 关 | 全族 | 实机 | _handleGameMode（0xe52cf4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2321`）、i5 实机 L10（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:10`） |
| `0604` | 通透等级：byte9 完整字节 `0/1/2` → UI `1/3/5` | i5、HeadFeel | 实机 | _handleTransparencyLevel（0xe52724）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1751`）、i5 实机 L27（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:27`） |
| `0605` | 降噪轮换：byte9..12 低 nibble 依次为普通/通透/抗风/ANC 是否参与 | i5、HeadFeel | 实机 | _handleAncList（0xe5201c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1087`）、i5 实机 L22（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:22`） |
| `0607` | ANC 等级：byte9 完整字节 `0/1/2` → UI `1/3/5` | i5 | 实机 | _handleANCLevel（0xe5281c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1845`）、i5 实机 L25（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:25`） |
| `0801` | 音频/多点：byte9 低 nibble `0` AAC/SBC+多点、`1` LDAC+单点；i5/i3 共用 | i3、i5 | 实机 | _handleConnectionMode（0xe52af0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2127`）、i5 实机 L12（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:12`） |
| `0802` | 多点连接状态；byte9 低 nibble `1` 开、`0` 关 | HeadFeel | 静态 | _handleMultiDevice（0xe51f14）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:989`） |
| `0803` | 多点设备名称列表 | HeadFeel | 静态 | _handleMultiDeviceNameList（0xe51d1c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:789`） |
| `0a01` | 触摸功能：byte9 低 nibble `0` 开、`1` 关 | 全族 | 实机 | _handleTouchControl（0xe52414）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1455`）、i5 实机 L19（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:19`） |
| `0b01` | 隐藏搜索：i3 专用；处理 `hideSearchReceived` | i3 | 静态 | _handleHideSearch（0xe51c14）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:691`） |
| `0c01` | 佩戴检测功能：byte9 低 nibble `0` 开、`1` 关 | i5、HeadFeel | 实机 | _handleInEarDetection（0xe52bf0）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:2224`）、i5 实机 L23（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:23`） |
| `0e02` | 寻机：byte9 方向 `00` 左、`01` 右、`02` 双耳；byte10 状态 | i5、HeadFeel（HeadFeel 未见 `findMeReceived`） | 静态 | _handleEarFind（0xe52340）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1372`） |
| `0e03` | 提示音等级：i5 handler 对 byte9 加 1；HeadFeel handler 直接取值 | i5、HeadFeel | 实机 | _handlePromptToneLevel（0xe5261c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1651`）、_handlePromptToneLevelHeadfree（0xe52518）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1552`）、i5 实机 L29（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:29`） |
| `0c00`、`0e06`、`0b00` | 共享分派中的额外分支，分别组合调用等级/提示音处理；适用型号和触发条件未核清 | 未定 | 未知 | process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`） |

命令键不是 `0x80` 清除后的读写对应关系；设置和查询使用不同 operation，不要把设置回包自动当成查询 Tag 的确认。

### 客户端状态同步与异常处理

以下为客户端设计建议；协议本身没有统一的 ACK 或超时机制。

- `write_complete` 只表示底层 write 返回。`state_confirmed` 应来自可解析通知；要确认一次设置，串行发设置并随后读取对应状态，比较字段值。
- 本协议未确认业务事务 id。一个字段有多个未完成设置时不要用“下一个回包”机械匹配先前请求；缓存最新状态、记录发送时间并串行处理同字段确认更稳妥。
- 原 App 未给出通用查询超时；第三方可配置如 2 秒的初始等待值，但需标明是实现参数、实测后调整。缺失字段显示未加载；对幂等查询可有限重试，不能把查询重试策略直接套在寻机和 OTA 写入上。
- 不自动将未知枚举降级为关；不将多个通知任意连接后按 TX 长度拆分。保留原始报文，待确认帧边界和兼容版本。
- 多点开关更新时同步音频状态；LDAC 切换时同步多点状态。音频编码器实际连接与 A2DP 状态由系统负责，BLE 回包只反映耳机设置。

## 按型号变化

| 项 | 去哪里查 |
|---|---|
| 识别前缀、SPP 是否允许、控制/OTA 的 SPP UUID、CMIIT | model_registry.json（`rose_reverse/analysis/model_registry.json`）；逐型号文档的“识别/传输”表有摘录和行号 |
| 配置类 cid、覆写方法、显示名与内部名 | 各配置类构造函数与 `customConfigEarObject`：i3 592/613/617/634，i5 737/763/767/773，HeadFeel 702/732/736/742/744/746 |
| 初始化顺序与目标 | 各配置的 `sendInitCommands`；分派表项 60078–60080 |
| 控件与取值 | 各配置类中 `Allocate*WidgetModel` 的调用；噪声/EQ/音频/触控列表由 `pp.txt` 对象池解引用，逐型号文档取值表 |
| 噪声 payload 宽度与寻机停止 operation | i3 覆盖为 3 字节 payload，i5/HeadFeel 4 字节；i5 停止 `0E 82 03 00`，HeadFeel 停止 `0E 02 03 00` |
| 电量 Tag 与槽位 | i3/i5 `0101` 左/右/盒；HeadFeel `0103` 单体复用 charge |
| 提示音等级语义 | i5 `0E 83` + UI−1；HeadFeel `0E 03` + UI 原值；接收加 1 规则也不同 |
| 固件门槛 | HeadFeel 自定义 EQ 与增益 `0x104`（Dart Smi，实际 130）；其他型号没有配置门槛 |
| OTA 类型与语言 | i3/i5 为 WQ，i3/i5 的 OTA 面板有 `hasEnglish`；HeadFeel 为 BES，无语言开关；方案细节见 [WQ](../ota/wq.md)、[BES](../ota/bes.md) |

## 控件与命令

控件模型类定义在 `roselink/rose_bluetooth/config/widget_model.dart`，界面共享，经 `RCommander` 调用当前配置的 Sender。下表的“Sender 方法”只写方法名；写“结构”表示容器或展示控件，写“范围外”表示 OTA 或云端业务入口，写“—”表示没有找到对应方法。“按方法名推断”表示 Sender 有名称相符的方法，但控件实际是否调用、参数如何取值尚未逐型号核对。

`analysis/model_brief.py` 已注册 `zt` 并会读取本表，但它只按控件类名套用全族方法列表，不按型号过滤：例如 i3 简报会列出 `setEqLight` 和长按触控，而 i3 配置没有这些入口；HeadFeel 简报也会列出 `setEqLight`，但 HeadFeel 配置没有 LIGHT 预设。工具输出只能当作候选列表，型号能力以型号文档的“设置命令”表为准，不能声称工具已验证族映射。

| 控件类 | 功能 | Sender 方法 | 说明 |
|---|---|---|---|
| `RDeviceComponentDisplayBarWidgetModel` | 电量展示 | 结构 | 电量槽取型号配置的 `BatteryType`；i3/i5 左/右/盒，HeadFeel 单体复用 charge |
| `RDeviceComponentNoiseBarWidgetModel` | 降噪模式 | `setNoiseModeAnc`、`setNoiseModeNormal`、`setNoiseModeWind`、`setNoiseModeTrans` | 可选模式取 `NoiseType`；i3 列表无抗风且发送 3 字节 payload，i5/HeadFeel 为 4 字节；控件调用按方法名推断 |
| `DeviceEqPanelItemWidgetModel` | EQ 预设 | `setEqPop`、`setEqHifi`、`setEqRock`、`setEqLight` | 预设取 `DeviceEQTypeModel`；i3 无 LIGHT，i5 有 LIGHT，HeadFeel 另有自定义曲线（`setEqCustom`、`setEqCustomData`、`getCustomEq`）；控件调用按方法名推断 |
| `DeviceOptionPanelWidgetModel` | 面板容器 | 结构 | |
| `DeviceGamePanelItemWidgetModel` | 游戏模式 | `setGameModeOn`、`setGameModeOff` | `00` 开、`01` 关；按方法名推断 |
| `DeviceMulDevicePanelItemWidgetModel` | 多点连接 | `setConnectMulOn`、`setConnectMulOff` | i3/i5 与音频模式耦合（`08 81`），HeadFeel 独立 `08 82`；i5 与 LDAC 互锁；按方法名推断 |
| `DeviceAudioPanelItemWidgetModel` | 音频模式 | `setAudioAacSbc`、`setAudioLdac`、`setAudioLhdc`、`setAudioLdacLhdc` | i3/i5 仅 AAC/SBC、LDAC；HeadFeel 另有 LHDC、LDAC+LHDC；按方法名推断 |
| `RDeviceTouchSettingItemWidgetModel` | 触控 | `setLeftTouchOne`、`setLeftTouchTwo`、`setLeftTouchThree`、`setLeftTouchLong`、`setRightTouchOne`、`setRightTouchTwo`、`setRightTouchThree`、`setRightTouchLong`、`setTouchOn`、`setTouchOff` | i3 仅单/双/三击、6 动作；i5 单/双/三/长、8 动作；HeadFeel 两键、8 动作；槽位/动作取值见各型号文档；控件调用按方法名推断 |
| `RDeviceFindItemWidgetModel` | 寻机 | `setFindDeviceLeft`、`setFindDeviceRight`、`setFindDeviceBoth`、`setFindOff` | i5/HeadFeel 有入口；`setFindDeviceBoth` 原样调用左耳；i5 停止 `0E82`，HeadFeel 停止 `0E02`；按方法名推断 |
| `RDeviceMoreItemWidgetModel` | 更多设置入口 | 结构 | |
| `RDeviceStarburstBusinessEntryItemWidgetModel` | 星芒业务入口 | 范围外 | 云端业务，不是耳机控制协议 |
| `DeviceOtaPanelItemWidgetModel` | OTA | 范围外 | i3/i5 WQ，HeadFeel BES；独立通道，见 [WQ](../ota/wq.md)、[BES](../ota/bes.md) |
| `DeviceDetectionSwitchPanelItemWidgetModel` | 佩戴检测开关 | `setEarDetectionOn`、`setEarDetectionOff` | i5 有入口；HeadFeel 有接收处理但无设置入口；i3 无；按方法名推断 |
| `DeviceDingLevelPanelItemWidgetModel` | 提示音等级 | `setDingLevel` | i5 UI1..5→线值 00..04；HeadFeel UI1..5→线值 01..05；i3 无入口；按方法名推断 |
| `DeviceAncLinePanelItemWidgetModel` | 降噪轮换 | `setAncLine` | i5/HeadFeel；线序普通/通透/抗风/ANC；按方法名推断 |
| `RDeviceGainLevelItemWidgetModel` | 增益 | `setGainLevel`、`getGainLevel` | HeadFeel 专用；版本门槛 130，线值 01..03；按方法名推断 |
| `RDeviceHideSearchItemWidgetModel` | 隐藏搜索 | `setHideSearchOn`、`setHideSearchOff`、`getHideSearch` | i3 专用；Tag `0B01`，不是寻机；按方法名推断 |

## 全族已知的坑

- 识别只看 `manufacturerData` 规范化后的前缀，不按可修改的设备名；连接地址用系统扫描对象的 remote id/address（[广播识别](#广播识别)）。
- i3 的降噪设置是 3 字节 payload（`ANC=01 00 00`、`trans=00 01 00`、`normal=00 00 01`），不能复用 i5/HeadFeel 的 4 字节格式（Zt219Sender::setNoiseModeAnc（0x81a3fc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:5171`））。
- i3 的 `setNoiseModeWind` 直接调用 `setNoiseModeNormal`，配置的 `NoiseType` 列表也没有抗风；不要把“Sender 有 wind 方法”当成 i3 支持抗风（Zt219Sender::setNoiseModeWind（0x815fbc）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:5151`）、i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:126`））。
- i3 的 HideSearch 是可见性控制，Tag `0B01`；不要把它当寻机 `0E02` 使用（i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:416`））。
- HeadFeel 的 `setFindOff` 发送 `0E 02 03 00`，而它的左/右寻机发送 `0E 82`；i5 的停止是 `0E 82 03 00`。这是 App 静态差异，设备是否接受未实测（Zt231Sender::setFindOff（0x7b399c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3205`）、Zt229Sender::setFindOff（0x7b394c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4349`））。
- i5 提示音设置用 `0E 83`，UI `1..5` 发送 `00..04`，接收 `0E 03` 后加 1；HeadFeel 设置用 `0E 03`，UI `1..5` 原样发送，接收不加 1。两型号不能共用同一个 setDing 实现（Zt229Sender::setDingLevel（0x8232d4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4663`）、Zt231Sender::setDingLevel（0x823394）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3537`）、接收处理（0xe52518/0xe5261c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:1552`））。
- 等级命令的线值映射：i5 的 `Zt229Sender` 覆盖 `setAncLevel`/`setTransLevel`，把 UI `1/3/5` 映射为 `00/01/02`；HeadFeel 继承基类 `ZtSender`，UI `1/3/5` 发送 `01/02/03`，而 `HeadfreeReceiveHandler` 把回包 `00/01/02` 映射为 UI `1/3/5`。i3 没有等级 UI。HeadFeel 发送 `01/02/03` 与回包 `00/01/02` 存在线值不对称（基类 setAncLevel（0x80b8e4）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:426`）、Zt229Sender::setAncLevel（0x80b81c）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523`）、Headfree 等级处理（0xdffcf8/0xe2f694）（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/receive_handler/zt/headfree_receive_handler.dart:265`））。
- i3/i5 的 `0101` 电量是左/右/盒三槽；HeadFeel 的 `0103` 是单体复用 charge 槽。不要把 `getInfo` 的 `0101` 回包套到 HeadFeel，也不要用 `getBattery` 的 `0103` 解析 i5。
- `0602` 降噪模式回包按 byte9 开始的全部尾部精确匹配，第五字节性质未证明；不能用“第一个非零 flag”替代精确匹配（[降噪模式尾部的精确匹配](#降噪模式尾部的精确匹配)）。
- i5/i3 的 `getConnectMul`/`setConnectMulOff` 走 `08 01` 或 `08 81 01`，与音频模式耦合；HeadFeel 的 `getConnectMul` 是 `08 02`，`setConnectMulOn/Off` 是 `08 82 01/00`，两者不能互换（Zt231Sender::getConnectMul（0xc77ea8）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3950`））。
- 所有 ZT 型号的接收路径都没有验证 RX 固定前缀、长度字段和末字节校验；未知 Tag 直接忽略，不报错（process（0xe51498）（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/zt_receiver.dart:10`））。
- 设置命令的 operation 是 `0x8x`，确认状态用读取 operation（如 `0602`）；不要自动把收到的 op 清除 `0x80` 后当成设置确认（[设置命令与参数](#设置命令与参数)）。
- `0d01` 固件版本取 byte9..11 的低 nibble 后拼接十进制文本（`9A 0B 0C`→`101112`），不是整字节整数或带点版本号（[固件版本与寻机](#固件版本与寻机)）。
- i5 只在 2026-10-18 验证了初始化读取；设置写入、持久化、寻机响铃和 OTA 都未实测，其他固件未验证（i5 实机验证（`rose_reverse/analysis/i5_hardware_validation.md`））。
- HeadFeel 初始化调用的 `getLoseLoss`（0x8e29d4（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:3883`））经选择器 `0xf495` 分派至 GDT 表项 67111，闭合到 `ZtSender::getAudioLdac` 的合并实现（0xc6e364（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:3749`）），实际发送 `08 EE 00 00 00 08 01 0A 00 09`，读回 Tag `0801`，并非未知帧。
- `ZTReceiver` 的 `substring` 参数在反汇编中为 tagged Smi（如 `0x28` 对应 20、`0x1c` 对应 14、`0x20` 对应 16），需右移一位解码。HeadFeel 的 `0103` 单体电量读取字符 `18,20)` 对应报文第 9 字节（payload byte 0）。
- 未实现的接收回调落入 `RFunctionLostReceiveHandler` 抛出 `RFunctionLostException`，由 `BleConnector`（[0xe553a0（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:9203`））捕获跳过；HeadFeel 的 `0e02`（寻机）即走此路径抛出并跳过，而其 `0803` 槽位在分派表中直接指向空返回桩（0xe593f0（`rose_reverse/analysis/i7_dispatch/table.tsv`））。
- i3/i5 是 WQ OTA，HeadFeel 是 BES OTA；OTA UUID、会话和固件格式都不同，不能把 i5 的 WQ 状态机套到 HeadFeel（i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:321`）、i5 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`）、HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:351`））。
- HeadFeel 的自定义 EQ 与增益门槛在配置里是 tagged Smi `0x104`，实际值 130；不要写成 260（HeadFeel 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:169`））。

## 维护

目前没有覆盖 ZT 全族的机器可读 Sender/Receiver 目录；[earfeel-i5-protocol.json](../models/earfeel-i5-protocol.json) 只描述 i5。本文的帧表、设置表和接收表是人工维护的族级依据，新增 ZT 型号时不要把它当成已生成的全族目录。

整理 ZT 型号时，先在本文补充全族共用事实，再在型号文档写差异。控件与命令的对应关系只维护本文的“控件与命令”表；`model_brief.py` 会读取本表，但不会按型号过滤，新增型号时仍要人工核对配置类和 Sender 调用点。新发现的接收 Tag 或字段处理差异写入本文的接收表和型号文档的“接收字段”。修改本文的章节标题时，同步更新各型号文档中的锚点链接。
