# OpenFeel MK2 控制协议

研究对象为 RoseLink 3.8.0（versionCode 5310）APK 中的 `OpenfeelMk2DeviceConfig`，APK SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。协议内容来自 Flutter AOT 静态反汇编；未连接 OpenFeel MK2 实机，未取得该型号蓝牙抓包。REA 的 APK 静态检查见 证据文件（`rose_reverse/analysis/rea-apk-inspection.json`），代码分析使用 Blutter。

## 识别、连接与初始化

- 规范化 manufacturer 数据的匹配前缀为 `0b0eb657`，协议为 `rs_common_v2`，App 展示名 `OpenFeel MK2`。单 manufacturer 项时，可推导公司标识 `0x0E0B`、payload 前两字节 `B6 57`；这不是实测广播。型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/openfeel_mk2_device_config.dart:10`）、注册表（`rose_reverse/analysis/model_registry.md`）。
- 普通 BLE 服务 `000000fe-0000-1000-8000-00805f9b34fb`，写特征 `000000f1-0000-1000-8000-00805f9b34fb`，通知特征 `000000f2-0000-1000-8000-00805f9b34fb`。配置允许 SPP，控制 UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`；具体平台选择条件、MTU、队列和断连规则见 [共享 V2 传输说明](earfeel-i7-protocol.md#2-发现地址和传输)。
- 初始化调用的 GDT selector `+0xd8ba` 对该配置 cid 508 的表项 `60086` 指向 `0x8e2444`；该共享闭包只调用 `getInfo`。查询帧包含 29 项通用功能 ID；出现某 ID 不能证明型号支持该功能。分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60087`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`）。

```text
FF 00 1E FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 2F 31 32 33 36 37 38 39 3A 3B 3C 3D 3F 45 46 49 E9 AA
```

该帧长 35 字节，默认 ATT MTU 23 的有效载荷不足；请求 MTU 512 不等于协商成功，不应假定硬件支持分拆写入。`FA 3E` 是共享的自定义 EQ 独立读取方法，不在 `getInfo` 中。其他查询、校验、接收和分包规则见 [共享 V2 帧说明](earfeel-i7-protocol.md#3-正常发送帧)及 [接收说明](earfeel-i7-protocol.md#5-接收帧分包与字段)。

## 型号能力与指令

型号配置列出左右耳与仓电量、触控、低音增强、自定义 EQ、游戏模式、多点、增益、听力保护、音频模式和寻机；无 ANC 面板。触控动作列表为 `00`–`07`，不含 `08` ANC 切换；未配置的界面不代表硬件没有相应能力。配置与能力证据（`rose_reverse/analysis/v2_capabilities.md`）。

以下是共享 Sender 可构造的 BODY；完整帧为 `FF SS LL BODY... CC AA`，校验为此前各字节求和的低 8 位，序号随发送变化。所有功能生效、状态回包及版本条件均待实机核实。

| 功能 | BODY | 备注 |
|---|---|---|
| 触控开 / 关 | `07 00` / `07 01` | 开关编码与游戏模式相反 |
| 触控映射 | `01 slot action` | 左槽 `01`–`04`，右槽 `11`–`14`；动作 `00`–`07` |
| 游戏开 / 关 | `0E 01` / `0E 00` | — |
| 多点开 / 关 | `32 01` / `32 00` | 编码独立于音频模式 |
| 音频模式 | `2B 00`–`2B 03` | AAC/SBC、LDAC、LHDC、LC3；实际音频协商由系统决定 |
| 低音增强 | `2A 05 value` | Sender 接受 0–4；构造证据（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4044`） |
| 自定义 EQ | `2A 04`，然后 `3E` 加 10 字节 | 负值使用符号位加绝对值；详见 [i7 EQ 编码](earfeel-i7-protocol.md#4-设置命令与参数)，适用范围仍须实测 |
| 增益 | `45 value` | 线值 0–2 |
| 听力保护 | `46 value` | 0–6 为 App 标签选择，不是声压实测 |
| 寻机 | `2F 01` / `2F 02` / `2F 03` / `2F 04` | 左 / 右 / 双耳 / 停止 |

接收 `0C` 可解析左右耳与仓的电量字节，`0D` 为固件版本，`3E` 为自定义曲线，`45`/`46` 为增益及听力保护。bit7、无效值和缓存行为以 [共享字段规则](earfeel-i7-protocol.md#53-字段表) 为准；不能仅因解析器支持就断言该固件一定上报。

## OTA

型号配置的 OTA 路由为 `ab`。静态代码中 BLE OTA 配置使用服务 `0000ff12-0000-1000-8000-00805f9b34fb`、写特征 `0000ff15-0000-1000-8000-00805f9b34fb`、通知特征 `0000ff14-0000-1000-8000-00805f9b34fb`；其会话与普通控制分开。基础配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart`）、AB 实现（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart`）。

研究中曾执行云端元数据和固件资源头的只读查询，但未连接耳机、未下载完整固件、未验证固件完整性或升级流程。AB OTA 的握手、分块、失败恢复与型号固件兼容性仍需进一步交叉核对，不能据此直接刷机。
