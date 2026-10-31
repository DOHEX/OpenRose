# Swift 2 控制协议

分析对象为 RoseLink 3.8.0（versionCode 5310）的 `Swift2DeviceConfig`；APK SHA-256 `83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。本文基于 Flutter AOT 静态反汇编，未连接 Swift 2 耳机或抓取蓝牙流量。商品踏浪 Swift 2 为骨传导耳机；App 中 `head` 是程序分类，不能仅据此推断佩戴形态。

## 型号与传输

- App 展示名 `SWIFT 2`，内部名 `swift2`，规范化 manufacturer 数据识别前缀 `5999064c`，协议 `rs_common_v2`。[型号配置](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart#L10)、[注册证据](/home/dohex/Projects/rose_reverse/analysis/model_registry.md)。识别按最长前缀 12、8、6 个十六进制字符尝试，该前缀并非实机广播样本。
- BLE 正常控制服务 `000000fe-0000-1000-8000-00805f9b34fb`、写特征 `000000f1-0000-1000-8000-00805f9b34fb`、通知特征 `000000f2-0000-1000-8000-00805f9b34fb`。配置允许 SPP，控制 UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`，独立 OTA SPP UUID 为 `0cf12d32-fac3-4553-bd80-d6832e7b3931`。[配置字段](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart#L524)。
- 普通控制的具体平台路由、订阅、MTU 请求及写队列见 [共享 V2 传输说明](earfeel-i7-protocol.md#2-发现地址和传输)；控制队列时序不应与 OTA 队列混用。

## 帧与初始化

共享 V2 Sender 将 BODY 封装为 `FF SS LL BODY... CC AA`：`SS` 为递增序号，`LL` 是 BODY 字节数，`CC` 是此前所有字节之和的低 8 位。共享 `getInfo` 在序号 0 时为：

```text
FF 00 1E FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 2F 31 32 33 36 37 38 39 3A 3B 3C 3D 3F 45 46 49 E9 AA
```

初始化 selector `+0xd8ba` 对该配置 cid 505 的表项 `60083` 指向只调用 `getInfo` 的共享闭包。[分派表](/home/dohex/Projects/rose_reverse/analysis/i7_dispatch/table.tsv#L60084)、[目标闭包](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common/sportfree_device_config.dart#L573)。查询包含通用功能 ID，不能据此推断 Swift 2 支持全部项目。35 字节帧超出默认 ATT MTU 23 的单次有效载荷，App 请求 MTU 512 不保证协商成功，不能假定设备支持任意拆包。[发送器](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart#L4597)。

接收帧按 `DD HH [L T V...] CS AA` 处理；长度、双校验形式、分包、零长度特例和字段解析见 [共享 V2 接收说明](earfeel-i7-protocol.md#5-接收帧分包与字段)。不要把共享解析器支持的全部字段等同于型号实际支持的功能。

## App 能力配置与命令

App 配置单体电量、触控、游戏模式、多点、提示音音量、寻机和 AB OTA；未配置 ANC、EQ、音频模式及提示音语言界面。`charge` 是复用的单体电量显示槽，不能解释为充电盒。[逐型号能力证据](/home/dohex/Projects/rose_reverse/analysis/v2_capabilities.md)。界面未配置不能推断硬件不支持该功能。

以下是共享 Sender 的命令体，完整帧须带当前序号与校验。设置结果均未实机验证。

| 功能 | BODY | 说明 |
|---|---|---|
| 触控开 / 关 | `07 00` / `07 01` | 00 为开 |
| 按键映射 | `01 slot action` | 共享编码的左槽 `01`–`04`、右槽 `11`–`14`；实际单体设备的物理键映射待验证 |
| 游戏开 / 关 | `0E 01` / `0E 00` | 01 为开 |
| 多点开 / 关 | `32 01` / `32 00` | 01 为开 |
| 提示音音量 | `2E value` | 共享 Sender 档位；0 是否可选取决于固件及 UI |
| 寻机 | `2F 01` / `2F 02` / `2F 03` / `2F 04` | 共享左 / 右 / 双耳 / 停止编码；单体设备实际行为未知 |

共享接收器 `0C` 解析三个电量字节，单体界面复用 `charge` 槽；仓槽 bit7 的语义未证实。固件版本 `0D` 的三个字节在 App 按十进制文本无分隔符拼接，不能误当标准语义版本号。

## OTA 与未验证项

配置选择 `ab` OTA；BLE OTA 采用基础配置的 `ff12` 服务、`ff15` 写入和 `ff14` 通知，SPP 使用上述独立 UUID。[型号 OTA 配置](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/swift2_device_config.dart#L544)、[AB OTA 实现](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/ab.dart)。实际 OTA 会话、固件兼容性、分块确认与中断恢复尚未实机验证。研究期间的云端只读元数据 / 文件头探测不等于设备升级验证，也未下载完整固件。
