---
status: accepted
---

# 协议测试向量的证据等级与来源

协议测试中的字节串证据强弱不一：有的是实机抓包，有的是从 RoseLink APK 提取的固定字面量，有的是按研究规则另行推算的动态示例，大多数接收报文只是按文档偏移合成的。若用编解码器自己生成的帧去断言编解码器，测试只能证明代码与自己一致。若把合成通知当成抓包，又会夸大验证程度。源码不再写注释（[ADR 0021](0021-no-code-comments.md)），各测试向量的来源与等级记录在这里。

## 决策

### 证据等级

- **实机抓包**：只有一份。i5 在 Linux 上的初始化读取（`rose_reverse/analysis/i5_live_read_2026-10-18.jsonl`），按查询的响应键收录在 `I5Fixtures.kt` 的 `i5Replies`。其中电池样例 `09ff00000101011100000164620000ffe2` 采自 2026-10-18，固件 125，充电盒字节 `FF` 表示未提供，不是 0。没有任何设置操作的实机抓包，会话测试中的设置确认全是合成的。
- **APK 提取的固定字面量**：从 RoseLink 发送端提取的固定帧，例如 `rose_reverse/analysis/i5_fixed_sender_commands.json`、`rose_reverse/analysis/i7_sender_commands.json`（序号 00）、RoseLink 3.8.0 的 `ZtSender` 固定命令、`RsCommonV2Sender` 的固定字面量（均为序号 00）。
- **独立推算的动态示例**：按恢复出的发送端参数规则，在编解码器之外手工算出的完整帧，或引用 `rose_reverse` 研究测试与文档中的示例；会话中的序号按实际初始化查询次数推算。
- **合成通知**：按已记录的头、标签与字节偏移构造的接收报文，不是抓包。没有依据的字段保持未知；合成报文不推断设备的拒绝或应答语义。

### 规则

- 发送向量必须独立于编解码器：测试中的期望帧来自提取或手工推算，不调用编解码器或帧构造函数生成。
- 接收向量除 i5 初始化抓包外一律视为合成，不据此宣称实机验证。
- 面板上的频段与增益上下限是界面标签，不是实测的 DSP 数据。

### 各测试的来源

