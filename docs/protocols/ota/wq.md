# WQ OTA 方案

APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）。WQ 是 App 的 OTA 路由之一：`DeviceOtaType.wq` 的枚举值为 3（枚举（`rose_reverse/analysis/blutter/objs.txt`）），`DeviceOtaController.initOtaManager` 把 `wq` 分派到 `WqOta`（分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3579`）），所有 `otaType=wq` 型号共用同一套字节格式、分块、超时和状态机。**没有实机 OTA 记录，完整固件未下载、未执行刷机**；以下除另注外均为静态分析。

| 型号 | 路由 | 门槛 | 出处 |
|---|---|---|---|
| EarFeel-i5（ZT，cid 501） | `wq`；BLE OTA 服务 `65786365-6c70-6f69-6e74-2e636f6d0000`，读写/通知特征 `65786365-6c70-6f69-6e74-2e636f6d0001`；OTA 面板 `hasEnglish=true`、`labelKey=ota_upgrade_language_switch` | —（OTA 面板未写 `sinceVersion`） | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:381`）、语言开关（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:389`） |
| EarFree-i3（ZT，cid 502） | `wq`；同 ZT 通道；OTA 面板 `hasEnglish=true` | — | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:330`） |
| CERAMICS-X（F8，cid 542） | `wq`；服务 `00007033-0000-1000-8000-00805f9b34fb`，写 `00002001-0000-1000-8000-00805f9b34fb`，读/通知 `00002002-0000-1000-8000-00805f9b34fb`；`otaUuid=00007034-0000-1000-8000-00805f9b34fb` | — | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:549`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:371`） |
| CERAMICS-X-7034（F8，cid 543） | 同上 | — | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:547`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:370`） |
| EXPLORER（F8，cid 541） | 同上 | — | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:715`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:357`） |
| CERAMICS-U（RS Common V2） | `wq`；`7033/2001/2002`；`otaUuid=00007034-…` | — | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:808`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:421`） |

`DeviceOtaPanelItemWidgetModel` 支持可空 `sinceVersion`（OTA 面板参数（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:5432`））：上述型号都没有写这个参数，因此本方案没有可解码的固件版本门槛。`hasEnglish` 与 OTA 页语言固件选项相关（推断），不是版本门槛。型号的 OTA 路由记录另见 v2_capabilities.json（`rose_reverse/analysis/v2_capabilities.json`） 的 `ota` 字段与 legacy_capabilities.md（`rose_reverse/analysis/legacy_capabilities.md`）；WQ 型号为 i5、i3、CERAMICS-X、CERAMICS-X-7034、EXPLORER、CERAMICS-U。

## 通道与 UUID

WQ 使用独立 GATT 服务，不能把普通控制帧（RS Common V2 的 `FF SS LL BODY CC AA` 或 ZT 的 `08 EE ...`）的长度、序号或求和校验套到 OTA 数据上。

| 型号组 | OTA 服务 | 写特征 | 读/通知特征 | 路由 UUID | 状态 | 出处 |
|---|---|---|---|---|---|---|
| i5 / i3（ZT） | `65786365-6c70-6f69-6e74-2e636f6d0000` | `65786365-6c70-6f69-6e74-2e636f6d0001` | 同写特征 | 无（两型号 `sppSupport=false`，配置不写 OTA SPP UUID） | 静态 | 默认 OTA 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1209`）、i5 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:773`）、i3 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:634`） |
| X / 7034 / EXPLORER（F8） | `00007033-0000-1000-8000-00805f9b34fb` | `00002001-0000-1000-8000-00805f9b34fb` | `00002002-0000-1000-8000-00805f9b34fb` | `00007034-0000-1000-8000-00805f9b34fb` | 静态 | 默认 OTA 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1182`）、X 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:549`）、7034 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:547`）、EXPLORER 覆写（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:715`） |
| CERAMICS-U（RS Common V2） | `00007033-0000-1000-8000-00805f9b34fb` | `00002001-0000-1000-8000-00805f9b34fb` | `00002002-0000-1000-8000-00805f9b34fb` | `00007034-0000-1000-8000-00805f9b34fb` | 静态 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:808`） |

`normalBleOtaServiceConfig` 与 `normalBleServiceConfig` 使用同一个协议族 getter：F8 分支返回 `7033/2001/2002`，ZT 分支返回 `6578…0000/…0001`，因此默认 OTA 服务由协议族决定，不是由 `otaType` 决定（静态，F8 分支（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1182`）、ZT 分支（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1209`）；族别对应见 正常服务配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1009`））。RS Common V2 的 WQ 型号不走这条默认分支，由配置直接写 `7033/2001/2002`。`otaUuid` 是 OTA 路由字段，不是控制 GATT Service；控制通道仍用各族的 `fdb3/ff16/ff17`、`011bf5da/7777/8888` 或 `00fe/00f1/00f2`。

