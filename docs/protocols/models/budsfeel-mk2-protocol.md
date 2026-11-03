# BudsFeel MK2 控制协议

研究对象为 RoseLink 3.8.0（versionCode 5310）中的 `BudsfeelMk2DeviceConfig`；APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。本文根据 Flutter AOT 静态反汇编整理，未连接 BudsFeel MK2 实机、未采集其蓝牙数据。

## 识别与通道

- App 展示名 `BudsFeel MK2`，内部名 `budsfeel_mk2`，规范化 manufacturer 数据识别前缀 `0b150a0b`，协议 `rs_common_v2`。型号配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:10`）、注册证据（`rose_reverse/analysis/model_registry.md`）。前缀用于静态匹配，不是实机广播样本。
- 普通 BLE 控制使用服务 `000000fe-0000-1000-8000-00805f9b34fb`、写特征 `000000f1-0000-1000-8000-00805f9b34fb`、通知特征 `000000f2-0000-1000-8000-00805f9b34fb`。控制 SPP UUID 为 `0cf12d31-fac3-4553-bd80-d6832e7b3931`，配置允许 SPP。基础控制服务配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、型号字段（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/budsfeel_mk2_device_config.dart:198`）。
- 型号另配 OTA 服务 `0000AE00-0000-1000-8000-00805F9B34FB`、写 `0000AE01-0000-1000-8000-00805F9B34FB`、通知 `0000AE02-0000-1000-8000-00805F9B34FB`，及 OTA SPP UUID `00001101-0000-1000-8000-00805F9B34FB`。这些 OTA 字段不能替代普通控制通道。
- 通道选择、通知订阅、MTU 请求和普通队列见 [共享 V2 传输说明](earfeel-i7-protocol.md#2-发现地址和传输)。写入完成不等于设备确认设置。

## 收发帧与查询

共享 V2 Sender 的发送帧为 `FF SS LL BODY... CC AA`：序号 `SS` 自增，`LL` 为 BODY 字节数，`CC` 为此前字节之和的低 8 位。共享 `getInfo` 在序号 0 时：

```text
FF 00 1E FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 2F 31 32 33 36 37 38 39 3A 3B 3C 3D 3F 45 46 49 E9 AA
```

初始化 selector `+0xd8ba` 对该配置 cid 530 的表项 `60108` 指向只调用 `getInfo` 的共享闭包。分派表（`rose_reverse/analysis/i7_dispatch/table.tsv:60109`）、目标闭包（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart:573`）。查询含 29 项通用功能 ID，不能据此增加本型号能力。35 字节帧超过默认 ATT MTU 23 的有效载荷；MTU 512 仅为 App 的请求值，不保证实际协商成功，也没有证据证明任意拆包可行。发送器证据（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4597`）。

接收帧为 `DD HH [L T V...] CS AA`，不是发送帧格式。接收校验、重组、零长度兼容及各 Tag 见 [共享 V2 接收规则](earfeel-i7-protocol.md#5-接收帧分包与字段)。

## 型号能力与命令

App 配置左右耳和仓电量、四种降噪模式、低音增强、游戏模式、多点、触控、寻机及 AAC/SBC、LDAC、LC3 音频模式；未启用自定义 EQ 或 ANC/通透强度档位界面。型号能力证据（`rose_reverse/analysis/v2_capabilities.md`）。配置入口不是实机验证结果。

下表为共享 Sender 中与已配置功能对应的 BODY；完整帧须用当前序号和校验构造。实际固件是否接受及其回应仍待验证。

| 功能 | BODY | 说明 |
|---|---|---|
| 降噪模式 | `09 01` / `02` / `03` / `04` | `09` 后参数依次为 ANC / 普通 / 通透 / 抗风 |
| 降噪轮换 | `36 anc wind normal trans` | 顺序为 ANC、抗风、普通、通透；每项 0 或 1 |
| 低音增强 | `2A 05 value` | 共享 Sender 接受值 0–4；实际 UI 配置及生效仍须核实 |
| 触控开 / 关 | `07 00` / `07 01` | 00 为开 |
| 触控映射 | `01 slot action` | 左 `01`–`04`、右 `11`–`14`；可选动作以型号配置为准 |
| 游戏开 / 关 | `0E 01` / `0E 00` | 01 为开 |
| 多点开 / 关 | `32 01` / `32 00` | 01 为开 |
| 音频模式 | `2B 00` / `2B 01` / `2B 03` | AAC/SBC / LDAC / LC3；系统负责音频协商 |
| 寻机 | `2F 01` / `2F 02` / `2F 03` / `2F 04` | 左 / 右 / 双耳 / 停止 |

共享接收字段 `0C` 可解析左右耳与仓电量，`09` 解析降噪模式，`0D` 解析固件版本；字段具体取值和缓存行为见共享接收说明。对该型号未抓到通知，不能断言全部字段必然上报。

## OTA

配置中的 OTA 路由为 `jl`，界面版本门槛为固件比较值 200；比较值不是 App 版本，也不应直接当作标准语义版本。型号能力表（`rose_reverse/analysis/v2_capabilities.md`）。APK 包含杰理 OTA SDK 相关代码及库；升级数据内层格式和设备响应尚未恢复，不能将 SDK 调用名当作已经验证的线协议。

研究期间进行的云端元数据查询不是耳机实机验证；完整固件、兼容性、写入、失败与恢复流程均未验证。
