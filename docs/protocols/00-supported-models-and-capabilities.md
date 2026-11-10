# RoseLink 3.8.0：支持耳机与能力清单

分析日期：2026-10-13。包名 `cn.ikaile.ruoshui.client`，版本 `3.8.0`，版本码 `5310`。

输入：RoseLink_3.8.0.apk（`rose_reverse/RoseLink_3.8.0.apk`）。SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。

本轮先完成型号与能力盘点：APK 注册了 **43 个具体耳机配置项**（34 个 `tws`、9 个 `head`），另有 AURAMIC 麦克风及 3 个未知/通用类型。43 包含同名硬件变体，不能解释为 43 个互不重复的零售产品；`head` 是程序分类，也不能单凭它断言佩戴形态。

这是型号与能力盘点：已确认客户端的静态注册、识别分发、功能入口与允许选项；部分型号另有协议分析和实机验证，不能把清单中的能力入口直接当作实机验证结果。

## 型号协议文档

- [AGATE / AGATE X](models/agate-protocol.md)
- [BudsFeel MK2](models/budsfeel-mk2-protocol.md)
- [BudsFree i](models/budsfree-i-protocol.md)
- [Cambrian](models/cambrian-protocol.md)
- [CERAMICS](models/ceramics-protocol.md)
- [CERAMICS-X](models/ceramics-x-protocol.md)
- [EarFeel-i5](models/earfeel-i5-protocol.md)
- [EarFeel i7](models/earfeel-i7-protocol.md)
- [EarFree-i3](models/earfree-i3-protocol.md)
- [EXPLORER](models/explorer-protocol.md)
- [HeadFeel](models/headfeel-protocol.md)
- [Jasmine](models/jasmine-protocol.md)
- [OpenFeel MK2](models/openfeel-mk2-protocol.md)
- [OpenFeel](models/openfree-protocol.md)
- [OpenFun](models/openfun-protocol.md)
- [RS-Common](models/rs-common-protocol.md)
- [SportFeel](models/sportfree-protocol.md)
- [SWIFT](models/swift-protocol.md)
- [Swift 2](models/swift2-protocol.md)

## 能力判定口径

- 表中的“有/✓”表示该型号配置显式构建对应控制入口，并以枚举、发送/接收实现交叉核对；不表示已经在实机执行成功。
- “—/无入口/无显式启用”表示此版本该型号配置未开放对应入口，不代表硬件绝对不支持。
- Codec 列是 App 提供的音频模式选项；AAC/SBC、LDAC、LHDC、LC3、aptX 不属于控制报文协议族。
- OTA 列记录客户端路由（WQ/BES/AB/JL/AB123A）。`type=none` 与无入口分开记录，不能算作可升级。
- 固件版本门槛是程序比较的整数，已对 Dart 标记整数解码；它们与展示固件版本号的编码关系仍需在协议阶段确认。
- 共享 Sender 有某方法，不能据此把该方法算入所有型号。

## 支持的耳机与识别分支

客户端按识别串的前 12 → 8 → 6 个十六进制字符尝试最长匹配；下表保留各配置的 `deviceIdentifiers`。这些字符串不是已经确认偏移的完整 BLE 广播样本。Dart 枚举序号也不是线上型号 ID。

