# CERAMICS-U 控制协议

协议字节基线取自 RoseLink 3.8.0（5310），控件和硬件分支已与 [3.8.6（5495）注册表](../supported-devices-3.8.6.json) 核对。`CeramicsUDeviceConfig`、`CeramicsUHsc2710DeviceConfig` 均使用 [RS Common V2](../families/rs-common-v2.md) 的 Sender、Receiver 与 ReceiveHandler。以下取值来自静态分析，设备响应和设置效果未经实机验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| 标准 | `0b0d0a0b` | `CERAMICS-U` | CERAMICS-U | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:23`） |
| HSC-2710 | `0b137957` | `CERAMICS-U-HSC-2710` | CERAMICS-U | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:23`） |

系统中共同的零售名称为 `CERAMICS-U`，按广播前缀区分硬件分支。

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

Sender 起始序号为 0。各分支均只调用一次 `getInfo`，委托给 共享闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`）。标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:48`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:48`） 的构造均将 Sender 序号置零。

| 分支 | 分派表项 | 初始化目标 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| 标准 | 60104 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60105`） |
| HSC-2710 | 60103 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60104`） |

序号 0 的合成查询帧为 `ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。详见[初始化与查询](../families/rs-common-v2.md#初始化与查询)。

## 电量

| 分支 | Tag | 布局 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| 标准 | `0C` | 左右耳与仓 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:127`） |
| HSC-2710 | `0C` | 左右耳与仓 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:127`） |

## 设置命令

BODY 使用十六进制。查询 `FA tt` 读取 Tag `tt`，各字段语义见[族设置命令](../families/rs-common-v2.md#设置命令)。固件门槛按三个固件分段的十进制拼接整数比较。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 见电量表 | — | `FA 0C → 0C` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:127`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:127`） |
| 降噪模式 | `setNoiseModeAnc/Normal/Trans/Wind` | `09 vv` | ANC=01，标准=02，通透=03，抗风噪=04 | — | `FA 09 → 09` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:145`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:145`） |
| 降噪档位 | `setAncLevel` | `2C vv` | 低=01，中=03，高=05 | — | `FA 2C → 2C` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:145`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:145`）、档位构造（`rose_reverse/analysis/v2_capabilities.md:56`） |
| 通透档位 | `setTransLevel` | `2D vv` | 低=01，高=05 | — | `FA 2D → 2D` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:145`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:145`）、低高列表（`rose_reverse/analysis/blutter/pp.txt:8851`） |
| 深度 ANC | `setDeepAncOn` | `09 06` | 深度模式=06 | — | `FA 09 → 09` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:614`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:569`）、217 行（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_limit_noise_anc_panel_item.dart:217`） |
| 自动 ANC（标准 U） | `setAncAutoOn` | `09 05` | 自动模式=05 | — | `FA 09 → 09` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:577`）、U 分支（`rose_reverse/analysis/ceramics_v2_protocol.md:475`） |
| 降噪轮换 | `setAncLine` | `36 a w n t` | ANC、抗风噪、标准、通透四个标志各为 00（跳过）或 01（参与） | — | `FA 36 → 36` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:651`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:606`） |
| EQ 预设 | `setEqHifi/Pop/Rock/Light` | `2A vv` | HIFI=00，POP=01，ROCK=02，经典调音=03 | — | `FA 2A → 2A` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:181`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:181`）、2533 行（`rose_reverse/analysis/blutter/asm/roselink/localization/language/zh_cn.dart:2533`） |
| 自定义曲线 | `setEqCustom`、`setEqCustomData` | `2A 04；3E g0…g9` | 10 段 20/100/300/500/1k/2k/3k/5k/8k/15k Hz，−6..6，步长 1，负数使用最高位符号 | 标准 ≥33，HSC-2710 无 | `FA 2A → 2A`、`FA 3E → 3E` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:181`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:181`）、2507 行（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2507`） |
| 增益 | `setGainLevel` | `45 vv` | 低=00，中=01，高=02 | 标准 ≥33，HSC-2710 无 | `FA 45 → 45` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:232`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:230`）、增益固件比较（`rose_reverse/analysis/v2_control_panel_routes.md:12`） |
| 游戏模式 | `setGameModeOn/Off` | `0E vv` | 关=00，开=01 | — | `FA 0E → 0E` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:245`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:239`） |
| 多点连接 | `setConnectMulOn/Off` | `32 vv` | 关=00，开=01 | — | `FA 32 → 32` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:258`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:252`） |
| 音频模式 | `setAudioAacSbc/Ldac/Lhdc` | `2B vv` | AAC/SBC=00，LDAC=01，LHDC=02 | — | `FA 2B → 2B` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:266`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:260`） |
| 触控绑定 | `setLeftTouch*`、`setRightTouch*` | `01 ss aa` | 左耳单/双/三击/长按=01..04，右耳=11..14。无=00、播放暂停=01、上一首=02、下一首=03、音量加=04、音量减=05、游戏=06、语音助手=07、降噪轮换=08 | — | `FA 01 → 01` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:297`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:291`）、[触摸动作](../families/rs-common-v2.md#触摸动作) |
| 触控总开关 | `setTouchOn/Off` | `07 vv` | 开=00，关=01 | — | `FA 07 → 07` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:297`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:291`） |
| 提示音音量 | `setDingLevel` | `2E vv` | 1..5=01..05，关闭=00 | 仅关闭档：标准 ≥33，HSC-2710 无 | `FA 2E → 2E` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:520`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:516`）、[静音选项判断](../families/rs-common-v2.md#门槛) |
| 提示音语言（3.8.6 标准分支） | `setEarLanguageChinese/English` | `31 vv` | 中文=00，英语=01 | — | `FA 31 → 31` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:306`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:300`）、语言控件分派（`rose_reverse/analysis/v2_control_panel_routes.md:11`） |
| 寻机 | `setFindDeviceLeft/Right/Both`、`setFindOff` | `2F vv` | 左=01，右=02，双耳=03，停止=04 | — | `2F` 事件（01..06） | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:288`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:282`） |
| 选项面板 | 结构 | — | 分组显示 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:223`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:221`） |
| 更多设置入口 | 结构 | — | 页面导航 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:319`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:313`） |
| 星芒业务入口 | 范围外 | — | 云端服务 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:380`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:374`） |
| OTA 入口 | 范围外 | — | 见 OTA 节 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:421`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:415`） |
| Windows APO | 结构 | — | 3.8.6 进入 Windows 主机音效页面 | — | — | 静态 | Windows APO 页面（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:513`） |

3.8.0 两支均配置提示音语言。3.8.6 的 HSC-2710 已移除该入口，首页（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:350`）与更多控制列表（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:168`）均无语言控件；普通 U 保留。

## 接收字段

共用[族字段表](../families/rs-common-v2.md#字段表)与 LTV 解析。型号差异如下。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| `0C` | 电量 | 按电量表使用槽位。仓电量的新鲜度依赖入盒位与有效的耳机读数。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:127`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:127`） |
| `09` | 降噪模式 | 基础四模式，另含深度 06；标准 U 另含自动 05。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:145`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:145`） |
| `2A` | EQ | 单字节 00=HIFI、01=POP。双字节按协议族的预设索引解释。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:181`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:181`） |
| `2F` | 寻机事件 | 01/02/03 开始左/右/双耳，04/05/06 停止双耳/左/右；单个事件只能说明对应一侧或双方的变化。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:288`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:282`） |

## 未实现与未知

- 未知：尚无本型号广播、BLE/SPP 收发或设置持久化的实机记录；以上命令均取自静态资料。
- 寻机可解码事件。开始或停止的一侧事件无法独立确认双耳状态，OpenRose 只读事件。面板与页面入口不产生新的可写设置。
- 星芒是在线业务入口，未实现。自定义曲线的频率是官方控件标签，实际滤波响应未知。

## OTA

标准分支走 [WQ](../ota/wq.md)，其 SPP OTA UUID 为 `00007034-0000-1000-8000-00805f9b34fb`。HSC-2710 走 [BES](../ota/bes.md)，其 SPP OTA UUID 为 `66666666-6666-6666-6666-666666666666`。配置中未见 OTA 固件门槛。状态为静态，OpenRose 未实现升级。标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_device_config.dart:421`）、HSC-2710（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_u_hsc_2710_device_config.dart:415`）。
