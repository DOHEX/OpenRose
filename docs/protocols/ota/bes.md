# BES OTA 方案

APK 基线 RoseLink 3.8.0（versionCode 5310，SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`）。BES 是 App 的 OTA 路由之一：`DeviceOtaType.bes` 的枚举值为 2（枚举（`rose_reverse/analysis/blutter/objs.txt`）），`DeviceOtaController.initOtaManager` 把 `bes` 分派到 `BesOta`（分派（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:3568`）），所有 `otaType=bes` 型号共用同一套帧格式、分块、ACK 和状态机。**没有 BES 实机 OTA 记录，完整固件未下载、未执行刷机**；以下除另注外均为静态分析。

| 型号 | 路由 | 门槛 | 出处 |
|---|---|---|---|
| EarFeel i7（RS Common V2，cid 523） | `bes`；OTA 面板 `needCheckWwsMode=true`；BLE OTA 服务 `66666666-6666-6666-6666-666666666666`，写/通知特征 `77777777-7777-7777-7777-777777777777`；OTA SPP UUID `66666666-…` | —（OTA 面板未写 `sinceVersion`） | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:608`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1024`） |
| HeadFeel（ZT，cid 500） | `bes`；OTA SPP UUID `66666666-…`；BLE OTA 服务 `6666…`、写/通知特征 `7777…`（服务/特征归类为推断）；OTA 面板无 `hasEnglish`，配置无 OTA 语言开关 | — | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:350`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:702`） |
| CERAMICS-U [HSC-2710]（RS Common V2） | `bes`；OTA 面板 `needCheckWwsMode=true`；BLE OTA 服务 `6666…`，写/通知特征 `7777…`；OTA SPP UUID `6666…` | —（OTA 面板未写 `sinceVersion`） | 构造（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:30`）、OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:414`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:760`） |

当前索引中路由 BES 的只有这三款：RS Common V2 的 28 个配置里只有 EarFeel i7 与 CERAMICS-U [HSC-2710] 的 `ota` 为 BES（v2_capabilities.json（`rose_reverse/analysis/v2_capabilities.json`））；ZT 中 i5/i3 为 WQ、HeadFeel 为 BES（legacy_capabilities.md（`rose_reverse/analysis/legacy_capabilities.md`））。三款的 OTA 面板都没有写 `sinceVersion`，因此没有可解码的固件版本门槛；`needCheckWwsMode` 是 OTA 入口的布尔前置参数，不是版本门槛。HeadFeel 的 OTA 面板没有 `hasEnglish`，配置也没有 OTA 语言切换，不能套用 i5 的 WQ 语言固件或 `keyId`。

i7 配置中 `sinceVersion` 的 raw `600`（`0x258`）→ 300 属于 AI 业务入口的 `DeviceOptionPanelWidgetModel`，不是 OTA 面板；不要把它记成 i7 的 OTA 门槛（静态，i7 AI 门槛（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:485`）、i7 分析（`rose_reverse/analysis/i7_config_ota_findings.md`））。

## 通道与 UUID

BES 使用独立 BLE 特征或 SPP socket，不把普通控制帧追加到 OTA 数据上。

| 型号 | OTA SPP UUID | BLE OTA 服务 | BLE 写特征 | BLE 通知特征 | 状态 | 出处 |
|---|---|---|---|---|---|---|
| EarFeel i7 | `66666666-6666-6666-6666-666666666666` | `66666666-…` | `77777777-7777-7777-7777-777777777777` | `77777777-…` | 静态字符串；服务/特征归类推断 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1024`） |
| HeadFeel | `66666666-…` | `6666…` | `7777…` | `7777…` | 静态字符串；服务/特征归类推断 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:702`）、字段（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:746`） |
| CERAMICS-U [HSC-2710] | `6666…` | `6666…` | `7777…` | `7777…` | 静态字符串；服务/特征归类推断 | customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:760`） |

