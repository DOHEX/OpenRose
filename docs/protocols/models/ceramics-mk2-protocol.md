# CERAMICS-MK2 控制协议

协议字节基线取自 RoseLink 3.8.0（5310），控件和硬件分支已与 [3.8.6（5495）注册表](../supported-devices-3.8.6.json) 核对。`CeramicsMk2DeviceConfig`、`CeramicsMk28912fDeviceConfig`、`CeramicsMk25736DeviceConfig` 均使用 [RS Common V2](../families/rs-common-v2.md) 的 Sender、Receiver 与 ReceiveHandler。以下取值来自静态分析，设备响应和设置效果未经实机验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| 标准 | `0b0e0a0c` | `ceramics-mk2` | CERAMICS-MK2 | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:23`） |
| 8912F | `0b0e208b` | `ceramics-mk2-8912f` | CERAMICS-MK2 | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:23`） |
| 5736 | `45d7b286` | `ceramics-mk2-5736` | CERAMICS-MK2 | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:23`） |

系统中共同的零售名称为 `CERAMICS-MK2`，按广播前缀区分硬件分支。

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

Sender 起始序号为 0。各分支均只调用一次 `getInfo`，委托给 共享闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`）。标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:48`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:48`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:48`） 的构造均将 Sender 序号置零。

| 分支 | 分派表项 | 初始化目标 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| 标准 | 60105 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60106`） |
| 8912F | 60106 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60107`） |
| 5736 | 60107 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60108`） |

序号 0 的合成查询帧为 `ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。详见[初始化与查询](../families/rs-common-v2.md#初始化与查询)。

## 电量

| 分支 | Tag | 布局 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| 标准 | `0C` | 左右耳与仓 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:163`） |
| 8912F | `0C` | 左右耳与仓 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:163`） |
| 5736 | `0C` | 仅左右耳 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:158`） |

## 设置命令