`resetOtaServer` 在已有控制连接上重新准备 OTA GATT 通道，不是向耳机发送重启命令。其 BLE 实现先确认普通控制通道就绪且设备仍连接，标记 OTA 未就绪，清空 OTA 服务/读写特征引用和服务缓存，重新 `discoverServices`，寻找 OTA 服务与配置的读/写特征，取消旧 `onValueReceived` 订阅并订阅新通知，通过 `_setNotifyValueWithRetry` 启用通知，成功后标记 OTA ready；已有 reset Future 未完成时直接返回同一个 Future。第三方对应实现是重新发现、匹配、订阅 OTA 特征，仅重复注册用户态回调不能完全复现（静态，`resetOtaServer`（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:227`）、清理与重新准备（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:420`）、重新发现（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:555`）、匹配特征（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:843`）、订阅（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:1075`））。

## 字节格式

App 发送的 WQ 帧是 hex 文本：

```text
70 07 6E | C0xx | ... | 33
```

前三字节 `70 07 6E` 和尾字节 `33` 是 App 固定值；它们是否称为同步字、包尾以及设备端是否校验，均未证明（未知）。消息类型 `H[0:4]` 中，发送用 `C0xx`，回复用 `00xx`。接收 `process @0xe2b060` 先对通知 hex 执行 `substring(6)`，即忽略前三字节；`H` 是去掉前三字节后的 hex 文本。App 不在此处验证前三字节的值，也没有显示接收拼包。以下所有偏移均为字符索引、半开区间（静态，接收预处理（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1380`））。

`process` 会忽略内容相同且距上次不超过 20 ms 的通知；其余通知在命令有效性验证之前都会把超时计数清零（静态，去重与计数（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1380`））。

### E2 头部数据

`otaC0E2 @0xe2ca8c`。记 `F` 为固件 hex 文本：

```python
n = int(H[20:24], 16)
o = int(H[12:20], 16)
payload_hex = F[o:o + 2*n]
tx = bytes.fromhex("70076ec0e2" + f"{n+1:04x}" + "1e" + payload_hex + "33")
```

**App 的 `o` 没有乘 2**：`wq.dart:3850–3864`（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3850`） 把 `o` 原样交给 substring，仅长度乘 2。这可能是回复使用 hex 字符偏移，也可能是固件入口特殊约定或现有 bug；静态证据无法判定。复现客户端应保留此行为并抓取真实 E1 回复再确认，不能擅自改成 `F[2*o:...]`。`n+1` 长度字段和 `1e` 字段的含义也没有证明（静态，E2 算法（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3769`））。

### E5 块数据

`_updateNextBlock @0xe2bfec`：

```python
status = int(H[12:14])  # App 用十进制 parse；00 成功
if status != 0:
    raise OtaError("02")
offset_hex = H[14:22]
if offset_hex == "00000000":
    send_E6()
else:
    offset = int(offset_hex, 16)  # 这里的偏移是字节单位
    count = int(H[22:26], 16)
    block = bytes.fromhex(F[2*offset:2*offset + 2*count])  # 限制到固件结尾
    block_sequence = (block_sequence + 1) & 0xff  # 构造时初值 0，第一块为 1
    crc = zlib.crc32(block) & 0xffffffff
    frame = (bytes.fromhex("70076ec0e5")
             + (len(block) + 9).to_bytes(2, "big")
             + bytes([block_sequence])
             + bytes.fromhex(offset_hex)
             + block + crc.to_bytes(4, "big") + b"\x33")
    for start in range(0, len(frame), 217):
        send_ota(frame[start:start + 217])
