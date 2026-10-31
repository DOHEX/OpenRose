---
status: accepted
---

# 以 Kotlin 共享核心和独立适配层初始化项目

OpenRose 以 Android 为首个交付平台，Windows、Linux 分阶段验收。首版同时包含 i5、i7 的型号支持，并从初始化开始考虑更多型号；Android 系统集成首版包含系统超级岛和融合设备中心，适配目标为 HyperOS 3、4。超级岛提供“不显示／官方／模块”配置，官方与模块两种模式均由系统界面呈现，不创建自绘浮层。界面风格的已确认约束见 [ADR 0001](0001-preserve-control-session-on-style-switch.md)。

超级岛模式表示呈现策略，不等同于“纯原生生成／模块主动构造”的技术路线选择。各模式与焦点通知的行为见 [ADR 0003](0003-official-island-and-headset-notifications.md)。

采用 Kotlin Multiplatform 共享核心、Compose Multiplatform 共享应用界面，首批内置 Miuix 和 Material 3。相较交接文档的 Flutter 与 Kotlin 两份协议实现方案，这能让应用和 Android 原生控制器复用 Kotlin 协议、命令和状态类型；Android Hook 与应用之间仍需明确的跨进程通信，不能通过共享代码或单例共享运行中的连接。

架构划分以下边界：

- **型号与能力**：区分零售名称、具体型号配置及固件条件下的能力。按识别证据选择型号配置，由能力和当前状态决定可提供的控制项。
- **协议与传输**：协议族负责命令编解码和字段含义，型号配置保留分支差异；平台传输负责连接、订阅、收发和恢复。i5 的 ZT 与 i7 的 RS Common V2 分别实现；i5 当前使用 BLE，i7 可按条件选择 BLE 或 SPP，不将整个控制接口限制为仅支持 BLE 的库接口。
- **界面风格**：Miuix 与 Material 3 消费相同的控制状态和操作接口，组件差异留在界面实现中。共享核心不依赖某一套界面组件库。
- **Android 系统集成**：使用 Kotlin 原生适配 HyperOS 与最新版现代 libxposed，应用和系统入口操作同一个权威控制会话。同一设备的连接、发送队列和状态有明确的所有者；按系统组件、可用类与方法等证据处理版本差异，分别记录 HyperOS 3、4 的结果。

这一划分允许独立增加型号、界面风格和系统兼容实现，代价是需要维护这些边界及相应验证。新增型号有时仍需新增协议实现、传输支持或能力处理，不能承诺只增加一条配置即可完成。

## 证据与验证边界

- `rose_reverse` 盘点了 43 个具体耳机配置项，包含同名硬件变体；该清单不代表 OpenRose 已支持这些配置。i5 已有单副设备在 Linux 上的 13 项初始化读取证据，设置尚未实机验证；i7 当前为协议研究和离线参考，尚无实机验证。
- 当前已明确的验收设备为 i5、小米 14（HyperOS 4）。i7、HyperOS 3 的实机验收环境尚未明确，不能将实现完成或上游声明记录为 OpenRose 实测通过。
- OppoPods 的参考快照为 `c201e251e68b59d46b6bc2758146b2c7f13ca2a1`；其发布记录和 README 明确涉及 HyperOS 4、OS 3/4 超级岛。参考其系统入口、能力映射和状态回调方式，ROSE 的协议与型号能力由本项目自己的证据确定。
- 2026-10-18 核实 libxposed API 和 Service 的最新正式发布均为 `102.0.0`：API 使用 `compileOnly("io.github.libxposed:api:102.0.0")`，Service 使用 `implementation("io.github.libxposed:service:102.0.0")`，锁定明确版本。新模块以框架 API 102 为初始基线，按实际使用能力设置模块声明，不直接照抄参考项目的 API 101 声明，也不混入旧版 Xposed API。
- Kotlin、Compose、Miuix 的版本组合、普通客户端最低 Android 版本及各平台传输依赖，在初始化阶段验证和选择；不直接继承 OppoPods 的构建参数。

## 来源

- [型号与能力盘点](/home/dohex/Projects/rose_reverse/docs/00-supported-models-and-capabilities.md)
- [i5 实机验证](/home/dohex/Projects/rose_reverse/analysis/i5_hardware_validation.md)
- [i7 协议与验证边界](/home/dohex/Projects/rose_reverse/docs/models/earfeel-i7-protocol.md)
- [OppoPods 固定快照 README](https://github.com/1812z/OppoPods/blob/c201e251e68b59d46b6bc2758146b2c7f13ca2a1/README.md)
- [OppoPods v2.1.0 发布记录](https://github.com/1812z/OppoPods/releases/tag/v2.1.0)
- [libxposed API 正式发布](https://central.sonatype.com/artifact/io.github.libxposed/api)
- [libxposed Service 正式发布](https://central.sonatype.com/artifact/io.github.libxposed/service)
