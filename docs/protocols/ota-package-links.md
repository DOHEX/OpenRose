# i5 / i7 官方 OTA 更新包链接与获取方法

查询日期：2026-10-14（北京时间）。来源是 RoseLink 3.8.0 / build 5310 的官方元数据接口，以及更新包服务器的实际响应。

**已经获取当前 i5 的 125 版、i7 的 3.0.4 版，各有 zh/en 两条资源链接；尚未取得完整历史版本列表。** 以下版本是查询时服务器选择的候选更新版本，不代表所有设备硬件或固件都可以升级至它。

## 1. 已验证的下载链接

下列四条链接的查询参数 `e` 对应 **2026-10-15 15:39:26（北京时间）**。链接格式符合七牛私有下载 URL；按其 `e` 为 Unix 秒级到期时间的规范解读。该签名语义依据格式与[七牛官方说明](https://developer.qiniu.com/kodo/1656/download-private)判断，本次没有等待到期再测试服务器拒绝行为。过期后重新查询元数据获取新链接。保留完整的 `?e=...&token=...`，不要仅复制文件路径。

| 型号 | 固件版本 | 请求语言 | 资源大小（字节） | 下载 | 原始响应 |
|---|---|---|---:|---|---|
| i5 | 125 | `zh` | 1,120,750 | [官方更新包](https://gd2-cdn.rosetechnic.com/data/2024-12-08/125.bin.txt-8faff4e5-8e81-4b8f-83d1-f341b769318b..webp?e=1792049966&token=xW4-x9hAIcl3ByVaqk_SB-aMIgvJL9jgmFeB11tI:XJ_wAmf8nHoyEmxvatcxAIUO3Ns=) | 查询与资源检查（`rose_reverse/analysis/ota_links/i5-zh-client5310.json`） |
| i5 | 125 | `en` | 1,115,428 | [官方更新包](https://gd2-cdn.rosetechnic.com/data/2024-12-08/en-125.bin.txt-363d0e40-726b-4ec2-a512-116575382f83..webp?e=1792049966&token=xW4-x9hAIcl3ByVaqk_SB-aMIgvJL9jgmFeB11tI:rKpmb_Cw--B-DO3aWYR1NBYGXbo=) | 查询与资源检查（`rose_reverse/analysis/ota_links/i5-en-client5310.json`） |
| i7 | 3.0.4 | `zh` | 2,793,010 | [官方更新包](https://gd2-cdn.rosetechnic.com/data/2026-08-14/ZT239_BES2700IH_OTA_LDAC_LHDC_AI_V3.0.4_20260813-e5e1afb5-d2ce-4787-8ff5-9592067bf29e.txt?e=1792049966&token=xW4-x9hAIcl3ByVaqk_SB-aMIgvJL9jgmFeB11tI:7srdcqW1xmQBVnFGJaWTjXW7J_A=) | 查询与资源检查（`rose_reverse/analysis/ota_links/i7-zh-client5310.json`） |
| i7 | 3.0.4 | `en` | 2,793,010 | [官方更新包](https://gd2-cdn.rosetechnic.com/data/2026-08-14/ZT239_BES2700IH_OTA_LDAC_LHDC_AI_V3.0.4_20260813-9049cc98-b7fc-4dbc-b80a-04743d9e82ae.txt?e=1792049966&token=xW4-x9hAIcl3ByVaqk_SB-aMIgvJL9jgmFeB11tI:jyeuiXgGjJPHfdEa3ByFZUNxgDE=) | 查询与资源检查（`rose_reverse/analysis/ota_links/i7-en-client5310.json`） |

i7 的服务端版本整数为 `304`，文件名明确包含 `V3.0.4`；i5 的服务端整数和文件名均为 `125`，本索引保留该写法，没有自行补成带点版本。

四个 URL 的 HEAD 均返回 HTTP 200，Content-Type 均为 `text/plain`；分别读取前 64 字节时均返回 HTTP 206，样本均为 ASCII 十六进制文本。**未下载完整固件、未执行升级，尚未验证完整资源的格式、哈希或设备兼容性。** 表中的大小是服务器公布的文本资源长度，不能直接当作解码后的固件字节数。i5 URL 虽以 `.webp` 结尾，但此资源响应为文本，不能按图片处理。

i5 的 zh/en 文件 ID、长度、ETag、前缀样本不同；i7 的 zh/en 文件 ID 和 URL 不同，长度、ETag、前缀样本相同。没有对完整文件逐字节比较，不能据此断言 i7 两个文件完全相同，也不能把 i7 的 `lang` 参数直接解释为已验证的耳机提示音语言切换。

### 更新说明与资源标识

| 型号 / 语言 | fileId | encodeData | 官方 versionDescribe |
|---|---|---|---|
| i5 / zh | `1865605042582433794` | `00000008000000010000012500004150` | 1、新增降噪梯度选择支持<br>2、新增通透梯度选择支持<br>3、新增提示音大小选择支持<br>4、新增查找耳机支持 |
| i5 / en | `1865605264821825538` | `00000008000000020000012500004150` | 1. Added support for selecting noise reduction gradients<br>2. Added support for transparent gradient selection<br>3. Added support for selecting the size of the prompt sound<br>4. Added support for finding headphones |
| i7 / zh | `2088197917412392961` | `null` | 1.优化充电问题 |
| i7 / en | `2088198128564355074` | `null` | 1. Optimize charging issues |

`encodeData` 是原始元数据字段，不是本次计算的校验值。现有研究没有证明其设备端验签含义。完整原始字段（oid、earType、minClientVersion、betaFlag 等）保留在 [链接索引 JSON](ota-package-links.json) 和上述原始响应文件中。索引 JSON 的结构由本次研究定义，`official_metadata` 下才是服务端原样返回的 `data` 对象。

## 2. 如何重新获取有效链接

接口：`POST https://api-internal.rosetechnic.com/app/ota/v2/get-by-key-id-and-tag`。本次匿名请求成功，不需要登录账号或读取用户凭证；使用普通 JSON 请求，没有发现此调用需要额外签名或加密。

```json
{"keyId":"earfeel-i7","pid":"","tag":1,"clientVersion":"5310","lang":"zh"}
```

| 请求字段 | i5 | i7 | 语义 |
|---|---|---|---|
| keyId | `earfree-i5` | `earfeel-i7` | 型号 trackingName；不是 BLE 广播识别串。服务端响应中的 keyId 可能是数值字符串，不应替换这里已验证的请求值。 |
| pid | `""` | `""` | 普通耳机目标的空字符串。 |
| tag | `1` | `1` | 升级自身耳机；其他枚举为 dongle=2、rx=3、tx=4。不是固件版本号或渠道。 |
| clientVersion | `"5310"` | `"5310"` | RoseLink App 构建号，JSON 类型为字符串；不是耳机当前或目标固件版本。 |
| lang | `zh` / `en` | `zh` / `en` | App 传入的语言参数；本次两种语言均查到记录。 |

```sh
curl --fail-with-body --connect-timeout 10 --max-time 30 \
  'https://api-internal.rosetechnic.com/app/ota/v2/get-by-key-id-and-tag' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  --data '{"keyId":"earfeel-i7","pid":"","tag":1,"clientVersion":"5310","lang":"zh"}'
```

响应外层为 `code` / `reason` / `data`；当前成功响应的 `code` 是字符串 `"200"`。客户端先检查 code，再检查 data 为对象且 `data.otaUrl` 为非空 URL。不能只凭 HTTP 200 或某个 version 数值判断有固件可下载。

| 服务端 data 字段 | 客户端用途 |
|---|---|
| version | 固件版本整数，用于显示/比较；不是下载文件序号。 |
| versionDescribe | 官方更新说明。 |
| otaUrl | 本次真实服务端响应的更新包 URL；完整保留签名查询参数。 |
| fileId | 官方资源标识；可用于记录本次候选包，不是已恢复的按 ID 下载 API。 |
| minClientVersion | 所需最低 App 构建号；此次四个候选包均为字符串 `4150`。 |
| encodeData | 原始附加字段，应保留；不据此声称验签已完成。 |
| betaFlag / activeFlag | 本次均为 false / 1；不代表已经找到查询 beta 或历史固件的参数。 |

注意官方 App 的两层命名：云端字段为 **`otaUrl`**；`DeviceService._buildOtaResult` 把该 URL 放进 App 内部 Map 的 **`otaContent`**。之后 `getOtaContent` 再通过静态资源客户端执行 GET 下载文本。`otaContent` 不是本次服务端响应的 URL 字段名。

已附只查询元数据的 获取脚本（`rose_reverse/examples/fetch_ota_links.py`），仅需 Python 标准库，JSON 输出包含原始响应、完整链接与签名到期时间：

```sh
# 默认获取 i5/i7 的 zh/en 四条当前记录
python3 examples/fetch_ota_links.py

# 只查询 i7 中文记录
python3 examples/fetch_ota_links.py i7 --lang zh
```

该脚本不下载固件，也不连接耳机。网络错误、业务 code 失败或缺少 otaUrl 时会保留已取得的响应并以状态码 2 退出。重新查询只能取得服务器当时选择的候选包；即使记录旧 fileId/路径，也没有证明能重新签发该旧文件的 URL。

## 3. 历史版本目前能否全部取得

**目前只能确认当前包可取，不能保证取到每个历史版本。** 在此 APK 已恢复的 OTA 字符串与调用链中，没有找到固件历史列表、分页或指定固件版本的请求参数。接口按型号、目标、App 构建号和语言返回单个对象。

| i7 / zh 实测 App 构建号 | 返回结果 | 对历史版本的含义 | 原始响应 |
|---|---|---|---|
| 5310 | version=304，有 otaUrl | 当前候选包 | 响应（`rose_reverse/analysis/ota_links/i7-zh-client5310.json`） |
| 4150 | version=304，与 5310 相同 fileId | 兼容边界仍返回当前包 | 响应（`rose_reverse/analysis/ota_links/i7-zh-client4150.json`） |
| 4149 | version=1，说明 `Client Version is too low`，无 otaUrl | App 过旧提示；不是可下载的“固件版本 1” | 响应（`rose_reverse/analysis/ota_links/i7-zh-client4149.json`） |

另一个路径 `/app/ota/v2/check-type` 被 App 用于调音社区的 `earTypeId` → `resourceName` 字符串补全，不是版本列表。`ota-beta` 是检查 `versionDescribe` 是否含标记后跳过主动更新提醒；没有找到独立 beta 渠道查询入口。此结论限定于本 APK 观察到的接口，不代表厂商服务端一定不存在内部历史接口。

继续获取旧版包需要已有历史下载 URL/元数据响应、旧版 App 的接口或缓存证据，或厂商提供的固件存档。即使旧文件仍保留在服务器，没有目录或签发入口也不能仅靠版本数字拼出含 UUID 和签名的可用 URL。本次没有发现可证明的完整历史入口。

## 4. 证据与协议关联

- 请求构造、匿名鉴权与下载封装（`rose_reverse/analysis/ota_request_findings.md`）
- 历史接口静态调查与参数证据（`rose_reverse/analysis/ota_history_api_findings.md`）
- 官方 fetchDeviceOtaByKeyId 的恢复代码（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:476`）
- otaUrl → 内部 otaContent 的字段映射（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10882`）
- [WQ OTA 方案（i5 等）：固件来源与完整性](ota/wq.md#固件来源与完整性)；[i5 协议](models/earfeel-i5-protocol.md)
- [BES OTA 方案（i7 等）：固件来源与完整性](ota/bes.md#固件来源与完整性)；[i7 协议](models/earfeel-i7-protocol.md)