- **会话夹具**：`CatalogLinkFixtures.kt` 的 `CatalogTransport` 让任一目录型号的控制连接按该型号回答初始化；除 i5 外全部通知都是合成的，`ztSyntheticReplies` 按文档偏移构造，未知字段保持未知。`ModelAdaptationSessionTest` 用文档化的合成通知驱动真实会话，从不使用抓包。
- **`ControlSessionAdvancedAudioTest`、`ControlSessionDeviceControlsTest`、`ControlSessionEqualizerTest`**：发送为独立的固定字面量，接收为合成的 DD/LTV 报文。`dd0011010100020003000400110012001300140043aa` 中记录类型 `01` 属于普通触控传感器，即使其中的动作全部可解码。均衡器测试的序号 03..0c 是在三次完整初始化查询之后独立推算的；单字节 `00`／`01` 分别为 HIFI／POP，双字节 `00`／`01` 记录的顺序相反。
- **`ControlSessionBehaviorTest`**：`0000000000ffff` 是合成的未知命令与枚举，与记录的初始化抓包无关。`dd00040ce4` 与随后的报文是在独立研究得出的值外面套上合成的 DD 帧：先是被拆开的电池记录，再是合并在一起的固件与模式。
- **`ControlSessionSettingTest`**：`09ff00000106020e000001000020` 是按独立逆向的接收表合成的已知 normal 尾部。`ff0302090714aa`、`ff0402fa0908aa` 是在三次初始化读取、20 字节写入容量下独立推算的序号 03／04。本测试中 i7 的接收报文全部按恢复出的 DD/LTV 接收规则合成。厂商的自动模式退出时可能上报 wind（`dd00020904ecaa`），会话保留实际结果。`09ff00000106820e000001000020` 是合成的高位操作码回显，不据此推断设备的拒绝或应答语义。`partialCycleReply` 的合成回复保留两种轮换顺序：i5 为 normal／transparency／wind／ANC，i7 为 ANC／wind／normal／transparency。
- **`ControlSessionTransportSelectionTest`**：合成的厂商数据记录，含产品前缀、V2 状态字节与按线上顺序排列的 BR/EDR 地址。
- **`AdvancedAudioProtocolTest`**：`ff03022a0432aa` 为 APK 中 `setEqCustom` 的固定字面量，`ff040b3e86838100010203040506ebaa` 为 `rose_reverse/docs/earfeel-i7-protocol.md` §4.4 的独立动态曲线示例，按会话序号 03／04 推进；`ff0502fa2a2aaa`、`ff0602fa3e3faa` 为 APK `getEq`／`getCustomEq` 的固定载荷，配上独立推算的序号 05／06。i7 曲线频段与增益上下限取自原面板。提示音的固件门限只作用于静音值。合成的曲线记录 `dd000b3e86838100010203040506c5aa` 复用发送示例中的十个符号加绝对值字节。Jasmine 的语言与槽位记录为合成。
- **`DeviceControlProtocolTest`**：
  - RoseLink 的型号动作表：`pp+b6c0`（i5）有八个动作，`pp+b228`（i7）有九个，都是 `typeFull`。
  - 多点与 LDAC 取自父类 `ZtSender.setConnectMulOn` 与共享的 `setAudioLdac` 字面量；共享的 `0882` 变体不是 i5 配置使用的命令。
  - 降噪模式取自 `RsCommonV2Sender` 的固定字面量，全部为序号 00。
  - `08ee00000004810d000001058e` 是 `rose_reverse/tests/test_i5_protocol.py` 的独立动态示例；`ff00030114081faa` 是 `rose_reverse/tests/test_i7_protocol.py` 的独立动态示例。
  - 档位帧按独立恢复的 `Zt229Sender` 参数规则与参考的高档向量推算。i7 直接发送界面档位，只有静音值要求固件 304（以固件 213 为反例）。
  - 触控槽位的完整帧按恢复出的发送端槽位参数推算；i5 左耳及单耳的完整设置帧按 APK 中的动作对象推算。
  - i5 第 9 字节高半字节的哨兵值是合成的，不代表完整的设备抓包；LTV 记录及其校验和为独立推算的合成数据。
