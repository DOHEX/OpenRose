---
status: accepted
---

# 型号能力证据与读数回读规则

型号能力来自 RoseLink 的型号配置类与界面面板，不来自实机测量。研究证据与实机验证是两回事（见词汇表“型号能力”），所以每个型号配置开放什么、按什么固件门控，都要能追溯到 RoseLink 的具体配置；配置里没有的就不开放，配置没有门控的也不自行加门限。逐型号的细节见 [docs/protocols/models/](../protocols/README.md#型号协议文档) 与[型号适配证据账本](../protocols/model-adaptation-ledger.md)。读数怎样从设备回复中得出、确认写入后留下哪些字段，由协议决定，沙盒按同一规则模拟。

## 决策

### 各型号配置的能力证据

- **EarFeel i7**（`earFeelI7`）：来源为 RoseLink 的 `rs_common_v2/earfeel_i7_device_config`。恢复出的配置没有为自动降噪模式（auto、dynamic）设固件门限。自定义曲线的频段 20、100、300、500、1000、2000、3000、5000、8000、15000 Hz 与增益上下限取自 i7 面板的频段标签和滑块边界，不是实测的滤波器中心频率。
- **EarFree-i3**（`earFreeI3`）：来源为 RoseLink 的 Zt219 配置。
- **EarFree i5／EarFeel-i5**（`earFreeI5`）：来源为 RoseLink 的 `zt/earfree_i5_device_config`。恢复出的配置不按固件门控任何能力。音频模式与多点由同一个 ZT 字段承载：选择 LDAC 会关闭多点（`multipointCoupled = true`）。
- **HeadFeel**（`headFeel`）：来源为 RoseLink 的 Headfree／Zt231 配置。RoseLink 以固件 130 同时门控增益档位与自定义曲线（`customSinceVersion`／`field_7`），对应 `headFeelCapabilities` 中的 `firmwareAtLeast(firmware, 130)`。曲线频段与上下限是面板标签和滑块边界。
- **OpenFeel MK2**（`openFeelMk2`）：只有自定义曲线可写。RoseLink 提供自定义 EQ 面板，另有单独的低音响度增强预设，OpenRose 把后者作为低音增强（`BASS_BOOST`），不作为均衡器预设。曲线频段与上下限取自共享 EQ 面板的标签和滑块边界。提示音面板提供 1..5 与关闭（00），没有固件门限，故为 0..5。
- **BudsFeel MK2、Jasmine、Swift 2**（`budsFeelMk2`、`jasmine`、`swift2`）：RoseLink 面板禁用了提示音的关闭选项，也没有固件门限，提示音档位为 1..5。
- **BudsFree i**（`budsFreeI`）：RoseLink 的触控动作表从 0 编号，跳过语音助手 4，音量加、减为 5、6。`RTouchInfoModel` 的 `TouchCtlType` 只有单击、双击、三击，不开放长按。
- **F8 基础型号**（`f8BasicReadings`）：可读字段为基础 F8 接收处理能解码的字段；这些耳机不上报充电盒槽位。

### 读数与回读

沙盒（`tools/ui-sandbox`）生成读数并模拟回读，规则与真实会话一致：

- **电量按型号布局**（`SandboxDevice.batteryReading`）：单体型号在充电盒槽位上报，只有耳塞的型号没有充电盒电量。
- **事件类与附带读数**（`SandboxDevice.choiceValue`）：低音档位只随自定义槽位的均衡器回复一起读到，寻机事件只在发生时才有，因此在此之前两者都没有值。
- **确认写入后留下的字段**（`SandboxWrites.simulateReadback`）：取编解码器给出的预期读回，再按设备回复的方式补全。触控分配写入完整绑定表的对应槽位；低音档位与均衡器的自定义槽位模式来自同一条回复（RS Common V2 的低音增强写入 `2A 05 N`，预期读回为均衡器 `custom_slot` 加上 `EQ_SLOT`）。
- **绑定表补全**（`SandboxWrites` 的 `assigning`）：把分配放进绑定表的对应槽位，该槽位保留设备自己的原始动作码。

## 后果

新增或修改型号配置时，每项能力与门限都要注明来自 RoseLink 的哪个配置或面板；面板标签不能写成测量值。沙盒与真实会话共用这些回读规则，沙盒中“写入后有值”的字段，必须是设备回复中确实会带回的字段。