BODY 使用十六进制。查询 `FA tt` 读取 Tag `tt`，各字段语义见[族设置命令](../families/rs-common-v2.md#设置命令)。固件门槛按三个固件分段的十进制拼接整数比较。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 见电量表 | — | `FA 0C → 0C` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:163`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:163`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:158`） |
| 降噪模式 | `setNoiseModeAnc/Normal/Trans/Wind` | `09 vv` | ANC=01，标准=02，通透=03，抗风噪=04 | — | `FA 09 → 09` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:220`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:220`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:215`） |
| 降噪档位 | `setAncLevel` | `2C vv` | 低=01，中=03，高=05 | — | `FA 2C → 2C` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:220`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:220`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:215`）、档位构造（`rose_reverse/analysis/v2_capabilities.md:56`） |
| 通透档位 | `setTransLevel` | `2D vv` | 低=01，中=03，高=05 | — | `FA 2D → 2D` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:220`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:220`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:215`）、低中高列表（`rose_reverse/analysis/blutter/pp.txt:8367`） |
| 降噪轮换 | `setAncLine` | `36 a w n t` | ANC、抗风噪、标准、通透四个标志各为 00（跳过）或 01（参与） | — | `FA 36 → 36` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:655`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:649`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:581`） |
| EQ 预设 | `setEqHifi/Pop/Rock` | `2A vv` | HIFI=00，POP=01，ROCK=02 | — | `FA 2A → 2A` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:251`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:251`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:246`） |
| 自定义曲线 | `setEqCustom`、`setEqCustomData` | `2A 04；3E g0…g9` | 10 段 20/100/300/500/1k/2k/3k/5k/8k/15k Hz，−6..6，步长 1，负数使用最高位符号 | 标准 ≥154，8912F、5736 无（3.8.6） | `FA 2A → 2A`、`FA 3E → 3E` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:251`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:251`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:246`）、2507 行（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2507`） |
| 游戏模式 | `setGameModeOn/Off` | `0E vv` | 关=00，开=01 | — | `FA 0E → 0E` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:302`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:300`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:297`） |
| 多点连接 | `setConnectMulOn/Off` | `32 vv` | 关=00，开=01 | — | `FA 32 → 32` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:315`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:313`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:310`） |
| 音频模式（标准、8912F） | `setAudioAacSbc/Ldac` | `2B vv` | AAC/SBC=00，LDAC=01 | — | `FA 2B → 2B` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:354`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:352`） |
| 触控绑定 | `setLeftTouch*`、`setRightTouch*` | `01 ss aa` | 左耳单/双/三击/长按=01..04，右耳=11..14。无=00、播放暂停=01、上一首=02、下一首=03、音量加=04、音量减=05、游戏=06、语音助手=07、降噪轮换=08 | — | `FA 01 → 01` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:383`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:381`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:327`）、[触摸动作](../families/rs-common-v2.md#触摸动作) |
| 触控总开关 | `setTouchOn/Off` | `07 vv` | 开=00，关=01 | — | `FA 07 → 07` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:383`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:381`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:327`） |
| 提示音音量 | `setDingLevel` | `2E vv` | 1..5=01..05，关闭=00 | 仅关闭档：标准 ≥153，8912F 和 5736 无 | `FA 2E → 2E` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:598`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:596`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:528`）、[静音选项判断](../families/rs-common-v2.md#门槛) |
| 提示音语言（标准、8912F） | `setEarLanguageChinese/English` | `31 vv` | 中文=00，英语=01 | — | `FA 31 → 31` | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:392`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:390`）、语言控件分派（`rose_reverse/analysis/v2_control_panel_routes.md:11`） |
| 寻机 | `setFindDeviceLeft/Right/Both`、`setFindOff` | `2F vv` | 左=01，右=02，双耳=03，停止=04 | — | `2F` 事件（01..06） | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:374`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:372`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:318`） |
| 选项面板 | 结构 | — | 分组显示 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:293`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:291`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:288`） |
| 更多设置入口 | 结构 | — | 页面导航 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:405`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:403`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:336`） |
| 星芒业务入口 | 范围外 | — | 云端服务 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:466`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:464`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:391`） |
| OTA 入口 | 范围外 | — | 见 OTA 节 | — | — | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:507`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:505`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:432`） |
| Windows APO | 结构 | — | 3.8.6 进入 Windows 主机音效页面 | — | — | 静态 | Windows APO 页面（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:513`） |

5736 在 3.8.0 使用固件门槛 154。3.8.6 的 EQ 构造只传 `hasCustom=true`，标准分支仍传 `customSinceVersion=154`。5736 构造（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:299`）、标准分支构造（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:449`）。

## 接收字段

共用[族字段表](../families/rs-common-v2.md#字段表)与 LTV 解析。型号差异如下。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| `0C` | 电量 | 按电量表使用槽位。仓电量的新鲜度依赖入盒位与有效的耳机读数。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:163`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:163`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:158`） |
| `09` | 降噪模式 | 基础四模式。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:220`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:220`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:215`） |
| `2A` | EQ | 单字节 00=HIFI、01=POP。双字节按协议族的预设索引解释。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:251`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:251`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:246`） |
| `2F` | 寻机事件 | 01/02/03 开始左/右/双耳，04/05/06 停止双耳/左/右；单个事件只能说明对应一侧或双方的变化。 | 静态 | 标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:374`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:372`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:318`） |

## 未实现与未知

- 未知：尚无本型号广播、BLE/SPP 收发或设置持久化的实机记录；以上命令均取自静态资料。
- 寻机可解码事件。开始或停止的一侧事件无法独立确认双耳状态，OpenRose 只读事件。面板与页面入口不产生新的可写设置。
- 5736 的配置无音频模式和提示音语言控件，电量只呈现左右耳。
- 星芒是在线业务入口，未实现。自定义曲线的频率是官方控件标签，实际滤波响应未知。

## OTA

标准、8912F 使用 [AB](../ota/ab.md)，其 SPP OTA UUID 与控制 UUID 同值。5736 的 OTA 类型为 `none`，无可用升级入口。配置中未见 OTA 固件门槛。状态为静态，OpenRose 未实现升级。标准（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_device_config.dart:507`）、8912F（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_8912f_device_config.dart:505`）、5736（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/ceramics_mk2_5736_device_config.dart:432`）。
