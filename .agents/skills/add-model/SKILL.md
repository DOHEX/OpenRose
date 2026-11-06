---
name: add-model
description: 适配耳机型号：新增 ROSE 型号配置，或修改已有型号的识别、能力、传输与电量布局时使用。
---

# 适配耳机型号

一个型号配置 = `ModelProfile` 枚举项 + `core/src/commonMain/kotlin/io/github/dohex/openrose/core/models/` 下的一份 `ModelSpec`。界面、写入校验、系统集成都从它推导，所以工作重心是**证据账本**：型号的每一项能力都对应一条研究证据，或明确记为“不实现”。

先读 `GLOSSARY.md` 中“型号配置”“型号能力”两条，以及 [ADR 0002](../../../docs/adr/0002-shared-kotlin-core-and-platform-adapters.md)。

## 1. 建证据账本

证据来源，按可信度从高到低：

1. `docs/protocols/models/<型号>-protocol.md`（及同名 `.json`）：字节级帧、字段、枚举、固件门槛。
2. `docs/protocols/00-supported-models-and-capabilities.md` 中该型号所在行，以及 `supported-devices.json` 的 `models`：识别前缀、协议族、电量槽、能力入口。
3. 已实现的同族型号（`EarFreeI5.kt`、`EarFeelI7.kt`）和对应编解码，只作为族内共性的参照。

逐列、逐节登记：值、出处（文件与小节）、验证状态（实机 / 仅静态分析 / 合成）。清单里的“有”只说明 App 配置了入口，不等于有字节级证据；缺少字节级证据的能力记为“不实现”并写明原因。

同一零售名称有多个识别前缀时（如 CERAMICS-MK2 三个分支、OpenFeel 三个前缀），能力、传输或固件门槛不同的分支各建一个枚举项；完全相同的分支合并到一个 `ModelSpec` 的 `advertisementPrefixes`。

**完成标准**：该型号在清单行中的每一列、协议文档中的每一节，在账本里都有一条记录。

## 2. 给每条记录归类

- **A 只写配置**：协议族已有编解码，帧与字段完全相同。
- **B 族内差异**：同族但帧、字段或枚举取值不同。在 `ModelSpec` 增加字段，由该族编解码读取；ZT 各型号在 RoseLink 中有不同的 Sender 子类（i3 / i5 / HeadFeel），默认按存在差异处理。
- **C 新能力类型**：现有 `DeviceSetting` / `DeviceField` 表达不了（低音增强、寻机、提示音语言、空间音频等）。
- **D 新协议族**：F8、RS Common 等尚无编解码的协议族。

C、D 分支的改动面见 [new-capability.md](new-capability.md)。

**完成标准**：账本每条记录都有 A–D 之一或“不实现”。

## 3. 写型号配置

在 `ModelProfile` 增加枚举项（命名 `<型号>_<协议族>`，如 `I5_ZT`）并在 `spec` 中映射；新建 `models/<型号>.kt`。账本到 `ModelSpec` 的对应：

| 证据 | 字段 | 注意 |
| --- | --- | --- |
| 识别前缀 | `advertisementPrefixes` | 小写十六进制，长度 12 / 8 / 6；清单中的前缀即规范化后的“company ID 小端 + payload” |
| 零售名称 | `nameBrand`、`nameModelNumber`、`displayName`、`shortLabel` | `match` 用 `contains`、按枚举顺序判定：型号词或品牌词与已有型号重叠时（如 BudsFeel MK2 与 OpenFeel MK2 都含 `mk2`），扩展 `ModelProfile.match` 的规则，不要换个词绕过 |
| 协议族 | `family` | |
| 控制传输 | `gatt`、`sppControlUuid`、`writeIntervalMillis` | 只有协议文档给出 RFCOMM UUID 时才填 SPP；SPP 连接还要有经典地址（广播解析或已配对设备地址），否则回退 BLE |
| 广播里的经典地址 | `advertisedClassicAddress` | 有文档给出偏移时才写解析函数，否则为 `null` |
| 电量槽 | `batteryLayout` | “单体（复用 charge 槽）”→ `SINGLE_UNIT`：应用显示一行“电量”，系统超级岛、焦点通知和融合设备中心不发布其电量 |
| EQ 预设的 App 显示名 | `equalizerPresetLabels` | |
| 能力与固件门槛 | `capabilities` | `writableSettings` 只列 A/B 类且有字节级证据的设置；固件门槛用 `firmwareComparisonInteger`（RoseLink 拼接十进制比较）；`readableFields` 取 `family.readableFields` |

`writableSettings` 同时决定界面是否出现该控制和 `SettingQueue` 是否放行写入，宁缺毋滥。

**完成标准**：`./gradlew :core:compileKotlinJvm` 通过，且 `ModelSpec` 每个字段的值都能在账本中找到出处。

## 4. 编解码与测试

- B 类差异：在族编解码中读取新加的 `ModelSpec` 字段；解码表（`ztFields`、`rsCommonV2Fields`）和 RS Common V2 的共享 `getInfo` 批量查询属于协议族，保持共用。
- 测试替身：编译器会在 `androidIntegration` 的 `OwnerRig.kt` 中报出 `roseAdvertisement`、`initializationReplies` 缺少分支，在那里登记该型号的广播与初始化回包；回包注明来源（实机记录或合成）。
- 编解码测试沿用现有写法：期望帧用协议文档中独立提取的十六进制字面量，不用编解码自己算出的值；合成的接收报文在注释中标“合成”。
- `ModelProfileTest` 会自动检查新型号能凭自己的前缀和名称被识别。

## 5. 验证与文档

1. 在各模块 main 源码中搜索新枚举名：除 `ModelProfile` 枚举和 `spec` 映射外不应出现。出现即说明某处在按型号分支，改为读取 `ModelSpec`。
2. 运行 `./gradlew spotlessApply offlineCheck`。detekt 基线只减不增：新代码直接满足规则；`baseline-limit.txt` 只在基线条目减少时同步下调。
3. 更新 `README.md` 与 `README.en.md` 的“支持的耳机”表、`docs/protocols.md`（实现说明与代码位置），必要时更新 `docs/protocols/README.md` 的验证情况。
4. 最终报告列出：账本中“不实现”的能力及原因；离线验证与实机验证分开写，没测过的写“未验证”。

**完成标准**：`offlineCheck` 通过，第 1 条的搜索结果干净，账本中没有未归类的记录。
