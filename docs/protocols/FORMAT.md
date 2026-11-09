# 协议文档格式

`docs/protocols/` 下的协议研究在 rose_reverse 工作区撰写，再复制到这里。本文规定文档结构、验证状态、出处写法和复制规则。OpenRose 的 `add-model` 按这里的章节读取型号文档；rose_reverse 的 `model_brief.py` 读取族文档的“控件与命令”表。

## 文件与维护方

| 路径 | 内容 | 维护方 |
| --- | --- | --- |
| `README.md` | 导读与索引 | OpenRose |
| `FORMAT.md` | 本文 | OpenRose |
| `model-adaptation-ledger.md` | `add-model` 的证据账本：已适配型号的每项证据如何归类 | OpenRose |
| `00-supported-models-and-capabilities.md`、`supported-devices.json` | 全部型号配置的识别与能力清单 | rose_reverse |
| `families/<协议族>.md` | 协议族文档 | rose_reverse |
| `models/<型号>-protocol.md`，可另附同名 `.json` | 型号文档 | rose_reverse |
| `ota-package-links.md`、`ota-package-links.json` | OTA 研究 | rose_reverse |

rose_reverse 维护的文件在这里是副本。发现错误时回到 rose_reverse 修正后重新复制，不直接改副本。OpenRose 对研究内容的解读和适配记录写在 OpenRose 维护的文件里，或写进 [实现说明](../protocols.md)。

文件名只用小写字母、数字和连字符，如 `budsfeel-mk2-protocol.md`。协议族文件名取 App 的协议标识：`rs-common-v2`、`rs-common`、`zt`、`f8`。

## 验证状态

每条事实标一个状态，只用下面四个词：

| 状态 | 含义 | 须附 |
| --- | --- | --- |
| 实机 | 在真实耳机上观测到 | 实机记录的文件与条目 |
| 静态 | 从 App 代码、资源或对象池直接读出 | 代码位置 |
| 推断 | 由静态事实归纳，代码里没有直接写出 | 推断依据 |
| 未知 | 没有找到证据 | 已经查过的范围 |

字节样例另注来源：“抓取”表示出自实机记录，“合成”表示按规则构造。“App 为该型号配置了控件”是静态事实；设备是否接受对应命令，在有实机记录之前都是未知。

## 出处

| 引用对象 | rose_reverse 源文件 | OpenRose 副本 |
| --- | --- | --- |
| 研究文件（`analysis/`、`tests/`、`examples/`、APK） | 相对链接 `[说明](../../analysis/<路径>#L<行>)` | ``说明（`rose_reverse/analysis/<路径>:<行>`）`` |
| `docs/` 内的其他文档 | 相对链接 | 相对链接，原样保留 |
| JSON 的 `path` 字段 | 仓库相对路径 `analysis/<路径>` | `rose_reverse/analysis/<路径>` |
| 代码地址 | `0x…`，即 `libapp.so` 的 AOT 虚拟地址 | 原样保留 |

不写本机绝对路径（`/home/…`），副本会随 OpenRose 公开。Blutter 行号对应 rose_reverse `AGENTS.md` 记录的 APK 与 Blutter 版本。

## 协议族文档

`families/<协议族>.md` 收录全族共用的事实，型号文档只写差异。章节依次为：

1. 开头一段：配置数量、APK 基线、验证概况。
2. `## 全族共用`：实现类（Sender、Receiver、ReceiveHandler，及用了其他实现类的型号），加“环节 | 结论 | 细节与证据”表，覆盖广播识别、广播字段、控制通道选择、控制特征、发送帧、初始化、命令编码、接收解析、客户端状态建议。细节可以链接到参照型号文档的小节，不必重写。
3. `## 按型号变化`：“项 | 去哪里查”表。
4. `## 控件与命令`：“控件类 | 功能 | Sender 方法 | 说明”表。第三列只写反引号包住的方法名，或写“结构”（容器、展示控件）、“范围外”（OTA、云端业务）、“—”（尚未找到对应方法）。只凭方法名对上、没有核对控件调用的行，在说明列写“按方法名推断”。`model_brief.py` 按列位置读取此表，不要增删列。
5. `## 全族已知的坑`：每条一句，附出处。
6. `## 维护`：写回本文的规则。

## 型号文档

`models/<型号>-protocol.md` 只写本型号与族文档不同的部分，以及本型号的取值；与族文档相同的项写“同族默认”并链接到族文档。协议族还没有族文档时，先写族文档。

标题为 `# <显示名> 控制协议`，下接一段：配置类、APK 基线、所属协议族（链接族文档）、验证概况。之后的章节依次为：

| 章节 | 内容 |
| --- | --- |
| `## 识别` | “分支 \| 识别前缀 \| 内部名 \| 显示名 \| 状态 \| 出处”表，同一零售名称的每个硬件分支一行；表后列出系统设备名里可能出现的零售名称与旧名 |
| `## 传输` | “项 \| 值 \| 状态 \| 出处”表，行固定为 BLE 服务、写特征、通知特征、SPP 是否允许、控制 SPP UUID、广播中的经典地址、写入间隔；OTA 等其他通道只列 UUID |
| `## 初始化` | 初始化目标（分派表项与函数）和它发送的查询帧 |
| `## 电量` | 电量槽布局：左右耳与仓、仅左右耳、单体（复用仓槽），或其他；附状态与出处 |
| `## 设置命令` | “功能 \| Sender 方法 \| BODY \| 取值 \| 固件门槛 \| 读回 \| 状态 \| 出处”表，规则见下 |
| `## 接收字段` | 只列处理方式或取值与族文档不同的 Tag：“Tag \| 字段 \| 本型号取值或差异 \| 状态 \| 出处”；全部相同时写“同族文档” |
| `## 未实现与未知` | 每条写明是什么、为什么没有结论、查过哪里 |
| `## OTA` | OTA 路由与门槛；OpenRose 不实现 OTA，此节只作记录 |

“设置命令”表中，App 为该型号配置的每个控件各占一行，没有字节级证据的控件也要列出：

- 取值：App 在本型号上提供的线值，写成“界面名=线值”，不是 Sender 能接受的全部值。
- 固件门槛：解码后的整数（Dart Smi 右移一位），没有门槛写“—”。
- 读回：确认设置所用的查询，以及回包的接收 Tag。
- 没有字节级证据的控件：BODY 写“—”，状态写“未知”，原因写进“未实现与未知”。

早于本格式的型号文档章节不同，修订该型号时改写为上面的结构。

### 型号 JSON

可选。提供时，顶层至少包含现有 i5、i7 JSON 共有的键：`schema_version`、`model`、`internal_name`、`source_apk`、`evidence_type`、`hardware_validated`、`transport`、`tx_layout`、`rx_layout`、`fixed_commands`、`setter_families`、`notifications`、`ota_status`。证据对象为 `{"path", "line", "address"}`，可加 `function`、`class`、`note`；`path` 按“出处”一节书写。

## 复制到 OpenRose

1. 先在 rose_reverse 提交，OpenRose 的提交说明写明对应的 rose_reverse 提交号。
2. 复制 rose_reverse `docs/` 下除 `README.md` 外的全部文件，目录结构不变。
3. 按“出处”一节改写研究文件链接和 JSON 的 `path`。源文件中残留的 `/home/…/rose_reverse/` 前缀一律改为 `rose_reverse/`。
4. 检查副本：不含 `/home/`；`docs/` 内的相对链接与锚点都能解析。
5. 更新本目录 `README.md` 的型号与协议族索引。