- **`EqualizerProtocolTest`**：i5 预设帧取自 `i5_fixed_sender_commands.json`；开关帧 `08ee00000006830b00008a`／`08ee00000006830b00018b` 为固定提取，不经客户端帧构造。固件显示标签故意与固件字节不一致，表明门限只看字节、标签不作为门限证据。i7 预设帧取自 `i7_sender_commands.json`（序号 00）。`ff0102fa2a26aa` 为 `getEq` 的固定载荷 `FA2A` 配会话序号 01。`ff0102fa0e0aaa` 中的 `FA0E` 是客户端推断：依据观察到的单 ID FA 查询及批量 `getInfo` 中的 `0E`。整帧前缀校验和的接收示例为合成，独立于编解码器手工推算。
- **`F8CodecTest`**：`0027010005010050da32` 按文档的帧头、标签与字节偏移合成；`00270100030a0001` 为合成的语言与入耳检测记录；`0027010003130003` 为合成的提示音档位回复。`11`／`12` 回复为合成，线上 0／1／2 即界面档位 5／3／1；共享的 CERAMICS-X 接收处理对这些型号把两个档位标签保持 Unknown。
- **`ModelProfileTest`**：规范化数据为小端 company ID 加载荷，末尾多加一个字节，以保证测的是前缀匹配。
- **`NoiseProtocolTest`**：`08ee00000006820e00010000008d` 为 RoseLink APK 固定发送端提取，两帧都不由测试计算；模式帧取自 RoseLink 3.8.0 的 `ZtSender` 固定命令。恢复出的 CERAMICS-U 配置有 ANC 低、中、高三档，通透只有低、高两档。`ff0102fa0905aa` 是客户端的单字段 FA 查询，序号 01 为独立推算。动态示例来自独立的 `rose_reverse` 测试与发送端参数证据；`08ee00000006850e000100000191` 为 i5 的独立动态示例，i7 发送端顺序为 ANC／wind／normal／transparency。接收缓冲区按独立恢复的偏移合成；i7 没有抓包，`09` 字段值 `04` 表示设备实际上报的 wind 模式。
- **`ProtocolCodecTest`**：类型 `99` 的值中完整包含一个 DD/09 帧，作为不透明值处理，为合成的独立示例。i5 字面量取自 `analysis/i5_fixed_sender_commands.json`。电池样例见上文“实机抓包”。`ff0012fa0d0c092c2d2a45460e322b01072f082e3649aa`、`ff0102fa3e3aaa` 为 RoseLink 3.8.6 i7 面板的命令顺序，加上 OpenRose 独立补充的 `FA 3E`。i5 字段示例（充电盒 50）来自独立的 i5 测试，周围的上下文为合成。`syntheticI7` 在独立研究得到的字段示例外构造帧，从不是抓到的接收样本。
- **`RsCommon386ProfilesTest`**：`ff000ffa010708090c0d0e122a2b2c2d2e33aa` 对应 RoseLink 3.8.6 的 `RsCommonSender.getInfo` 及两个型号专属的初始化分派入口。
- **`RsCommonCodecTest`**：独立取自发送端：左单击、右单击前缀为 `FF 00 03 01 01 0`／`FF 00 03 01 11 0`，动作码按一位十进制数字拼接。左耳三击槽位为 `03`，右耳槽位与左耳平行，为 `11`／`12`／`13`。型号动作表跳过语音助手 4，音量加、减为 5／6；`TouchCtlType` 只有单、双、三击，动作表中没有语音助手与降噪切换。BudsFree i 没有通知抓包，载荷按协议族文档的字符下标 11／15／19／31／35／39 换算成低半字节合成。`ff00020e` 为合成，没有真实设备通知抓包，帧封装只按文档的字节位置推断。
- **`RsCommonV2Mk2CustomEqAndPromptVolumeTest`**：初始化顺序为 RoseLink 3.8.6 面板顺序，只有 OpenFeel MK2 能提供 OpenRose 单独读取的自定义曲线。OpenFeel MK2 面板没有 OpenRose 可写的具名预设，低音响度增强预设是独立命令，作为 `BASS_BOOST`。RoseLink 没有为该型号设置 `customSinceVersion`，因此没有固件门限。`ff00022a042faa`、`ff010b3e86838100010203040506e8aa` 为 APK 的 `setEqCustom`（`2A 04`）与 `setEqCustomData`（`3E` 加十个符号-幅值增益），按序号 00／01 独立推算，−1 编码为 `0x81`；`ff0202fa2a27aa`、`ff0302fa3e3caa` 为 `getEq`／`getCustomEq` 读回，序号 02／03 独立推算。
- **`RsCommonV2SpatialAndMicTest`**：通知为合成；麦克风未激活时，不能报告独立麦克风模式为激活。
- **`RsV2ModelVariantsTest`**：`ff010835141a0b14112609caaa` 为合成的 RoseLink `setTime(2026-11-20 17:38:09)`，年份拆成 `20` 与 `26` 两个字节。
- **`SingleUnitBatteryProtocolTest`**：合成；`FF` 表示未提供，`00` 表示未知，两者都不能变成 0%。
- **`ZtModelProtocolTest`**：`09ff00000101030a0050` 为合成通知，只按已恢复的字段偏移构造。HeadFeel 自定义曲线为静态证据：先发 `setEqCustom` 的固定选择字节 `03`，再发 `setEqCustomData` 的十个符号-幅值字节（[headfeel-protocol.md](../protocols/models/headfeel-protocol.md)）；校验和按 08EE 布局计算，曲线帧 20 字节，等于默认的 BLE 写入容量。曲线频段与上下限是恢复出的面板标签。EarFree i5 的配置没有自定义曲线，因此去掉 `CUSTOM_EQ` 可读字段。

## 后果

新增协议测试向量时，在本 ADR 对应条目登记其证据等级与来源。只有实机抓包能作为实机验证的依据，验证情况以 [docs/protocols/](../protocols/README.md) 为准。上述 `rose_reverse/...` 路径指作者本地的研究工作区，没有随仓库发布。