三款都在 `customConfigEarObject` 直接写入 `field_37`（OTA SPP UUID）和 `field_6b`/`field_77`/`field_7b`，不调用 `normalBleOtaServiceConfig`。把 `field_6b` 解释为 BLE OTA 服务、`field_77`/`field_7b` 解释为写/通知特征，依据是 `normalBleOtaServiceConfig` 对同名字段的赋值（该函数按协议族分支设置服务/特征）；这是**推断**，未做 OTA 服务实机发现。`normalBleOtaServiceConfig` 本身没有按 `otaType=bes` 选择 `6666/7777` 的分支，不能把它的 UUID 直接当作 BES 通道（静态，默认 OTA 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:1143`））。

`ensureOtaTransportReady` 在 OTA 页启动前准备 OTA 通道（静态，通道 ready（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_transport.dart:6`） `0xac69e0`）；其具体 BLE/SPP 连接步骤未恢复。SPP 实际配对、SDP 服务发现和 BES OTA socket 的 UUID 使用仍需实机确认（未知）。

## 字节格式

OTA 帧与普通 V2 帧不同，直接写入 `writeOta`，不加普通 V2 头、序号或求和校验：

```text
opcode:u8 | payload_length:u32le | payload
```

`LE32(x)` 可用 Python `(x & 0xffffffff).to_bytes(4, 'little')`；`crc32` 用 `zlib.crc32(data)`。CRC 为共用 `CRC32Lib` 的标准反射 CRC-32/ISO-HDLC（多项式 `0xEDB88320`，初始值/xorout `0xFFFFFFFF`），BES 输出低字节先（静态，CRC 实现（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:163`））。

| App 函数 | opcode | payload | 原始帧/说明 |
|---|---:|---|---|
| getOtaProtocolVersion | 99 | `00 00 00 01` | `99 04 00 00 00 00 00 00 01` |
| setOtaUser | 97 | `01` | `97 01 00 00 00 01`；`compareOtaDataWithEncodeData` 在该 APK 总 true |
| getCurrentVersion | 8E | ASCII `BEST` | `8E 04 00 00 00 42 45 53 54` |
| setUpgradeType | 9D | `01` | `9D 01 00 00 00 01` |
| getRoleSwitchRandomId | 9B | 空 | `9B 00 00 00 00` |
| selectSide | 7B | `00` | `7B 01 00 00 00 00`；不要擅自把 `00` 解释为左/右/双耳 |
| transmissionBreakPointCheck | 8C | `BEST + 32 个 00 + 01 02 03 04 + CRC32LE(32 个 00+01 02 03 04)` | payload 长 44，`8C 2C 00 00 00 ...`；随机 ID 查询值未进入此帧，nonce 固定 `01020304` |
| sendOtaStartPackage | 80 | `BEST + LE32(固件字节数) + LE32(CRC32(固件))` | payload 长 12，`80 0C 00 00 00 ...` |
| sendOtaConfigure | 86 | 见下 | payload 长 92，`86 5C 00 00 00 ...` |
| getDataPackageCMD 数据 | 85 | 每片固件字节 | len4 字段以 ByteData4 构造，低 16 位写值，另两字节 0 |
| getDataPackageCMD 分段 CRC | 82 | `BEST + LE32(CRC32(segment))` | payload 长 8，`82 08 00 00 00 ...`；没有 segment 偏移/序号字段 |
| getAllPackageCrc | 88 | 空 | `88 00 00 00 00`；整镜像期标志设 true |
| overwriteImage | 92 | `BEST` | `92 04 00 00 00 42 45 53 54`；防重复覆盖 |

`sendOtaConfigure` 的 payload 逐字节恢复为：

```python
config_without_crc = (
    bytes.fromhex('58 00 00 00')
    + firmware[-4:-1] + b'\x00'
    + bytes(4) + bytes(32) + bytes(32) + bytes(6) + bytes(6)
)
assert len(config_without_crc) == 88
config = config_without_crc + LE32(crc32(config_without_crc))
tx = bytes([0x86]) + LE32(92) + config
```

第一组 `58 00 00 00` 在 payload 内（可能是配置自身长度，语义未证明），外层长度 92；固件最后 4 字节中的前 3 字节保留、最后 1 字节强制 0。不能概括成取整个 final LE32，也不能根据函数名推断这是版本或签名字段。`firmware` 对应无空白 hex 文本解码后的完整字节（静态，86 构造（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5624`））。

