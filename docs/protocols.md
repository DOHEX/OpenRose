# ZT / RS Common V2 协议实现说明

本文说明 OpenRose 的 ZT 与 RS Common V2 控制实现：i5、i7，以及新增的 BudsFeel MK2、OpenFeel MK2、Swift 2、Jasmine。内容包括帧格式、字段编码、设置命令、确认机制、测试覆盖，以及尚未经过实机验证的行为。

协议本身的逆向研究（包括其他型号和 OTA）见 [docs/protocols/](protocols/README.md)。本文只讲 OpenRose 实际怎么做，以及和官方 App 不同的地方。

## 目录

- [代码位置](#代码位置)
- [证据与验证状态](#证据与验证状态)
- [通用机制](#通用机制)
- [i5：ZT 协议](#i5zt-协议)
- [i7：RS Common V2 协议](#i7rs-common-v2-协议)
- [设置命令](#设置命令)
- [型号能力与固件门槛](#型号能力与固件门槛)
- [i7 控制通道选择（BLE / SPP）](#i7-控制通道选择ble--spp)
- [测试覆盖](#测试覆盖)
- [待真机验证](#待真机验证)
- [附录：Issue 对照与证据索引](#附录issue-对照与证据索引)

## 代码位置

| 内容 | 位置 |
| --- | --- |
| 型号配置（识别、传输、电量布局、按固件推导的能力） | `core/.../models/` 中每款型号的独立文件；字段定义在 `models/ModelSpec.kt` |
| 型号入口、协议族、广播识别、名称匹配 | `core/.../protocol/ModelProfile.kt` |
| 字段模型与编解码接口 | `core/.../protocol/ProtocolCodec.kt` |
| ZT 编解码（i5） | `core/.../protocol/ZtCodec.kt` |
| RS Common V2 编解码（i7 与四个新增型号） | `core/.../protocol/RsCommonV2Codec.kt` |
| 能力类型（降噪、EQ、触控、音频等） | `core/.../NoiseControl.kt`、`EqualizerCapabilities.kt`、`DeviceControlCapabilities.kt` |
| 控制会话（发送队列、设置确认、连接生命周期） | `core/.../ControlSession.kt` |
| Android BLE / SPP 传输、音频 profile 查询 | `androidApp/.../AndroidBleTransport.kt`、`AndroidSppConnection.kt`、`AndroidAudioProfiles.kt` |

`core/...` 即 `core/src/commonMain/kotlin/io/github/dohex/openrose/core`。编解码器是纯 Kotlin 对象，只负责识别、构帧和解析；扫描、GATT、订阅和串行写入由平台传输层负责。OpenRose 不提供 OTA 入口。

## 证据与验证状态

所有协议依据都来自官方 App **RoseLink 3.8.0（versionCode 5310）**，APK SHA-256 为 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。这些是逆向得出的结论，不是厂商公开的规范。

文中 `rose_reverse/...` 指作者本地的逆向研究工作区（反汇编产物、提取脚本、实机记录），没有随本仓库发布。测试使用的关键数据文件如下：

| 数据 | 文件 | SHA-256 |
| --- | --- | --- |
| i5 发送命令常量（独立提取） | `rose_reverse/analysis/i5_fixed_sender_commands.json` | `40ea600ec08358140c45f0dcff893807262273f6db9506eb7e17519f17dc6a87` |
| i5 实机读取记录 | `rose_reverse/analysis/i5_live_read_2026-10-18.jsonl` | `a2cf839f3d35795203310dfc6f145b5e4e7fb3f326466bd7c27c8728186d3aa4` |
| i7 发送命令常量（独立提取） | `rose_reverse/analysis/i7_sender_commands.json` | `10f4722e1f77f3c0493b8720fb6928b8019a08522a6d5756b7df775b7fdb399d` |
| i5 参考测试（Python） | `rose_reverse/tests/test_i5_protocol.py` | `8e50403bb0bc6ad5f5f139a9947a4c4a6b47d6a182ee543e73b06e8fefda50ca` |
| i7 参考测试（Python） | `rose_reverse/tests/test_i7_protocol.py` | `831c61151b456f69c21617c902b72ce7171f2787b646bda96403183e22da296b` |

**真机数据只有一份**：一副 i5（固件 `125`）在 Linux / BlueZ 5.87 / Bleak 3.0.2 下完成的 13 项初始化读取。测试里凡是 i5 的真实接收报文都出自这份记录。

**i7 没有任何真机数据**。测试中所有 i7 接收报文都是按协议规则构造的合成数据。

因此，下文描述的设置写入、回读时序和持久化行为，在两个型号上都还没有经过真机验证。测试通过只说明实现符合逆向得出的规则。

### 新增型号的证据边界

BudsFeel MK2、OpenFeel MK2、Swift 2、Jasmine 的实现依据各自的[型号协议文档](protocols/README.md#型号协议文档)和结构化能力清单；逐列、逐节的依据及 A–D 分类见[适配证据账本](protocols/model-adaptation-ledger.md)。这四款的广播与通知夹具均为合成数据，实机连接、状态读取、设置效果和持久化均未验证。

四款共用 RS Common V2 字段表、发送序号、接收缓冲和 `getInfo` 批量查询。查询中的功能 ID 不决定可写能力；所有控制仍受各自 `ModelSpec.capabilities.writableSettings` 限制。文档没有给出这四款广播中经典地址的偏移，故不复用 i7 的地址解析；SPP 需平台提供已配对设备的经典地址，否则回退 BLE。控制 UUID 与 OTA UUID 分开，新增型号也不开放 OTA。

Swift 2 的电量由 `charge` 槽承载，在应用中显示一行“电量”，不向超级岛、焦点通知或融合设备中心发布电量。Jasmine 只显示左右耳电量；共享解析器即使解析出仓槽值，也不在应用或系统界面显示充电盒电量。

名称识别同时考虑品牌和型号代际，避免 BudsFeel MK2 与 OpenFeel MK2 的 `mk2` 冲突；缺少 MK2 或 Swift 的代际信息时，不把旧款名称当作新款。

| 新控制 | 型号 | 发送 BODY | 回读确认 |
| --- | --- | --- | --- |
| 低音增强 | BudsFeel MK2、OpenFeel MK2 | `2A 05 value`，0–4 | 查询 `FA 2A`；只有新鲜的 `2A 05 value` 档位字段才能确认 |
| 提示音语言 | Jasmine | `31 00` 中文、`31 01` 英文 | 查询 `FA 31`，按新鲜 `31` 状态确认 |

寻机 `2F` 只有局部事件，不能证明查询可返回完整双耳状态，故没有开放写入。OpenFeel MK2 自定义 EQ 缺少型号专属频率和滑块范围，Swift 2 按键映射缺少物理键对应关系，四款提示音音量缺少型号与固件允许档位；这些控制均未启用。完整未实现列表及依据见证据账本。



## 通用机制

### 设备识别

扫描时用 BLE 广播里的 manufacturer data 识别型号，不看设备名：

1. 按 company ID 从小到大排序；
2. 先拼接所有 company ID（各 2 字节，小端），再按同样顺序拼接所有 payload；
3. 转成十六进制，依次取前 12、8、6 位查找型号前缀，较长的前缀优先（与 RoseLink 相同）。

注意是"全部 ID 在前、全部 payload 在后"，不是每项 ID 和 payload 交替拼接。

| 型号配置 | 前缀 |
| --- | --- |
| i5 ZT | `525300010001` |
| i7 RS Common V2 | `b8e5290f` |
| BudsFeel MK2 RS Common V2 | `0b150a0b` |
| OpenFeel MK2 RS Common V2 | `0b0eb657` |
| Swift 2 RS Common V2 | `5999064c` |
| Jasmine RS Common V2 | `0b0a0a11` |

前缀只用来识别型号，不是蓝牙地址。连接时使用系统扫描结果给出的设备 ID。测试中的单 company 广播样例是按算法推导的，不是抓包数据。

另有一条按名称匹配的路径（`ModelProfile.match`）：读取系统**已配对设备列表**、以及观察系统音频连接时，没有广播数据可用，只能按设备名（包含 `earfree` / `earfeel` / `i5` / `i7` 等）判断是否为 ROSE 耳机。Android 伴生设备唤醒的名称过滤（`ModelProfile.namePattern`）由同一组品牌词生成，不另行维护。

### GATT 与发送队列

| 型号配置 | 服务 | 写特征 | 通知特征 |
| --- | --- | --- | --- |
| i5 ZT | `011bf5da-0000-1000-8000-00805f9b34fb` | `00007777-0000-1000-8000-00805f9b34fb` | `00008888-0000-1000-8000-00805f9b34fb` |
| i7 RS V2 | `000000fe-0000-1000-8000-00805f9b34fb` | `000000f1-0000-1000-8000-00805f9b34fb` | `000000f2-0000-1000-8000-00805f9b34fb` |

四个新增型号均使用表中 RS V2 的正常控制 GATT 参数；其 SPP 控制服务为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`，写入间隔为 100 ms。型号配置保留显式传输参数，编解码不按型号枚举分支。

- 在同一服务内找写特征和通知特征。写特征支持无响应写入就用无响应写入，否则用有响应写入。
- 先完成通知订阅（含 CCCD），再发初始化查询。
- 所有写入走同一个串行队列，每次写入完成后间隔 100 ms 再发下一条。这个间隔不是等待回复的超时。
- 连接、服务发现、通知订阅、收到第一条可信字段、所有字段读取完成，是分开记录的几个阶段。

### 字段状态

每个字段的读数是以下四种之一：

| 状态 | 含义 |
| --- | --- |
| `Loading` | 还没读到 |
| `Known` | 读到并解析成功 |
| `Unknown` | 读到了，但值无法识别或长度不对 |
| `NotProvided` | 设备明确表示没有这个值（例如电量 `FF`） |

每个读数都保留原始字节（`rawHex`）。未知、长度错误、未提供的字段**不会**被当成"关闭"或 0。

只有收到至少一个有意义的 `Known` 字段，才算协议就绪。只收到 `NotProvided` 或电池原始标志位不算。

### 请求与回复的匹配

- i5：按命令字匹配回复。
- i7：按回复中出现的字段类型集合匹配。发送序号和接收帧第二字节都不参与匹配。
- 初始化查询里的共享未知 ID，不要求设备一定回复。

平台层保证同一时刻只有一个连接所有者、请求串行发送、超时和断开时清理状态、不同会话之间互不干扰。编解码器不会把"GATT 写入完成"当作"状态已读到"。

### 设置流程

所有设置都走同一个流程：

1. 会话调用 `prepareSetting(setting, value, firmware)`，得到：
   - `writeFrames`：要依次写入的帧；
   - `readback`（以及可能的 `additionalReadbacks`）：写入后用来确认的查询；
   - `expectedValues`：期望回读到的字段值。
2. 参数检查全部在构帧之前完成。非法参数抛 `IllegalArgumentException`，型号不支持的设置或选项抛 `UnsupportedOperationException`，会话把它们转成明确的失败结果。被拒绝的请求不消耗 i7 序号。
3. 写入帧和回读查询在同一个发送队列里执行。写入完成只代表数据发出去了，不代表设备已执行。
4. 根据回读结果确定设置状态：

| 回读情况 | 结果 |
| --- | --- |
| 目标字段读到，值与期望一致 | `CONFIRMED` |
| 目标字段读到，值与期望不同 | `NOT_APPLIED` |
| 目标字段仍未知 | 继续等待，超时则 `TIMED_OUT` |

`prepareSetting` 不修改已确认的设备状态；界面始终显示设备回读的值，而不是用户刚选的值。完整的设置帧不会被拆分发送（i5 最长 14 字节，i7 降噪轮换设置最长 10 字节）。

## i5：ZT 协议

### 发送帧

```text
08 EE 00 00 00 [group] [op] [lengthLE16] [payload...] [sum8]
```

- `length` 为整帧长度（含自身和校验）。
- `sum8` 为前面所有字节之和的低 8 位。
- 普通报文最长 14 字节，写入容量至少 10 字节即可，不依赖大 MTU。

初始化依次发送 13 条查询：

```text
0101 0602 0201 0603 0801 0d01 0401 0a01 0605 0c01 0607 0604 0e03
```

测试逐条比对独立提取的完整发送报文。

### 接收解析

只在**单条完整通知**内按已知偏移解析，不推断接收帧的前缀、总长度、校验规则，也不跨通知拼包。

- 命令字在第 5、6 字节，字段从第 9 字节开始；每条命令先检查最小字段长度。
- 降噪模式按整段已知尾部匹配，例如 `0000010020` 表示抗风噪。末尾的 `20` 不当作通用校验字节。

### 字段编码

| 字段 | 编码 |
| --- | --- |
| 电量 | 第 11、12、15 字节分别为左耳、右耳、充电盒；第 13、14 字节为左右耳充电标志 |
| 固件 | 第 9、10、11 字节各取低半字节，按十进制拼接。实测 `[1,2,5]` 显示为 `125` |
| 游戏模式、触控开关、检测开关 | 低半字节 `0` 开，`1` 关 |
| 音频 / 多点 | `0` = AAC/SBC 且多点开；`1` = LDAC 且多点关。i5 没有独立的 `0802` 多点查询 |
| ANC 档位、通透档位 | 整字节 `0/1/2` 对应界面 `1/3/5` |
| 提示音音量 | 低半字节 `0..4` 对应界面 `1..5` |
| 降噪轮换 | 顺序为 标准 / 通透 / 抗风 / ANC |

电量规则：

- `FF` 记为 `NotProvided`，不显示为 0%。
- 可显示的百分比为 1–100。原始值 0 记为 `Unknown`，与 `FF` 区分（这不代表硬件不可能是 0%）。
- 充电盒电量只有在至少一只耳机正在充电时才视为当前值，否则记为 `Unknown`，不显示成刚读到的有效电量。

实测样例：`09ff00000101011100000164620000ffe2` 表示左耳 100%、右耳 98%、均未充电、充电盒 `FF`。

## i7：RS Common V2 协议

### 发送帧

```text
FF [seq] [bodyLength] [body...] [sum8] AA
```

- `seq` 从 0 开始，到 255 后回绕。序号属于编解码器会话，初始化、设置和回读共用，断开后重置为 0。
- `bodyLength` 只计 body。
- `sum8` 为从 `FF` 到 body 末尾所有字节之和的低 8 位。

### 初始化与 MTU

官方初始化只发一条 `getInfo`，完整 35 字节：

```text
ff001efa010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649e9aa
```

OpenRose 另外发一条 `FA 3E` 查询读取自定义 EQ 曲线，这是客户端自己的选择。

默认 ATT MTU 为 23 时，每次写入只能放 20 字节，装不下 35 字节的 `getInfo`。这时 OpenRose 把查询拆成多条**各自完整**的 `FA` 查询，每条最多放 `容量 - 6` 个 ID，每条使用新序号并重新计算校验，而不是把一条帧切成几段写。容量为 20 时会产生三条信息查询加一条曲线查询，第三条只含共享 ID `49`（本型号没有对应字段）。

这种拆分方式是根据官方 App 中已出现的多 ID / 单 ID 查询归纳出来的，设备是否接受还没有实测。

### 接收帧

```text
DD [HH] [L T V...]... [CS] AA
```

- `HH` 视为不透明字节，不用于确认或序号关联。
- `L = 1 + V 的长度`。
- `CS` 接受两种算法：从 `DD` 到 CS 之前所有字节之和，或仅 LTV 部分之和，均取低 8 位。

解析器的处理规则：

- 通过 LTV 结构和 CS 校验来确认 `AA` 是否为帧尾，因此支持分包、合包，以及 V 中出现 `AA` / `DD` 的情况。
- 接收缓冲区上限 8192 字节，超出清空；断开时也清空。
- 协议没有总长度字段，所以不会在一条未完成的帧内部搜索新的 `DD` 来重新同步。V 中即使嵌着一段校验正确的帧，也当作普通数据。
- 损坏的前缀和"还没收完"的帧无法区分，此时等待更多数据，直到读取超时、缓冲区满或会话重置。
- `L = 00` 表示吸收剩余内容作为最后一个字段，但仍校验 CS（官方 App 在这种情况下走快速分支，不校验）。
- LTV 结构不完整时整帧丢弃（官方 App 可能已经更新了前面的字段）。
- `FE` 记录和未知记录保留原始数据并记录原因，不能用来建立协议就绪。

### 字段编码

| 字段 | 编码 |
| --- | --- |
| 电量 | 低 7 位为百分比，可显示范围 1–100；原始 0 记为 `Unknown`，与 `FF` 区分 |
| 入盒标志 | 左右耳 bit7 为入盒标志；耳机值为 `FF` 时标志也记为未知。充电盒的 bit7 只保留原始值，不解释为充电 |
| 固件 | 前三个字节各按无符号十进制转成文字后直接拼接（见[固件门槛](#固件门槛)） |
| 游戏模式、多点 | `1` 开，`0` 关 |
| 触控开关、检测开关 | `00` 开，`01` 关（与游戏模式相反） |
| ANC 档位、通透档位 | 线上值直接为 `1/3/5` |
| EQ 预设 | 单字节 `00/01` 为 HIFI / POP；双字节 `00/01` 为 POP / HIFI。保留原始格式 |
| 自定义 EQ | 十个整数，范围 −6..6，符号位加绝对值编码 |
| 触控绑定 | 八个必需的 `(slot, action)` 对，同一 slot 重复出现时以后者为准 |
| 降噪轮换 | 顺序为 ANC / 抗风 / 标准 / 通透 |

充电盒电量只有在值为 1–100、且至少一只在盒内的耳机原始值不是 `FF` 时才算当前值，否则记为 `Unknown`；只有 `FF` 记为 `NotProvided`。不会用历史缓存冒充当前读数。

## 设置命令

下表中 i5 列为 `group op` + payload，i7 列为 body。i7 的"补读"指 OpenRose 自己选择发送的单字段 `FA xx` 查询：它符合已观察到的 `FA ID` 查询规律，但不是官方 App 里对应的专用方法，设备是否接受待实测。

### 降噪

| 设置 | i5 | i7 | 回读 |
| --- | --- | --- | --- |
| 降噪模式 | `0682` + ANC `01000000`、标准 `00010000`、抗风 `00000100`、通透 `00000001` | `09` + ANC `01`、标准 `02`、通透 `03`、抗风 `04`、自动 `05`、动态 `07` | i5 `0602`；i7 `FA09` |
| ANC 档位 | `0687` + `00/01/02`（界面 1/3/5） | `2C` + `01/03/05` | i5 `0607`；i7 `FA2C` |
| 通透档位 | `0684` + `00/01/02`（界面 1/3/5） | `2D` + `01/03/05` | i5 `0604`；i7 `FA2D` |
| 降噪轮换 | `0685` + 标准 / 通透 / 抗风 / ANC 四个 `00/01` | `36` + ANC / 抗风 / 标准 / 通透 四个 `00/01` | i5 `0605`；i7 `FA36` |

说明：

- i5 不开放共享发送器里的自动 ANC；i7 不开放共享的深度 ANC（`06`）。
- i7 的自动、动态 ANC 作为普通模式选择。官方 App 的 i7 型号配置为这两项创建控件时没有固件门槛，所以 OpenRose 在固件未知时也不单独关闭它们，不借用 EAP（152）或增益（213）的门槛。
- 离开自动模式时，OpenRose 明确发送用户选的目标模式，并以设备回读为准。官方 App 关闭自动模式时会发送抗风 `0904`，却立刻显示 ANC；OpenRose 不照搬这一行为。
- i5 只把已知的读取命令当作状态来源。设置命令字（如 `0682`）去掉 `0x80` 位后看起来像读取命令，但不会被当作确认。
- 降噪轮换必须明确给出四种常规模式各自的开关，不接受遗漏、多余或未知的值。官方 App 没有"至少选一项"的限制，所以 OpenRose 也不加（`minimumCycleSelections = 0`）。全部关闭时设备如何响应，待实测。

### EQ 与游戏模式

| 设置 | i5 | i7 | 回读 |
| --- | --- | --- | --- |
| EQ 预设 | `0281` + 预设值（见下表） | `2A` + 预设值（见下表） | i5 `0201`（`08ee00000002010a0003`）；i7 `FA2A`（seq 0 时为 `ff0002fa2a25aa`） |
| 游戏模式 开 / 关 | `0683 00` / `01` | `0E 01` / `00` | i5 `0603`（`08ee00000006030a0009`）；i7 补读 `FA0E`（`ff0002fa0e09aa`） |

| 预设 | i5 标签 / 值 | i7 标签 / 值 |
| --- | --- | --- |
| `pop` | 弱水经典 / `00` | 弱水经典 / `01` |
| `hifi` | 日系柔情 / `01` | 原声再现 / `00` |
| `rock` | 乐器大师 / `02` | 古典乐器 / `02` |
| `light` | 清新空灵 / `03` | 日系甜美 / `03` |
| `eap` | 不支持 | 欧美流行 / `06`，需固件 ≥ 152 |

- 标签取自官方 App 的中文文案，不代表实测频响。
- i7 的 POP / HIFI 在界面上的顺序与线上值相反，不能用界面位置当线上值。
- 耳机主页按 POP、HIFI、ROCK、LIGHT、EAP 的顺序列出当前型号和固件可写的预设。i5 没有自定义 EQ；i7 的预设列表不包含 custom、custom slot 等非预设项。

### 自定义 EQ（仅 i7）

- 频段标签为 20 / 100 / 300 / 500 / 1k / 2k / 3k / 5k / 8k / 15k Hz，每段为 −6..6 的整数。频率只是界面标签，不是实测的滤波器中心频率。
- 曲线必须恰好十个整数，缺项、多项或越界都在构帧前拒绝。
- 应用曲线时依次写入 `2A 04`（切到自定义）和 `3E` + 十个值。负数用最高位作符号位加绝对值，例如 −6 = `86`、−1 = `81`，不是补码。
  - 例：曲线 `-6,-3,-1,0,1,2,3,4,5,6` → `ff00022a042faa`、`ff000b3e86838100010203040506e7aa`（seq 0）。运行时两条共用会话序号，各自重算校验。
- 写入后分别用 `FA 2A` 回读 EQ 模式、`FA 3E` 回读曲线，两项都与请求一致才算确认。
- 读取时，单字节 `2A 04` 表示 `custom`（索引 4 来自 i7 配置的 `getCustomIndex`）；双字节 `2A 05 [slot]` 表示 `custom_slot`，保留槽位和原始值，不当作曲线已应用。

### 触控绑定

两款耳机都有八个槽位：左右耳各有单击、双击、三击、长按。每个槽位可选该型号的全部动作。

| 动作 | i5 值 | i7 值 |
| --- | --- | --- |
| `none` 无功能 | `06` | `00` |
| `play_pause` 播放 / 暂停 | `05` | `01` |
| `previous_track` 上一曲 | `02` | `02` |
| `next_track` 下一曲 | `03` | `03` |
| `volume_up` 音量加 | `00` | `04` |
| `volume_down` 音量减 | `01` | `05` |
| `voice_assistant` 语音助手 | `04` | `07` |
| `noise_cycle` 降噪轮换 | `07` | `08` |
| `game_mode` 游戏模式 | 不支持 | `06` |

- i5 发送 `0481 [side] [gesture] [action]`：side `00` 左、`01` 右；gesture `00/01/02/03` 为单击 / 双击 / 三击 / 长按。回读 `0401`（`08ee00000004010a0005`）。
  - 例：左耳双击设为播放 / 暂停 → `08ee00000004810d000001058e`
- i7 发送 `01 [slot] [action]`：左耳 slot `01–04`，右耳 `11–14`。回读为补读 `FA01`。
  - 例：右耳长按设为降噪轮换 → `ff00030114081faa`（seq 0）
- 每次只写目标槽位，不重写其余七个，也不会触发所绑定的动作。
- 请求值为 `FieldValue.TouchAssignment(side, gesture, action)`，不接受原始动作码；读取值是包含八个 `TouchBinding` 的 `FieldValue.TouchKeys`，保留原始码和未知动作。
- 确认时只比较目标槽位，其他槽位是未知动作也不影响。但回读仍需完整结构：i5 八个槽位字节齐全，i7 八个 slot ID 齐全。

### 触控开关、检测开关、提示音

| 设置 | i5 | i7 | 参数 |
| --- | --- | --- | --- |
| 触控总开关 | `0A81 00/01`（开 / 关），回读 `0A01` | `07 00/01`（开 / 关），补读 `FA07` | `Toggle` |
| 检测功能开关 | `0C81 00/01`（开 / 关），回读 `0C01` | `08 00/01`（开 / 关），补读 `FA08` | `Toggle` |
| 提示音音量 | `0E83 00..04`，回读 `0E03` | `2E 01..05`；静音 `2E 00` 需固件 ≥ 304 | `Number(1..5)`，i7 符合条件时可为 0 |

- 两款耳机的触控和检测开关都是 `00` 开、`01` 关，与 i7 游戏模式 / 多点的极性相反。
- 检测功能开关只是开关，不会产生佩戴、摘下或入盒事件。
- i5 提示音只有 1–5 档，发送时减 1，没有静音。必须用 i5 自己的 `0E03` 查询（`08ee0000000e030a0011`），不能用共享父类的 `0E06`。第 5 档的完整帧为 `08ee0000000e830b000496`。
- i7 的 304 门槛只限制静音这一档，不限制整个音量功能。

### 音频与多点

**i5**：音频和多点共用 `0881`，两者联动。

| 值 | 含义 | 完整帧 |
| --- | --- | --- |
| `00` | AAC/SBC，多点开 | `08ee00000008810b00008a` |
| `01` | LDAC，多点关 | `08ee00000008810b00018b` |

两类操作都回读 `0801`（`08ee00000008010a0009`），`expectedValues` 同时包含音频模式和多点，两者都对上才算确认。不存在"LDAC + 多点开"的组合。官方发送器里还有一个 `0882` 共享变体，不能用它替换 i5 的 `0881`，也不能据此给 i5 开放 LHDC / LC3。

**i7**：音频和多点相互独立。

| 设置 | body | 回读 |
| --- | --- | --- |
| 音频 | `2B 00/01/02/03` = AAC/SBC、LDAC、LHDC、LC3 | `FA2B` |
| 多点 | `32 01/00` = 开 / 关 | `FA32` |

各自只等待目标字段的回读，不套用 i5 的联动规则。

这里的"音频模式"是耳机端的配置项，系统最终协商到哪种编码还取决于手机。

### 增益与听力保护（仅 i7）

| 设置 | body | 范围 | 门槛 |
| --- | --- | --- | --- |
| 增益 | `45 00..02` | 低 / 中 / 高 | 固件 ≥ 213 |
| 听力保护 | `46 00..06` | 0 关闭；1–6 对应界面标签 75 / 80 / 85 / 90 / 95 / 100 dB | 固件 ≥ 213 |

增益没有可靠的 dB 对应关系；听力保护的 dB 数值只是界面标签，没有验证实际声压。`FA45` 回读在官方发送器里有对应方法；`FA46`、`FA2E` 是补读。

## 型号能力与固件门槛

### 能力模型

界面只显示当前型号和固件实际可写的设置，由以下能力对象决定：

| 能力 | 内容 |
| --- | --- |
| `NoiseControlCapabilities` | 可选降噪模式；ANC / 通透档位各自的 `levels` 与 `visibleModes`；轮换最少选择数 |
| `EqualizerCapabilities` | 可写预设（`presets`）；自定义 EQ 频段与范围（`custom`，仅 i7） |
| `TouchControlCapabilities` | 槽位集合与动作集合 |
| `AudioControlCapabilities` | 音频选项；`multipointCoupled`（i5 为 true，i7 为 false） |

`DeviceCapabilities.readableFields` 来自协议族的 `ProtocolFamily.readableFields`，只表示解析器认识哪些字段，**不能**用来判断能否写入；能否写入只看型号配置声明的 `writableSettings`。初始化查询里包含某个共享 ID，也不说明该型号支持对应功能。

### 档位的显示条件

官方 App 只在当前模式为 ANC 时显示 ANC 档位，为通透时显示通透档位；抗风噪、标准、i7 的自动和动态模式下两组都不显示。OpenRose 采用同样的规则：

- 模式还没读到、未知或未提供时，两组档位都隐藏。
- 用户点了切换但设备还没确认时，档位区域不提前变化。
- 这只是显示条件。两组档位的读数一直保留，写入时也不检查当前模式。

真机记录显示，i5 在抗风噪模式下仍能读到 ANC 档位 5 和通透档位 1，说明档位读数与当前模式相互独立。但这不能说明这两组参数在该模式下是否影响声音。

官方 App 的指令层同样不绑定模式：i5 的 `setAncLevel`（`0687`）和 `setTransLevel`（`0684`）都不带模式参数，也不检查当前模式。固件在其他模式下是否接受档位写入，尚无证据。

### 固件门槛

i7 的固件门槛不是语义化版本比较。官方 App 的算法是：

1. 取 `0D` 字段的前三个字节；
2. 每个字节按无符号十进制转成文字，**不加分隔符**直接拼接；
3. 把拼接结果当十进制整数，与门槛比较。解析失败按 0 处理。

| 字节 | 拼接 | 整数 | ≥ 152？ |
| --- | --- | --- | --- |
| `01 05 02` | `"152"` | 152 | 是 |
| `00 0F 01` | `"0151"` | 151 | 否 |
| `00 0F 02` | `"0152"` | 152 | 是 |
| `00 10 00` | `"0160"` | 160 | 是 |
| `01 04 FF` | `"14255"` | 14255 | 是 |

（以上用于说明比较规则，不代表这些固件真实存在。）

| 功能 | 门槛 |
| --- | --- |
| EQ 预设 EAP | ≥ 152 |
| 增益、听力保护 | ≥ 213 |
| 提示音静音 | ≥ 304 |

OpenRose 从 `FieldValue.Firmware.parts` 重新计算这个整数，要求恰好三个 0–255 的值，不信任单独传入的显示文字。固件未知或无效时，所有受门槛限制的选项都不开放，基础功能照常可用。门槛同时作用于界面和 `prepareSetting`，绕过界面也发不出不支持的命令。已经读到的 EAP 状态照常显示。

i5 的固件显示用低半字节拼接（见上文），目前没有 i5 功能依赖固件门槛。

## i7 控制通道选择（BLE / SPP）

i7 支持 BLE 和传统蓝牙 SPP 两种控制通道。每次连接前选一次，之后不变。

### 从广播取传统蓝牙地址

用前文的规则规范化 manufacturer data 得到十六进制串 `H`。只有满足以下条件才提取地址：

- 已识别为 i7；
- `H` 长度至少 34；
- `H[14:16] == "01"`。

地址取 `H[22:34]`，六个字节按原顺序转成大写冒号格式（不倒序）。全 `00` 或全 `FF` 视为没有地址。

扫描得到的设备 ID 保持不变：BLE 用扫描 ID，SPP 用单独提取的 BR 地址。这条广播规则还没有在真机上确认，广播本身也没有校验或认证。

### 选择规则

官方 App 的规则（`ConnectManager._shouldUseSppControl`）：平台支持 SPP、型号支持、没有强制 BLE、音频 profile 不是 LE Audio，就用 SPP。它看的是每台设备已连接的音频 profile，不看当前编码或是否在播放。所以设置里选了 LC3、设备名、设备类型、A2DP 是否在播放，都不能替代实际的音频连接证据。

OpenRose 把音频连接事实分为 `UNKNOWN` / `CLASSIC` / `LE_AUDIO` 三种，只有同时满足以下条件才用 SPP，否则一律用 BLE：

- 型号为 i7，且有可用的 BR 地址；
- 平台 SPP 可用；
- 用户没有打开"强制使用 BLE"；
- 有明确证据表明耳机通过传统蓝牙连接了音频（`CLASSIC`）。

Android 上通过公开 API 查询 A2DP、HEADSET，以及 API 33+ 的 LE Audio profile。Android 12 / 12L（API 31–32）没有公开的 [`LE_AUDIO`](https://developer.android.com/reference/android/bluetooth/BluetoothProfile#LE_AUDIO) 常量，无法排除 LE Audio，所以返回 `UNKNOWN` 并使用 BLE，不使用隐藏的 profile ID。profile 代理未就绪、查询失败、没有目标音频连接或无法按地址关联时，同样返回 `UNKNOWN`。

选择结果和原因会暴露给状态消费者。修改"强制使用 BLE"只影响下一次连接。

### SPP 连接

- 控制 UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。不是标准串口 `1101`，也不是 OTA 用的 `66666666-6666-6666-6666-666666666666`。
- SPP 上收发与 BLE 相同的 RS Common V2 帧。接收端按字节流处理分包、合包；发送端同样走共享队列，每次写完间隔 100 ms 并等待回读。
- SPP 可以一次写完 35 字节的初始化帧；BLE 容量不足时按前文方式拆分查询。
- 一次会话只建立一种控制连接。SPP 失败后不会在后台自动改用 BLE、重放设置或同时尝试两种连接。
- Android 使用公开的 RFCOMM 接口：连接前取消设备发现，在后台线程执行阻塞的连接和读写，取消时关闭 socket 以解除阻塞。参考：[`getProfileProxy`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getProfileProxy(android.content.Context,%20android.bluetooth.BluetoothProfile.ServiceListener,%20int))、[`BluetoothLeAudio.getConnectedDevices`](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio#getConnectedDevices())、[`BluetoothSocket`](https://developer.android.com/reference/android/bluetooth/BluetoothSocket)。

## 测试覆盖

测试位于 `core/src/commonTest/kotlin/io/github/dohex/openrose/core/protocol/`。预期发送报文都取自独立提取的数据，测试不调用 OpenRose 自己的构帧代码来生成预期值。

| 测试 | 覆盖内容 |
| --- | --- |
| `ProtocolCodecTest` | 广播识别；两型号的初始化查询（含 i7 小 MTU 拆分）；i5 实机读取样本；i7 分包、校验算法和字段极性；未知 / 截断报文不能建立就绪；电量 `0` 与 `FF` 的区分；i7 序号回绕 |
| `NoiseProtocolTest` | i5 四种、i7 六种降噪模式；各档位；两型号的轮换顺序；回读查询；非法参数、未知轮换值、不支持的选项；序号连续、回绕和重置 |
| `EqualizerProtocolTest` | 全部可写预设；EAP 门槛；两型号相反的游戏模式极性；回读查询；未知预设、自定义 / 槽位名称；被拒请求不消耗序号；EQ、游戏、ANC、初始化共用序号 |
| `DeviceControlProtocolTest` | 八个触控槽位地址、两型号全部动作码和所有槽位 / 动作组合；单槽期望值；全部音频选项；i5 音频 / 多点联动；开关极性；提示音档位；初始化、触控、检测、音频共用 i7 序号 |
| `AdvancedAudioProtocolTest` | i7 自定义 EQ 的两条写入与 `FA2A` / `FA3E` 回读；`45` / `46` / `2E` 各值；整条计划无效时不分配序号；固件门槛；接收时保留原始数据 |

会话层的行为（设置确认、断开清理、通道选择等）在 `core/src/commonTest/.../core/ControlSession*Test.kt` 中测试。

### 测试数据的来源

| 类型 | 说明 | 例子 |
| --- | --- | --- |
| 固定发送报文 | 从 APK 独立提取 | `08ee00000002010a0003`（i5 EQ 查询） |
| 动态发送报文 | 用独立提取的参数推导 | `ff00030114081faa`（i7 右耳长按设为降噪轮换） |
| i5 实机接收 | 来自 `i5_live_read_2026-10-18.jsonl`，固件 125 | `09ff00000102010b000118`（EQ = HIFI）、`09ff00000106030b00011e`（游戏模式关） |
| 合成接收 | 按协议规则构造，**不是抓包** | i5 `000000000002010000a0`（POP）；i7 `dd00022a0009aa`（单字节 HIFI）、`dd00040d010502f6aa`（固件 152） |

i5 实机记录还覆盖了：触控绑定、触控开、检测关、提示音 2 档、LDAC 配置，以及抗风噪模式下的 ANC / 通透档位读数。这些只能证明读取解析正确。

## 待真机验证

以下行为目前只有静态分析或离线测试支持：

**i5**

- 所有设置命令的写入、回读时序和断电后是否保留
- Android（小米 14 / HyperOS 4）上的控制连接
- 降噪轮换全部关闭时设备的反应
- 非当前模式下写入档位是否生效
- 触控绑定的实际触发、提示音实际响度、系统编码协商结果

**i7**

- 所有接收解析（目前没有任何真实报文）
- MTU 不足时拆分的 `FA` 查询，以及 `FA0E`、`FA01`、`FA07`、`FA08`、`FA46`、`FA2E` 等补读查询是否被设备接受
- 广播中 BR 地址的提取规则
- SPP 控制通道
- 自动 / 动态 ANC 的实际表现
- 各固件版本与功能门槛的对应关系

OpenRose 没有实现寻机、提示音语言切换和 OTA。

## 附录：Issue 对照与证据索引

### Issue 对照

| 功能 | Issue |
| --- | --- |
| 初始化读取 | [#3](https://github.com/DOHEX/OpenRose/issues/3)、[#4](https://github.com/DOHEX/OpenRose/issues/4) |
| 降噪模式、档位、轮换 | [#5](https://github.com/DOHEX/OpenRose/issues/5)、[#6](https://github.com/DOHEX/OpenRose/issues/6) |
| EQ 预设、游戏模式 | [#7](https://github.com/DOHEX/OpenRose/issues/7)、[#8](https://github.com/DOHEX/OpenRose/issues/8) |
| 触控绑定、触控 / 检测开关、提示音、音频 / 多点 | [#9](https://github.com/DOHEX/OpenRose/issues/9)–[#12](https://github.com/DOHEX/OpenRose/issues/12) |
| i7 自定义 EQ、增益、听力保护、提示音静音 | [#13](https://github.com/DOHEX/OpenRose/issues/13)、[#14](https://github.com/DOHEX/OpenRose/issues/14) |
| i7 BLE / SPP 通道选择 | [#15](https://github.com/DOHEX/OpenRose/issues/15) |

### 证据索引

以下路径均位于 `rose_reverse/`。反汇编文件在 `analysis/blutter/asm/` 下，下表省略这一前缀；`roselink/` 与 `roselink_bluetooth/` 是官方 App 的两个 Dart 包。

**协议研究文档**

- `docs/models/earfeel-i5-protocol.md`、`docs/models/earfeel-i7-protocol.md`：i5、i7 型号差异；i5 / i7 的共用内容在 `docs/families/zt.md`、`docs/families/rs-common-v2.md`，OTA 在 `docs/ota/wq.md`、`docs/ota/bes.md`（本仓库中的副本见 [docs/protocols/](protocols/README.md)）
- `analysis/i5_hardware_validation.md`：i5 实机验证记录
- `analysis/i7_receiver_findings.md`：i7 接收处理分析
- `analysis/i7_eq_findings.md`：i7 EQ 分析

**型号配置**

| 内容 | 位置 |
| --- | --- |
| i5 型号配置（ANC / 通透档位能力、音频与多点联动 `useAudioModeNotMulDeviceMode`） | `roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:130`（`0x68da48..0x68da6c`）、`:221..242` |
| i7 型号配置（自动 / 动态 ANC 控件 `0x681fd4..0x682010`、提示音） | `roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart`、`:809..840` |
| 触控槽位与动作表 | i5 `earfree_i5_device_config.dart:672..702`；i7 `earfeel_i7_device_config.dart:951..985`；`device_type_config/touch_info.dart:36..40`、`:60..64`；动作对象 `objs.txt:999..1122`（`pp.txt:8785`、`:8377`） |
| EQ 预设列表与中文标签 | i5 `pp.txt:8781`、`objs.txt:1745..1766`；i7 `pp.txt:8369`、`localization/language/zh_cn.dart:2549..2625`；`device_eq_panel_item.dart:29..64` |
| `NoiseType` 枚举（`normal=0, trans=1, wind=2, anc=3`，App 内部索引，不是线上值） | `analysis/blutter/objs.txt:40317` |

**界面逻辑**

| 内容 | 位置 |
| --- | --- |
| 档位显示条件（`_RAncModeCtlLevelBarState::build`，`0xb3746c` 判断 ANC、`0xb379d8` 判断通透） | `roselink/widgets/device_widgets/device_anc.dart:112`、`:610`、`:1947` |
| 降噪轮换无最少选择数（`0xb8c758..0xb8ca0c`） | `roselink/modules/device_control/widget/device_anc_line_panel_item.dart` |
| EAP 门槛（`EarVersionCheck`，Dart 标记整数 `0x130` = 152） | `objs.txt:1707..1712`；`device_eq_panel_item.dart:1418..1446`；`constant/ear_version_check.dart:41..66` |
| 提示音静音门槛 | `device_ding_level_panel_item.dart:900..994` |

**发送与接收**

| 内容 | 位置 |
| --- | --- |
| i5 发送器（EQ / 游戏 `:2256..2731`；触控绑定 `:1472..1935`；触控 / 检测 `:1070..1407`；音频 / 多点 `:2131..2144`、`:2216..2228`、`:2932`、`:3749`；档位 `:4383`、`:4523`；提示音 `:4663..4718`、`:4736`） | `roselink_bluetooth/commander/sender/zt_sender.dart` |
| i7 发送器（触控绑定 `:2459..2698`；触控 / 检测 `:2073..2114`、`:2379..2420`；音频 / 多点 `:2840..3002`、`:5009`；EQ / 游戏 `:3213`、`:3252`、`:4312..4515`） | `roselink_bluetooth/commander/sender/rs_common_v2_sender.dart` |
| 档位命令入口 | `roselink_bluetooth/commander/r_commander.dart:1781` |
| i7 固件字段转换（`:17498..17616`，无分隔拼接 `:17703..17719`） | `rs_common_v2_receiver.dart` |
| i7 固件状态写入 | `rs_common_v2_receive_handler.dart:5658..5665` |
| 广播规范化 | `roselink_bluetooth/scanner/r_ble_scanner.dart:3737` |
| BR 地址提取 | `base_device_config.dart:451..685` |
| SPP 选择 | `connect_manager.dart:4614`（`_shouldUseSppControl`） |
| 提取的发送常量 | i5 `analysis/i5_fixed_sender_commands.json:155..225`；i7 `analysis/i7_sender_commands.json:830..869`、`:1190..1309`、`:2157..2162` |
| 参考客户端 | `examples/i7_ble_client.py:16..21` |
| i5 实机记录行号 | 抗风模式 `:6`、ANC 档位 `:25`、通透档位 `:27`、EQ `:8`、游戏 `:10`、触控等 `:12,17,19,23,29` |
