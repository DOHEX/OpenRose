# Coral 控制协议

协议字节基线取自 RoseLink 3.8.0（5310），控件和硬件分支已与 [3.8.6（5495）注册表](../supported-devices-3.8.6.json) 核对。`CoralDeviceConfig` 均使用 [RS Common V2](../families/rs-common-v2.md) 的 Sender、Receiver 与 ReceiveHandler。以下取值来自静态分析，设备响应和设置效果未经实机验证。

## 识别

| 分支 | 识别前缀 | 内部名 | 显示名 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- |
| Coral | `0b0e0a0e` | `coral` | Coral | 静态 | 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:23`） |

零售名称为 `Coral`。

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

Sender 起始序号为 0。各分支均只调用一次 `getInfo`，委托给 共享闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`）。Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:48`） 的构造均将 Sender 序号置零。

| 分支 | 分派表项 | 初始化目标 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| Coral | 60102 | `0x8e2444` | 静态 | GDT（`rose_reverse/analysis/i7_dispatch/table.tsv:60103`） |

序号 0 的合成查询帧为 `ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa`。详见[初始化与查询](../families/rs-common-v2.md#初始化与查询)。

## 电量

| 分支 | Tag | 布局 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| Coral | `0C` | 左右耳与仓 | 静态 | 电量控件（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:138`） |

## 设置命令

BODY 使用十六进制。查询 `FA tt` 读取 Tag `tt`，各字段语义见[族设置命令](../families/rs-common-v2.md#设置命令)。固件门槛按三个固件分段的十进制拼接整数比较。

| 功能 | Sender 方法 | BODY | 取值 | 固件门槛 | 读回 | 状态 | 出处 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 电量展示 | 结构 | — | 见电量表 | — | `FA 0C → 0C` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:138`） |
| 降噪开关 | `setNoiseModeAnc/Normal` | `09 vv` | 开=01，关=02 | — | `FA 09 → 09` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:372`）、开关分支（`rose_reverse/analysis/v2_control_panel_routes.md:9`） |
| 低音增强 | `setEqBassPlus` | `2A 05 vv` | 1 档=00，2 档=01，3 档=02 | — | `FA 2A → 2A 05 vv` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:156`）、从档位数到线值（`rose_reverse/analysis/v2_control_panel_routes.md:13`） |
| 游戏模式 | `setGameModeOn/Off` | `0E vv` | 关=00，开=01 | — | `FA 0E → 0E` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:169`） |
| 多点连接 | `setConnectMulOn/Off` | `32 vv` | 关=00，开=01 | — | `FA 32 → 32` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:182`） |
| 音频模式 | `setAudioAacSbc/Ldac/Lhdc/Lc3` | `2B vv` | AAC/SBC=00，LDAC=01，LHDC=02，LC3=03 | — | `FA 2B → 2B` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:227`） |
| 触控绑定 | `setLeftTouch*`、`setRightTouch*` | `01 ss aa` | 左耳单/双/三击/长按=01..04，右耳=11..14。无=00、播放暂停=01、上一首=02、下一首=03、音量加=04、音量减=05、游戏=06、语音助手=07、降噪轮换=08 | — | `FA 01 → 01` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:249`）、[触摸动作](../families/rs-common-v2.md#触摸动作) |
| 触控总开关 | `setTouchOn/Off` | `07 vv` | 开=00，关=01 | — | `FA 07 → 07` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:249`） |
| 提示音音量 | `setDingLevel` | `2E vv` | 1..5=01..05 | — | `FA 2E → 2E` | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:413`）、[静音选项判断](../families/rs-common-v2.md#门槛) |
| 寻机 | `setFindDeviceLeft/Right/Both`、`setFindOff` | `2F vv` | 左=01，右=02，双耳=03，停止=04 | — | `2F` 事件（01..06） | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:307`） |
| 选项面板 | 结构 | — | 分组显示 | — | — | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:218`） |
| 更多设置入口 | 结构 | — | 页面导航 | — | — | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:258`） |
| OTA 入口 | 范围外 | — | 见 OTA 节 | — | — | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:316`） |
| Windows APO | 结构 | — | 3.8.6 进入 Windows 主机音效页面 | — | — | 静态 | Windows APO 页面（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/widget_model.dart:513`） |
| 星芒业务入口 | 范围外 | — | 3.8.6 在线业务入口 | — | — | 静态 | 3.8.6 星芒控件（`rose_reverse/analysis/3.8.6/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:291`） |

## 接收字段

共用[族字段表](../families/rs-common-v2.md#字段表)与 LTV 解析。型号差异如下。

| Tag | 字段 | 本型号取值或差异 | 状态 | 出处 |
| --- | --- | --- | --- | --- |
| `0C` | 电量 | 按电量表使用槽位。仓电量的新鲜度依赖入盒位与有效的耳机读数。 | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:138`） |
| `09` | 降噪模式 | 01=ANC、02=标准。 | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:372`） |
| `2A` | 低音增强 | 双字节 05 vv，第二字节为档位；UI 有 00..02。 | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:156`） |
| `2F` | 寻机事件 | 01/02/03 开始左/右/双耳，04/05/06 停止双耳/左/右；单个事件只能说明对应一侧或双方的变化。 | 静态 | Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:307`） |

## 未实现与未知

- 未知：尚无本型号广播、BLE/SPP 收发或设置持久化的实机记录；以上命令均取自静态资料。
- 寻机可解码事件。开始或停止的一侧事件无法独立确认双耳状态，OpenRose 只读事件。面板与页面入口不产生新的可写设置。

## OTA

使用 [AB](../ota/ab.md) 路由，登记的 SPP OTA UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`。配置中未见 OTA 固件门槛。状态为静态，OpenRose 未实现升级。Coral（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart:316`）。
