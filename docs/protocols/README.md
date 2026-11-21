# ROSE 耳机协议研究

这个目录收录了对官方 App **RoseLink** 的逆向研究：它支持哪些耳机、各型号用什么协议、能控制什么，以及固件 OTA 怎么下发。

这些是官方 App 的行为记录，不是厂商规范，大部分也没有经过真机验证。OpenRose 实际实现的 52 个型号配置见[协议实现说明](../protocols.md)；支持范围与未实现项见[型号适配证据账本](model-adaptation-ledger.md)。

## 研究基础

- 历史基线：RoseLink 3.8.0（versionCode 5310）Android 版，包名 `cn.ikaile.ruoshui.client`
- APK SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`
- 当前基线为 RoseLink 3.8.6（versionCode 5495），APK SHA-256 为 `42563496da6cdea9a6c9f16a886daaa1550b099d60156dd3ae9f6c555d89cf14`。见[新版配置与版本差异](3.8.6-model-delta.md)和[机器清单](supported-devices-3.8.6.json)。
- 方法：App 由 Flutter 编写，用 [Blutter](https://github.com/worawit/blutter) 对 ARM64 AOT 代码静态反汇编后分析
- 真机验证：只有一副 i5 在 Linux 上完成了 13 项初始化读取

本次同步对应 rose_reverse 提交 `99f8a725a64460de02770ac48326922fb2bd5e78`。

文中形如 `rose_reverse/...` 的路径指作者本地的研究工作区（APK、反汇编产物、提取脚本和实机记录），没有随本仓库发布。引用中的 `:行号` 和 `0x` 开头的地址可用来在反汇编产物中定位证据。

## 协议族

RoseLink 3.8.6 的 48 个蓝牙耳机配置使用四种控制协议：

| 协议族 | 控制通道 | 帧格式 | 代表型号 |
| --- | --- | --- | --- |
| ZT | BLE（HeadFeel 可用 SPP） | `08 EE 00 00 00 group op lengthLE16 payload sum8`，普通报文最长 14 字节 | EarFree i5、EarFree i3、HeadFeel |
| RS Common V2 | BLE / SPP | 发送 `FF seq len body sum8 AA`；接收 `DD HH [L T V...] CS AA` | EarFeel i7、BudsFeel MK2、OpenFeel MK2、Swift 2、Jasmine、CERAMICS-MK2 等 31 个配置 |
| F8 | BLE（部分支持 SPP） | `HEAD CMD 01 00 LEN DATA...` | Ceramics、Ceramics X、Agate、Explorer |
| RS Common | BLE（部分支持 SPP） | 早期 RS 通用帧 | BudsFree i、Cambrian、OpenFeel、OpenFun、SportFeel、Swift |

全族共用的识别、传输、帧、初始化与接收规则，以及按型号变化的项去哪里查，见协议族文档：[ZT](families/zt.md)、[F8](families/f8.md)、[RS Common V2](families/rs-common-v2.md)、[RS Common](families/rs-common.md)。

## 型号协议文档

| 型号 | 协议族 | 主要功能 | 验证情况 | 文档 |
| --- | --- | --- | --- | --- |
| EarFeel i5 / EarFree i5 | ZT | 4 种降噪模式，ANC / 通透各 3 档，4 个 EQ 预设，8 个触控槽位，游戏模式，检测开关，AAC / LDAC 与多点联动，WQ OTA | 真机读取已验证；设置和 OTA 未测 | [Markdown](models/earfeel-i5-protocol.md) · [JSON](models/earfeel-i5-protocol.json) |
| EarFree i3 | ZT | 降噪、EQ、触控、游戏模式、音频与多点 | 客户端接入有证据的控制；隐藏搜索无可靠回读，不开放；未实机验证 | [Markdown](models/earfree-i3-protocol.md) |
| HeadFeel / HeadFree | ZT | 单体电量、降噪、EQ、物理键、音频与多点、增益 | 客户端接入有证据的控制；自定义 EQ 在固件 ≥ 130 时开放；寻机 / OTA 未开放；未实机验证 | [Markdown](models/headfeel-protocol.md) |
| EarFeel i7 | RS Common V2 | 6 种降噪模式（含自动 / 动态），ANC / 通透各 3 档，5 个 EQ 预设，自定义 10 段 EQ，8 个触控槽位，游戏模式，AAC / LDAC / LHDC / LC3，独立多点，增益与听力保护，BES OTA | 仅离线测试 | [Markdown](models/earfeel-i7-protocol.md) · [JSON](models/earfeel-i7-protocol.json) |
| BudsFeel MK2 | RS Common V2 | 4 种降噪模式与触控轮换，低音增强，触控映射，游戏模式，多点，AAC / SBC / LDAC / LC3，寻机，JL OTA | 客户端接入有证据的常规控制；寻机 / OTA 未实现，实机未验证 | [Markdown](models/budsfeel-mk2-protocol.md) |
| OpenFeel MK2 | RS Common V2 | 低音增强，自定义 10 段 EQ，触控映射（无降噪动作），游戏模式，多点，增益与听力保护，AB OTA | 客户端接入有证据的常规控制；自定义 EQ 与提示音音量 0–5 已开放；寻机 / OTA 未实现，实机未验证 | [Markdown](models/openfeel-mk2-protocol.md) |
| 踏浪 Swift 2 | RS Common V2 | 骨传导，单体电量，触控映射，游戏模式，多点，提示音音量，寻机，AB OTA | 客户端接入单体电量、基础控制与提示音音量 1–5；按键映射 / 寻机 / OTA 未实现，实机未验证 | [Markdown](models/swift2-protocol.md) |
| Jasmine | RS Common V2 | 左右耳电量，触控开关与映射，游戏模式，多点，提示音语言（中 / 英），寻机，无 OTA | 客户端接入有证据的常规控制与提示音音量 1–5；寻机未实现，实机未验证；商品“茉莉”对应未确认 | [Markdown](models/jasmine-protocol.md) |
| AGATE / AGATE X | F8 | 左右耳电量、EQ、触控；AGATE 有游戏模式 | 客户端接入；未实机验证 | [Markdown](models/agate-protocol.md) |
| CERAMICS | F8 | 左右耳电量、EQ、触控、游戏模式 | 客户端接入；折叠后的 EQ 读回存在限制；未实机验证 | [Markdown](models/ceramics-protocol.md) |
| CERAMICS-X / X 7034 | F8 | 降噪、EQ、触控、音频与多点、提示音语言 | 两硬件配置分别接入；OTA 未实现；未实机验证 | [Markdown](models/ceramics-x-protocol.md) |
| EXPLORER | F8 | 降噪模式、EQ、触控、音频与多点、佩戴检测、提示音语言 | 客户端接入有证据的控制，含 ANC / 通透档位与提示音音量 1–5；寻机 / OTA 未开放；未实机验证 | [Markdown](models/explorer-protocol.md) |
| BudsFree i | RS Common | 降噪、EQ、触控、游戏、音频 | 客户端接入，含触控绑定；未实机验证 | [Markdown](models/budsfree-i-protocol.md) |
| Cambrian | RS Common | 单体电量、降噪、通透档位、EQ、游戏、音频与多点 | 客户端接入有证据的控制；寻机 / OTA 未实现；未实机验证 | [Markdown](models/cambrian-protocol.md) |
| OpenFeel / OpenFree | RS Common | 三硬件分支的 EQ、触控、游戏、音频与多点 | 分支分别接入；未实机验证 | [Markdown](models/openfree-protocol.md) |
| OpenFun | RS Common | 两硬件分支的触控、游戏、多点 | 分支分别接入；寻机未实现；未实机验证 | [Markdown](models/openfun-protocol.md) |
| SportFeel / SportFree | RS Common | 触控、游戏、多点、空间音频 | 客户端接入有证据的控制；寻机未实现；未实机验证 | [Markdown](models/sportfree-protocol.md) |
| SWIFT | RS Common | 单体电量、游戏模式 | 客户端接入有证据的控制；寻机未实现；未实机验证 | [Markdown](models/swift-protocol.md) |
| CERAMICS-MK2（标准、8912F、5736） | RS Common V2 | 三分支独立识别；5736 仅读左右耳电量。曲线、语言和提示音按分支及固件开放。 | 静态分析，未实机验证 | [Markdown](models/ceramics-mk2-protocol.md) |
| CERAMICS-U（标准、HSC-2710） | RS Common V2 | 深度降噪、三档 ANC、两档通透；自动降噪与提示音语言按分支开放。 | 静态分析，未实机验证 | [Markdown](models/ceramics-u-protocol.md) |
| FURINA | RS Common V2 | 深度降噪、轮换、EQ、佩戴检测、音频模式。 | 静态分析，未实机验证 | [Markdown](models/furina-protocol.md) |
| Zircon | RS Common V2 | 深度降噪、轮换、EQ、佩戴检测、音频模式。 | 静态分析，未实机验证 | [Markdown](models/zircon-protocol.md) |
| Coral | RS Common V2 | 两种降噪模式、三档低音增强。 | 静态分析，未实机验证 | [Markdown](models/coral-protocol.md) |
| SpeakFeel（speakfree） | RS Common V2 | 单体电量、EQ、提示音语言、aptX / HD / Adaptive。 | 静态分析，未实机验证 | [Markdown](models/speakfree-protocol.md) |
| SPEAKFEEL（speakfeel2） | RS Common V2 | 单体电量、两档低音增强、独立麦克风。与 speakfree 用广播区分。 | 静态分析，未实机验证 | [Markdown](models/speakfeel2-protocol.md) |
| Magician | RS Common V2 | 保留 3.8.0 配置；单体电量、提示音语言、aptX / HD。 | 静态分析，未实机验证 | [Markdown](models/magician-protocol.md) |
| Astro X | RS Common V2 | 连接时校时。按型号开放轻拍、空间模式、内容模式与耳道自适应；增益与听力保护需固件 ≥152。 | 静态分析，未实机验证 | [Markdown](models/astro-x-protocol.md) |
| OpenFeel L | RS Common V2 | 预设、自定义曲线、增益和听力保护。 | 静态分析，未实机验证 | [Markdown](models/openfeel-l-protocol.md) |
| FIRST ANGEL | RS Common V2 | 音乐和游戏有不同的 EQ 选项；提示音静音需固件 ≥510。 | 静态分析，未实机验证 | [Markdown](models/first-angel-protocol.md) |
| MOKA | RS Common V2 | 单体电量、预设、自定义曲线、音频模式。 | 静态分析，未实机验证 | [Markdown](models/moka-protocol.md) |
| Hyacinth | RS Common V2 | 预设 EQ、触控、提示音语言和音频模式。 | 静态分析，未实机验证 | [Markdown](models/hyacinth-protocol.md) |
| Opal | RS Common V2 | 三种降噪模式、预设 EQ、触控。 | 静态分析，未实机验证 | [Markdown](models/opal-protocol.md) |
| Mirror | RS Common V2 | 预设 EQ、触控、提示音语言。 | 静态分析，未实机验证 | [Markdown](models/mirror-protocol.md) |
| KY01 | RS Common V2 | 预设 EQ、触控、提示音语言。触控动作允许降噪轮换。 | 静态分析，未实机验证 | [Markdown](models/ky01-protocol.md) |
| Luminous Patamon、Tailmon、Gabumon、Agumon | RS Common V2 | 四个独立前缀；仅左右耳电量、触控、游戏、多点、提示音。 | 静态分析，未实机验证 | [Markdown](models/luminous-protocol.md) |
| BudsFeel AE Neo | RS Common V2 | 仅左右耳电量、自定义 EQ、增益、听力保护。 | 静态分析，未实机验证 | [Markdown](models/budsfeel-ae-neo-protocol.md) |
| EarFeel iE Neo | RS Common V2 | 仅左右耳电量、三种降噪模式和轮换、自定义 EQ、增益、听力保护。 | 静态分析，未实机验证 | [Markdown](models/earfeel-ie-neo-protocol.md) |
| EarFeel iE2 | RS Common V2 | 含仓电量、四种降噪模式和轮换、自定义 EQ、AAC/SBC 与 LDAC。 | 静态分析，未实机验证 | [Markdown](models/earfeel-ie2-protocol.md) |
| SportFeel 8912F | RS Common V2 | 含仓电量、HIFI 预设、触控、音频模式；与早期 SportFeel 的协议和电量布局不同。 | 静态分析，未实机验证 | [Markdown](models/sportfeel-8912f-protocol.md) |
| Cambrian S | RS Common | 单体电量、降噪、EQ、通透档位与音频模式。 | 静态分析，未实机验证 | [Markdown](models/cambrian-s-protocol.md) |
| OpenLink | RS Common | 左右耳电量、触控、游戏、多点与提示音。 | 静态分析，未实机验证 | [Markdown](models/openlink-protocol.md) |

## 其他资料

| 文档 | 内容 |
| --- | --- |
| [文档格式](FORMAT.md) | 本目录文档的结构、验证状态、出处写法和从 rose_reverse 复制的规则 |
| [3.8.6 型号清单](supported-devices-3.8.6.json) | 48 个蓝牙耳机配置及其 GDT 分派、控件和选项，对应 [版本差异](3.8.6-model-delta.md) |
| [3.8.0 型号与能力清单](00-supported-models-and-capabilities.md) | 3.8.0 的 43 个耳机配置（App 内分类为 34 个 `tws`、9 个 `head`）的识别前缀、协议族、电量、降噪、EQ、触控、音频选项、OTA 方式和固件门槛 |
| [supported-devices.json](supported-devices.json) | 上述清单的机器可读版本 |
| [OTA 研究](ota-package-links.md) | 官方 OTA 接口（`api-internal.rosetechnic.com`）的请求格式、鉴权方式，以及已获取的 i5 / i7 固件包地址 |
| [ota-package-links.json](ota-package-links.json) | OTA 版本元数据的机器可读版本 |

## 阅读提示

- 清单中标为"有"的能力，表示官方 App 为该型号配置了对应入口，不代表真机上一定能用。标为"无"也不代表硬件不支持。
- 同一零售名称可能对应多个硬件分支，识别前缀才是区分依据。
- 共享发送器里存在某个命令，不代表所有使用该协议的型号都支持它。
