# RoseLink 协议研究与型号文档

本目录汇集了关于 ROSE / ROSESELSA（弱水时砂）系列耳机的逆向工程协议分析、43 款型号能力矩阵、各型号协议规范以及官方 OTA 更新机制研究。

研究基线为 **RoseLink 3.8.0（versionCode 5310）Android APK**（SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`），代码证据主要来自 Flutter ARM64 AOT 静态反汇编分析及 Linux 实机验证。

> **提示**：逆向研究文档记录的是官方客户端在反汇编与静态分析层面的协议事实；[OpenRose 工程实现与实测证据](../protocols.md) 则记录本项目在 Kotlin Multiplatform 客户端中的具体实现、测试向量、状态流及硬件验证边界。

---

## 协议报文族概览

RoseLink 客户端将支持的 43 款耳机配置分为四大控制报文族：

| 协议族 | 传输通道 | 帧结构特征 | 代表型号 |
|---|---|---|---|
| **ZT** | BLE GATT | 帧格式为 `08 EE 00 00 00 group op lengthLE16 payload sum8`；最长普通报文 14 字节 | EarFeel i5 / EarFree i5、EarFree i3、HeadFeel |
| **RS Common V2** | BLE GATT / 传统蓝牙 SPP | TX: `FF sequence bodyLength body sum8 AA`<br>RX: `DD opaqueHH [L T V...] CS AA` | EarFeel i7、BudsFeel MK2、OpenFeel MK2、Swift 2、Jasmine、Ceramics MK2 等 28 款 |
| **F8** | BLE GATT（部分支持 SPP） | 8 字节定长或专用命令帧 | Ceramics、Ceramics X、Agate、Explorer |
| **RS Common** | BLE GATT（部分支持 SPP） | 早期 RS 通用控制帧 | BudsFree i、Cambrian、OpenFeel、OpenFun、SportFeel、Swift |

---

## 各型号协议文档

目前已整理出详细控制协议规范的型号如下：

| 型号 / 配置 | 协议族 | 控制传输 | 功能要点 | 验证范围 | 文档入口 |
|---|---|---|---|---|---|
| **EarFeel i5 / EarFree i5** | ZT | BLE | 左右耳与盒电量、4 态降噪（含抗风噪）、ANC/通透各 3 档、4 项 EQ 预设、触控 8 槽映射、游戏模式、检测开关、AAC/LDAC 与多点耦合、WQ OTA | Linux 实机完成 13 项初始化读取；设置与 OTA 待实测 | [Markdown](models/earfeel-i5-protocol.md) · [JSON](models/earfeel-i5-protocol.json) |
| **EarFeel i7** | RS Common V2 | BLE / SPP（按条件选路） | 左右耳与盒电量、6 态降噪（含自动/动态 ANC）、ANC/通透各 3 档、5 项 EQ 预设（含 EAP 固件门槛 ≥152）、自定义 10 段 EQ、触控 8 槽、游戏模式、AAC/LDAC/LHDC/LC3 独立多点、增益/听力保护（≥213）、BES OTA | 协议编解码、自定义 EQ、离线测试通过；未连接实机 | [Markdown](models/earfeel-i7-protocol.md) · [JSON](models/earfeel-i7-protocol.json) |
| **BudsFeel MK2** | RS Common V2 | BLE / SPP | 左右耳与盒电量、4 态降噪模式与触控轮换、低音增强、触控映射、游戏模式、多点连接、AAC/SBC/LDAC/LC3、寻机、JL OTA（≥200） | 静态分析，未连接实机 | [Markdown](models/budsfeel-mk2-protocol.md) |
| **OpenFeel MK2** | RS Common V2 | BLE / SPP | 左右耳与盒电量、低音增强、自定义 10 段 EQ、触控映射（无 ANC 动作）、游戏模式、多点连接、增益与听力保护、音频模式、AB OTA | 静态分析，未连接实机 | [Markdown](models/openfeel-mk2-protocol.md) |
| **踏浪 Swift 2** | RS Common V2 | BLE / SPP | 骨传导耳机形态、单体电量（复用 charge 槽）、触控按键映射、游戏模式、多点连接、提示音音量、寻机、AB OTA | 静态分析，未连接实机 | [Markdown](models/swift2-protocol.md) |
| **Jasmine** | RS Common V2 | BLE / SPP | 左右耳电量、触控总开关与映射、游戏模式、多点连接、提示音语言切换（中/英）、寻机、无 OTA 路由 | 静态分析，未连接实机；与“茉莉”商品对应关系待证实 | [Markdown](models/jasmine-protocol.md) |

---

## 全型号能力矩阵与数据

- **[RoseLink 3.8.0 型号与能力清单](00-supported-models-and-capabilities.md)**：包含全部 43 个具体耳机配置项（34 个 TWS、9 个 Head）的识别前缀（manufacturerData）、协议族归属、电量槽位、降噪模式、EQ 支持、触控槽位、音频模式列表、OTA 路由及固件门槛比较。
- **[机器可读型号清单 JSON](supported-devices.json)**：由静态分析提取生成的完整结构化设备能力库。

---

## 官方 OTA 更新包与获取方法

- **[官方 OTA 更新包链接与协议研究](ota-package-links.md)**：记录官方 API（`api-internal.rosetechnic.com`）元数据请求格式、鉴权方式及当前已获取的 i5 / i7 固件更新包下载地址与历史版本分析。
- **[OTA 链接索引 JSON](ota-package-links.json)**：包含已验证的各版本固件元数据与资源详情。

---

## OpenRose 客户端工程实现

关于 OpenRose 客户端现已实现的功能与验证记录，请参阅：
- **[OpenRose i5 / i7 读取协议与实测证据](../protocols.md)**：
  - SHA-256 证据链与测试用例覆盖
  - 广播规范化与 Manufacturer Data 匹配算法
  - GATT 发现、先订阅后查询时序及串行 FIFO 发送队列
  - 降噪模式与档位映射（#5 / #6）
  - 预设 EQ 与游戏模式（#7 / #8）
  - 单槽触控绑定、检测开关与提示音音量（#9～#12）
  - i7 自定义十段 EQ 与固件门槛处理（#13～#14）
  - i7 蓝牙控制传输选路（BLE vs SPP）（#15）
