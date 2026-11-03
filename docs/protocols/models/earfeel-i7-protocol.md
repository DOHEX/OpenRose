# EarFeel i7 / earfeel-i7 第三方控制协议

分析日期：2026-10-14。目标为 RoseLink **3.8.0 / versionCode 5310** 的 i7 配置；不是其他同名硬件/固件版本的通用保证。

## 1. 范围、证据和可实现能力

本说明恢复广播识别、BLE/SPP连接配置、RS Common V2发送帧、读取/设置命令、通知重组和字段解析，以及BES OTA打包与状态机。常规控制已到可实现客户端的字段与字节粒度，附可独立运行的编解码和BLE集成参考。2026-10-14 已验证匿名 OTA 元数据请求、更新包资源 HEAD 与前 64 字节 Range 响应，见 [OTA 更新包链接](../ota-package-links.md)。**未连接实机、未抓取蓝牙数据、未下载完整固件、未执行OTA**；未知设备行为和实现选择明确区分。

协议目录 [earfeel-i7-protocol.json](earfeel-i7-protocol.json) 的**结构由本次研究定义**，不是官方App自带的JSON或官方公开规范。内容的来源为官方App代码，`evidence`记录代码路径、行号和地址。客户端的缓存、超时和输入限制是参考实现的选择。

- APK：`rose_reverse/RoseLink_3.8.0.apk`
- 包名：`cn.ikaile.ruoshui.client`；SHA256：`83cdd7e3e7f6e7d61a26dc6a8fab65f183d5a18a66a70f64a17f69d93e58e40b`。
- 配置：`EarfeelI7DeviceConfig extends BaseDeviceConfig`，class id523；不直接继承 `RsCommonV2DeviceConfig`。
- Sender：`RsCommonV2Sender`；Receiver：`RsCommonV2Receiver`；handler：`RsCommonV2ReceiveHandler`。
- 内部名：`earfeel-i7`；显示名：`EarFeel i7`；识别前缀：`b8e5290f`。

