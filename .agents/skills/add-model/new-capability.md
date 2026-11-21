# 新能力类型与新协议族

[add-model](SKILL.md) 第 2 步归入 C、D 类的记录，按这里补全改动面。下面列出的 `when` 大多按枚举穷举，漏改会编译失败；标为“不穷举”的位置要自己逐一核对。

## C：新能力类型

先判断它是不是新领域概念：是，就用 `domain-modeling` 在 `GLOSSARY.md` 登记术语，代码、界面与文档统一使用该术语。

**共享耳机控制**（`shared/headset`）

- `DeviceField`（`protocol/ProtocolCodec.kt`，带界面显示名）与 `DeviceSetting`（`ControlState.kt`）。
- 在 `HeadsetCommand` 定义类型化写入操作，并在跨进程解码时校验值类型。
- 能力带可选值（档位、模式列表）时，在 `DeviceCapabilities` 增加字段，并在每个型号的 `capabilities` 中显式赋值。
- 协议族字段表（`ztFields` / `rsCommonV2Fields`）、编解码的 `prepareSetting`（写帧、读回查询、期望值）和解码分支。
- `DeviceState.validate(command)` 检查可写设置与前置条件，各命令的 `checkOptions(device)` 检查型号选项与范围。设置队列在入队和发送前分别校验，沙盒使用同一规则。型号不支持的设置返回 `UNSUPPORTED`，档位越界返回 `INVALID_ARGUMENT`。
- 需要新的取值形状时新增 `FieldValue` 子类型，并同步下面所有跨进程编码。

**跨进程**（`platform/android/integration`）

- `wire/ControlWire.kt`：模块宿主与应用之间的控制状态，`encode(capabilities)` / `decodeCapabilities` 不穷举，新的能力字段要手动加入；`encode(FieldValue)` 穷举。
- `wire/BridgeSnapshotCodec.kt`：系统集成快照，`capabilities` 映射不穷举，`toBridgeValue` 穷举。只有系统界面需要该字段时才改 `NativeHeadsetSnapshot` 的解析。

**界面**（`shared/ui`）

- 控制卡片以 `writableSettings` 中是否含该设置、能力对象是否存在作为显示条件，保持与现有卡片一致。
- `NoiseControlCard.kt` 中的 `DeviceSetting.displayName()`、`requestedValueText`，以及 `DeviceValueFormatting.kt` 的 `displayFieldValue` / `semanticLabel`（新的文本取值要有中文标签）。
- 界面测试覆盖 `InterfaceStyle.entries` 全部风格。

## D：新协议族

- `ProtocolFamily`（`protocol/ModelProfile.kt`）：新增枚举项与 `displayName`，补全 `readableFields`、`promptVolumeLevels` 的穷举分支。
- 在 `models/ModelProtocol.kt` 添加本族的配置子类。新编解码实现 `ProtocolCodec`，由 `createProtocolCodec` 的对应分支传入此子类。编解码实例随每次连接尝试重建，会话内序号、接收缓冲放在实例内，`reset()` 清空。
- 字段表与初始化查询放在该族编解码内，表示“族认识的字段”；型号实际提供什么只由 `ModelSpec.capabilities` 决定。
- `ProtocolReply.accepted` 决定一条通知能否推进初始化：只有带已知语义值的字段才算。
- 帧格式、校验和、分包与写入容量（`initQueries(maxWriteSize)`）都要有协议文档中的字面量测试；没有字节级文档的族不实现。
