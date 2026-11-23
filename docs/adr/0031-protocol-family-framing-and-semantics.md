---
status: accepted
---

# 协议族帧格式与读写语义

OpenRose 按协议族实现四个编解码器：`F8Codec`、`RsCommonCodec`、`RsCommonV2Codec` 与 `ZtCodec`。各型号的字节表、命令与字段偏移记录在 [docs/protocols/](../protocols/README.md) 的协议族文档（[ZT](../protocols/families/zt.md)、[F8](../protocols/families/f8.md)、[RS Common V2](../protocols/families/rs-common-v2.md)、[RS Common](../protocols/families/rs-common.md)）与型号文档中。本 ADR 只记录代码依赖的协议族层面事实，以及各族共用的读写语义。除另行注明的 i5 实机读取外，这些事实都来自 RoseLink 的静态分析，没有经过实机确认。

## 决策

### 各族共用的读写语义

- **读数只推进本型号可读的字段**：多个型号共用的解码器可能返回某型号无法用于控制的诊断槽位。`ConnectionAttempt` 的 `forReadableFields` 只保留本型号能力中的可读字段；若一个都不剩，这条回复不计入初始化进度。
- **预设库切换**：有独立音乐与游戏预设的型号（`ModelSpec.gameEqualizerPresets`），以已确认的游戏模式读数选择预设库。切换预设库会使此前的均衡器读数失效，除非同一回复已带来新值（`DeviceReadings` 的 `changesPresetBank`，仅对 `refreshEqualizerAfterGameMode` 的协议生效）。
- **自定义曲线单独写入**：有曲线编辑器的型号，选择自定义模式是另一次写入，`writablePresets` 因此去掉 `custom`。ZT 与 RS Common V2 都把每个频段的有符号增益编码为一个符号-幅值字节（`customCurveBytes`），例如 −1 编码为 `0x81`。
- **降噪档位**：档位控件是否可见（`NoiseLevelCapabilities.isVisibleIn`）不是读写档位的协议前提。`NoiseControlCapabilities.minimumCycleSelections` 默认为 0：已恢复的型号界面与发送端都没有规定降噪轮换至少选几项。
- **触控绑定**：一次 `TouchAssignment` 只设置一个槽位，设备回复的仍是完整的 `TouchKeys`。`SettingConfirmation.confirmationValue` 只核对目标槽位，其余绑定保持设备上报的值。
- **校验先于序号**：`SettingQueue.set` 在提交前校验命令，校验不分配编解码器序号。RS Common V2 的 `prepareSetting` 先由 `rsV2Setting` 校验完整记录，再分配任何一个序号。
- **单体电量**：`BatteryLayout.SINGLE_UNIT` 表示只有一个单元，协议在充电盒槽位上报它的电量。

### F8

- 帧头 5 字节，没有应用层校验和（`f8Fields`、`F8Codec`）。
- 降噪与通透档位：RoseLink 的 F8 接收处理把 `11`／`12` 回复中缩减后的线上档位映射为界面档位，0／1／2 分别对应 5／3／1（`noiseLevelValue`）。类转储中出现的立即数 10／6／2 是这三个值的 Dart Smi 标记形式，并非档位 10／6／2。0／1／2 以外的线上值不解码。以上均未经实机确认。
- 只有型号配置开放了档位控件，才解码档位（`decodeNoiseLevel`）。CERAMICS-X 与 7034 共用 CERAMICS-X 的接收处理，但档位标志关闭，`0x11`／`0x12` 两个标签保持 Unknown。
- 均衡器读回值 2：扩展型号为 Rock，AGATE 上是第一个预设的别名，CERAMICS 上有歧义（`ModelProtocol.F8.eqAtTwo`）。
- 提示音音量对应 RoseLink 的 `setDingLevel`，帧为 `00 32 01 00 01 0N`，N 为一位十进制档位；读回使用 `13` 查询（`promptVolumeFrame`）。

### RS Common

- 旧版 RS Common 的序号字节固定，没有校验和（`RsCommonCodec`）。
- RoseLink 把触控动作码作为一位十进制数字拼进帧里，所以型号的动作码只能是 0–9（`ModelProtocol.RsCommon` 的 `init` 校验）。
- 按键解析：RoseLink 读取去掉空格的报文中字符下标 11／15／19／31／35／39 处的单个十六进制字符。换算成值的字节下标为 1／3／5／11／13／15，取各字节的低半字节（`RsCommonCodec.decodeTouch`）。设备的实际响应未测过。

### RS Common V2

- 发送帧以 `FF` 开头，接收帧以 `DD` 开头，载荷为 LTV 记录，发送序号按会话递增（`RsCommonV2Codec`）。
- `DD` 字节可能是未接收完的字段中的不透明数据。拆包时要等到当前帧的校验和吻合才确认帧结束（`findComplete`）。校验和取从 `DD` 起各字节之和的低 8 位；`parseFrame` 也接受只对记录部分求和的校验。
- 广播：RS Common V2 的厂商数据在类型字节 `01` 之后携带 BR/EDR 地址（`rsV2ClassicAddress`）。
- 初始化的 `getInfo`：`ModelProtocol.RsCommonV2.infoIds` 是该型号 FA 批量查询中按顺序排列的功能 ID；为 null 时使用原发送端的固定列表。RoseLink 3.8.6 按面板顺序构造 `getInfo`，3.8.0 使用发送端的默认列表。
- 十进制记录：部分记录把整个十六进制串当作十进制数字解读，值超过一个字节时也一样（`RsV2Readings` 的 `decimal`）。
- `49` 记录：只有 2 字节且每字节为 0 或 1 时才是独立麦克风状态；更长的 `49` 记录携带扩展固件版本（`microphone`）。
- 电池记录 `0C` 依次为左耳、右耳、充电盒。每字节的低 7 位为百分比，bit7 为入盒标志：`0x64` 为 100% 未入盒，`0xE4` 为 100% 入盒，`0x32` 为 50% 未入盒，`0xB2` 为 50% 入盒；`FF` 表示未提供（`RsV2Readings` 的 `battery`）。Android 集成测试 `InCasePromptTest` 的夹具按此构造记录，校验和对从 `dd` 起的每个字节求和；`OwnerRig` 使用的广播识别前缀取自协议识别表，不是抓到的广播。

### ZT

- 请求帧以 `08EE` 开头，命令键固定，回复按半字节编码（`ZtCodec`）。
- 有独立多点的 ZT 型号在预设槽位 3 放自定义曲线，其他型号放 light 预设（`ModelProtocol.Zt.eqPresets`）。

## 后果

新增型号时，协议族层面的帧格式与读写语义以本 ADR 和协议族文档为准，型号配置只声明不同的取值。修改某个编解码器时，若改变了上面任一事实，须同步更新本 ADR 与对应的协议族文档。
