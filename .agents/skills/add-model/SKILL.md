---
name: add-model
description: 适配耳机型号：新增 ROSE 型号配置，或修改已有型号的识别、能力、传输与电量布局时使用。
---

# 适配耳机型号

一个型号配置 = `ModelProfile` 枚举项 + `core/src/commonMain/kotlin/io/github/dohex/openrose/core/models/` 下的一份 `ModelSpec`。界面、写入校验、系统集成都从它推导，所以工作重心是**证据账本**：型号的每一项能力都对应一条研究证据，或明确记为“不实现”。

先读 `GLOSSARY.md` 中“型号配置”“型号能力”两条，以及 [ADR 0002](../../../docs/adr/0002-shared-kotlin-core-and-platform-adapters.md)。

## 1. 建证据账本

协议文档的结构、验证状态用词和出处写法见 [docs/protocols/FORMAT.md](../../../docs/protocols/FORMAT.md)。证据来源，按可信度从高到低：

1. 型号文档 `docs/protocols/models/<型号>-protocol.md`（及同名 `.json`）。“识别”“传输”“初始化”“电量”“设置命令”“接收字段”各表的每一行直接成为一条账本记录，“设置命令”表每行对应一项能力；“传输”表写“同族默认”的行到族文档的“默认传输”取值，“OTA”节的字节格式与状态机在 `docs/protocols/ota/<方案>.md`。
2. 协议族文档 `docs/protocols/families/<族>.md`：型号文档写“同族默认”的项到这里取值；“全族已知的坑”逐条判断是否适用于本型号。
3. `docs/protocols/00-supported-models-and-capabilities.md` 中该型号所在行，以及 `supported-devices.json` 的 `models`：识别前缀、协议族、电量槽、能力入口。
4. 已实现的同族型号（`core/.../models/` 下的文件）和对应编解码，只作为族内共性的参照。

每条记录写明值、出处（文件与小节）和 FORMAT 的验证状态（实机 / 静态 / 推断 / 未知）。“App 配置了控件”只是静态事实；没有字节级证据（BODY 为“—”或状态为“未知”）的能力记为“不实现”并写明原因。账本写进 `docs/protocols/model-adaptation-ledger.md`，沿用其中已有的结构。

`docs/protocols/` 下除 `README.md`、`FORMAT.md` 和账本外，都是 rose_reverse 的副本。发现副本有误时写进最终报告，由 rose_reverse 修正后重新复制，不在本仓库改副本。

同一零售名称有多个识别前缀时（如 CERAMICS-MK2 三个分支、OpenFeel 三个前缀），能力、传输或固件门槛不同的分支各建一个枚举项；完全相同的分支合并到一个 `ModelSpec` 的 `advertisementPrefixes`。

**完成标准**：该型号在清单行中的每一列、型号文档的每一节（按 FORMAT 组织时为各表的每一行），在账本里都有一条记录。

## 2. 给每条记录归类

- **A 只写配置**：协议族已有编解码，帧与字段完全相同。
- **B 族内差异**：同族但帧、字段或枚举取值不同。在 `ModelSpec` 增加字段，由该族编解码读取；ZT 各型号在 RoseLink 中有不同的 Sender 子类（i3 / i5 / HeadFeel），默认按存在差异处理。
- **C 新能力类型**：现有 `DeviceSetting` / `DeviceField` 表达不了（低音增强、寻机、提示音语言、空间音频等）。
- **D 新协议族**：F8、RS Common 等尚无编解码的协议族。

C、D 分支的改动面见 [new-capability.md](new-capability.md)。

**完成标准**：账本每条记录都有 A–D 之一或“不实现”。

## 3. 写型号配置

在 `ModelProfile` 增加枚举项（命名 `<型号>_<协议族>`，如 `I5_ZT`）并在 `spec` 中映射；新建 `models/<型号>.kt`。账本到 `ModelSpec` 的对应：

| 证据（FORMAT 章节） | 字段 | 注意 |
| --- | --- | --- |
| 识别前缀（识别） | `advertisementPrefixes` | 小写十六进制，长度 12 / 8 / 6；清单中的前缀即规范化后的“company ID 小端 + payload” |
| 零售名称（识别） | `nameBrand`、`nameModelNumber`、`nameRequiresModel`、`displayName`、`shortLabel` | `match` 先按型号词判定，型号词被多个型号共用时（如 BudsFeel MK2 与 OpenFeel MK2 都含 `mk2`）再看品牌词；品牌词或型号词单独出现会认错型号时，设 `nameRequiresModel = true`，要求两者同时出现。规则不够用时扩展 `ModelProfile.match`，不要换个词绕过 |
| 协议族（开头） | `family` | |
| 控制传输（传输） | `gatt`、`sppControlUuid`、`writeIntervalMillis` | 只有协议文档给出 RFCOMM UUID 时才填 SPP；SPP 连接还要有经典地址（广播解析或已配对设备地址），否则回退 BLE |
| 广播里的经典地址（传输） | `advertisedClassicAddress` | 有文档给出偏移时才写解析函数，否则为 `null` |
| 电量槽（电量） | `batteryLayout` | 左右耳与仓 → `EARBUDS_AND_CASE`；仅左右耳 → `EARBUDS_ONLY`，系统界面不发布仓电量；单体（复用 charge 槽）→ `SINGLE_UNIT`，应用显示一行“电量”，系统超级岛、焦点通知和融合设备中心不发布其电量 |
| EQ 预设的 App 显示名（设置命令的取值列） | `equalizerPresetLabels` | |
| 能力与固件门槛（设置命令） | `capabilities` | `writableSettings` 只列 A/B 类且有字节级证据的设置；固件门槛用 `firmwareComparisonInteger`（RoseLink 拼接十进制比较）；`readableFields` 取 `family.readableFields` |

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
3. 更新 `README.md` 与 `README.en.md` 的“支持的耳机”表、`docs/protocols.md`（实现说明与代码位置）和 `docs/protocols/README.md` 的验证情况；`docs/protocols/` 下的副本不改。
4. 最终报告列出：账本中“不实现”的能力及原因；离线验证与实机验证分开写，没测过的写“未验证”。

**完成标准**：`offlineCheck` 通过，第 1 条的搜索结果干净，账本中没有未归类的记录。
