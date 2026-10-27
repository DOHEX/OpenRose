# i5 / i7 读取协议与证据

读取实现日期：2026-10-18。读取范围为 [#3](https://github.com/DOHEX/OpenRose/issues/3) 和 [#4](https://github.com/DOHEX/OpenRose/issues/4)。2026-10-19 新增 [#5](https://github.com/DOHEX/OpenRose/issues/5)、[#6](https://github.com/DOHEX/OpenRose/issues/6) 的降噪设置、[#7](https://github.com/DOHEX/OpenRose/issues/7)、[#8](https://github.com/DOHEX/OpenRose/issues/8) 的预设 EQ / 游戏模式，以及 [#9](https://github.com/DOHEX/OpenRose/issues/9) 至 [#12](https://github.com/DOHEX/OpenRose/issues/12) 的触控、检测、i5 提示音和音频 / 多点设置，见文末。`core.protocol.ProtocolCodec` 是纯 Kotlin 会话实例；平台传输负责扫描、GATT、订阅和串行写入，协议负责识别、初始化报文和状态解析。没有 OTA 发送入口。

## 来源与实测边界

共同原始来源是 RoseLink 3.8.0 / versionCode 5310，APK SHA-256 为 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。研究者恢复的目录和 Python 参考 API 不属于厂商公开规范。

| 证据 | 来源 | SHA-256 |
| --- | --- | --- |
| i5 独立 TX 常量提取 | `rose_reverse/analysis/i5_fixed_sender_commands.json` | `40ea600ec08358140c45f0dcff893807262273f6db9506eb7e17519f17dc6a87` |
| i5 实际 RX 读取记录 | `rose_reverse/analysis/i5_live_read_2026-10-18.jsonl` | `a2cf839f3d35795203310dfc6f145b5e4e7fb3f326466bd7c27c8728186d3aa4` |
| i7 独立 TX 常量提取 | `rose_reverse/analysis/i7_sender_commands.json` | `10f4722e1f77f3c0493b8720fb6928b8019a08522a6d5756b7df775b7fdb399d` |
| i5 独立离线参考测试 | `rose_reverse/tests/test_i5_protocol.py` | `8e50403bb0bc6ad5f5f139a9947a4c4a6b47d6a182ee543e73b06e8fefda50ca` |
| i7 独立离线参考测试 | `rose_reverse/tests/test_i7_protocol.py` | `831c61151b456f69c21617c902b72ce7171f2787b646bda96403183e22da296b` |

本工作区研究目录为 `/home/dohex/Projects/rose_reverse`。可追溯说明见其 `docs/earfeel-i5-protocol.md`、`docs/earfeel-i7-protocol.md`、`analysis/i5_hardware_validation.md` 和 `analysis/i7_receiver_findings.md`。

i5 实际样本仅覆盖单副设备在 Linux / BlueZ 5.87 / Bleak 3.0.2 上的 13 项初始化读取，固件读数 `125`。本项目测试使用其中的原始 RX 字面值；这不代表 Android、小米 14、HyperOS 4 或设置命令已通过实机验收。i7 没有实际 RX 抓包或耳机实测；所有 i7 RX 测试数据均为明确标注的合成缓冲区。测试数和上游研究状态不等同于实机通过。

## 识别与 GATT

识别使用 manufacturer data：按 company ID 数值排序，先拼接所有 ID 的 LE16，再拼接同序的全部 payload，最后与具体型号配置前缀匹配。不可按名称识别，也不能逐项交错拼 ID / payload。`525300010001` 对应 i5 ZT；`b8e5290f` 对应 i7 RS Common V2。测试的单 company 广播示例来自静态算法推导，并非广告抓包。连接使用操作系统扫描对象的 ID，前缀不是蓝牙地址。

| 型号配置 | 服务 | 写 | 通知 |
| --- | --- | --- | --- |
| i5 ZT | `011bf5da-0000-1000-8000-00805f9b34fb` | `00007777-0000-1000-8000-00805f9b34fb` | `00008888-0000-1000-8000-00805f9b34fb` |
| i7 RS V2 | `000000fe-0000-1000-8000-00805f9b34fb` | `000000f1-0000-1000-8000-00805f9b34fb` | `000000f2-0000-1000-8000-00805f9b34fb` |

平台发现同一服务内的写与通知特征，按实际属性优先无响应写，否则有响应写。先完成通知注册与 CCCD，再发送初始化读取。每条写入完成后间隔 100 ms；此间隔不是业务回复超时。连接、服务发现、通知订阅、收到可信协议字段和逐字段读取完成分别表示。原始数据、未知字段和未提供字段均保留。

## i5 ZT

TX 为 `08 EE 00 00 00 group op lengthLE16 payload sum8`，长度包含整帧，校验是此前所有字节之和低 8 位。13 条初始化查询顺序为 `0101, 0602, 0201, 0603, 0801, 0d01, 0401, 0a01, 0605, 0c01, 0607, 0604, 0e03`。测试逐条对比独立提取的完整 TX 字面值。普通报文最多 14 字节，读取无需依赖较大 MTU。

RX 仅按照一次完整通知内的已知偏移解析。没有推断 RX 前缀、整体长度、校验规则或跨通知拼接。命令在字节 5/6，字段从字节 9 起；各命令先检查最小字段长度。降噪模式按整个已知尾部匹配，其中 `0000010020` 是抗风；尾部最后的 `20` 不被另行假定为通用校验字节。

电量来自字节 11/12/15，左右充电标志在 13/14。实际电量样本 `09ff00000101011100000164620000ffe2` 表示左 100%、右 98%、充电均关闭、盒值 `FF`。`FF` 使用 `NotProvided(rawHex="ff")`，不会变为 0。已知可显示百分比为 1..100；原始 0 保留为 `Unknown("00")`，与 FF 区分，这不表示硬件物理上不可能是 0%。盒当前值还需至少一耳正在充电，否则保留原值并标为未知，不能显示为刚取得的有效盒电量。固件为字节 9/10/11 的低半字节十进制拼接，实际 `[1,2,5]` 展示 `125`。

游戏、触摸和检测功能低半字节 `0` 开 / `1` 关。音频 `0` 为 AAC/SBC 且多点开，`1` 为 LDAC 且多点关，不能给 i5 套用独立 `0802` 查询。ANC 与通透等级全字节 `0/1/2` 映射 `1/3/5`；提示音低半字节 `0..4` 映射 `1..5`。轮换顺序为普通 / 通透 / 抗风 / ANC，触摸动作表保留 i5 专属映射。

## i7 RS Common V2

TX 为 `FF sequence bodyLength body checksum AA`。序号从 0 开始并以 256 回绕；长度只含 body；校验为从 FF 到 body 的和低 8 位。序号属于协议会话实例，断开重置。RX 的第二头字节保持 opaque，不用作业务 ACK 或发送序号关联。

官方初始化只有 `getInfo`，完整 35 字节 TX 为 `ff001efa010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649e9aa`。实现另加完整 `FA 3E` 查询读取曲线，此补读是客户端选择。默认 ATT MTU 23 只有 20 字节值容量；容量不足时按每条 `capacity-6` 个 ID 分组为多条完整 FA 查询，每条使用新序号和校验，不任意切开完整命令。容量 20 时产生三条信息查询，再加一条曲线查询；第三条仅含共享 ID49，没有本型号已知字段可等待。这种 FA 分组来自已观察的多 ID / 单 ID 查询归纳，设备接受情况待实测。

RX 为 `DD opaqueHH [L T V...]... CS AA`，`L=1+V长度`。接受研究中看到的两种 CS：整帧前缀之和或仅 LTV 内容之和，均取低 8 位。按 LTV 和 CS 验证 AA 候选，支持分包、合包和 V 中的 AA / DD。缓冲区上限 8192 字节，断开清空。没有总帧长时，不在尚未完成的前帧内部搜索新的 DD 来恢复；V 中即使出现一条校验正确的内嵌帧也保持不透明。无法与缺失分片区分的损坏前缀会等待后续字节、读取超时或缓冲区上限 / 会话重置，而不会从其内部构造已知状态。`L00` 吸收余下内容作为最后一条字段，本客户端仍验证 CS，比原 App 的 L00 快速分支更严格。候选帧的 LTV 布局不完整时拒绝整帧，不像原 App 可能已更新较早字段；这是客户端的完整性选择。FE 和未知记录保留原帧并记录原因，不建立协议就绪。

电量低 7 位表示百分比，已知可显示范围为 1..100；原始 0 作为未知保留，不与 FF 混同。左右 bit7 是盒内标志，耳值 FF 的标志仍为未知；盒 bit7 仅保留原始标志，不能标为充电。盒当前值需 1..100 且至少一个盒内耳的 raw 不为 FF，否则当前盒值保留为 Unknown，只有 FF 为 NotProvided。本实现不将历史缓存伪装为当前读数。固件使用前三个完整无符号字节十进制拼接。

游戏和多点 `1` 开 / `0` 关；触摸与检测功能相反。ANC / 通透线上等级直接为 `1/3/5`。EQ 单字节 00/01 为 HIFI/POP，双字节 00/01 为 POP/HIFI，原始格式保留。自定义 EQ 为十个符号位加绝对值编码的整数，范围 −6..6。触摸是八个必需 slot 的 `(id,action)` 对，重复 slot 以后者覆盖；轮换顺序为 ANC / 抗风 / 普通 / 通透。

`supportedFields` 仅声明当前读者理解的字段，不能用于显示写入能力。设置能力必须按本型号与已读取固件判断：EAP 门槛 152，增益 / 听力保护门槛 213，提示音关闭门槛 304。固件未知时不启用受限设置。共享初始化 ID 不证明额外硬件能力。

## 字段可用性与请求匹配

`Loading`、`Known`、`Unknown` 和 `NotProvided` 分开表达。每个回包保留完整 rawHex，每个读取项保留自己的原始字节。字段未知、长度错误和未提供均不会变成默认关闭或默认数值。仅得到 NotProvided 或电池原始标志不能建立协议就绪。

i5 请求按命令键匹配；i7 按实际收到的可识别记录类型集合匹配，发送序号和 RX HH 不参与业务确认。共享未知 ID 不被当作必须返回的本型号字段。平台层负责单一所有者、串行请求、超时、断开清理和会话标识隔离；编解码器不会自行假定 GATT 写完成等于状态已读。

## 降噪设置协议（#5 / #6）

`prepareSetting(setting, value, firmware)` 返回有序 `writeFrames`、独立的 `readback` 查询以及 `expectedValues`。它只构造协议计划，不修改已确认设备状态。会话在同一发送队列中执行设置和读取；底层写入完成不等于设备确认。i5 仅把已知读取命令当作状态来源，不把 `0682` 等设置操作码清除 `80` 后当成确认。i7 仍按收到的字段类型和真实值确认，不使用 opaque HH。

| 设置 | i5 ZT | i7 RS V2 | 读取确认 |
| --- | --- | --- | --- |
| 降噪模式 | `0682` + ANC `01000000`、普通 `00010000`、抗风 `00000100`、通透 `00000001` | `09` + ANC `01`、普通 `02`、通透 `03`、抗风 `04`、自动 `05`、动态 `07` | i5 `0602`；i7 `FA09` |
| ANC 档位 | `0687` + `00/01/02` 对应界面 `1/3/5` | `2C` + `01/03/05` | i5 `0607`；i7 `FA2C` |
| 通透档位 | `0684` + `00/01/02` 对应界面 `1/3/5` | `2D` + `01/03/05` | i5 `0604`；i7 `FA2D` |
| 触摸降噪轮换 | `0685` + 普通 / 通透 / 抗风 / ANC 四个 `00/01` | `36` + ANC / 抗风 / 普通 / 通透 四个 `00/01` | i5 `0605`；i7 `FA36` |

`NoiseControlCapabilities` 将型号设置能力与 `supportedFields` 分开。i5 不开放共享发送器的自动 ANC；i7 不开放共享深度 ANC `06`。已恢复 i7 型号配置在 `0x681fd4..0x682010` 创建自动与动态 ANC 控件，没有这些控件的固件门槛；因此未知固件不单独关闭这两项，也不借用 EAP 的 152 或增益的 213 门槛。这只描述 RoseLink 3.8.0 的型号配置，不证明所有硬件固件组合均已验证。相关来源为 `rose_reverse/docs/earfeel-i7-protocol.md` 第 1、4.5 节，以及 `analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart`。

轮换输入必须明确包含四种常规模式和四个非未知布尔值，拒绝遗漏、额外模式和未知开关。恢复的公共轮换 UI 在 `0xb8c758..0xb8ca0c` 先建立四个 `0`，把选中项改为 `1`，随后直接调用 `setAncLine`，发送器也没有最小选中数检查；客户端因此不额外发明至少选一项或两项的规则，`minimumCycleSelections=0`。空轮换的设备接纳与实际触摸效果仍待实测。来源为 `analysis/blutter/asm/roselink/modules/device_control/widget/device_anc_line_panel_item.dart` 与两个发送器的 `setAncLine`。

i7 自动与动态 ANC 作为模式选择。离开自动模式时明确发送用户选择的目标模式，并保留设备读回的实际状态；不复制厂商关闭开关发送抗风 `0904` 却立刻乐观显示 ANC 的行为。若读回结果与目标不同，会话可记录未应用，而不能把目标值写入已确认状态。

所有参数检查都发生在构造设置帧前；非法类型、档位或轮换参数抛出 `IllegalArgumentException`，不支持的设置或模式抛出 `UnsupportedOperationException`，由会话转换为明确的公开操作结果。i7 初始化、设置及读取确认共用同一个编解码器序号；无效请求不消耗序号，断开重置仍从 `00` 开始。i5 最大设置帧 14 字节，i7 轮换设置最大 10 字节，不分割完整设置帧。

`NoiseProtocolTest` 使用上述独立 APK 提取的完整固定 TX 字面值，以及研究参考测试和已恢复参数映射的独立动态例子。覆盖四种 i5 模式、六种 i7 模式、各档位、型号轮换线序、确认查询、非法参数、未知轮换、不支持选项、输入快照、序号连续 / 回绕 / 重置；RX 示例明确为合成数据。实际设置回包时序、持久化、自动模式表现与固件矩阵未获得本次实机证据。用户当前无法提供 i5 / 小米 14（HyperOS 4），本次继续实现与离线验证；已有 i5 初始化读取记录不扩大为设置验收通过，i7 仍无设备实测。

### 原始 App 的模式与档位显示（2026-10-22 核对）

本次直接核对 RoseLink 3.8.0 的既有 Blutter 静态反汇编产物，并复核上述 APK SHA-256。以下是原始客户端的配置、显示和指令构造证据，不是厂商公开协议规范或本次硬件实测。

i5 的 `EarfreeI5DeviceConfig` 分别启用 ANC 档位和通透档位，并为两者分别配置三项 `NoiseLevelType` 列表；它们是两个独立的能力开关和两组可选值。见 [i5 型号配置](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:130)，赋值地址为 `0x68da48..0x68da6c`。

原始 `NoiseType` 枚举为 `normal=0`、`trans=1`、`wind=2`、`anc=3`，见 [枚举对象](/home/dohex/Projects/rose_reverse/analysis/blutter/objs.txt:40317)。这些是应用内部索引，不能当作两类协议共用的线上模式值。原始档位控件由当前降噪状态取得模式值，再按该值选择显示内容：

| i5 当前模式 | 原始 App 显示的档位 |
| --- | --- |
| 主动降噪 `anc` | ANC 档位，界面值 `1/3/5` |
| 通透 `trans` | 通透档位，界面值 `1/3/5` |
| 抗风噪 `wind` | 两组均隐藏 |
| 标准 `normal` | 两组均隐藏 |

模式传入档位控件的路径见 [当前状态到控件](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:1947)。`_RAncModeCtlLevelBarState::build` 在 `0xb3746c` 比较模式是否为 `3`，命中才构造 `noise-anc-level`；在 `0xb379d8` 比较是否为 `1`，命中才构造 `noise-trans-level`。其余分支使用空组件，见 [ANC 显示分支](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:112) 和 [通透显示分支](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/widgets/device_widgets/device_anc.dart:610)。因此原始 App 将通透档位归于通透模式，未归于抗风噪模式。

指令层仍分别处理模式和档位：i5 `Zt229Sender::setAncLevel`（`0x80b81c`）构造 `0687`，`setTransLevel`（`0x80b3a0`）构造 `0684`，二者均将 `1/3/5` 映射为 `0/1/2`，不携带模式参数，也未在这些构造函数中检查当前模式。见 [ANC 档位发送器](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4523) 和 [通透档位发送器](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/zt_sender.dart:4383)。已检查的 `RCommander` 两个档位入口也直接构造并发送指令，见 [命令入口](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/r_commander.dart:1781)。这些证据确立了客户端显示条件，但不能证明耳机固件在其他模式下接受或拒绝档位写入。

已有 i5 实机初始化读取也体现了当前模式与档位读数的独立性：模式为 `wind` 时，仍读到 ANC 档位 `5` 和通透档位 `1`，见 [原始模式回包](/home/dohex/Projects/rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:6)、[ANC 档位回包](/home/dohex/Projects/rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:25) 和 [通透档位回包](/home/dohex/Projects/rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:27)。这只验证该副设备能在抗风模式读取两组值，不能证明两组参数当时均影响声音，或断电后持久化、跨模式写入的行为。

OpenRose 已据此将两组档位分别建模为可选的 `NoiseLevelCapabilities`，各自声明 `levels` 与 `visibleModes`；缺失的能力不开放对应档位设置。i5 和 i7 分别声明 ANC 档位只在 `anc` 显示、通透档位只在 `transparency` 显示。i7 自动与动态模式的原始 controller 值为 `4`、`6`，同样不匹配档位控件的 `3`、`1` 分支，见 [i7 接收处理依据](/home/dohex/Projects/rose_reverse/analysis/i7_receiver_findings.md:120)。两套界面风格都使用已确认模式决定显示；模式尚未读到、未知或未提供时隐藏两组档位，待处理的切换请求不提前改变显示区域。档位读数继续保留，参数校验采用每组自己的取值范围，显示条件不作为协议写入前置条件。

## 预设 EQ 与游戏模式（#7 / #8）

两类设置沿用同一个 `prepareSetting` 和会话确认流程。EQ 使用归一化的 `FieldValue.Text("pop" / "hifi" / "rock" / "light" / "eap")`；游戏使用明确的 `FieldValue.Toggle`。`EqualizerCapabilities.presets` 表示当前型号和固件可写预设；游戏写入能力由 `writableSettings` 表达。i5 不开放自定义 EQ；本阶段的 i7 预设入口也不把 custom、custom slot 或共享游戏 EQ 当成预设选项。

| 归一化预设 | i5 中文标签 / `0281` payload | i7 中文标签 / `2A` payload |
| --- | --- | --- |
| `pop` | 弱水经典 / `00` | 弱水经典 / `01` |
| `hifi` | 日系柔情 / `01` | 原声再现 / `00` |
| `rock` | 乐器大师 / `02` | 古典乐器 / `02` |
| `light` | 清新空灵 / `03` | 日系甜美 / `03` |
| `eap` | 不支持 | 欧美流行 / `06`，固件比较整数 ≥152 |

标签是原 App 的型号配置与中文资源文案，不表示实测频响、声学效果或通用流派定义。i5 型号配置使用对象池 `pp.txt:8781` 的四项列表，具体中文标题位于 `objs.txt:1745..1766`；`device_eq_panel_item.dart:29..64` 在中文下优先使用这些自定义标题。i7 列表在 `pp.txt:8369`，中文资源位于 `localization/language/zh_cn.dart:2549..2625`。i7 的 POP/HIFI 界面序号与发送值相反，不能将界面位置当作线值。

| 功能 | i5 ZT | i7 RS V2 |
| --- | --- | --- |
| EQ 确认查询 | `0201`，完整 TX `08ee00000002010a0003` | `FA2A`，seq00 完整 TX `ff0002fa2a25aa` |
| 游戏开 / 关 | `0683 00 / 01` | `0E 01 / 00` |
| 游戏确认查询 | `0603`，完整 TX `08ee00000006030a0009` | 客户端 `FA0E` 单字段查询，seq00 完整例 `ff0002fa0e09aa` |

i5 EQ、游戏设置和查询的完整 TX 来自 `analysis/i5_fixed_sender_commands.json:155..225`，对应 `zt_sender.dart:2256..2731`。i7 五种预设、游戏开关及 `getEq` 来自 `analysis/i7_sender_commands.json:830..869`、`:1190..1309`，对应 `rs_common_v2_sender.dart:3213`、`:3252`、`:4312..4515`。这些是独立于 OpenRose 编码器的 APK 提取向量；实际队列使用当前会话序号并重算 i7 校验。i7 的 `FA0E` 是根据已观察的 `FA ID` 单字段查询与 `getInfo` 中的 `0E` 作出的客户端补读选择，不是独立恢复的专用 `getGameMode` 方法；设备接纳仍待实测（`i7_sender_commands.json:2157..2162`）。

### EAP 的固件比较域

EAP 是 EQ 选项，不是独立功能开关。`objs.txt:1707..1712` 的 EAP 对象包含 `EarVersionCheck`，Dart 标记整数 `0x130` 解码为 **152**。`device_eq_panel_item.dart:1418..1446` 按该检查筛选预设；`constant/ear_version_check.dart:41..66` 将设备固件文本按十进制整数解析，失败按 0，再执行 ≥152 比较。该数字不是 RoseLink 的版本号，也不是语义化版本比较。

i7 RX `0D` 取前三个完整无符号字节，各自转为十进制文本并直接连接：`01 05 02` → `[1,5,2]` → `"152"` → 152。不是低半字节、字节拼成的大端整数或固定 `major*100+minor*10+patch`。例如 `[0,15,1]` → `"0151"` → 151，不开放；`[0,15,2]` → 152，开放；`[0,16,0]` → `"0160"` → 160，开放；`[1,4,255]` → 14255，亦满足该比较规则。这些用于核对原比较域，不代表这些固件实际存在或已验证。原始转换位于 `rs_common_v2_receiver.dart:17498..17616`，无分隔插值位于 `:17703..17719`，设备固件状态写入位于 `rs_common_v2_receive_handler.dart:5658..5665`。

OpenRose 从有效的 `FieldValue.Firmware.parts` 重建该整数，要求恰好三个 `0..255` 值，不信任可单独传入的 `displayText`。未知或无效固件不开放 EAP；基础四项仍可用。固件门槛同时用于显示能力和协议准备，不能绕过界面直接发送不受支持的 EAP。已经读到的 EAP 状态仍按实际字段显示，不因固件未知伪造另一个预设。

### EQ / 游戏验证证据

`EqualizerProtocolTest` 以独立 TX 字面值覆盖全部可写预设、EAP 门槛、相反的游戏极性和确认查询；另覆盖非法类型、未知预设、自定义 / 槽位名称、被拒绝请求不消耗序号，以及 EQ / 游戏 / ANC / 初始化共用序号。

合成 i5 RX 如 `000000000002010000a0`（POP）、`000000000006030000a0`（游戏开）只验证已恢复偏移与低半字节规则；不假定 RX 总帧校验。合成 i7 RX 如 `dd00022a0009aa`（单字节 HIFI）、`dd00032a00000aaa`（双字节 POP）、`dd00020e01eeaa`（游戏开）与 `dd00040d010502f6aa`（固件 152）按独立 LTV / 整帧前缀校验例构造，均不是抓包。i7 EQ 单字节 `00/01` 为 HIFI/POP，双字节 `00/01` 为 POP/HIFI，保留格式与原始值；不得按发送表统一覆盖（`analysis/i7_receiver_findings.md:80..89`）。

另外保留实际 i5 初始化读取 `09ff00000102010b000118`（HIFI）与 `09ff00000106030b00011e`（游戏关），来源 `analysis/i5_live_read_2026-10-18.jsonl:8,10`，固件 125。这两条只支持读取解析；本次没有设备，未验收预设或游戏切换、设置后的回读、声学 / 延迟效果或持久化。i7 全部设置与 RX 仍为离线验证。

## 触控、检测、i5 提示音与音频 / 多点（#9～#12）

### 单槽触控绑定

两型号当前配置均提供左右耳各单击、双击、三击和长按共八个槽位，每个槽位可选择该型号完整动作表。`TouchControlCapabilities` 提供槽位集合和动作集合；没有把其他型号的额外动作或推测的固件门槛加入此配置。i5 使用 `earfreeI5TouchList` 与 `typeFull`（`earfree_i5_device_config.dart:672..702`、`device_type_config/touch_info.dart:60..64`）；i7 使用 `rsCommonV2TouchList` 与 `typeFull`（`earfeel_i7_device_config.dart:951..985`、`touch_info.dart:36..40`）。实际动作对象分别为 `pp.txt:8785`、`:8377` 指向的 `objs.txt:999..1122`。

| 归一化动作 | i5 线值 | i7 线值 |
| --- | --- | --- |
| `none`（无功能） | `06` | `00` |
| `play_pause`（播放 / 暂停） | `05` | `01` |
| `previous_track`（上一曲） | `02` | `02` |
| `next_track`（下一曲） | `03` | `03` |
| `volume_up`（音量增加） | `00` | `04` |
| `volume_down`（音量降低） | `01` | `05` |
| `voice_assistant`（语音助手） | `04` | `07` |
| `noise_cycle`（降噪轮换） | `07` | `08` |
| `game_mode`（游戏模式） | 不支持 | `06` |

i5 发送 `0481 [side][gesture][action]`，`side=00/01` 分别为左 / 右，`gesture=00/01/02/03` 分别为单 / 双 / 三 / 长。i7 发送 `01 [slot][action]`，左侧四个 slot 为 `01/02/03/04`，右侧为 `11/12/13/14`。每次只发送目标槽位配置，不重写其他七槽，也不执行所绑定的播放、音量或降噪动作。发送证据为 `zt_sender.dart:1472..1935`、`rs_common_v2_sender.dart:2459..2698`；独立动态例包括 i5 左双击播放暂停 `08ee00000004810d000001058e` 和 i7 右长按降噪轮换 `ff00030114081faa`（seq00），见独立参考 `tests/test_i5_protocol.py`、`tests/test_i7_protocol.py`。

请求值 `FieldValue.TouchAssignment(side, gesture, action)` 不接受原始动作码；读取值仍是包含八个 `TouchBinding` 的 `FieldValue.TouchKeys`，原始动作码与未知动作均保留。设置后的 i5 查询为 `0401`（完整 TX `08ee00000004010a0005`）；i7 为客户端 `FA01` 补读。`expectedValues[TOUCH_KEYS]` 保存单槽请求投影，会话只比较该槽：目标动作未知时继续等有效回读，目标已知且不同为未应用，目标相同为已确认。其他槽的未知动作不阻碍目标确认。仍要求已恢复的完整 RX 结构：i5 八个槽位字节齐全，i7 八个 slot ID 齐全；没有据此放宽为接收只含目标槽的短包。槽位、动作与类型检查都在构帧和消耗序号前完成。

### 触控与检测功能开关、i5 提示音

| 设置 | i5 ZT | i7 RS V2 | 归一化参数 |
| --- | --- | --- | --- |
| 触控总开关 | `0A81 00/01` 开 / 关，读取 `0A01` | `07 00/01` 开 / 关，补读 `FA07` | `FieldValue.Toggle` |
| 检测功能开关 | `0C81 00/01` 开 / 关，读取 `0C01` | `08 00/01` 开 / 关，补读 `FA08` | `FieldValue.Toggle` |
| 提示音音量 | `0E83 00..04`，读取 `0E03` | 见下文 #13～#14：`2E 01..05`，`00` 受固件门槛限制 | `FieldValue.Number(1..5)`（i7 另含符合条件时的 0） |

触控与检测开关均为 `00` 开、`01` 关，不能复用 i7 游戏 / 多点的相反极性。检测功能开关不产生佩戴、摘下或入盒事件。固定 TX 常量来源为 `analysis/i5_fixed_sender_commands.json` 中 `setEarDetection*`、`setTouch*` 及读取方法，i7 对应 `analysis/i7_sender_commands.json` 的同名方法；原始发送函数在 `zt_sender.dart:1070..1407` 与 `rs_common_v2_sender.dart:2073..2114`、`:2379..2420`。

i5 提示音仅接受界面 1～5 档，减一后为 `00..04`，不提供静音 0。`zt_sender.dart:4663..4718` 明确校验 1..5 并执行减一；应使用本型号 `Zt229Sender.getDingLevel` 的 `0E03`（完整 TX `08ee0000000e030a0011`，`:4736`），不能套用共享父类 `0E06`。独立第 5 档例为 `08ee0000000e830b000496`。i7 的档位直接编码为 `2E 01..05`；仅静音 `2E 00` 需要固件比较整数 ≥304，不能把 304 当作整个音量功能的门槛（`device_ding_level_panel_item.dart:900..994`、`earfeel_i7_device_config.dart:809..840`）。完整门槛、回读和证据见下文 #13～#14。

### 音频与多点

`AudioControlCapabilities` 将可选配置与联动规则分别表达。i5 只有 `aac_sbc`、`ldac`，`multipointCoupled=true`；i7 只有 `aac_sbc`、`ldac`、`lhdc`、`lc3`，`multipointCoupled=false`。当前恢复的这些基础型号配置未发现独立固件门槛，不借用 EQ、增益或提示音静音门槛。实际系统是否协商到某种 codec、所有固件 / 平台组合是否接纳配置，仍需链路与硬件证据。

i5 音频与多点共用 `0881`：`00` 同时表示 AAC/SBC 配置与多点开，完整 TX `08ee00000008810b00008a`；`01` 同时表示 LDAC 配置与多点关，完整 TX `08ee00000008810b00018b`。两类公开操作都补读 `0801`（完整 TX `08ee00000008010a0009`），`expectedValues` 同时包含 `AUDIO_MODE` 和 `MULTIPOINT`，只有两字段都取得一致新回读才确认，不能制造 LDAC 与多点同时开启的已确认状态。

耦合证据在 i5 配置 `earfree_i5_device_config.dart:221..242`：`useAudioModeNotMulDeviceMode=true`，多点对应音频值 0，非多点对应 1。`ZtSender.setAudioAacSbc`（`:2131..2144`）调用父类 `setConnectMulOn`（`:2932`）生成 `0881 00`；父类 `setConnectMulOff`（`:2216..2228`）调用去重标签为 `Zt231Sender.setAudioLdac`（`:3749`）的共享函数生成 `0881 01`。独立固定提取中也存在 `0882` 的共享变体，不能据此替换 i5 的 `0881` 或开放 i5 的 LHDC / LC3。

i7 分别发送 `2B 00/01/02/03` 对应 AAC/SBC、LDAC、LHDC、LC3；多点为 `32 01/00` 开 / 关，固定向量见 `rs_common_v2_sender.dart:2840..3002`、`:5009` 与 `analysis/i7_sender_commands.json`。分别补读 `FA2B`、`FA32`，只要求各自目标字段的新回读，不把 i5 联动或互斥规则套入 i7，也不让另一未返回字段阻碍目标确认。其他字段只按真实收到的数据更新。客户端单字段补读规则同前，独立参考 `examples/i7_ble_client.py:16..21` 也分别查询这两个字段；这不证明实际所有音频 / 多点组合均能工作。

### 本阶段验证范围

`DeviceControlProtocolTest` 将独立 APK 固定 TX、从独立发送参数推导的完整动态例、实际读取样本和合成 RX 明确区分。覆盖八槽地址、两型号全部动作码及全部允许槽位 / 动作组合、单槽期望投影、全部音频选项、i5 双字段联动、开关极性、两型号的提示音档位、非法参数与不支持选项，以及初始化 / 触控 / 检测 / 音频共享 i7 序号。没有在测试中调用本客户端构造器生成预期字节。

合成 RX 例 `0000000000040100000f050f0f0f0f0f0f` 仅左双击动作已知，其余为未知；`dd00110101ff02ff03ff04ff11ff12ff13ff140844aa` 仅右长按已知，八个 i7 slot ID 均存在。它们只验证结构与字段语义，不是抓包。另保留固件 125 的实际 i5 初始化读取：触控绑定、触控开、检测关、提示音 2 档、LDAC 配置，来源 `analysis/i5_live_read_2026-10-18.jsonl:12,17,19,23,29`；这不能扩大为设置、触控实际触发范围、提示音响度、系统 codec 协商或持久化已验收。本次硬件仍不可用，i7 无实测。寻机、语言设置和 OTA 没有新增可执行入口。

## i7 自定义 EQ 与音频设置（#13～#14）

### 自定义十段 EQ

i7 的 `EqualizerCapabilities.custom` 提供界面所列频段 `20/100/300/500/1000/2000/3000/5000/8000/15000 Hz` 和整数范围 `-6..6`。频率是应用界面标签，不代表已测得的滤波器中心频率。曲线必须恰好包含十个整数；缺项、增项和越界值在构帧及序号分配前拒绝。

应用曲线按同一会话序号依次写入 `2A 04`（进入 custom）和 `3E` 加十个值。负值使用最高位符号标志加绝对值，例如 `-6=86`、`-1=81`，不是补码；正值原样写入。独立例 `-6,-3,-1,0,1,2,3,4,5,6` 的固定 `2A 04` 向量为 `ff00022a042faa`，独立 `3E` 动态向量为 `ff000b3e86838100010203040506e7aa`；运行时两次写入共用会话序号并各自重算校验。之后分别使用 `FA 2A` 回读 EQ 模式和 `FA 3E` 回读曲线；客户端将第二条查询保留为额外回读，只有两项都得到与请求一致的新值才确认整条曲线。

读取时，单字节 `2A 04` 表示 `custom`。这个自定义索引 4 来自 i7 配置的 `getCustomIndex`，不是把自定义预设与槽位值假定成同一语义。双字节 `2A 05 [slot]` 表示 `custom_slot` 并保留槽位及原始值，不能当作 custom 曲线已成功应用。查询 `FA2A`、`FA3E` 和设置 `2A04` 是 RoseLink Sender 的独立方法 / 向量；RX 测试使用合成 LTV 缓冲区，不是设备抓包。来源为 `analysis/i7_sender_commands.json`、`docs/earfeel-i7-protocol.md` 第 4.4～4.5 节及 `analysis/i7_eq_findings.md`。

### 增益、听力保护和提示音

增益 `45 00..02` 对应低 / 中 / 高三个界面档位；没有可依据的 dB 增益映射。听力保护 `46 00..06` 中 0 表示关闭，1～6 对应界面标记 75/80/85/90/95/100 dB；这些是标签，未证明实际声压、暴露剂量或医学效果。两项只有固件比较整数 ≥213 时开放。提示音 `2E 01..05` 直接对应 1～5 档，不依赖 304；`2E 00` 静音入口仅在固件比较整数 ≥304 时开放。i5 仍只支持提示音 1～5 档并将其映射至 `0E83 00..04`。

固件门槛从恰好三个 `0..255` 的完整固件字节分别转成十进制文本并无分隔拼接，再转成整数比较；例如 `[2,1,3] → 213`。无效或未知固件不开放增益 / 听力保护及静音 0，提示音基础 1～5 档仍可用。不信任单独提供的固件展示文字。协议写入分别使用 `45`、`46`、`2E`，并按字段单独回读；`FA45` 有独立 Sender 查询证据，`FA46`、`FA2E` 单 ID 查询是基于 `FA ID` 规律采用的客户端补读，仍需实机确认设备是否接纳。

TX 字面值取自独立静态提取 `analysis/i7_sender_commands.json` 和其对应汇编 Sender；当前不具备 i7 实机，不能称设置写入或回读已通过硬件验收。实现不增加提示音语言切换或 OTA 入口。

`AdvancedAudioProtocolTest` 用独立 TX 字面值检查两条 EQ 写入、`FA2A` / `FA3E` 和 45/46/2E 各值；另验证整条计划无效时不分配序号、固件门槛和 raw-preserving RX。custom、custom_slot 与曲线接收例均为合成缓冲区，不是实机抓包。

## i7 控制传输选择（#15）

广播规范化沿用排序后先拼所有 company ID（LE16）、再拼所有 payload 的规则。只有已识别的 i7、规范化十六进制串长度至少 34，且 `H[14:16] == "01"`，才使用 `H[22:34]` 作为 BR 地址；六个字节按原顺序转为大写冒号格式，不倒序。全零、全 FF 不提供地址。扫描 `DeviceId` 保持原身份，BLE 使用扫描标识，SPP 使用单独的 BR 地址，不把任意发现 ID 当作蓝牙 MAC。该广播尚未经过实机确认，也没有已知校验和或认证机制。

来源为 `/home/dohex/Projects/rose_reverse/docs/earfeel-i7-protocol.md` 第 2 节，及其中记录的 `base_device_config.dart:451..685`、`r_ble_scanner.dart:3737`。`ConnectManager._shouldUseSppControl`（`connect_manager.dart:4614`）要求平台 SPP 可用、型号支持、未强制 BLE、音频 profile 非 LE Audio。`A2dpManager.prefersBleControl` 使用每设备已连接的音频 profile；同一设备出现 LE Audio 时优先该类型，不查询当前 codec 或是否正在播放。因此配置值 LC3、设备名称、设备 type 和 A2DP 是否播放均不能替代音频链路证据。

OpenRose 保留 `UNKNOWN / CLASSIC / LE_AUDIO` 三种平台事实。仅 i7、有可用 BR 地址、SPP 可用、未强制 BLE，且有肯定的传统音频连接证据时选 SPP；其它条件均使用 BLE。Android 查询 A2DP、HEADSET 和 API 33+ 的公开 LE Audio profile；Android 12 / 12L（API 31–32）缺少同一公开 [LE_AUDIO 常量](https://developer.android.com/reference/android/bluetooth/BluetoothProfile#LE_AUDIO)，保守返回未知并使用 BLE，不填入隐藏 profile ID。所需代理未就绪、查询失败、没有目标音频连接或无法按地址关联时保守使用未知。查询结果只用于一次连接的选择；界面设置变化不重新选择当前连接。选择结果及原因公开给状态消费者。

普通控制 UUID 为 **`0cf12d31-fac3-4553-bd80-d6832e7b3931`**，不是标准串口 `1101`，也不是 OTA 的 `66666666-6666-6666-6666-666666666666`。SPP 在同一 socket 读写相同 RS Common V2 控制帧，接收仍按协议流处理分段 / 合并，发送仍由共享队列在本地写完成后间隔 100ms 并等待设备回读。流传输可容纳 35 字节初始化完整帧；BLE 容量不足时继续采用已有完整 FA 查询降级，不随意拆 TX。一次会话只创建一种控制连接；SPP 失败不会在后台自动另开 BLE、重放设置或并发抢占。

Android 使用公开 RFCOMM 接口，连接前取消 discovery，后台执行阻塞连接 / 流读写；取消时关闭 socket，解除阻塞。公开接口依据：[音频 profile 代理](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getProfileProxy(android.content.Context,%20android.bluetooth.BluetoothProfile.ServiceListener,%20int))、[LE Audio 已连接设备](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio#getConnectedDevices())、[BluetoothSocket 的连接与关闭](https://developer.android.com/reference/android/bluetooth/BluetoothSocket)。这些文档支持平台资源处理方式，不构成 i7 实测证据。双传输的离线行为及真实链路边界另见验证记录。
