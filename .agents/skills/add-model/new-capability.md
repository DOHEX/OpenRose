# 新能力类型与新协议族

[add-model](SKILL.md) 第 2 步归入 C、D 类的记录，按这里补全改动面。下面列出的 `when` 大多按枚举穷举，漏改会编译失败；标为“不穷举”的位置要自己逐一核对。

## C：新能力类型

先判断它是不是新领域概念：是，就用 `domain-modeling` 在 `GLOSSARY.md` 登记术语，代码、界面与文档统一使用该术语。

**core**

- `DeviceField`（`protocol/ProtocolCodec.kt`，带界面显示名）与 `DeviceSetting`（`ControlState.kt`）。
- 能力带可选值（档位、模式列表）时，在 `DeviceCapabilities` 增加字段，并在每个型号的 `capabilities` 中显式赋值。
- 协议族字段表（`ztFields` / `rsCommonV2Fields`）、编解码的 `prepareSetting`（写帧、读回查询、期望值）和解码分支。
- `SettingQueue.validateSetting`：按能力校验取值。型号不支持该能力返回 `UNSUPPORTED`，取值越界返回 `INVALID_ARGUMENT`。
- 需要新的取值形状时新增 `FieldValue` 子类型，并同步下面所有跨进程编码。

**跨进程**（androidIntegration）

- `wire/ControlWire.kt`：模块宿主与应用之间的控制状态，`encode(capabilities)` / `decodeCapabilities` 不穷举，新的能力字段要手动加入；`encode(FieldValue)` 穷举。
- `wire/BridgeSnapshotCodec.kt`：系统集成快照，`capabilities` 映射不穷举，`toBridgeValue` 穷举。只有系统界面需要该字段时才改 `NativeHeadsetSnapshot` 的解析。

**界面**（sharedUI）

- 控制卡片以 `writableSettings` 中是否含该设置、能力对象是否存在作为显示条件，保持与现有卡片一致。
- `NoiseControlCard.kt` 中的 `DeviceSetting.displayName()`、`requestedValueText`，以及 `DeviceValueFormatting.kt` 的 `displayFieldValue` / `semanticLabel`（新的文本取值要有中文标签）。
- 界面测试覆盖 `InterfaceStyle.entries` 全部风格。

## D：新协议族

- `ProtocolFamily`（`protocol/ModelProfile.kt`）：新增枚举项与 `displayName`，补全 `readableFields`、`promptVolumeLevels` 的穷举分支。
- 新编解码实现 `ProtocolCodec`，并在 `createProtocolCodec` 中按族分发。编解码实例随每次连接尝试重建：会话内序号、接收缓冲放在实例内，`reset()` 清空。
- 字段表与初始化查询放在该族编解码内，表示“族认识的字段”；型号实际提供什么只由 `ModelSpec.capabilities` 决定。
- `ProtocolReply.accepted` 决定一条通知能否推进初始化：只有带已知语义值的字段才算。
- 帧格式、校验和、分包与写入容量（`initQueries(maxWriteSize)`）都要有协议文档中的字面量测试；没有字节级文档的族不实现。