```

`len(block)+9` 包含序号 1 字节、偏移 4 字节和 CRC 4 字节。CRC 只覆盖 `block`，不覆盖 E5 包头、序号、偏移；算法是标准反射 CRC-32/ISO-HDLC（多项式 `0xEDB88320`，初始值 `0xFFFFFFFF`，最终取反/xorout `0xFFFFFFFF`），输出 4 字节大端，可用 `zlib.crc32(block)` 复现（静态，E5 构造与分片（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3133`）、CRC 实现（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:107`）、初始值/取反（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:163`）、多项式（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:364`））。

## 传输窗口、分段与 ACK

- E5 按 **217 字节**对整个帧分片，包括头部和尾部所在的片；不是每片一个独立 E5 帧。原程序按 434 个 hex 字符分片，后一片接着前一片写；片间用 `Timer.run` 排程，不显式等待 ACK，最后一片发完才等下一次块请求。一次 GATT 写入 217 字节通常要求 ATT MTU ≥220（静态，E5 构造及分片（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3133`））。
- 每个 E5 块由服务端的下一块请求驱动，没有逐帧 ACK；块请求中的 `offset` 为字节偏移，`count` 为请求字节数。块数据限制到固件结尾（静态，请求解析与序号（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2770`））。
- `increaseTimeOutCount @0xacab38` 使用 1 秒 `Future.delayed` 周期，`Duration@db0781` 的微秒值 `0xf4240=1000000`。启动阶段立即调用一次，因此首次计数从 0→1 是立即发生，而后按秒增加；应称为每秒计数，不应武断写成精确“3 秒首次重试”。有效通知先把计数归零；计数到 3 时仅重发保存的最后一次写入数据（E5 分片时保存的是最后一片），随后清空保存的数据；计数超过 10 时停止并报错 03。这不能证明设备能从任意位置继续升级（静态，超时计数对象（`rose_reverse/analysis/blutter/objs.txt:49060`））。
- C002 查询发出后 500 ms 检查初始化是否完成，未完成则增加 `retryCount`；`retryCount≤6` 时等待 **2 秒**后 `resetOtaServer`，再重发查询，超过 6 停止。2 秒对象 `Duration@db0831` 的微秒值 `0x1e8480=2000000`，与升级过程的 1 秒计数循环不同（静态，2 秒对象（`rose_reverse/analysis/blutter/objs.txt:49104`）、C002 版本字段（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1602`））。
- 超时及块发送还依赖 `field_2b` 页面存活/允许继续标志：WQ 页进入时置 true、退出时置 false；`field_3f` 是升级进行标志，E1 发出后置 true。计数循环只在两标志都 true 时继续，避免把构造器初始 false 误读成设备取消回复（静态，进入页面（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:1879`）、退出页面（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:2076`））。
- E6/E8 非成功状态每 500 ms 再查询；对象 `Duration@db0791` 的微秒值 `0x7a120=500000`（静态，E6/E8 轮询（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2499`）、500 ms 对象（`rose_reverse/analysis/blutter/objs.txt:49064`））。
- 接收侧未见跨通知重组，若实机出现拆包需先抓包确认边界；第三方应自行缓冲并按 `H[0:4]` 分派，且不能把普通控制的 TX 构帧规则（长度、求和/CRC）套到 WQ 接收或 OTA 数据上（推断；WQ `process` 未验证接收前缀/长度，接收预处理（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1380`））。

## 状态机

| 阶段 | 发送字节 | 回复处理 |
|---|---|---|
| 查询 | `70 07 6E C0 02 00 05 00 FF FF FF FF 33` | `H[0:4]=0002` 且 `len(H)=64`，解析版本和状态；版本来自 `H[16:20]`、`H[20:24]`，均为 16 进制数，选择较小值 |
| 开始 | `70 07 6E C0 E1 00 01 1D 33` | `00e1`：保存完整 H，按下节生成 E2 |
| 头部数据 | E2 动态帧 | `00e2` 且 `H[12:14]==03` → E3；否则 App 错误码 04 |
| 开始块传输 | `70 07 6E C0 E3 00 01 1F 33` | `00e3` → 解析块请求 |
| 块数据 | E5 动态帧，分片连续写入 | `00e5` → 去掉 H 最后两字符，再解析块请求 |
| 完成传输 | `70 07 6E C0 E6 00 01 C1 33` | `00e6` 且 `H[12:14]==00` → E8；否则 500 ms 后再发 E6 |
| 完成升级 | `70 07 6E C0 E8 00 01 C1 33` | `00e8` 且 `H[12:14]==00` → 成功并发送 C003；否则 500 ms 后再发 E8 |
| 结束 | `70 07 6E C0 03 00 02 00 00 33` | App 标记成功之后发送；没有单独等待这个命令应答 |