厂商站点也列出 EARFEEL i7；这里只用来核对产品名称，字节协议以APK为证。[厂商产品列表](https://global.roseselsa.com/collections/wireless-earbuds)

| 能力 | 本型号配置和已恢复控制 | 限制 |
|---|---|---|
| 电量 | 左、右、仓；左右入仓flag | 仓值有历史缓存；仓bit7语义待实测 |
| 降噪 | ANC、普通/关闭、通透、抗风；自动ANC、动态自动ANC | 共享深度ANC可解析，i7未配置其入口 |
| 强度 | ANC/通透各3档 | UI和线上均1/3/5，不使用i5的0/1/2换算 |
| EQ | HIFI/POP/ROCK/LIGHT/EAP；10段个性化EQ | EAP门槛152；曲线范围−6..+6整数；线上HIFI/POP与UI index相反 |
| 增益 | 3档，线值0/1/2 | 界面门槛213；没有具体dB增益表 |
| 听力保护 | 关闭及75/80/85/90/95/100 dB阈值标签 | 界面门槛213；标签不是实测SPL或医学效果证明 |
| 触摸 | 总开关；左右单/双/三击/长按；9动作 | 触摸动作码与i5不同 |
| 游戏 | 开关 | `01`开、`00`关 |
| 佩戴检测 | 检测功能开关 | `00`开、`01`关；不是实时佩戴事件 |
| 音频模式 | AAC/SBC、LDAC、LHDC、LC3 | 与多点分别设置；实际编码协商由系统负责 |
| 多点 | 独立开关 | 不据独立报文断言所有Codec组合都能同时工作 |
| 寻机 | 左、右、双耳、全停 | 双耳是独立命令；回包含单耳停止事件 |
| 提示音 | 音量1..5；关闭0 | 关闭选项另外要求固件整数>=304 |
| ANC轮换 | 四种常规模式参与flag | 线上顺序ANC/抗风/普通/通透 |
| 版本/地址 | 3byte固件显示；main/sub MAC读取 | MAC不是已确定的左/右对应 |
| 升级 | BES，独立BLE/SPP通道 | 含分段ACK/CRC；固件兼容、角色、恢复未实测 |
| AI业务入口 | 配置门槛300；已恢复SDK转发前缀 | SDK内层消息/云业务未恢复，不是基础耳机控制 |
| 提示音语言 | 无已确认的i7专用入口 | 不复制i5的语言固件切换，也不因共享31命令增加能力 |

固件门槛使用App版本文本转十进制整数的比较，不是系统App版本，也不是标准semantic version。版本回包的编码见接收章节。例如回包V=`01 05 02`形成文本`152`。152、213、304、300在这里都是该比较域中的整数。

增益/听力的213已通过GDT getter返回field7直接确认；不是仅凭字段位置推断。原App关闭自动/动态ANC时发送**抗风模式`09 04`**，同时乐观显示ANC；客户端应以通知回包更新最终状态。

证据：i7配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:10`）、EAP模型（`rose_reverse/analysis/blutter/objs.txt:1705`）、门槛/初始化GDT补核（`rose_reverse/analysis/i7_dispatch_findings.md`）、提示音关闭判定（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_ding_level_panel_item.dart:900`）、自动关闭native核对（`rose_reverse/analysis/i7_config_ota_findings.md`）。

## 2. 发现、地址和传输

### 2.1 广播识别

共用 scanner `_advertisementDataToHexString @0xa0daa8` 把 manufacturer keys 数值排序；先拼所有 keys 的 2 字节小端，再拼依此顺序的所有 payload，最后转 lowercase hex。不是 key/payload 逐项交错。

`DeviceConfig.identifyDevice @0x90a2ac` 先尝试前 12 hex chars、再前 8 chars、最后前 6 chars 的表匹配，因此 i7 用前 8 chars `b8e5290f`。单个 manufacturer 项的静态推导样例是 key/company `0xe5b8`，payload 开始 `29 0f`。此例不是实机广告。

Base `getInfoFromRawData @0x748ddc` 的 rs_common_v2 分支，对规范化后的 hex H：

| 条件/偏移 | 解释 |
|---|---|
| `len(H)>=22`；`H[12:14]` | broadcastDeviceType；01→tws、04→mic、其余→head |
| `H[14:16]=="01"` | brEdr="true"；其余="false" |
| `len(H)>=34`；`H[22:34]` | bleAddress/btAddress；6 字节顺序用 `:` 分隔，`to2` 先转 upper case，未见颠倒 |

field `4b=bleAddress`、`4f=btAddress`，实际字段名称见 EarObject.toMap。BLE 连接仍使用 OS 扫描对象的 address/remoteId；SPP 可使用广播的 BR/EDR 地址，不能用 discovery ID 当 MAC。广播 H 中 bytes 4..5、8..10 的语义此路径未读，不补猜测。

- manufacturer 规范化:3737（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/scanner/r_ble_scanner.dart:3737`）
- 前缀识别:191（`rose_reverse/analysis/blutter/asm/roselink/constant/device_config.dart:191`）
- V2 广播解析:451 起，0x749298（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:451`）
- MAC 格式化:685，0x74953c（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:685`）

### 2.2 传输选择

`ConnectManager._shouldUseSppControl @0xa009fc`：平台 SPP 可用且该型号 sppSupport=true、全局没有强制 BLE，音频 Profile 非 LE Audio 才选 BtConnector。`isSppOperatingSystem @0x9ecba0` 包含 android、windows、ohos、openharmony、harmonyos、harmony。LE Audio 情况由 A2dpManager.prefersBleControl 决定，不能直接根据 UI 选了 LC3 就断言连接已进入 LE Audio。

普通 BLE 流程复用 i5 已核的 BleConnector：connect、discoverServices、匹配 00fe/00f1/00f2、注册 onValueReceived、setNotifyValue(true)，writeWithoutResponse 属性优先，否则有响应写；请求 MTU512。普通队列等待 write Future 完成后间隔100ms；OHOS BLE 特殊间隔350ms。**业务应答不是 GATT write Future**。SPP 将同一 V2 控制字节帧发往自己的 socket；BES OTA 则独立 socket/channel。

- 控制选择:4614，0xa009fc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:4614`）
- SPP 操作系统与强制BLE/LEAudio条件:95，0x9ecb04（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/src/roselink_platform.dart:95`）
- BtConnector SPP连接:41，0x9f4270；控制 UUID 取对象field33（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/bt_connector.dart:41`）
- BLE 请求MTU:9320，0xe555b4（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:9320`）

### 2.3 通道表和连接步骤

| 通道 | 服务/连接UUID | 写 | 读/通知 |
|---|---|---|---|
| BLE正常控制 | `000000fe-0000-1000-8000-00805f9b34fb` | `000000f1-0000-1000-8000-00805f9b34fb` | `000000f2-0000-1000-8000-00805f9b34fb` |
| SPP正常控制 | `0cf12d31-fac3-4553-bd80-d6832e7b3931` | RFCOMM/socket byte stream | 同socket读取 |
| BLE BES OTA | `66666666-6666-6666-6666-666666666666` | `77777777-7777-7777-7777-777777777777` | 同特征订阅 |
| SPP BES OTA | `66666666-6666-6666-6666-666666666666` | 独立OTA socket | 同OTA socket |

1. 扫描并按manufacturer前缀匹配，保存系统remote id。SPP通过系统配对/SDP服务发现连接其专用UUID；不能把BLE的00FE服务或型号前缀当RFCOMM通道号。
2. BLE连接后发现实际服务/特征，核对write属性。先订阅00F2并完成CCCD设置，再发送查询。
3. 默认公共BLE层优先无响应写，尝试请求MTU512；不支持无响应写时用有响应写。SPP直接写同一完整RS V2报文，接收时作为字节流重组。
4. 普通控制共用FIFO，底层write完成后留100ms；OHOS BLE特定分支350ms。这里没有统一业务ACK关联或自动重发。
5. 分别表示transport_connected、notifications_subscribed、protocol_received、每个字段的loaded/freshness；断开时清空序号所属会话、分包buffer和待处理查询。

`getInfo`整帧35字节，比ATT MTU23下的20字节值容量大，所需普通ATT MTU至少38。不要将它任意拆成两个独立write而宣称设备已经支持TX拼接。优先使用协商后的可写容量；参考BLE例子在容量不足时，将FA的ID列表分成多条完整FA查询帧。这是从App观察到的FA多ID/单ID查询归纳的客户端降级策略，设备接纳仍需验证。每条新逻辑查询使用新的序号和校验。

正常i7单值设置7字节，触摸设置8字节，ANC循环10字节，自定义EQ数据16字节。没有额外业务帧分片参数出现在这些命令里。普通控制序列和BES OTA序列分别维护。

通道证据：型号定制（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:1024`）、默认BLE配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/base_device_config.dart:993`）、SPP选择（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/connect_manager.dart:4614`）、普通写属性（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/connector/ble_connector.dart:5921`）、公共队列（`rose_reverse/analysis/i5_queue_protocol.md`）。本研究没有发现正常控制的自定义登录token/密码握手；系统配对要求仍取决于设备/平台，不能据此声明所有固件均无需配对。

## 3. 正常发送帧

`FF SS LL BODY... CC AA`。`SS` 为 1 字节序号，i7 Sender 构造时为 0；每次 transform 使用当前值再加 1，下一次若超过 255 先归零。`LL=len(BODY)`，包括命令及参数，不包括帧头、序号、长度、校验与结尾；全帧长 `LL+5`。`CC=sum(FF SS LL BODY...) & 0xff`，并非 CRC。尾字节固定 `AA`。

证据：transform（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4750`）（0xdfa838），i7 Sender 初值（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:49`）（0x681848），CommandUtil.checkSum（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:189`） 与 加法 reducer（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:454`）。

以下完整帧样例全使用 `SS=00`，实际串行发送时必须使用当前序号并重新求和。把同一完整样例反复写入会重复序号。初始化查询长 35 字节，超过默认 ATT MTU 23 的单次值容量，须按 BLE transport 的能力处理；普通单值设置只有 7 字节，自定义 EQ 16 字节。

```python
def pack(body, seq):
    if not 0 <= seq <= 255 or not 1 <= len(body) <= 255:
        raise ValueError("invalid frame fields")
    raw = bytes([0xff, seq, len(body)]) + bytes(body)
    return raw + bytes([sum(raw) & 0xff, 0xaa])
```

### 3.1 初始化与查询

`getInfo` 返回 BODY：

```text
fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49
```

对应第一次写入：

```text
ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa
```

这是一条共享的批量查询，包含 29 个功能 ID，不能用它推断 i7 支持所有对应功能。FA 是已观察到的普通查询前缀：`FA 2A` 查询 EQ 模式，`FA 45` 查询增益，`FA 3E` 查询自定义 EQ。`getInfo` 没有查询 `3E`，想获取当前自定义曲线须另发 `FA 3E`。`getEarMac` 特殊为 `FE 34`。对其它已知 ID 拼接 `FA ID` 属于由现有查询方法归纳的构造方法，没有找到每个 ID 对应独立 App getter。

### 官方初始化与参考客户端补读

已补核GlobalController的GDT+0xd8ba：i7配置cid523→表index60101→0x8e2444。目标函数仅调用`RCommander.getInfo`。AOT输出把该共享代码标为Sportfree匿名closure，并不表示i7使用SportFree型号配置。

因此官方i7初始化只包含`getInfo`；**不含自定义EQ数据3E**。参考客户端用`getInfo → getCustomEq`填充普通状态和当前曲线。这是新增补读选择，有意与官方实际初始化区分。GDT证据（`rose_reverse/analysis/i7_dispatch_findings.md`）

## 4. 设置命令与参数

| 方法 | BODY | seq=00 完整帧 | 证据 |
|---|---|---|---|
| `setFindOff` | `2f 04` | `ff 00 02 2f 04 34 aa` | setFindOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1166`） / 0x7b38f4 |
| `setFindDeviceBoth` | `2f 03` | `ff 00 02 2f 03 33 aa` | setFindDeviceBoth（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1207`） / 0x7b3b64 |
| `setFindDeviceRight` | `2f 02` | `ff 00 02 2f 02 32 aa` | setFindDeviceRight（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1283`） / 0x7bb13c |
| `setFindDeviceLeft` | `2f 01` | `ff 00 02 2f 01 31 aa` | setFindDeviceLeft（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1305`） / 0x7bb284 |
| `setAncAutoDynamicOn` | `09 07` | `ff 00 02 09 07 11 aa` | setAncAutoDynamicOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1368`） / 0x80a748 |
| `setAncAutoOn` | `09 05` | `ff 00 02 09 05 0f aa` | setAncAutoOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1409`） / 0x80a7a0 |
| `setNoiseModeTrans` | `09 03` | `ff 00 02 09 03 0d aa` | setNoiseModeTrans（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1570`） / 0x80d8c0 |
| `setNoiseModeNormal` | `09 02` | `ff 00 02 09 02 0c aa` | setNoiseModeNormal（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1611`） / 0x815834 |
| `setNoiseModeWind` | `09 04` | `ff 00 02 09 04 0e aa` | setNoiseModeWind（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1652`） / 0x815f64 |
| `setNoiseModeAnc` | `09 01` | `ff 00 02 09 01 0b aa` | setNoiseModeAnc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1693`） / 0x81a3a4 |
| `getGainLevel` | `fa 45` | `ff 00 02 fa 45 40 aa` | getGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1973`） / 0x822c3c |
| `setEarDetectionOff` | `08 01` | `ff 00 02 08 01 0a aa` | setEarDetectionOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2073`） / 0x826944 |
| `setEarDetectionOn` | `08 00` | `ff 00 02 08 00 09 aa` | setEarDetectionOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2114`） / 0x8284f8 |
| `setTouchOff` | `07 01` | `ff 00 02 07 01 09 aa` | setTouchOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2379`） / 0x8e87cc |
| `setTouchOn` | `07 00` | `ff 00 02 07 00 08 aa` | setTouchOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2420`） / 0x90fd08 |
| `setAudioLc3` | `2b 03` | `ff 00 02 2b 03 2f aa` | setAudioLc3（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2840`） / 0xc6dd34 |
| `setAudioLhdc` | `2b 02` | `ff 00 02 2b 02 2e aa` | setAudioLhdc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2881`） / 0xc6de70 |
| `setAudioLdac` | `2b 01` | `ff 00 02 2b 01 2d aa` | setAudioLdac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2922`） / 0xc6e0ac |
| `setAudioAacSbc` | `2b 00` | `ff 00 02 2b 00 2c aa` | setAudioAacSbc（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2963`） / 0xc6e274 |
| `setConnectMulOff` | `32 00` | `ff 00 02 32 00 33 aa` | setConnectMulOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3002`） / 0xc77cbc |
| `setGameModeOff` | `0e 00` | `ff 00 02 0e 00 0f aa` | setGameModeOff（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3213`） / 0xc78c24 |
| `setGameModeOn` | `0e 01` | `ff 00 02 0e 01 10 aa` | setGameModeOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3252`） / 0xc78dec |
| `getCustomEq` | `fa 3e` | `ff 00 02 fa 3e 39 aa` | getCustomEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3962`） / 0xc87f48 |
| `setEqCustom` | `2a 04` | `ff 00 02 2a 04 2f aa` | setEqCustom（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4003`） / 0xc87fd4 |
| `setEqEap` | `2a 06` | `ff 00 02 2a 06 31 aa` | setEqEap（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4312`） / 0xcb322c |
| `setEqLight` | `2a 03` | `ff 00 02 2a 03 2e aa` | setEqLight（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4353`） / 0xcbfc20 |
| `setEqRock` | `2a 02` | `ff 00 02 2a 02 2d aa` | setEqRock（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4394`） / 0xcbfdf0 |
| `setEqHifi` | `2a 00` | `ff 00 02 2a 00 2b aa` | setEqHifi（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4435`） / 0xcd3c40 |
| `setEqPop` | `2a 01` | `ff 00 02 2a 01 2c aa` | setEqPop（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4474`） / 0xdf57a0 |
| `getEq` | `fa 2a` | `ff 00 02 fa 2a 25 aa` | getEq（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4515`） / 0xdfa0b8 |
| `getEarMac` | `fe 34` | `ff 00 02 fe 34 33 aa` | getEarMac（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4556`） / 0xdfa274 |
| `getInfo` | `fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49` | `ff 00 1e fa 01 07 08 09 0c 0d 0e 12 2a 2b 2c 2d 2e 2f 31 32 33 36 37 38 39 3a 3b 3c 3d 3f 45 46 49 e9 aa` | getInfo（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:4597`） / 0xdfa670 |
| `setConnectMulOn` | `32 01` | `ff 00 02 32 01 34 aa` | setConnectMulOn（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:5009`） / 0xe342e8 |

ANC 发送线值为 `09 01` ANC、`09 02` 普通、`09 03` 通透、`09 04` 抗风、`09 05` 自适应、`09 07` 动态自适应。共享的 `09 06` 是 `setDeepAncOn`，i7 未配置深度 ANC UI，不应据此增加能力。接收研究已用 UI 互证 RX 05 自适应、06 深度、07 动态自适应与 TX 一致；对应App内部状态4、5、6不是NoiseType枚举0..3之一。

EQ 线值 `2A 00` HIFI、`2A 01` POP、`2A 02` ROCK、`2A 03` LIGHT、`2A 04` custom、`2A 06` EAP。i7 配置 UI 值 POP=0/HIFI=1，而 Sender 返回 POP=1/HIFI=0，必须交换；其余不交换。EAP 模型版本门槛对象 `EarVersionCheck off_8_Smi=0x130` 是标记整数，实际为 152，不是 304。

音频设置 `2B 00/01/02/03` 分别 AAC/SBC、LDAC、LHDC、LC3；i7 配置只列这四项。多点是独立命令 `32 00/01`，与 i5 的 codec/多点耦合协议不同。游戏 `0E 00/01` 是关闭/开启；入耳与触摸开关则是反向极性 `08 00/01`、`07 00/01`，00 开、01 关。

寻机 `2F 01` 左，`2F 02` 右，`2F 03` 双耳，`2F 04` 停止；i7 的双耳调用确实与单耳不同。

### 4.1 动态参数命令

| BODY | Sender 接受值 | i7 UI/语义 | 证据 |
|---|---|---|
| `2c value` | `[1, 2, 3, 4, 5]` | ANC 等级；i7 low/medium/high 对应1/3/5 | setAncLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1510`） / 0x80b788 |
| `2d value` | `[1, 2, 3, 4, 5]` | 通透等级；i7 low/medium/high 对应1/3/5 | setTransLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1450`） / 0x80b30c |
| `2e value` | `[0, 1, 2, 3, 4, 5]` | 提示音1..5；0静音入口要求固件整数>=304 | setDingLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2014`） / 0x823184 |
| `45 value` | `[0, 1, 2]` | 增益低/中/高三档0/1/2，无具体dB量化证据 | setGainLevel（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1914`） / 0x82189c |
| `46 value` | `[0, 1, 2, 3, 4, 5, 6]` | 听力保护0关，1..6显示75/80/85/90/95/100 dB（UI阈值标签，未测量SPL） | setHearingProtection（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1855`） / 0x820f64 |
| `2f value` | `[1, 2, 3, 4]` | 1左、2右、3双耳、4停 | setFindDevice（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:1229`） / 0x7b3b94 |

这些函数直接把参数放入1字节，没有 i5 那种 1/3/5→0/1/2 的等级换算。值越界时返回空列表，RCommander 的正常路径不会生成控制帧。

### 4.2 ANC轮换

`36 [ANC] [wind] [normal] [transparency]`，四标志 true=1/false=0。线上顺序与 i7 UI 列表 normal/trans/wind/ANC 不同。Sender 参数线序是 r2、r6、r3、r5；公共 UI caller 的列表 0→r3、1→r5、2→r6、3→r2，且 i7 模型 `pp+b218` 按 normal/trans/wind/ANC 定义。

证据：setAncLine（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:945`）；公共 UI caller（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_anc_line_panel_item.dart:798`）；i7 配置（`rose_reverse/analysis/blutter/asm/roselink/rose_bluetooth/config/rs_common_v2/earfeel_i7_device_config.dart:873`）；模型枚举（`rose_reverse/analysis/blutter/objs.txt:1849`）。另用 `llvm-objdump -D --triple=aarch64 --start-address=0x756cb8 --stop-address=0x756f08` 可恢复 Blutter 省略的 mixin：r3加入PP1a298 normal，r5加入PP11fb8 trans，r6加入PP11fc0 wind，r2加入PP11fc8 ANC，互相吻合。

### 4.3 触摸动作

`01 slot action`：左耳单击/双击/三击/长按 slot 为 `01/02/03/04`，右耳为 `11/12/13/14`。动作 0 无，1 播放暂停，2 上一首，3 下一首，4 音量+，5 音量−，6 游戏模式，7 语音助手，8 ANC循环。Sender不校验action，但i7配置仅允许0..8。这个 action 码表与 i5 完全不同。

证据：_setTouchKey（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:2482`） 与其8个wrapper；值模型（`rose_reverse/analysis/blutter/objs.txt:998`）及`pp+b228`。

### 4.4 个性化EQ

先 `2A 04` 进入 custom，再 `3E b0..b9` 下发十个增益值，App在正常队列中分两次写入。每个 double 先 `.toInt()` 向0截断；正数写绝对值，负数写 `0x80|abs(value)`。它是符号位加绝对值编码，不是有符号补码。例 −6=86、−1=81、0=00、+6=06；−0.5截断0所以写00。

i7 EQ UI 频段标签依次 20、100、300、500、1k、2k、3k、5k、8k、15k；UI纵轴−6..+6。这些是界面标签，未验证声学滤波中心。Sender自身未检查列表长度，客户端应针对i7强制十项并限制−6..+6整数值，以免产生意外数据。

```text
ff 00 0b 3e 86 83 81 00 01 02 03 04 05 06 e7 aa
```

上例对应 −6、−3、−1、0、1、2、3、4、5、6，seq=00。

证据：setEqCustomData（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3656`）、intToEqList（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3735`）、double截断（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/rs_common_v2_sender.dart:3929`）；EQ轴及频段（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_panel.dart:2404`）；setEqCustom后发数据（`rose_reverse/analysis/blutter/asm/roselink/modules/device_control/widget/device_custom_eq_session.dart:450`）。

### 4.5 自定义曲线工作流、门槛和自动模式关闭

10段顺序：**20/100/300/500/1k/2k/3k/5k/8k/15k**。滑块范围−6..+6、步长1；发送器对double向0截断，整数原样编码。负数是高bit符号+低7bit绝对值，不是补码，不乘10、不乘2。UI标签没有提供滤波器Q/类型，也没有实测中心频率证据。

应用曲线：先发送`2A 04`进入custom，再发送`3E`加10个gain，两条不同序号的完整帧；最后分别读取EQ模式和曲线。命名preset保存/删除发生在App本地session/服务中，不能把本地名字或数据库id直接写入耳机报文。原App恢复内建模式时有先写custom零曲线再恢复目标模式的路径，不代表所有内建模式必须经过零曲线才能切换。EQ UI/工作流笔记（`rose_reverse/analysis/i7_eq_findings.md`）

增益线值0/1/2只代表3档，建议显示档位1/2/3并保留raw；本次没有具体增益dB对应。听力保护线值0关闭，1..6对应UI阈值75/80/85/90/95/100 dB。提示音线值1..5直接对应档位，0是关闭；0的UI条件>=304，基础档位不是全部受304限制。

自动模式没有独立ON/OFF命令，使用正常降噪模式设置：auto on=`09 05`；dynamic on=`09 07`。i7的两个关闭开关发送fallback=`09 04`（抗风），却把本地noise状态立即写成ANC。参考API `set_auto_anc(enabled=false)`和`set_dynamic_anc(enabled=false)`复现发送wind的实际行为，状态以回包为准。客户端若希望关闭后回ANC，可以显式选择ANC，这是客户端行为选择，不能称为原App发送命令。

## 5. 接收帧、分包与字段

### 5.1 帧格式与校验

`EarfeelI7DeviceConfig` 创建 `RsCommonV2Receiver` + `RsCommonV2ReceiveHandler`，后者 class id 421。接收走 `_process` → `CommandUtil.splitData` → 去头尾 → `_processDataContext` → `_processLTV` → 各字段 handler。

正常接收格式：

```
DD HH [L T V...] [L T V...] ... CS AA
```

`HH` 是第二头字节，接收路径没有校验它的具体值或解释其语义。L 为单字节长度，含 T 和 V，不含 L 自身：`L = 1 + len(V)`。LTV 的总长为 `L+1`。这里没有整体长度字段，也未发现事务序号或请求/响应 ACK 编号。

`CS` 是 AA 之前一个字节。`_isValidPacket` 接受两个算法：

```
CS == sum(frame[0:-2]) & 0xFF
或
CS == sum(frame[2:-2]) & 0xFF
```

第一种含两字节头，第二种仅含 LTV 内容。App 对两个计算分支都调用 `CommandUtil.checkSum`。若两个都不符，整个候选包被拒绝。

`splitData` 用 LTV 的长度跳过各记录，抵达 CS+AA 后校验，故不能简单 `split(AA)`：V 中可以包含 AA。它会扫描输入中的 `dd` 并提取多个完整帧；候选解析失败时从候选 DD 后一个字节继续查找。扫描使用 hex 字符串 indexOf，未见显式字节对齐筛选，第三方实现宜限制在字节边界。

接收 `_hasPrefix` 容许 DD 或 FF，但 `splitData` 和 `_isValidPacket` 只认 DD。因此孤立的 `FF ... AA` 虽会走完整帧分支，最终找不到任何可解析帧。这是原 App 静态路径差异，应记录，不应声称官方已经完整接受 FF 响应。

完整帧解析把前 2 字节、末尾 AA 去掉，保留 CS 在 context 尾部。每个 LTV 按长度取完后，通常仅余 CS；长度小于 2 字节时循环退出，CS 不当成字段。

L=00 有兼容特例：`_parseLTVPacket` 如果发现最终 AA，直接返回从当前帧头到输入尾的字符串，绕过 `_isValidPacket`。然后 `_processDataContext` 把有效 L 算作 `(remaining_hex_chars - 4)/2`，即 `remaining_bytes - 2`；这使当前 T 的 V 覆盖余下内容（除最后 CS）。它因此只能可靠表达该帧余下的最后一个字段，不能继续识别里面的其他 LTV。第三方实现可保留此兼容格式，但应仍自行校验 CS，并记录零长度特例使用情况。

Type FE 在 `_processDataContext` 中直接跳过，既不更新状态也不做 received 标记。尚未从此路径解释它的协议语义。

### 5.2 分包与容错

- 输入先转换为字符串并去空格；实际字符应为小写 hex（比较 DD/DD 等是小写字符串比较）。
- 若输入有 DD/FF 前缀并以 AA 结束，直接走完整帧集合解析，未消费之前暂存 buffer。
- 若输入有前缀但末尾不是 AA，作为首段保存；已有未完成 buffer 则放弃旧 buffer，用新首段替换。
- 无前缀的中间段只有在 buffer 已有 DD/FF 前缀时才追加。
- 无前缀且以 AA 结束的尾段追加到 buffer，递归 `_process`，然后清空 buffer。
- 该 receiver 中未见分包超时或 buffer 容量限制。第三方实现宜在断开时清空、限制长度并对未完成帧设置超时。
- LTV 长度不足或非法长度使当前剩余 context 停止解析；已成功更新的较早字段不回滚。
- `_processLTV` 对单项 handler 异常做 catch，日志后返回 false，下一条 LTV 可以继续。未知 type 原样日志并返回 false。
- receiver 构造时 `Map<RsCommonCommandEnum,bool>` 的各项为 false；字段成功处理后相应项才被标 true。这是已收到字段记录，不是硬件能力广告。一个帧返回 true 表示至少一项被成功处理，不是设置操作的显式 ACK。

### 5.3 字段表

字段宽度中“至少”表示 App 只读取前若干字节而忽略后续。默认十进制解析表示将 hex 文本传给 `int.parse` 而不指定 radix，属于原 App 的处理方式；它对正常 00..09 可用，对 0A 等含字母的值抛异常。

| Type | 字段 | V 格式与语义 | App 行为 |
|---|---|---|---|
| 01 | 触控按键 | `(id,action)` byte pairs；至少含所有 01/02/03/04/11/12/13/14 | 缺 id 或长度不是 pair 对齐则整条字段失败；重复 id 后出现者覆盖 |
| 07 | 触控总开关 | 精确 `00` 开，`01` 关 | 其他值不更新 |
| 08 | 佩戴检测开关 | 精确 `00` 开，`01` 关 | 其他值不更新 |
| 09 | 降噪模式 | `01` ANC、`02` 普通、`03` 通透、`04` 抗风、`05` 自动ANC、`06`深度ANC、`07`动态ANC | 映射controller 3/0/1/2/4/5/6；未知值不更新；i7未启用深度ANC UI |
| 0C | 左/右/仓电量 | 至少3 bytes；每byte低7位电量，bit7独立flag | 左/右flag为入仓；仓flag语义尚不明；FF为特殊无效值 |
| 0D | 固件版本 | 至少3 bytes，三个无符号byte | 每byte按hex解析为十进制，然后无分隔符拼接成字符串；额外字节忽略 |
| 0E | 游戏模式 | 精确 `01` 开，`00` 关 | 其他值不更新 |
| 2A | EQ 模式/自定义槽位 | 1byte 或2bytes；第二byte可能为custom slot | 两个格式的00/01映射不同，详见下面 |
| 2B | 音频模式 | 通常1byte，整个hex值按默认十进制parse | 原始整数交audioModeReceived，无范围检查 |
| 2C | ANC等级 | 通常1byte，默认十进制parse | 原值存controller，无加1或1/3/5重映射 |
| 2D | 通透等级 | 通常1byte，默认十进制parse | 原值存controller，无重映射 |
| 2E | 提示音等级 | 通常1byte，默认十进制parse | 原值交dingLevelReceived，无重映射 |
| 2F | 寻机状态 | 精确1..6，详见下面 | 单耳事件只更新对应耳，另一耳沿用旧状态 |
| 32 | 多点连接 | 精确 `01` 开，`00` 关 | 其他值不更新 |
| 36 | ANC切换循环 | 至少4bytes，顺序ANC/抗风/普通/通透 | 等于1表示加入对应mode；handler只追加，未见clear |
| 3E | 自定义EQ增益 | 任意长度byte数组，sign-magnitude | bit7为负号、低7位绝对值；没有小数缩放 |
| 45 | 增益等级 | 精确1byte，范围0..2 | 超范围不更新 |
| 46 | 听力保护 | 精确1byte，范围0..6 | 超范围不更新 |

#### 按键

`01/02/03/04` = 左耳单击/双击/三击/长按，`11/12/13/14` = 右耳同四种操作。pair 可以任意排列，receiver 通过 Map 汇总后按此固定序列输出。

i7 配置的 action 值是：0无功能、1播放暂停、2上一曲、3下一曲、4音量加、5音量减、6游戏模式、7语音助手、8降噪切换。receiver 对 action 数字本身不做范围检查。第三方客户端应保留未知 action，不把未知值强行变成“无功能”。

#### EQ

原 App 的 `_handleEQData` 有两个分支：

```
len(V)==1: 00→controller1, 01→controller0, other→原值
len(V)==2: controller=V[0]，不交换00/01
```

处理2byte且controller=5时，`eqDataReceived` 将 V[1] 作为自定义EQ槽位。i7配置标签的controller值是0POPS/1HIFI/2ROCK/3LIGHT/6EAP，5为custom。因此按 i7 配置显示，单byte00为HIFI、单byte01为POPS；二byte00为POPS、二byte01为HIFI。该差异不应根据TX命令表自动统一，客户端应该保留值长度和raw值。

`3E`调用 `EqData.fromFormat`，依次将每byte解为 `-(b&0x7F)`（bit7=1）或 `b&0x7F`（bit7=0），再转double。它没有解析槽位编号，没有验证必须10个频段，也没有频点表；频点/槽位范围应来自i7配置及sender。0x80为负零，实际decode为0。

#### 电池与仓缓存

receiver 日志明确写左/右最高bit为“入仓L/R”，因此可确定 `left_in_case`、`right_in_case`。仓的bit7只是传入 `RBatteryData.field_27` 的boolean，尚不能独立确定“充电中”。百分比为低7位，FF会得到127，应当保留为无效/未取得。

`RsCommonV2ReceiveHandler.batteryDataReceived` 总是先更新左右入仓状态。左右百分比只有1..100显示，0或>100显示“-”；不要把App显示规则误当成0%必不可能。

仓电量须1..100，且至少一个入仓耳不是FF，才被视为当前有效仓数据：

```
case_pct in1..100 and
 ((left_in_case and raw_left!=FF) or (right_in_case and raw_right!=FF))
```

满足时写仓百分比/flag并保存本地缓存，标示非缓存；不满足时 `_showCachedCaseBattery` 读取历史值而非展示当前帧的仓byte。因此第三方状态最好包含 raw/current/cached/freshness 字段，避免把上次仓电量伪装为刚取得。

#### 寻机

- 01仅将左耳true；02仅将右耳true。
- 03把左右和both三个状态都置true。
- 04把三个状态都置false。
- 05仅左耳false；06仅右耳false。
- 其余值无更新。

这段是事件更新逻辑，不保证每条通知都描述完整双耳状态。例如先03再05后，官方both状态仍为true；客户端可以保留原始事件，并以左右当前状态自行计算“双方均在响”，同时标明这是客户端自己的派生值。

#### 模式05/06/07语义交叉确认

`NoiseType`枚举只有普通/通透/抗风/ANC四项，不能从它推断controller4..6。确认来自对应UI：自动ANC控件比较 `value()==Smi8`，即controller4；深度ANC控件比较 `value()==Smi0xA`，即controller5，并绑定setDeepAnc；动态ANC控件比较 `value()==Smi0xC`，即controller6。故接收05/06/07确为auto/deep/dynamic，与发送代码语义一致。i7配置deep UI缺失不影响共享receiver识别其值。

### 5.9 共享扩展边界

`31` 语言为默认十进制parse值后直接写 `earLanguage`，但i7无语言UI。

`34` 要求12bytes，前6bytes mainMac，后6bytes subMac，以原顺序用冒号连接；没有反转，也不是已确定的left/right。`35`忽略V，调用timeSyncReceived。

`33`空间音效、`37`陀螺仪、`38`空间模式、`39`头部动作、`3A`耳自适应、`3B/3C`拍击控制、`3D`空间音乐模式、`3F`dongle固件、`47`耳塞贴合测试、`48`AI、`49`扩展固件/独立麦克风、`50..79`麦克风系列、`E0/E1/E3/E4/40/42`Opus/星闪SDK、`F0`主从切换等都属于共享receiver的额外分支。没有i7配置或init/query覆盖的项目不可认作i7实际支持。

### 5.10 接收证据索引

完整通知研究与51条证据（`rose_reverse/analysis/i7_receiver_findings.md`） 和 机器字段布局（`rose_reverse/analysis/i7_receiver_layouts.json`） 保留各字段具体函数/地址/长度与原App更新逻辑。主分派：RsCommonV2Receiver.process（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart:739`），拆包：CommandUtil.splitData（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:831`），校验：CommandUtil._isValidPacket（`rose_reverse/analysis/blutter/asm/roselink_bluetooth/commander/sender/command_util.dart:1553`）。所有偏移从0开始，区间为半开；LTV表描述的是V，不含帧头/长度/类型/CS/AA。

## 6. 客户端状态和异常处理

- 序号从0递增并绕回，属于发送封装；RX第二头字节HH仍是opaque。不能仅凭发送有seq就认定它是可关联的业务ACK id。
- `write_complete`与`state_confirmed`分开。设置后读取相应字段，等待实际通知比较值；不能用原App乐观UI状态代替硬件确认。
- 对同字段串行设置/确认，缓存最近回包时间；客户端自行配置查询超时与有限查询重试。100ms仅是写间隔。寻机与OTA不使用普通查询重试策略。
- 电池状态同时保留raw、入仓flag、当前仓数据可用性。历史仓电量可以显示，但必须带cached/freshness标识；bit7仓flag只保留raw，不擅自称为充电中。
- 寻机是局部事件：先双耳启动后仅停左耳，官方both标志不会自动清除。客户端可按左右实际标志重新派生both，需注明这是自己的状态规则。
- 轮换回包应替换当前的4个flag，而不是沿用原Apphandler不断append且未见clear的列表累积行为。
- 21类恢复字段中31语言、34地址、35时间同步等有共享用途说明；未知type、扩展SDK和未启用能力保存raw。查询getInfo含某ID不证明它能启用对应硬件功能。
- 新会话清空未发队列、buffer和所有待确认请求。参考encoder是会话实例，从0开始；不要把文档里seq00的完整样例反复固定发送。

参考实现与原App的明确差异：StreamDecoder使用字节边界、有限buffer和AA候选+LTV/CS验证；L00也检查CS，比原App更严格；截断/非法LTV会拒绝候选整帧，而原App可能已更新较早字段；未知cycle flag保留unknown，原App只把等于1的项加入；FE报告ignored_by_app。参考代码没有自动把历史仓缓存混进当前帧，也没有完整GUI状态机或后台查询超时器。这些实现选择不应伪装成设备要求。

## 7. BES OTA

以下是静态恢复的App状态机和打包，不是实机验证的刷机方案。离线BES编解码模块只构造字节，BLE参考客户端未暴露OTA发送入口。

### 7.1 字节格式

OTA 帧与普通 V2 帧不同，直接送 writeOta，不加普通 V2头、序列/校验：

```text
opcode:u8 | payload_length:u32le | payload
```

下面 `LE32(x)` 与 `crc32` 均可用 Python `(x & 0xffffffff).to_bytes(4, 'little')`、`zlib.crc32(data)`。CRC为共用 `CRC32Lib` 的标准反射 CRC-32/ISO-HDLC（0xEDB88320，init/xorout FFFFFFFF）；BES 输出低字节先。

| App函数 | opcode | payload | 原始帧/说明 |
|---|---:|---|---|
| getOtaProtocolVersion | 99 | 00 00 00 01 | `99 04 00 00 00 00 00 00 01` |
| setOtaUser | 97 | 01 | `97 01 00 00 00 01`；compareOtaDataWithEncodeData在该APK总true |
| getCurrentVersion | 8E | ASCII BEST | `8E 04 00 00 00 42 45 53 54` |
| setUpgradeType | 9D | 01 | `9D 01 00 00 00 01` |
| getRoleSwitchRandomId | 9B | 空 | `9B 00 00 00 00` |
| selectSide | 7B | 00 | `7B 01 00 00 00 00`；不要擅自解释00是左/右/双耳 |
| transmissionBreakPointCheck | 8C | BEST + 32个00 + 01 02 03 04 + CRC32LE(32个00+01 02 03 04) | payload长44，`8C 2C 00 00 00 ...`；随机ID查询值未进入此帧，nonce固定01020304 |
| sendOtaStartPackage | 80 | BEST + LE32(固件字节数) + LE32(CRC32(固件)) | payload长12，`80 0C 00 00 00 ...` |
| sendOtaConfigure | 86 | 见下 | payload长92，`86 5C 00 00 00 ...` |
| getDataPackageCMD数据 | 85 | 每片固件字节 | len4字段以ByteData4构造，低16位写值，另两字节0 |
| getDataPackageCMD分段CRC | 82 | BEST + LE32(CRC32(segment)) | payload长8，`82 08 00 00 00 ...`；没有segment偏移/序号字段 |
| getAllPackageCrc | 88 | 空 | `88 00 00 00 00`；整镜像期标志设true |
| overwriteImage | 92 | BEST | `92 04 00 00 00 42 45 53 54`；防重复覆盖 |

CRC 校验参考：breakpoint 最后4字节可由 `zlib.crc32(bytes(32)+bytes([1,2,3,4]))` 算出。

`sendOtaConfigure` payload逐字节恢复为：

```python
config_without_crc = (
    bytes.fromhex('58 00 00 00')
    + firmware[-4:-1] + b'\x00'
    + bytes(4) + bytes(32) + bytes(32) + bytes(6) + bytes(6)
)
assert len(config_without_crc) == 88
config = config_without_crc + LE32(crc32(config_without_crc))
tx = bytes([0x86]) + LE32(92) + config
```

注意第一组58 00 00 00在payload内（可能配置自身长度，但语义未证明），外层长度92；firmware最后4字节中的前三字节保留，最后1字节强制0。不能概括成取整个finalLE32，也不能根据函数名推断这是版本/签名字段。`firmware` 对应无空白hex文本解码后的完整字节。

证据：

- 直写OTA:111/375，0xac1a20/0xac1ce0（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:111`）
- 99:691，0xac203c（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:691`）
- 97:12，0xac1934（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:12`）
- 8E:7182，0xac68d0（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:7182`）
- 9D/9B/7B:6892，0xac6638/0xac66d0/0xac6798（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:6892`）
- 8C:6558，0xac62b8（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:6558`）
- 80:6281，0xac5fec（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:6281`）
- 86:5624，0xac58b8（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5624`）
- 85分片:3270，0xac3b54；LE长度ByteData4:3385（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:3270`）
- 82:4078，0xac43a8（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4078`）
- 88:5234，0xac532c（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5234`）
- 92:5472，0xac573c（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:5472`）
- CRC实现:163，0xac50fc（`rose_reverse/analysis/blutter/asm/roselink/lib/crc.dart:163`）

### 7.2 传输窗口、分段与ACK

`startOta`拿到完整hex固件并清空偏移/计数/挂起段。记 `N=len(firmware)`；`calculateBLESinglePacketLen` 使用当前 BleConnector.field53 MTU M，常见路径 P=min(508,M-10)，SPP路径P=512；有个特殊分支：当0<N<M-10，原程序直接返回M。实际每85帧最多 P-5 字节固件。若GATT报告 `dataLen: A > max: B`，代码将P缩到B，重试同一未成功片段。

段大小来自N/100整数截断，往上对齐256（**整除时仍多加256**），并设置至少4096：`segment_bytes=max(4096,256*(int(N/100)//256+1))`。最后段截到固件末尾。每片85用 `_sendOtaDataSerial` 等writeOta Future完成后才发下一片，未见额外软件sleep。发完全部片，计算本段CRC，发送82，挂起 `{startHex, consumedHex, crcCommand}`，**等83状态01才移动确认偏移**和发下一段。SPP/ATT写完成不等于83。

`83` status不是01→取消定时器、清空pending、回滚到上次已确认偏移并重发整个段，允许3次重发；第4次NACK失败。83状态01清空所有pending、NACK/retry计数，不存在pending时视重复ACK，不重复推进。

82 ACK定时器5秒（`Duration@db08c1.off8=0x4c4b40`微秒）。到期只重发82分段CRC命令，最多2次；仍未应答终止。计数与NACK重发整段计数分开。

段全部确认后发88进入whole-image CRC期；84状态01→92并本地标记成功，不另等92成功回包。84只在整镜像期消费；某些设备用87状态01给最终成功，App在whole期也支持这个分支。覆盖后重启/重新连接与固件验签仍需设备验证。

- 包大小:1042，0xac23b4（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:1042`）
- 段算法/发送:2664，0xac3500（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:2664`）
- write串行await:4875，0xac4be8（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4875`）
- NACK整段重发:2214，0xac306c（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:2214`）
- ACK计时器:4463，0xac47a0（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4463`）
- 超时/仅82重发:4586，0xac48d0（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:4586`）
- 5秒对象:49140（`rose_reverse/analysis/blutter/objs.txt:49140`）

### 7.3 OTA状态机

`BESOtaPage._startOta`先ensureOtaTransportReady，startOta获取固件并建立订阅，然后依次发99和97；不是先发协商再下载固件。

`process @0xac2720`对raw notification hex H只取 `H[0:2].toLowerCase()` 分类；状态字段均 `H[10:12]`即**raw byte5**。此函数未验证header length也未实现跨通知拼接；接收83/84额外检查H长度>=12；其他分支对短帧不统一保护。

| 回复opcode | 条件/处理 |
|---:|---|
| 98 | byte5==01→8E查询版本；否则终止并错误01 |
| 8F | 不读版本payload；依次发9D、9B、7B、8C |
| 8D | 不读断点payload，发80启动；不能据函数名声称实现设备断点恢复 |
| 81 | 发86配置 |
| 87 | byte5==01且非whole期→下一段；whole期→92+success；失败→终止错误01 |
| 83 | byte5==01且pending存在→确认挂起段+下一段；else NACK策略 |
| 84 | 必须whole期；byte5==01→92+success，else失败01 |
| 9A / 9C / 9E / 7C 等 | 此process未看到专门处理；99/9B/9D/7B的答复不作为下一阶段条件 |

普通内容相同的回复会被去重，83允许重复进入以pending判定，whole期成功84也允许重复进入；一旦已overwrite有防重标志。第三方应采用明确阶段与pending匹配，保留raw未知帧。不能套用控制协议长度、CRC或opcode分类来解析BES。

- BES页面启动:586，0xac16d4（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/view/bes_ota_page.dart:586`）
- startOta获取固件:803，0xac2134（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:803`）
- 接收与状态机:1377，0xac2720（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:1377`）
- ensure通道ready:6，0xac69e0（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_transport.dart:6`）

### 7.4 固件来源、完整性与缺口

BES与i5共用 getOtaContent→DeviceService→ApiDeviceService→StaticResourceClient.getText，保存文本latestData，按hex char数/2计算字节数、CRC和substring，因此输入为无空白纯hex文本，未见本地BES压缩/解密。需要至少4字节用于配置尾部取值。`compareOtaDataWithEncodeData @0xac05a0`仍直接true；encodeData字段不是设备/应用验签已经证明的依据。

元数据共用 `POST /app/ota/v2/get-by-key-id-and-tag`：keyId=`earfeel-i7`（custom内部名本已小写，trackingName继续lowercase）；pid空串；自身目标tag1；clientVersion=`5310`；lang由调用init/checkOtaNewVersion的参数传给fetchDeviceOtaMsg，而非i7 BLE广播。默认base URL是 `https://api-internal.rosetechnic.com`。服务端链接字段为 **`data.otaUrl`**；`ApiClient._processResponse` 先取 `data`，`DeviceService._buildOtaResult @0xacc120` 将其中的 `otaUrl` 重命名为 App 内部的 **`otaContent`**，控制器再保存 URL。这一步不下载固件；之后 `getOtaContent` 才下载 hex 文本。完整固件格式/签名与兼容硬件仍未验证。

2026-10-14 以 `keyId="earfeel-i7"`、`pid=""`、`tag=1`、`clientVersion="5310"` 匿名请求，`lang="zh"` / `"en"` 均成功返回版本 304（资源文件名含 V3.0.4）与不同 `fileId`/URL。响应中的 `data.keyId="115"` 是服务端记录字段，不应替换请求的型号名称。两资源 HEAD 均为 200；`bytes=0-63` Range 均为 206，前 64 字节全部为 ASCII hex。这个采样证明资源可访问与文本前缀形式，不代表已下载/校验完整固件或实际升级。签名 URL 有时效，应在需要时重新请求元数据；当前链接、时效和响应见 [OTA 更新包链接](../ota-package-links.md)、中文响应（`rose_reverse/analysis/ota_links/i7-zh-client5310.json`）、英文响应（`rose_reverse/analysis/ota_links/i7-en-client5310.json`）。

另以 App 构建号 `clientVersion="4150"` 请求仍得到版本 304；`"4149"` 则得到 `version=1`、`versionDescribe="Client Version is too low"` 和空 `otaUrl`。因此这里的构建号门槛不能当成固件历史版本选择器，低版本失败响应中的 `version=1` 也不是已取得的历史升级包。来源：4150 响应（`rose_reverse/analysis/ota_links/i7-zh-client4150.json`）、4149 响应（`rose_reverse/analysis/ota_links/i7-zh-client4149.json`）。

i7配置没有独立LangItem，也没有ota_upgrade_language_switch，BES页没有WQ页的en/zh切换回调。共享Sender具备language方法并不意味着i7固件/UI支持该功能；普通控制语言能力由主任务根据实际模块/命令再界定。

- BES版本检查传deviceLang参数:7678，0xe29e78（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/plan/bes.dart:7678`）
- 共用getOtaContent:1666（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1666`）
- 恒true校验hook:1723（`rose_reverse/analysis/blutter/asm/roselink/modules/device_ota/controller/ota_controller.dart:1723`）
- 元数据API:476/650，0xacc6d4（`rose_reverse/analysis/blutter/asm/roselink/core/api/service/api_device_service.dart:476`）
- 服务端 otaUrl → 内部 otaContent:10882，0xacc5ec（`rose_reverse/analysis/blutter/asm/roselink/core/services/device/device_service.dart:10882`）
- 下载HTTP/UTF8:642（`rose_reverse/analysis/blutter/asm/roselink/core/api/static_resource_client.dart:642`）

待实机核实：SPP连接实际UUID与配对流程、广播多项规范化与BR/EDR地址；固件兼容/签名/尾部3字节含义；BES所有opcode未知回包格式与长度、GATT/流式跨帧拼接；断点/左右耳角色和7B side语义；OTA后复位/重连与电源中断恢复。当前恢复可以复现官方App已观察到的静态打包/状态机，不能声称OTA已实测。

## 8. 参考代码与离线校验

- [JSON协议目录](earfeel-i7-protocol.json)：自定义schema；33个确认适用固定变体、9类参数、21类通知布局、发现/通道、固件门槛和证据。
- 正常控制编解码（`rose_reverse/examples/i7_protocol.py`）：序号、求和、参数、manufacturer识别、RX重组/字段。
- BLE集成参考（`rose_reverse/examples/i7_ble_client.py`）：扫描、订阅、串行队列、初始化、EQ两步设置、设置后补读、MTU小容量下完整查询分组和断连。
- BES离线包构造（`rose_reverse/examples/i7_bes_ota_codec.py`）：80/86/85/82等包、CRC端序、纯hex输入验证；**不含OTA写入或升级会话**。
- 12项离线测试（`rose_reverse/tests/test_i7_protocol.py`）：APK常量逐字节核对、序号回绕、参数帧、两种RX校验、嵌入AA/DD、分包/合包、EQ格式、键映射、freshness、BES标准CRC测试向量和小MTU查询组。

正常控制示例的公开API名称是研究者定义的snake_case；`getInfo`等原App方法名也保留。例子：

```python
from i7_protocol import Encoder
encoder = Encoder()
packet = encoder.encode('set_noise_mode', mode='anc')
packet2 = encoder.encode('set_touch_key', side='left', gesture='double', action='play_pause')
# 使用两个完整命令，自动获取不同seq：
mode_packet = encoder.encode('setEqCustom')
eq_packet = encoder.encode('set_custom_eq', gains=[0,0,-1,0,1,0,0,0,0,0])
```

在准备好的Python环境安装`bleak==3.0.2`后，从项目根目录运行：

```bash
python3 examples/i7_ble_client.py --scan
python3 examples/i7_ble_client.py --device '扫描返回的系统标识' --seconds 30
python3 examples/i7_ble_client.py --device '扫描返回的系统标识' \
  --command '{"name":"set_audio_mode","mode":"ldac"}'
python3 examples/i7_ble_client.py --device '扫描返回的系统标识' \
  --command '{"name":"set_custom_eq","gains":[0,0,-1,0,1,0,0,0,0,0]}'
python3 -m unittest discover -s tests -p test_i7_protocol.py -v
```

BES输入必须是无空白、偶数字符的hex文本。不能把.bin直接当hex字符切片或对下载内容随意去头尾；兼容固件结构/签名和配置尾3bytes的意义尚待样本与实机确认。

Bleak示例使用扫描对象连接、发现的特征对象订阅、显式`response=`；接口依据 [BleakClient官方文档](https://bleak.readthedocs.io/en/latest/api/client.html) 和 [BleakScanner官方文档](https://bleak.readthedocs.io/en/latest/api/scanner.html)。无依赖的编解码、BES包构造和测试无需Bleak。

## 9. 完整度和仍需实机确认的项目

已完成：型号与配置、识别串和广播地址字段、BLE/SPP/OTA通道、发送长度/序号/求和、官方初始化、全部本型号界面控制参数、普通接收定界/校验和核心状态、EQ数值/频段标签/流程、BES传输窗口/段ACK/CRC/重试、固件输入与元数据来源、固件门槛，以及客户端参考与离线校验。

未声称完成实机验证：广播完整原文与平台SPP配对/SDP；每个写入被耳机接纳的返回时序/持久化；实际Codec/多点/LE Audio组合条件；RX HH的设备意义、L00真实使用及碎片边界；仓flag语义与EQ声学滤波参数；BES角色/side、固件兼容/验签、未知回包定界、覆盖后复位/断电恢复。AI供应商SDK内层消息和云业务也不在基础控制协议实现范围。

测试RX样例均为构造的缓冲区，前缀、数值用于验证解析器规则，**不是蓝牙抓包**。本说明区分源码观察、客户端设计与未测设备行为；离线测试通过不能替代耳机实测。