CRC 校验参考：breakpoint 最后 4 字节可由 `zlib.crc32(bytes(32)+bytes([1,2,3,4]))` 算出。

证据索引：直写 OTA 111/375（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:111`） `0xac1a20/0xac1ce0`；99 691（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:691`） `0xac203c`；97 12（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:12`） `0xac1934`；8E 7182（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:7182`） `0xac68d0`；9D/9B/7B 6892（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:6892`） `0xac6638/0xac66d0/0xac6798`；8C 6558（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:6558`） `0xac62b8`；80 6281（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:6281`） `0xac5fec`；86 5624（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5624`） `0xac58b8`；85 分片 3270（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:3270`） `0xac3b54`、LE 长度 ByteData4 3385（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:3385`）；82 4078（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4078`） `0xac43a8`；88 5234（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5234`） `0xac532c`；92 5472（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5472`） `0xac573c`；CRC 实现 163（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:163`） `0xac50fc`。以上均为静态。

## 传输窗口、分段与 ACK

`startOta` 拿到完整 hex 固件后清空偏移、计数和挂起段。记 `N=len(firmware)`：

- `calculateBLESinglePacketLen` 使用当前 `BleConnector.field53` MTU `M`，常见路径 `P=min(508, M-10)`，SPP 路径 `P=512`；特殊分支：当 `0<N<M-10` 时原程序直接返回 `M`。实际每片 85 帧最多 `P-5` 字节固件。若 GATT 报告 `dataLen: A > max: B`，代码把 `P` 缩到 `B`，重试同一未成功片段（静态，包大小（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:1042`） `0xac23b4`）。
- 段大小来自 `N/100` 整数截断，往上对齐 256（**整除时仍多加 256**），并设置至少 4096：`segment_bytes = max(4096, 256 * (int(N/100)//256 + 1))`。最后段截到固件末尾（静态，段算法（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:2664`） `0xac3500`）。
- 每片 85 用 `_sendOtaDataSerial` 等 `writeOta` Future 完成后才发下一片，未见额外软件 sleep。发完全部片后计算本段 CRC，发送 82，挂起 `{startHex, consumedHex, crcCommand}`，**等 83 状态 01 才移动确认偏移**并发下一段。SPP/ATT 写完成不等于 83（静态，write 串行 await（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4875`） `0xac4be8`）。
- `83` 状态不是 01 → 取消定时器、清空 pending、回滚到上次已确认偏移并重发整个段，允许 3 次重发；第 4 次 NACK 失败。`83` 状态 01 清空所有 pending、NACK/retry 计数；不存在 pending 时视为重复 ACK，不重复推进（静态，NACK 整段重发（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:2214`） `0xac306c`）。
- 82 ACK 定时器 5 秒（`Duration@db08c1.off8=0x4c4b40` 微秒）。到期只重发 82 分段 CRC 命令，最多 2 次；仍未应答则终止。该计数与 NACK 重发整段计数分开（静态，ACK 计时器（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4463`） `0xac47a0`、超时/仅 82 重发（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4586`） `0xac48d0`、5 秒对象（`rose_reverse/analysis/blutter/objs.txt:49140`））。
- 段全部确认后发 88 进入 whole-image CRC 期；84 状态 01 → 发 92 并在本地标记成功，不另等 92 成功回包。84 只在整镜像期消费；某些设备用 87 状态 01 给最终成功，App 在 whole 期也支持这个分支。覆盖后的重启/重新连接与固件验签仍需设备验证（静态，88（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5234`）、92（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5472`））。

BES 没有普通控制通道那种逐帧业务 ACK；82 的 83 应答和 85 的写完成是不同层。所有重试计数都是 App 的本地状态，不能证明设备端断点续传。

## 状态机

`BESOtaPage._startOta` 先 `ensureOtaTransportReady`，`startOta` 获取固件并建立订阅，然后依次发 99 和 97；不是先发协商再下载固件（静态，BES 页面启动（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/bes_ota_page.dart:586`） `0xac16d4`、startOta（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:803`） `0xac2134`）。

`process @0xac2720` 对 raw notification hex `H` 只取 `H[0:2].toLowerCase()` 分类；状态字段均为 `H[10:12]` 即 **raw byte5**。此函数未验证 header length，也未实现跨通知拼接；接收 83/84 时额外检查 `len(H)>=12`，其他分支对短帧不统一保护（静态，接收与状态机（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:1377`））。

| 回复 opcode | 条件/处理 |
|---:|---|
| 98 | byte5==01 → 8E 查询版本；否则终止并报错 01 |
| 8F | 不读版本 payload；依次发 9D、9B、7B、8C |
| 8D | 不读断点 payload，发 80 启动；不能据函数名声称实现设备断点恢复 |
| 81 | 发 86 配置 |
| 87 | byte5==01 且非 whole 期 → 下一段；whole 期 → 92+success；失败 → 终止错误 01 |
| 83 | byte5==01 且 pending 存在 → 确认挂起段+下一段；否则按 NACK 策略处理 |
| 84 | 必须处于 whole 期；byte5==01 → 92+success，否则失败 01 |
| 9A / 9C / 9E / 7C 等 | 此 `process` 未看到专门处理；99/9B/9D/7B 的答复不作为下一阶段条件 |

普通内容相同的回复会被去重；83 允许重复进入以 pending 判定，whole 期成功 84 也允许重复进入；一旦已 overwrite 有防重标志。第三方应采用明确阶段与 pending 匹配，保留 raw 未知帧；不能套用控制协议的长度、CRC 或 opcode 分类来解析 BES。

## 固件来源与完整性

| 项 | 行为 | 状态 | 出处 |
|---|---|---|---|
| 下载链 | BES 与 i5 共用 `getOtaContent` → `DeviceService` → `ApiDeviceService` → `StaticResourceClient.getText`，保存文本 `latestData`，按 hex char 数/2 计算字节数、CRC 和 substring；输入为无空白纯 hex 文本，未见本地 BES 压缩/解密；至少需要 4 字节用于配置尾部取值 | 静态 | 共用 getOtaContent（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1666`）、HTTP/UTF-8（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:642`） |
| 完整性 hook | `compareOtaDataWithEncodeData @0xac05a0` 仍直接返回 true；`encodeData` 字段不是设备/应用验签已经证明的依据 | 静态 | 恒 true 校验 hook（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1723`） |
| 元数据请求 | `POST /app/ota/v2/get-by-key-id-and-tag`；`keyId=earfeel-i7`（custom 内部名本已小写，trackingName 继续 lowercase）；`pid` 空串；自身目标 `tag=1`；`clientVersion="5310"`；`lang` 由调用 init/checkOtaNewVersion 的参数传给 `fetchDeviceOtaMsg`，不由 i7 BLE 广播决定；默认 base URL `https://api-internal.rosetechnic.com` | 静态 | BES 版本检查传 deviceLang（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:7678`） `0xe29e78`、元数据 API（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:476`） `0xacc6d4`、接口路径（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:650`） |
| URL 映射 | 服务端链接字段为 `data.otaUrl`；`ApiClient._processResponse` 先取 `data`，`DeviceService._buildOtaResult @0xacc120` 把 `otaUrl` 重命名为 App 内部 `otaContent`，控制器再保存 URL；此步不下载固件，之后 `getOtaContent` 才下载 hex 文本 | 静态 | otaUrl → otaContent（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10882`） `0xacc5ec` |
| i7 实测 | 2026-10-14 以 `keyId="earfeel-i7"`、`pid=""`、`tag=1`、`clientVersion="5310"` 匿名请求，`lang="zh"` / `"en"` 均成功返回版本 304（资源文件名含 `V3.0.4`）与不同 `fileId`/URL；响应中的 `data.keyId="115"` 是服务端记录字段，不应替换请求的型号名称 | 静态（服务端响应） | 中文响应（`rose_reverse/analysis/ota_links/i7-zh-client5310.json`）、英文响应（`rose_reverse/analysis/ota_links/i7-en-client5310.json`） |
| 资源可访问性 | 两个 i7 资源的 HEAD 均为 200，`bytes=0-63` Range 均为 206，前 64 字节全部为 ASCII hex；只证明资源可访问与文本前缀形式，不代表已下载/校验完整固件或实际升级 | 静态（服务端响应） | [OTA 更新包链接](../ota-package-links.md) |
| 构建号边界 | `clientVersion="4150"` 仍得到版本 304；`"4149"` 得到 `version=1`、`versionDescribe="Client Version is too low"` 和空 `otaUrl`。构建号门槛不能当成固件历史版本选择器，低版本失败响应中的 `version=1` 也不是已取得的历史升级包 | 静态（服务端响应） | 4150 响应（`rose_reverse/analysis/ota_links/i7-zh-client4150.json`）、4149 响应（`rose_reverse/analysis/ota_links/i7-zh-client4149.json`） |
| 语言 | i7 配置没有独立 `LangItem`，也没有 `ota_upgrade_language_switch`；BES 页没有 WQ 页的 en/zh 切换回调。共享 Sender 具备 language 方法，不代表 i7 固件/UI 支持该功能 | 静态 | BES 页（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/bes_ota_page.dart:586`）、i7 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:30`） |

HeadFeel 的 OTA 面板没有 `hasEnglish`，配置也没有 OTA 语言切换；不能套用 i5 的 WQ 状态机、语言固件或 `keyId`（静态，HeadFeel OTA 面板（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:350`）、customConfigEarObject（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:702`））。CERAMICS-U [HSC-2710] 只有 BES 路由配置，元数据请求、`keyId`、语言和固件版本均未核对。

## 未知与缺口

- SPP 连接的实际 UUID 与配对流程、广播多项规范化与 BR/EDR 地址未验证（i7 待实机清单（`rose_reverse/analysis/i7_config_ota_findings.md`））。
- 固件兼容/签名/尾部 3 字节含义未验证；完整固件未下载，`compareOtaDataWithEncodeData` 恒 true，不能声称完整性校验或“可刷任意固件”。
- BES 所有 opcode 的未知回包格式与长度、GATT/流式跨帧拼接未恢复；`process` 只按 `H[0:2]` 分类且不校验包头长度。
- 断点续传、左右耳角色和 `7B` side 语义未证明；`8D` 的断点 payload 未被读取，不能声称实现设备端断点恢复。
- OTA 后复位/重连与电源中断恢复未验证。
- HeadFeel 与 CERAMICS-U [HSC-2710] 的 BES 会话未恢复，只有路由与通道配置；不能把 i7 的会话细节当作这两款已实现。
- i7 配置中 `sinceVersion` raw `600→300` 属于 AI 业务入口，不是 OTA 门槛；OTA 面板本身未写 `sinceVersion`（i7 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:485`））。
- 没有 BES 实机 OTA 记录；离线 BES 编解码模块只构造字节，参考 BLE 客户端未暴露 OTA 发送入口。

## 维护

本文件收录所有 `otaType=bes` 型号共用的 OTA 事实。新增型号先核对配置中的 `otaType`、OTA 面板参数（`needCheckWwsMode`/`sinceVersion`/`hasEnglish`）、OTA SPP UUID 与 BLE 服务/特征字段，再在本文件型号表加一行；型号文档的 `## OTA` 只保留本型号路由、门槛和与本文的差异，不重复帧格式、窗口、ACK、状态机。字节格式、分块、ACK 或状态机的改动必须附 Blutter 地址或实机记录；实机结论注明记录文件和条目。链接用相对路径，研究文件写到 `../../analysis/…`，站内文档写到 `../…`。