| 型号 / 硬件分支 | 识别前缀 | 控制报文族 | 电量显示 | 控制传输配置 | 直接证据 |
|---|---|---|---|---|---|
| CERAMICS | `18840100`、`18840103` | f8 | 左、右 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_device_config.dart:10`） |
| CERAMICS-X | `18840109` | f8 | 左、右、盒 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart:10`） |
| CERAMICS-X [7034] | `18840112` | f8 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/ceramics_x_7034_device_config.dart:10`） |
| CERAMICS-MK2 | `0b0e0a0c` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:10`） |
| CERAMICS-MK2 [8912F] | `0b0e208b` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:10`） |
| CERAMICS-MK2 [5736] | `45d7b286` | rs_common_v2 | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:10`） |
| CERAMICS-U | `0b0d0a0b` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:10`） |
| CERAMICS-U [HSC-2710] | `0b137957` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:10`） |
| AGATE | `18840505` | f8 | 左、右 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_device_config.dart:10`） |
| AGATE X | `18840506` | f8 | 左、右 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/agate_x_device_config.dart:10`） |
| EXPLORER | `18840107` | f8 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/f8/explorer_device_config.dart:10`） |
| EarFeel-i5 | `525300010001` | zt | 左、右、盒 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i5_device_config.dart:10`） |
| HeadFeel | `525300020002` | zt | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/headfree_device_config.dart:10`） |
| EarFree-i3 | `f10203`、`010203` | zt | 左、右、盒 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/zt/earfree_i3_device_config.dart:10`） |
| BudsFree i | `12cc3e4c` | rs_common | 左、右 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/budsfree_device_config.dart:10`） |
| SWIFT | `0b0b0a0b` | rs_common | 单体（复用 charge 槽） | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/swift_device_config.dart:10`） |
| Cambrian | `0b0c0a0c` | rs_common | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/cambrian_device_config.dart:10`） |
| OpenFun | `0b0a0a0e`、`0b0a0a10` | rs_common | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfun_device_config.dart:10`） |
| SportFeel | `0b0a0a0f` | rs_common | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:10`） |
| OpenFeel | `0b0a0a0b`、`0b0a0a0c`、`0b0a0a0d` | rs_common | 左、右、盒 | BLE；未启用 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/openfree_device_config.dart:10`） |
| OpenFeel MK2 | `0b0eb657` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:10`） |
| SpeakFeel [speakfree] | `0b0f0a0b` | rs_common_v2 | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfree_device_config.dart:10`） |
| Magician | `0b0e0a0d` | rs_common_v2 | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/magician_device_config.dart:10`） |
| FURINA | `0b110a0c` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:10`） |
| Zircon | `0b110a0b` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/zircon_device_config.dart:10`） |
| Hyacinth | `0b120a0b` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/hyacinth_device_config.dart:10`） |
| Jasmine | `0b0a0a11` | rs_common_v2 | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart:10`） |
| Astro X | `0b0e0a0b` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/astro_x_device_config.dart:10`） |
| Opal | `0b0a0a12` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/opal_device_config.dart:10`） |
| Mirror | `0b0a0a13` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/mirror_device_config.dart:10`） |
| KY01 | `0a0b0a0d` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ky01_device_config.dart:10`） |
| Coral | `0b0e0a0e` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:10`） |
| BudsFeel MK2 | `0b150a0b` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:10`） |
| SWIFT 2 | `5999064c` | rs_common_v2 | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart:10`） |
| Patamon | `0b0ae916` | rs_common_v2 | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_patamon_device_config.dart:10`） |
| Tailmon | `0b0a941f` | rs_common_v2 | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_tailmon_device_config.dart:10`） |
| Gabumon | `0b0a2aed` | rs_common_v2 | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_gabumon_device_config.dart:10`） |
| Agumon | `0b0a34fd` | rs_common_v2 | 左、右 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/luminous_agumon_device_config.dart:10`） |
| EarFeel i7 | `b8e5290f` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:10`） |
| SPEAKFEEL [speakfeel2] | `0b0e3ce6` | rs_common_v2 | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/speakfeel2_device_config.dart:10`） |
| OpenFeel L | `45d7cbed` | rs_common_v2 | 左、右、盒 | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_l_device_config.dart:10`） |
| FIRST ANGEL | `0b0e64be` | rs_common_v2 | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/first_angel_device_config.dart:12`） |
| MOKA | `68f5b8eb` | rs_common_v2 | 单体（复用 charge 槽） | BLE；配置允许 SPP | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/moka_device_config.dart:12`） |


F8 6 项、ZT 3 项、RS Common 6 项、RS Common V2 28 项。SPP 是否实际选用还取决于平台、强制 BLE 设置与当前音频 Profile；LE Audio 场景优先 BLE。部分型号另有 USB HID 控制或 OTA，见能力表及连接层证据。

## F8、ZT、RS Common：逐型号能力

| 型号 / App 显示别名 | 协议 / Sender | 电量显示 | 降噪模式 | EQ | 按键自定义 | 游戏低延迟 | 音频模式 | 多设备 | 佩戴检测入口 | 寻机入口 | OTA | 额外控制入口 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AGATE | F8 / F8Sender | 左、右 | — | Rock、HIFI | 单击、双击、长按 | ✓ | — | — | — | — | — | — |
| AGATE X | F8 / F8Sender | 左、右 | — | Rock、POP | 单击、双击、长按 | — | — | — | — | — | — | — |
| CERAMICS | F8 / F8Sender | 左、右 | — | POP、HIFI、ROCK | 单击、双击、长按 | ✓ | — | — | — | — | — | — |
| CERAMICS-X | F8 / F8Sender | 左、右、盒 | ANC、抗风、关闭、通透 | POP、HIFI、ROCK | 单击、双击、三击、长按 | ✓ | AAC/SBC、LDAC、多设备 | ✓ | — | — | WQ | 中/英文提示语言；星芒业务入口 |
| CERAMICS-X-7034 | F8 / F8Sender | 左、右、盒 | ANC、抗风、关闭、通透 | POP、HIFI、ROCK | 单击、双击、三击、长按 | ✓ | AAC/SBC、LDAC、多设备 | ✓ | — | — | WQ | 中/英文提示语言；星芒业务入口 |
| EXPLORER | F8 / ExplorerSender | 左、右、盒 | ANC、关闭、通透；ANC/通透各3级 | POP、HIFI、ROCK（专属 EQ 标签） | 单击、双击、三击、长按 | ✓ | AAC/SBC、LDAC、多设备 | ✓ | ✓ | ✓ | WQ | 提示音音量；中/英文提示语言；更多设置 |
| EarFree-i3 | ZT / Zt219Sender | 左、右、盒 | ANC、关闭、通透 | POP、HIFI、ROCK | 单击、双击、三击 | ✓ | AAC/SBC、LDAC | ✓ | — | — | WQ | 隐藏搜索 / HideSearch；更多设置 |
| EARFREE-i5 / EarFeel-i5 | ZT / Zt229Sender | 左、右、盒 | ANC、抗风、关闭、通透；ANC/通透各3级 | POP、HIFI、ROCK、LIGHT | 单击、双击、三击、长按 | ✓ | AAC/SBC、LDAC | ✓ | ✓ | ✓ | WQ | 提示音音量；ANC 模式轮换；星芒业务入口；OTA 提示语言切换 |
| HeadFree / HeadFeel | ZT / Zt231Sender | 单体（复用 charge 槽） | ANC、抗风、关闭、通透；ANC/通透各3级 | POP、HIFI、ROCK；自定义 EQ，版本 ≥130 | 电源键单/双/三击、ANC 键双/三击 | ✓ | AAC/SBC、LDAC、LHDC、LDAC+LHDC | ✓ | —（有接收处理） | ✓ | BES | 增益等级（版本 ≥130）；提示音音量；ANC 模式轮换；更多设置 |
| BudsFree i | RS Common / RsCommonSender | 左、右 | ANC、关闭、通透 | POP、HIFI、ROCK | 单击、双击、三击 | ✓ | AAC/SBC、LDAC | — | — | — | — | — |
| Cambrian | RS Common / RsCommonSender | 单体（复用 charge 槽） | ANC、抗风、关闭、通透；通透3级 | POP、HIFI、ROCK | 单击、双击、三击；键标签为音量− / 音量+ | ✓ | AAC/SBC、LDAC | ✓ | — | ✓ | AB | 提示音音量；星芒业务入口；更多设置 |
| OpenFree / OpenFeel | RS Common / RsCommonSender | 左、右、盒 | — | BASS、BALANCE | 单击、双击、三击；动作列表按设备 ID 变化 | ✓ | AAC/SBC、LC3、多设备；按 ID 增加 LDAC / LHDC | ✓ | — | — | — | — |
| OpenFun | RS Common / RsCommonSender | 左、右 | — | — | 单击、双击、三击；存在设备 ID 条件 | ✓ | — | ✓ | — | ✓ | — | 提示音音量；更多设置 |
| SportFree / SportFeel | RS Common / RsCommonSender | 左、右 | — | — | 单击、双击、三击 | ✓ | — | ✓ | — | ✓ | — | 提示音音量；空间音频开关；更多设置 |
| SWIFT | RS Common / RsCommonSender | 单体（复用 charge 槽） | — | — | 单击、双击、三击 | ✓ | — | — | — | ✓ | — | — |


HeadFeel、Cambrian、SWIFT 的 `charge` 电量槽表示单体电量，不是充电盒。该组详细 Sender/Receiver/Handler、等级开关及型号证据见 legacy_capabilities.md（`rose_reverse/analysis/legacy_capabilities.md`）。

## RS Common V2：逐型号能力

以下 28 个耳机配置均有游戏/低延迟、多点连接、提示音音量入口。自定义 EQ 单独列出，其余 EQ 列是预设或音效选项。

| 型号/硬件分支 | ANC 模式 | EQ/音效 | 自定义 EQ | 触控 | Codec 允许列表 | 佩戴检测 | 寻机 | OTA 路由 | 其他显式能力 |
|---|---|---|---|---|---|---|---|---|---|
| Astro X | — | BASS/BALANCE/BASS+ | 无显式启用 | 有 | AAC/SBC/LDAC/LHDC | 有 | 有 | AB | 增益；听力保护；空间音频；耳道自适应；轻拍控制 |
| BudsFeel MK2 | ANC/正常/通透/抗风噪 | 低音增强 | 无显式启用 | 有 | AAC/SBC/LDAC/LC3 | — | 有 | JL（≥ 200） | ANC 循环配置；无 ANC/通透档位 UI |
| CERAMICS-MK2 [5736] | ANC/抗风噪/正常/通透 | POP/HIFI/ROCK | ≥ 154 | 有 | 无选择入口 | — | 有 | 面板；type=none | ANC 循环配置；ANC 3 档、通透 3 档 |
| CERAMICS-MK2 [8912F] | ANC/抗风噪/正常/通透 | POP/HIFI/ROCK | 有 | 有 | AAC/SBC/LDAC | — | 有 | AB | ANC 循环配置；提示音语言；ANC 3 档、通透 3 档 |
| CERAMICS-MK2 | ANC/抗风噪/正常/通透 | POP/HIFI/ROCK | ≥ 154 | 有 | AAC/SBC/LDAC | — | 有 | AB | ANC 循环配置；提示音语言；ANC 3 档、通透 3 档 |
| CERAMICS-U | ANC/抗风噪/正常/通透 | eq_custom_LIGHT_01/POP/HIFI/ROCK | ≥ 33 | 有 | AAC/SBC/LDAC/LHDC | — | 有 | WQ | 增益；自动 ANC；深度 ANC；ANC 循环配置；提示音语言；ANC 3 档、通透 2 档 |
| CERAMICS-U [HSC-2710] | ANC/抗风噪/正常/通透 | eq_custom_LIGHT_01/POP/HIFI/ROCK | 有 | 有 | AAC/SBC/LDAC/LHDC | — | 有 | BES | 增益；深度 ANC；ANC 循环配置；提示音语言；ANC 3 档、通透 2 档 |
| Coral | ANC 开/关 | 低音增强 | 无显式启用 | 有 | AAC/SBC/LDAC/LHDC/LC3 | — | 有 | AB | 无 ANC/通透档位 UI |
| EarFeel i7 | ANC/抗风噪/正常/通透 | eq_custom_POPS_i7/eq_custom_HIFI_i7/eq_custom_ROCK_i7/eq_custom_LIGHT_i7/eq_custom_EAP_i7 | 有 | 有 | AAC/SBC/LDAC/LHDC/LC3 | 有 | 有 | BES | 增益；听力保护；自动 ANC；动态自动 ANC；ANC 循环配置；ANC 3 档、通透 3 档；EAP EQ ≥ 152 |
| FIRST ANGEL | — | first_angle_game_eq_1/HIFI/POP | 有（音乐/游戏分离） | 有 | 无选择入口 | — | 有 | AB + USB AB123A | — |
| FURINA | ANC/正常/通透/抗风噪 | POP/HIFI/ROCK | 无显式启用 | 有 | AAC/SBC/LDAC/LHDC/LC3 | 有 | 有 | AB（面板默认） | 深度 ANC；ANC 循环配置；提示音语言；ANC 3 档、通透 3 档 |
| Hyacinth | — | POP/HIFI/ROCK | 无显式启用 | 有 | AAC/SBC/LDAC | — | 有 | 无入口 | 提示音语言 |
| Jasmine | — | — | 无显式启用 | 有 | 无选择入口 | — | 有 | 面板；type=none | 提示音语言 |
| KY01 | — | POP/HIFI/ROCK | 无显式启用 | 有 | 无选择入口 | — | 有 | AB | 提示音语言 |
| Luminous Agumon | — | — | 无显式启用 | 有 | 无选择入口 | — | 有 | 无入口 | — |
| Luminous Gabumon | — | — | 无显式启用 | 有 | 无选择入口 | — | 有 | 无入口 | — |
| Luminous Patamon | — | — | 无显式启用 | 有 | 无选择入口 | — | 有 | 无入口 | — |
| Luminous Tailmon | — | — | 无显式启用 | 有 | 无选择入口 | — | 有 | 无入口 | — |
| Magician | — | — | 无显式启用 | — | AAC/SBC/LDAC/Aptx/AptxHD | — | 有 | AB | 提示音语言 |
| Mirror | — | POP/HIFI/ROCK | 无显式启用 | 有 | 无选择入口 | — | 有 | 无入口 | — |
| MOKA | — | HIFI | 有 | — | AAC/SBC/LDAC | — | — | AB；USB AB123A | — |
| Opal | ANC/正常/通透 | POP/HIFI/ROCK | 无显式启用 | 有 | 无选择入口 | — | 有 | 无入口 | 无 ANC/通透档位 UI |
| OpenFeel L | — | POP/HIFI | 有 | 有 | AAC/SBC/LDAC | — | 有 | AB | 增益；听力保护 |
| OpenFeel MK2 | — | eq-bass-loudness-boost | 有 | 有 | AAC/SBC/LDAC/LHDC/LC3 | — | 有 | AB | 增益；听力保护 |
| SPEAKFEEL [speakfeel2] | — | 低音增强 | 无显式启用 | 有 | AAC/SBC/LDAC/LHDC | — | 有 | AB + USB AB123A | 增益；听力保护；独立麦克风模式 |
| SpeakFeel [speakfree] | — | POP/HIFI/ROCK | 无显式启用 | — | AAC/SBC/LDAC/Aptx/AptxHD/Aptx Adaptive | — | 有 | 面板；type=none | 提示音语言 |
| SWIFT 2 | — | — | 无显式启用 | 有 | 无选择入口 | — | 有 | AB | — |
| Zircon | ANC/正常/通透/抗风噪 | POP/HIFI/ROCK | 无显式启用 | 有 | AAC/SBC/LDAC/LHDC/LC3 | 有 | 有 | AB | 深度 ANC；ANC 循环配置；提示音语言；录音入口（固件 ≥ 30）；ANC 3 档、通透 3 档 |


Luminous 系列当前 App 展示名分别为 Agumon、Gabumon、Patamon、Tailmon。SpeakFeel `[speakfree]` 与 SPEAKFEEL `[speakfeel2]` 是不同注册分支，不能只忽略大小写后按展示名合并。详细枚举、触控动作值、模块行号和地址见 v2_capabilities.md（`rose_reverse/analysis/v2_capabilities.md`）。录音、AI/星芒入口只证明 App 暴露服务入口，尚不能视为硬件内置功能或可离线实现的设备协议。

## 第三方客户端必须保留的型号差异

1. CERAMICS-X 有普通与 7034 两分支；CERAMICS-MK2 有普通、8912F、5736 三分支；CERAMICS-U 有普通与 HSC-2710 两分支。展示名相同，识别 ID、连接参数、OTA 与功能门槛仍可能不同。
2. OpenFeel/OpenFree：`0b0a0a0b` 提供 LDAC，`0b0a0a0c` 提供 LHDC，`0b0a0a0d` 两者均提供；三种均有 AAC/SBC、LC3、多设备模式，触控允许动作也需按识别分支处理。
3. ANC 强度、通透强度、自适应 ANC 是不同能力。BudsFeel MK2、Opal 有模式切换但没有强度入口；CERAMICS-U 两分支为 ANC 三档、通透两档。
4. HeadFree→HeadFeel、EARFREE-i5→EarFeel-i5、OpenFree→OpenFeel、SportFree→SportFeel、SpeakFree→SpeakFeel 是当前配置名称与内部旧名称的对应。应以识别串及配置分支选协议。
5. EXPLORER 重写了 F8 的普通模式命令和降噪状态处理；ZT 的 i3/i5/HeadFeel 使用不同 Sender 子类；电量及音频模式接收处理也有型号覆写。

## 非耳机与通用配置

AURAMIC 的 `earType=mic`，使用 RS Common V2 的麦克风分支，含收发器、增益、静音、降噪、LED、自动关机、BLE 常开、录音、低切、音色、时码等候选模块；具体模块须由设备状态确认。USB 主页仅 OTA，存在 AB123A/AB OTA 路由。它不计入上面的耳机数量。

`UNKNOWN`、`RS_COMMON`、`RS_COMMON_V2` 是未知/通用协议配置。通用 V2 存在动态能力模块构建；模块列表不能当作某款耳机全功能承诺，也不能凭它增加额外零售型号。

## 连接层现有结论与协议文档完成条件

四族已有 GATT 服务/读写特征、SPP 选择和型号覆写证据，见 transport_findings.md（`rose_reverse/analysis/transport_findings.md`）。控制族与 BLE/SPP/USB 传输分开建模；不能对所有 SPP 设备固定使用标准串口 UUID。

要达到可据文档实现客户端的完整度，后续必须按每款型号补齐：广播字段及偏移、连接/配对与订阅、帧结构与校验、命令和回包字段、初始化及能力发现、参数范围/枚举、状态联动/互斥、分包/队列/超时/重试、固件门槛、OTA 子协议，并附逐条十六进制样例及验证状态。当前阶段只完成型号与能力，连接参数已初步分析；这些命令级项目仍未完成。

## 可复核证据与结构化清单

- 型号注册与名称证据（`rose_reverse/analysis/model_registry.md`）；注册 JSON（`rose_reverse/analysis/model_registry.json`） / CSV（`rose_reverse/analysis/model_registry.csv`）。
- [统一型号与能力 JSON](supported-devices.json)。
- Manifest 身份（`rose_reverse/analysis/assets_inventory/manifest_identity.json`）。
- Dart 对象池（`rose_reverse/analysis/blutter/objs.txt`） 与 Pool 引用表（`rose_reverse/analysis/blutter/pp.txt`）。

REA Android 入口缺少 JADX MCP JAR，本轮未获得该工具的代码 Evidence；代码证据来自已安装 Blutter 对 Dart 3.11.4 AOT 的本地静态解析。文档中的行号为生成的汇编文本行号，地址为 `libapp.so` AOT 虚拟地址，不是蓝牙命令码。未执行 APK，未访问其云服务，原 APK 的 SHA-256 已再次核对。
