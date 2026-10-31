# Jasmine 控制协议

分析对象为 RoseLink 3.8.0（versionCode 5310）的 `JasmineDeviceConfig`。APK SHA-256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。本文依据 Flutter AOT 静态反汇编，未连接该型号实机。商品“茉莉”与 Jasmine 的对应关系仅由名称推测，尚未获得直接证据。

## 识别与传输

- 注册名 `jasmine` / `Jasmine`，识别前缀 `0b0a0a11`，协议 `rs_common_v2`；[型号配置](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart#L10)、[注册表](/home/dohex/Projects/rose_reverse/analysis/model_registry.md)。前缀作用于扫描器规范化后的 manufacturer 数据，匹配依次尝试前 12、8、6 个十六进制字符；不是一段已经抓到的广播。
- App 配置 BLE 正常控制服务 `000000fe-0000-1000-8000-00805f9b34fb`、写特征 `000000f1-0000-1000-8000-00805f9b34fb`、通知特征 `000000f2-0000-1000-8000-00805f9b34fb`；配置允许 SPP，控制 UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`。连接和通道选择规则见 [i7 文档的共享 V2 传输说明](earfeel-i7-protocol.md#2-发现地址和传输)，具体设备行为仍待验证。
- 先订阅通知，再发送完整查询；App 请求 MTU 512，普通队列的写完成间隔为 100 ms（OHOS 分支 350 ms）。传输写入完成不代表设备已执行设置。

## 帧、查询和接收

共享 V2 Sender 的发送帧为 `FF SS LL BODY... CC AA`：`SS` 是递增序号，`LL` 为 BODY 长度，`CC` 为之前所有字节之和的低 8 位。每次发送须按当前序号重算校验。

静态分派分析将 Jasmine 初始化指向共享 `getInfo`：

```text
FF 00 1E FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 2F 31 32 33 36 37 38 39 3A 3B 3C 3D 3F 45 46 49 E9 AA
```

证据：[型号注册和 class 信息](/home/dohex/Projects/rose_reverse/analysis/model_registry.md)、[GDT 表](/home/dohex/Projects/rose_reverse/analysis/i7_dispatch/table.tsv)、[共享 getInfo](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart#L4597)。共享查询包含多个功能 ID，**不证明本型号支持全部功能**。该帧 35 字节，默认 ATT MTU 23 的有效载荷不足；MTU 请求不保证协商成功，不能假定设备支持分拆写入。

正常接收帧是 `DD HH [L T V...] CS AA`，`L` 包含类型及值而不包含自身；接收器允许两种累加和。分包、零长度兼容情况和字段解释见 [V2 接收说明](earfeel-i7-protocol.md#5-接收帧分包与字段)；共享解析不等于每种字段均由 Jasmine 上报。

## 客户端配置及常规命令

App 为 Jasmine 配置左右耳电量、触控、游戏模式、多点连接、提示音、寻机及提示音语言；未配置 ANC、EQ 或佩戴检测界面。这些是 App 配置事实，不证明硬件缺少相应功能。[逐型号能力证据](/home/dohex/Projects/rose_reverse/analysis/v2_capabilities.md)。

以下为共享 Sender 的命令体，完整帧需按上述规则构造；功能实际生效和回包均未实机验证。

| 控制 | BODY | 说明 |
|---|---|---|
| 触控总开关 | `07 00` / `07 01` | 开 / 关 |
| 游戏模式 | `0E 01` / `0E 00` | 开 / 关 |
| 多点连接 | `32 01` / `32 00` | 开 / 关 |
| 寻机 | `2F 01` / `02` / `03` / `04` | 左 / 右 / 双耳 / 停止；这些是 `2F` 后的参数 |
| 提示音语言 | `31 00` / `31 01` | 中文 / 英文 |
| 触控映射 | `01 key action` | key 为左 `01`–`04`、右 `11`–`14`；配置允许的动作见能力清单 |

公共接收器的 `0C` 字段至少有左右耳及仓三个字节；显示配置仅列左右耳，不能推断盒电量一定会上报。`0D` 为三个版本字节，`0E` 和 `32` 分别处理游戏及多点状态，具体解析与边界见共享接收说明。

## OTA 与未验证事项

配置中 OTA 类型为 `none`，OTA 控制器对该类型直接返回，不进入管理器初始化：[型号配置](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/jasmine_device_config.dart#L23)、[OTA 控制器](/home/dohex/Projects/rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart#L3330)。这只描述该版本 App 的路径，不证明硬件无法升级。

尚未获取 Jasmine 的实机广播、请求与回包、写入结果及固件版本；商品中文名映射也未确认。不能将共享 Sender 中其他命令直接当成本型号支持项。