流程：C002 查询 → E1 开始 → E2 头部 → E3 开始块传输 → E5 块数据（直到请求偏移为 0）→ E6 完成传输 → E8 完成升级 → C003 结束。E1 发出后 `field_3f` 置 true；E8 状态 00 后 App 标记成功，再发 C003，不等待 C003 应答。错误路径包括状态位非 0 的 App 错误码 02、计数超过 10 的 03、E2 回复第三个字节非 03 的 04。回复 `00xx` 的消息类型与发送 `C0xx` 一一对应，但 App 的分派表还接受其他 `00xx` 分支；第三方应保留未识别回复的原始字节（静态，E1/E2/E3/E5 分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2293`）、E6/E8 与 C003（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2499`））。

`resetOtaServer` 是本地重新准备 GATT 通道，不改变上述升级阶段；它的行为见“通道与 UUID”。升级中断后的设备端恢复语义未知。固定帧字节的来源见 E8 固定帧（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:2726`）、E6/E3 固定帧（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:3681`）。

## 固件来源与完整性

| 项 | 行为 | 状态 | 出处 |
|---|---|---|---|
| 启动 | `WqOta.otaC0E1 @0xaca954` 先调用 `DeviceOtaController.compareOtaDataWithEncodeData`，通过后读取 `getOtaContent`，非空才发送启动指令 | 静态 | 启动函数（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:15`） |
| 完整性 hook | 本 APK 的 `compareOtaDataWithEncodeData @0xac05a0` 直接返回 true，没有验证 `encodeData` 的逻辑；不能把函数名当成完整性验证证据 | 静态 | compareOtaDataWithEncodeData（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1723`） |
| 下载链 | `DeviceOtaController.getOtaContent @0xac01e0`（读取自身 `field_57`）→ `DeviceService.getOtaContent @0xac0270` → `ApiDeviceService.getOtaContent @0xac0304` → `StaticResourceClient.getText @0xac0368`；HTTP GET 返回 String，字节列表按 UTF-8 解码，空响应转 `""` | 静态 | 下载入口（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1666`）、服务层（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:437`）、HTTP/UTF-8（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:642`） |
| 输入格式 | WQ 直接把返回文本保存在 `latestData`，再按十六进制字符索引截取；输入是**无空白纯 hex 文本**，不是 `.bin` 字节或 Base64。字符索引前没有解密、解压或空白规范化；`CommonUtil.stringToList @0x820190` 在截取并构造帧之后才移除 ASCII 空格和 `0x` | 静态 | 下载入口（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1666`） |
| 元数据 URL | 服务端响应 `data.otaUrl`；`ApiClient._processResponse` 先取 `data`，`DeviceService._buildOtaResult @0xacc120` 把 `otaUrl` 重命名为 App 内部字段 `otaContent`，控制器保存该 URL；此步不下载固件，之后 `getOtaContent` 才下载文本 | 静态 | URL 映射（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10882`）、控制器字段（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3197`） |
| 元数据请求 | `POST /app/ota/v2/get-by-key-id-and-tag`，参数 `keyId`、`pid`、`tag`、`clientVersion`、`lang`，`token=false`；自身耳机目标 `tag=1`，`pid=""`；`clientVersion` 是 App buildNumber 转字符串（当前 `"5310"`，不是 `"3.8.0"`）；`lang` 由调用 init/checkOtaNewVersion 的参数传给服务端，不由 BLE 广播决定；默认 base URL `https://api-internal.rosetechnic.com`（ApiClient（`rose_reverse/analysis/blutter/asm/roselink/core/api/api_client.dart:1412`）、StaticResourceClient（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:592`）） | 静态 | 元数据 API（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:476`）、接口路径（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:650`）、buildNumber（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:590`）、SystemService（`rose_reverse/analysis/blutter/asm/roselink/core/services/system/system_service.dart:605`）、PackageInfo（`rose_reverse/analysis/blutter/asm/package_info_plus/package_info_plus.dart:345`）、pid（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10373`）、tag=1（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:2247`）、lang（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1178`） |
| keyId | 来自设备 `trackingName`，不是广播识别串。链：配置写 `field_1b` → `RName.trackingName @0x7547a8` 读该字段并调用 `GDT[cid-0x1000]` → `DeviceOtaController.initOtaManager @0xb45d64` → `OtaManager.attachSession` → `bindSession @0xb46274` 保存到控制器 `field_23` → `fetchDeviceOtaMsg` 取它作为 `fetchDeviceOtaByTag` 的 keyId | 静态 | trackingName（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/object_info.dart:6`）、i5 内部名（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:742`）、读取并 attachSession（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3622`）、attachSession → bindSession（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_manager.dart:281`）、bindSession（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3737`） |
| 语言固件 | i5 配置开启 `ota_upgrade_language_switch`，OTA 页两个语言选项分别以 `"en"`、`"zh"` 请求元数据；提示音语言切换是**选择对应语言固件再 OTA**，没有普通控制通道独立的设置语言命令 | 静态 | 语言开关（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:389`）、语言回调（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:1203`）、en（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/wq_ota_page.dart:1324`） |
| i5 实测 | 2026-10-14 以 `keyId="earfree-i5"`、`pid=""`、`tag=1`、`clientVersion="5310"` 匿名请求，`lang="zh"` 和 `"en"` 均返回版本 125 与下载 URL；响应中的 `data.keyId="8"` 是服务端记录字段，不应替换请求的型号名称 | 静态（服务端响应） | 中文响应（`rose_reverse/analysis/ota_links/i5-zh-client5310.json`）、英文响应（`rose_reverse/analysis/ota_links/i5-en-client5310.json`） |
| 资源可访问性 | 两个 i5 资源的 HEAD 均为 200，`bytes=0-63` Range 均为 206，采样 64 字节全部为 ASCII hex；只验证元数据与资源可访问性、文本前缀，不是完整固件或刷机验证；链接带时效签名，需要时重新请求元数据 | 静态（服务端响应） | [OTA 更新包链接](../ota-package-links.md) |

`keyId` 的 trackingName 附加颜色后缀只针对 `ceramics-x`、`ceramics-x-7034`、`explorer`（静态，trackingName（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/device_type_config/object_info.dart:6`））。i3 的 trackingName、固件版本和服务端记录没有核对；F8 与 CERAMICS-U 的元数据请求也没有逐型号验证，不能把 i5 的 `keyId`、版本号或状态机结果直接套过去。完整固件格式、签名和兼容硬件仍未验证。

