# ROSE 耳机协议研究

这个目录收录了对官方 App **RoseLink** 的逆向研究：它支持哪些耳机、各型号用什么协议、能控制什么，以及固件 OTA 怎么下发。

这些是官方 App 的行为记录，不是厂商规范，大部分也没有经过真机验证。OpenRose 实际实现的六个型号配置见[协议实现说明](../protocols.md)；新增配置的支持范围与未实现项见[型号适配证据账本](model-adaptation-ledger.md)。

## 研究基础

- 分析对象：RoseLink 3.8.0（versionCode 5310）Android 版，包名 `cn.ikaile.ruoshui.client`
- APK SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`
- 方法：App 由 Flutter 编写，用 [Blutter](https://github.com/worawit/blutter) 对 ARM64 AOT 代码静态反汇编后分析
- 真机验证：只有一副 i5 在 Linux 上完成了 13 项初始化读取

文中形如 `rose_reverse/...` 的路径指作者本地的研究工作区（APK、反汇编产物、提取脚本和实机记录），没有随本仓库发布。引用中的 `:行号` 和 `0x` 开头的地址可用来在反汇编产物中定位证据。

## 协议族

RoseLink 支持的 43 个耳机配置使用四种控制协议：

| 协议族 | 控制通道 | 帧格式 | 代表型号 |
| --- | --- | --- | --- |
| ZT | BLE | `08 EE 00 00 00 group op lengthLE16 payload sum8`，普通报文最长 14 字节 | EarFree i5、EarFree i3、HeadFeel |
| RS Common V2 | BLE / SPP | 发送 `FF seq len body sum8 AA`；接收 `DD HH [L T V...] CS AA` | EarFeel i7、BudsFeel MK2、OpenFeel MK2、Swift 2、Jasmine、Ceramics MK2 等 28 款 |
| F8 | BLE（部分支持 SPP） | 8 字节定长帧或专用命令帧 | Ceramics、Ceramics X、Agate、Explorer |
| RS Common | BLE（部分支持 SPP） | 早期 RS 通用帧 | BudsFree i、Cambrian、OpenFeel、OpenFun、SportFeel、Swift |

## 型号协议文档

| 型号 | 协议族 | 主要功能 | 验证情况 | 文档 |
| --- | --- | --- | --- | --- |
| EarFeel i5 / EarFree i5 | ZT | 4 种降噪模式，ANC / 通透各 3 档，4 个 EQ 预设，8 个触控槽位，游戏模式，检测开关，AAC / LDAC 与多点联动，WQ OTA | 真机读取已验证；设置和 OTA 未测 | [Markdown](models/earfeel-i5-protocol.md) · [JSON](models/earfeel-i5-protocol.json) |
| EarFeel i7 | RS Common V2 | 6 种降噪模式（含自动 / 动态），ANC / 通透各 3 档，5 个 EQ 预设，自定义 10 段 EQ，8 个触控槽位，游戏模式，AAC / LDAC / LHDC / LC3，独立多点，增益与听力保护，BES OTA | 仅离线测试 | [Markdown](models/earfeel-i7-protocol.md) · [JSON](models/earfeel-i7-protocol.json) |
| BudsFeel MK2 | RS Common V2 | 4 种降噪模式与触控轮换，低音增强，触控映射，游戏模式，多点，AAC / SBC / LDAC / LC3，寻机，JL OTA | 客户端接入有证据的常规控制；寻机 / OTA 未实现，实机未验证 | [Markdown](models/budsfeel-mk2-protocol.md) |
| OpenFeel MK2 | RS Common V2 | 低音增强，自定义 10 段 EQ，触控映射（无降噪动作），游戏模式，多点，增益与听力保护，AB OTA | 客户端接入有证据的常规控制；自定义 EQ / 寻机 / OTA 未实现，实机未验证 | [Markdown](models/openfeel-mk2-protocol.md) |
| 踏浪 Swift 2 | RS Common V2 | 骨传导，单体电量，触控映射，游戏模式，多点，提示音音量，寻机，AB OTA | 客户端接入单体电量与基础控制；按键映射 / 提示音音量 / 寻机 / OTA 未实现，实机未验证 | [Markdown](models/swift2-protocol.md) |
| Jasmine | RS Common V2 | 左右耳电量，触控开关与映射，游戏模式，多点，提示音语言（中 / 英），寻机，无 OTA | 客户端接入有证据的常规控制；寻机 / 提示音音量未实现，实机未验证；商品“茉莉”对应未确认 | [Markdown](models/jasmine-protocol.md) |

## 其他资料

| 文档 | 内容 |
| --- | --- |
| [型号与能力清单](00-supported-models-and-capabilities.md) | 全部 43 个耳机配置（App 内分类为 34 个 `tws`、9 个 `head`）的识别前缀、协议族、电量、降噪、EQ、触控、音频选项、OTA 方式和固件门槛 |
| [supported-devices.json](supported-devices.json) | 上述清单的机器可读版本 |
| [OTA 研究](ota-package-links.md) | 官方 OTA 接口（`api-internal.rosetechnic.com`）的请求格式、鉴权方式，以及已获取的 i5 / i7 固件包地址 |
| [ota-package-links.json](ota-package-links.json) | OTA 版本元数据的机器可读版本 |

## 阅读提示

- 清单中标为"有"的能力，表示官方 App 为该型号配置了对应入口，不代表真机上一定能用。标为"无"也不代表硬件不支持。
- 同一零售名称可能对应多个硬件分支，识别前缀才是区分依据。
- 共享发送器里存在某个命令，不代表所有使用该协议的型号都支持它。
