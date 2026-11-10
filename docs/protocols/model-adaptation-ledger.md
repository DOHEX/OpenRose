# 型号适配证据账本

本账本覆盖本次新增的四个配置，来源为 [型号清单](00-supported-models-and-capabilities.md) 的「支持的耳机与识别分支」「RS Common V2：逐型号能力」、[结构化清单](supported-devices.json) 对应的完整 `models` 对象，以及各型号协议文档。四者均为 RoseLink 3.8.0 静态分析，未实机验证；合成夹具不是设备抓包。A：共用编解码与配置；B：族内差异；C：新能力类型；D：新协议族；不实现：证据或实现不足。下表每条状态均为静态分析，除非特别标明合成。

## 配置字段与识别分支清单

| 字段 / 清单列 | BudsFeel MK2 | OpenFeel MK2 | SWIFT 2 | Jasmine | 归类与来源 |
|---|---|---|---|---|---|
| 型号、displayName、shortLabel | BudsFeel MK2 | OpenFeel MK2 | SWIFT 2 | Jasmine | A；各协议文档识别节、JSON `display_name`；shortLabel 使用展示名 |
| 前缀 | `0b150a0b` | `0b0eb657` | `5999064c` | `0b0a0a11` | A；清单识别表、各协议文档识别节；夹具由前缀合成 |
| nameBrand / nameModelNumber / nameRequiresModel | budsfeel / mk2 / true | openfeel / mk2 / true | swift / swift2 / true | jasmine / jasmine / false | B；各协议文档展示名；MK2 需品牌和型号共同匹配，SWIFT 2 需型号数字，避免误认旧版 |
| 协议族 | rs_common_v2 | rs_common_v2 | rs_common_v2 | rs_common_v2 | A；清单识别表、各协议文档识别节 |
| 电量显示 / batteryLayout | 左右及仓 | 左右及仓 | 单体，复用 charge | 仅左右 | B；清单识别表、JSON `battery_types`、各协议文档电量节；Jasmine 系统电量可发布左右，不发布仓；Swift 单体不作为仓发布，解码时不要求左右槽具有在盒标志 |
| 控制传输 / gatt | BLE FE/F1/F2 | 同左 | 同左 | 同左 | A；各协议文档识别、传输节；UUID 均为蓝牙基础 UUID 全长形式 |
| sppControlUuid | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | 同左 | 同左 | 同左 | A；各协议文档识别、传输节；有经典地址时才可选用 |
| writeIntervalMillis | 100 | 100 | 100 | 100 | A；各文档传输节及 [RS Common V2 族文档的默认传输](families/rs-common-v2.md#默认传输)；非 OHOS 队列 |
| advertisedClassicAddress | null | null | null | null | 不实现；各文档识别节没有给出地址偏移，不能套用 i7 布局 |
| equalizerPresetLabels | 空 | 空 | 空 | 空 | 不实现；清单能力表没有这四款可映射的常规预设显示名，低音增强另列 |
| readableFields | V2 接收器支持的字段集合 | 同左 | 同左 | 同左 | A；各文档接收字段节；解析能力不代表设备一定上报 |
| 固件门槛 | 不用于已启用控制 | 同左 | 同左 | 同左 | A；各协议设置命令节没有已启用控制的门槛；BudsFeel OTA ≥200 只属于 OTA |

## 能力清单逐列

| 清单列 | BudsFeel MK2 | OpenFeel MK2 | SWIFT 2 | Jasmine | 归类 / 证据 |
|---|---|---|---|---|---|
| ANC 模式 | ANC、普通、通透、抗风 | 无入口 | 无入口 | 无入口 | A；BudsFeel 协议「设置命令」`09`；其余不实现 |
| EQ/音效 | 低音增强 0–4 | 低音增强 0–4 | 无入口 | 无入口 | C；前两者协议设置命令节 `2A 05` 的 Sender 范围；实际 UI 范围及设备效果未实测 |
| 自定义 EQ | 无入口 | 有，10 字节 | 无入口 | 无入口 | 不实现；OpenFeel 协议「设置命令」给出格式，未给该型号频率和滑块上下限，不能沿用 i7 数值 |
| 触控 | 总开关与左右单/双/三/长按 | 同左 | 总开关；物理键映射未知 | 总开关与左右单/双/三/长按 | A；各协议设置命令节 `07`、`01`；Swift 按键绑定不实现 |
| 触控动作 | 00–08：无、播放暂停、上一曲、下一曲、音量加减、游戏、助手、ANC 轮换 | 00–07，不含 ANC 轮换 | 00–07，但物理槽对应未知 | 00–07，不含 ANC 轮换 | A；JSON `capability_details.TouchCtlType` 与 `IntValueModel`；Swift 绑定不实现 |
| Codec 允许列表 | AAC/SBC、LDAC、LC3 | AAC/SBC、LDAC、LHDC、LC3 | 无选择入口 | 无选择入口 | A；各协议设置命令节 `2B`；无入口者不实现 |
| 佩戴检测 | 无入口 | 无入口 | 无入口 | 无入口 | 不实现；清单能力表及 JSON `wear` |
| 寻机 | 有 | 有 | 有 | 有 | 不实现；各协议设置命令节 `2F` 给出命令，但现有状态确认不能可靠表达完整寻机事件，Swift 单体寻机行为也未知 |
| OTA 路由 | JL，固件比较值 ≥200 | AB | AB | 面板，type=none | 不实现；各协议 OTA 节；升级会话和恢复未恢复，Jasmine 不进入 OTA 管理器 |
| 其他显式能力 | ANC 轮换；无强度 UI | 增益、听力保护 | 无 | 中英提示语言 | A：Buds `36`；OpenFeel `45` 0–2、`46` 0–6；C：Jasmine `31 00/01`；各协议设置命令节 |
| 游戏低延迟 | 有 | 有 | 有 | 有 | A；各协议设置命令节 `0E 01/00` |
| 多点连接 | 有 | 有 | 有 | 有 | A；各协议设置命令节 `32 01/00`，与音频模式独立 |
| 提示音音量 | 有入口 | 有入口 | 有入口 | 有入口 | 不实现；清单共有项和 Swift 2 协议设置命令节共享 Sender 档位，但各型号/固件允许范围未确定，不能沿用 i7 门槛 |
| 电量显示 | 左、右、仓 | 左、右、仓 | 单体 charge | 左、右 | B；清单识别表和 JSON `battery_types`；共享接收 `0C` 不保证全部字段实机上报 |
| 其他未列的显式能力 | 无 ANC/通透档位 UI；无佩戴检测 | 无 ANC 面板 | 无 ANC、EQ、音频模式、语言面板 | 无 ANC、EQ、佩戴检测面板 | 不实现；各协议设置命令节及 JSON `capabilities`；无入口不等于硬件不支持 |

## 协议文档逐节

| 协议文档 | 小节 | 内容与处理 | 分类 / 验证 |
|---|---|---|---|
| [BudsFeel MK2](models/budsfeel-mk2-protocol.md) | 识别、传输 | 前缀、BLE、控制 SPP 如上；OTA AE00/AE01/AE02 及 OTA SPP 独立 | A 控制通道；OTA 不实现；静态 |
| 同上 | 初始化、接收字段 | 共享 V2 帧及 getInfo，35 字节，MTU 协商不保证成功；接收 DD | A；静态，初始化回包夹具合成 |
| 同上 | 设置命令 | ANC、轮换、低音、触控、游戏、多点、音频按上表；寻机未开放 | A/C/不实现，静态 |
| 同上 | OTA | JL、门槛 200，升级内层格式未知 | 不实现；静态 |
| [OpenFeel MK2](models/openfeel-mk2-protocol.md) | 识别、传输、初始化 | 前缀、BLE/SPP、共享 getInfo；FA 3E 是独立读取而非 getInfo | A；静态，回包夹具合成 |
| 同上 | 设置命令 | 触控动作 00–07、游戏、多点、音频、低音、增益、听力保护；自定义 EQ 范围未确定，寻机未开放 | A/C/不实现；静态 |
| 同上 | OTA | AB、FF12/FF15/FF14，握手和恢复未核对 | 不实现；静态 |
| [Swift 2](models/swift2-protocol.md) | 识别、传输 | 前缀、BLE、控制 SPP；OTA SPP 不用于控制 | A；静态 |
| 同上 | 初始化、接收字段 | 共享 V2 getInfo、DD 接收，MTU 协商未保证 | A；静态，回包夹具合成 |
| 同上 | 电量、设置命令 | 单体 charge 独立于左右耳槽有效性解码、触控总开关、游戏、多点；物理键映射、提示音范围和单体寻机未确定 | A/B/不实现；静态 |
| 同上 | OTA | AB、FF12/FF15/FF14 和独立 OTA SPP；实际升级未验证 | 不实现；静态 |
| [Jasmine](models/jasmine-protocol.md) | 识别、传输 | 前缀、BLE、控制 SPP、100ms 队列；中文商品名关联未确认 | A；静态 |
| 同上 | 初始化、接收字段 | 共享 V2 getInfo、DD 接收；MTU 未保证 | A；静态，回包夹具合成 |
| 同上 | 电量、设置命令 | 左右电量、触控、游戏、多点、提示语言；寻机状态未开放 | A/B/C/不实现；静态 |
| 同上 | OTA、未实现与未知 | type=none，不进入管理器；无实机证据 | 不实现；静态 |

## 仅有清单的 37 个未适配分支

以下均为“不实现”：来源为 [识别分支清单](00-supported-models-and-capabilities.md#支持的耳机与识别分支)；缺少独立型号的初始化、命令/响应及参数范围等字节级证据，不能只根据族名或界面入口推断。多前缀归属一个配置的按清单一行计数。状态均为静态盘点、无本项目实机验证；F8 / RS Common 为 D（未实现协议族），ZT / RS Common V2 仍需型号级证据。

| 分支 | 前缀 | 族 | 处理 |
|---|---|---|---|
| CERAMICS | 18840100、18840103 | F8 | 不实现（D） |
| CERAMICS-X | 18840109 | F8 | 不实现（D） |
| CERAMICS-X [7034] | 18840112 | F8 | 不实现（D） |
| CERAMICS-MK2 | 0b0e0a0c | V2 | 不实现 |
| CERAMICS-MK2 [8912F] | 0b0e208b | V2 | 不实现 |
| CERAMICS-MK2 [5736] | 45d7b286 | V2 | 不实现 |
| CERAMICS-U | 0b0d0a0b | V2 | 不实现 |
| CERAMICS-U [HSC-2710] | 0b137957 | V2 | 不实现 |
| AGATE | 18840505 | F8 | 不实现（D） |
| AGATE X | 18840506 | F8 | 不实现（D） |
| EXPLORER | 18840107 | F8 | 不实现（D） |
| HeadFeel | 525300020002 | ZT | 不实现 |
| EarFree-i3 | f10203、010203 | ZT | 不实现 |
| BudsFree i | 12cc3e4c | RS Common | 不实现（D） |
| SWIFT | 0b0b0a0b | RS Common | 不实现（D） |
| Cambrian | 0b0c0a0c | RS Common | 不实现（D） |
| OpenFun | 0b0a0a0e、0b0a0a10 | RS Common | 不实现（D） |
| SportFeel | 0b0a0a0f | RS Common | 不实现（D） |
| OpenFeel | 0b0a0a0b、0b0a0a0c、0b0a0a0d | RS Common | 不实现（D）；三个前缀能力不同 |
| SpeakFeel [speakfree] | 0b0f0a0b | V2 | 不实现 |
| Magician | 0b0e0a0d | V2 | 不实现 |
| FURINA | 0b110a0c | V2 | 不实现 |
| Zircon | 0b110a0b | V2 | 不实现 |
| Hyacinth | 0b120a0b | V2 | 不实现 |
| Astro X | 0b0e0a0b | V2 | 不实现 |
| Opal | 0b0a0a12 | V2 | 不实现 |
| Mirror | 0b0a0a13 | V2 | 不实现 |
| KY01 | 0a0b0a0d | V2 | 不实现 |
| Coral | 0b0e0a0e | V2 | 不实现 |
| Patamon | 0b0ae916 | V2 | 不实现 |
| Tailmon | 0b0a941f | V2 | 不实现 |
| Gabumon | 0b0a2aed | V2 | 不实现 |
| Agumon | 0b0a34fd | V2 | 不实现 |
| SPEAKFEEL [speakfeel2] | 0b0e3ce6 | V2 | 不实现 |
| OpenFeel L | 45d7cbed | V2 | 不实现 |
| FIRST ANGEL | 0b0e64be | V2 | 不实现 |
| MOKA | 68f5b8eb | V2 | 不实现 |

## 本次离线验证

- `./gradlew :core:compileKotlinJvm` 通过。
- `./gradlew spotlessApply` 与 `./gradlew offlineCheck` 分开执行并通过；格式化、detekt、Android lint、覆盖率和 README 双语同步检查通过，没有增加基线条目。
- 可执行协议冒烟逐款覆盖广播 / 名称识别、共享 getInfo、游戏模式发送及合成回读确认；临时程序已移除。
- 正式回归覆盖重叠名称与旧款排除、低音增强 / 语言的非法值和未支持控制拒绝、缓存值不能确认新写入、单体电量独立解码，以及单体 / 仅双耳电量的系统发布边界。
- Miuix 和 Material 3 的低音增强、提示音语言选择界面已实际渲染并检查截图；正式界面测试覆盖两种风格的选择回调。
- main 源码中的四个新型号枚举名仅出现在 `ModelProfile` 声明及 `spec` 映射，其他行为读取型号配置。

实机验证：四款的广播、BLE / SPP 连接、初始化读取、设置效果与持久化均**未验证**。离线回包全部标为合成，不作为实机证据。

