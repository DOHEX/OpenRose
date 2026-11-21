# FURINA 控制协议

协议字节基线取自 RoseLink 3.8.0（5310），控件和硬件分支已与 [3.8.6（5495）注册表](../supported-devices-3.8.6.json) 核对。`FurinaDeviceConfig` 均使用 [RS Common V2](../families/rs-common-v2.md) 的 Sender、Receiver 与 ReceiveHandler。以下取值来自静态分析，设备响应和设置效果未经实机验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| FURINA | `0b110a0c` | `furina` | FURINA | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:23`） |

零售名称为 `FURINA`。

## 传输

控制通道见[族默认传输](../families/rs-common-v2.md#默认传输)。

| 项 | 值 | 状态 | 出处 |
| --- | --- | --- | --- |
| BLE 服务 | 同族默认 | 同族 | 同族 |
| 写特征 | 同族默认 | 同族 | 同族 |
| 通知特征 | 同族默认 | 同族 | 同族 |
| SPP 是否允许 | 同族默认 | 同族 | 同族 |
| 控制 SPP UUID | 同族默认 | 同族 | 同族 |
| 广播中的经典地址 | 同族默认 | 同族 | 同族 |
| 写入间隔 | 同族默认 | 同族 | 同族 |

## 初始化

下列初始化记录基于 3.8.0。3.8.6 的首帧字段顺序见[逐型号查询表](../3.8.6-model-delta.md#rs-common-v2-的实际握手查询)。

Sender 起始序号为 0。各分支均只调用一次 `getInfo`，委托给 共享闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`）。FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:43`） 的构造均将 Sender 序号置零。

| 分支 | 分派表项 | 初始化目标 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| FURINA | 60099 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60100`） |

序号 0 的合成查询帧为 `ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。详见[初始化与查询](../families/rs-common-v2.md#初始化与查询)。

## 电量

| 分支 | Tag | 布局 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| FURINA | `0C` | 左右耳与仓 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:122`） |

## 设置命令

BODY 使用十六进制。查询 `FA tt` 读取 Tag `tt`，各字段语义见[族设置命令](../families/rs-common-v2.md#设置命令)。固件门槛按三个固件分段的十进制拼接整数比较。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 见电量表 | — | `FA 0C → 0C` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:122`） |
| 降噪模式 | `setNoiseModeAnc/Normal/Trans/Wind` | `09 vv` | ANC=01，标准=02，通透=03，抗风噪=04 | — | `FA 09 → 09` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:140`） |
| 降噪档位 | `setAncLevel` | `2C vv` | 低=01，中=03，高=05 | — | `FA 2C → 2C` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:140`）、档位构造（`rose_reverse/analysis/v2_capabilities.md:56`） |
| 通透档位 | `setTransLevel` | `2D vv` | 低=01，中=03，高=05 | — | `FA 2D → 2D` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:140`）、低中高列表（`rose_reverse/analysis/blutter/pp.txt:8367`） |
| 深度 ANC | `setDeepAncOn` | `09 06` | 深度模式=06 | — | `FA 09 → 09` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:639`）、217 行（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_limit_noise_anc_panel_item.dart:217`） |
| 降噪轮换 | `setAncLine` | `36 a w n t` | ANC、抗风噪、标准、通透四个标志各为 00（跳过）或 01（参与） | — | `FA 36 → 36` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:690`） |
| EQ 预设 | `setEqHifi/Pop/Rock` | `2A vv` | HIFI=00，POP=01，ROCK=02 | — | `FA 2A → 2A` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:173`） |
| 游戏模式 | `setGameModeOn/Off` | `0E vv` | 关=00，开=01 | — | `FA 0E → 0E` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:231`） |
| 多点连接 | `setConnectMulOn/Off` | `32 vv` | 关=00，开=01 | — | `FA 32 → 32` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:244`） |
| 音频模式 | `setAudioAacSbc/Ldac/Lhdc/Lc3` | `2B vv` | AAC/SBC=00，LDAC=01，LHDC=02，LC3=03 | — | `FA 2B → 2B` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:298`） |
| 触控绑定 | `setLeftTouch*`、`setRightTouch*` | `01 ss aa` | 左耳单/双/三击/长按=01..04，右耳=11..14。无=00、播放暂停=01、上一首=02、下一首=03、音量加=04、音量减=05、游戏=06、语音助手=07、降噪轮换=08 | — | `FA 01 → 01` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:327`）、[触摸动作](../families/rs-common-v2.md#触摸动作) |
| 触控总开关 | `setTouchOn/Off` | `07 vv` | 开=00，关=01 | — | `FA 07 → 07` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:327`） |
| 佩戴检测 | `setEarDetectionOn/Off` | `08 vv` | 开=00，关=01 | — | `FA 08 → 08` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:519`） |
| 提示音音量 | `setDingLevel` | `2E vv` | 1..5=01..05 | — | `FA 2E → 2E` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:578`）、[静音选项判断](../families/rs-common-v2.md#门槛) |
| 提示音语言 | `setEarLanguageChinese/English` | `31 vv` | 中文=00，英语=01 | — | `FA 31 → 31` | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:336`）、语言控件分派（`rose_reverse/analysis/v2_control_panel_routes.md:11`） |
| 寻机 | `setFindDeviceLeft/Right/Both`、`setFindOff` | `2F vv` | 左=01，右=02，双耳=03，停止=04 | — | `2F` 事件（01..06） | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:318`） |
| 选项面板 | 结构 | — | 分组显示 | — | — | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:214`） |
| 更多设置入口 | 结构 | — | 页面导航 | — | — | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:349`） |
| 个性化入口 | 结构 | — | 连接弹窗外观 | — | — | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:936`）、1143 行（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:1143`） |
| OTA 入口 | 范围外 | — | 见 OTA 节 | — | — | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:418`） |
| Windows APO | 结构 | — | 3.8.6 进入 Windows 主机音效页面 | — | — | 静态 | Windows APO 页面（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:513`） |

## 接收字段

共用[族字段表](../families/rs-common-v2.md#字段表)与 LTV 解析。型号差异如下。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| `0C` | 电量 | 按电量表使用槽位。仓电量的新鲜度依赖入盒位与有效的耳机读数。 | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:122`） |
| `09` | 降噪模式 | 基础四模式，另含深度 06。 | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:140`） |
| `2A` | EQ | 单字节 00=HIFI、01=POP。双字节按协议族的预设索引解释。 | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:173`） |
| `2F` | 寻机事件 | 01/02/03 开始左/右/双耳，04/05/06 停止双耳/左/右；单个事件只能说明对应一侧或双方的变化。 | 静态 | FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:318`） |

## 未实现与未知

- 未知：尚无本型号广播、BLE/SPP 收发或设置持久化的实机记录；以上命令均取自静态资料。
- 寻机可解码事件。开始或停止的一侧事件无法独立确认双耳状态，OpenRose 只读事件。面板与页面入口不产生新的可写设置。
- 个性化入口在首页底部调用 `openPersonalization`，页面呈现连接弹窗主题、资源与耳机颜色。见14101 行（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/view/device_personalization_page.dart:14101`）。没有型号专用 V2 设置与读回。

## OTA

FURINA 的 OTA 面板使用默认 [AB](../ota/ab.md) 路由，登记的 SPP OTA UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。配置中未见 OTA 固件门槛。状态为静态，OpenRose 未实现升级。FURINA（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/furina_device_config.dart:418`）。