## 未知与缺口

- 完整 OTA 通知的原始前缀、跨通知重组与设备 MTU 未验证；App 的 `process` 只做 `substring(6)`，没有显示拼包（接收预处理（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/wq.dart:1380`））。
- E2 的 `o` 偏移单位未闭合：App 未乘 2，可能是 hex 字符偏移或固件入口约定/现有 bug；需要真实 E1 回复和完整固件文本样本。
- 查询回复返回的各状态位（含左右耳/双耳升级指示）语义未验证；`H[12:14]` 之外的字段未逐一解释。
- 固件与硬件版本的兼容条件、设备端签名验证/拒绝条件、升级中断与重新连接恢复均未验证。
- 前三字节 `70 07 6E` 和尾字节 `33` 的设备端含义、校验未证明；WQ `process` 不验证接收前三字节，第三方应保留原始字节。
- i3 的 `keyId`、固件版本和服务端记录未核对（i3 OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:330`））；F8 型号的 OTA 元数据、trackingName 颜色后缀和语言固件选择未逐项验证。
- 没有 WQ 实机 OTA 记录；i5 的实机记录只覆盖初始化读取（实机验证（`rose_reverse/analysis/i5_hardware_validation.md`）），完整固件未下载，`compareOtaDataWithEncodeData` 恒 true，不能声称完整性校验或“可刷任意固件”。

## 维护

本文件收录所有 `otaType=wq` 型号共用的 OTA 事实。新增型号先核对配置中的 `otaType`、OTA 面板参数、OTA 服务/特征和 `otaUuid`，再在本文件型号表加一行；型号文档的 `## OTA` 只保留本型号路由、门槛和与本文的差异，不重复字节格式、窗口、ACK、状态机。字节格式、分块、超时或状态机的改动必须附 Blutter 地址或实机记录；实机结论注明记录文件和条目。链接用相对路径，研究文件写到 `../../analysis/…`，站内文档写到 `../…`。
