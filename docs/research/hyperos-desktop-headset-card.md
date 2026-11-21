# 电脑持有 i5 时，手机的融合设备中心显示什么

2026-11-21。小米 14 运行 HyperOS `OS4.0.0.27.XNCCNXM`（Android 17），系统互联服务 `com.milink.service` 版本为 `18.2.2.0.2609141543`。同一副 ROSE EarFree i5 已分别与手机和 Linux 桌面配对。用系统蓝牙设置切换音源，观察手机真实的控制中心与融合设备中心。此次没有 Windows 或第二台澎湃手机参与，也没有运行尚待实现的 OpenRose 局域网互联。

| 状态 | 音源证据 | 手机融合设备中心 |
| --- | --- | --- |
| 手机持有，i5 未置顶 | Android 报告 i5 为活动耳机，Linux 报告未连接 | 控制中心有 i5 入口，「可用设备」有 i5 卡片。点开可见左右耳各 99%、盒电量未知、音量、通透／降噪／关闭和「更多设置」。 |
| Linux 持有，i5 未置顶 | BlueZ 报告经典蓝牙已连接；向 i5 定向发送静音测试流时，PipeWire 输出为 `RUNNING`。手机经典蓝牙和 LE 链路均已断开 | 控制中心快捷入口和「可用设备」中的 i5 均消失。 |
| Linux 持有，i5 已置顶 | 重复手机断开、Linux 接入，BlueZ 再次确认连接 | i5 名称保留在「置顶设备」，从「可用设备」消失。点开实际呈现 `Device disconnected`，提示确保耳机经蓝牙连接本机并在使用。此页没有降噪或「更多设置」入口。 |
| 接回手机 | Android 恢复 i5 活动音频连接，Linux 断开 | i5 回到「可用设备」。实验结束恢复原先未置顶状态。 |

该系统包里存在独立的远端耳机模式操作。从该已安装版本的指令调用关系可以追到远端模式更新进入耳机会话，并由当前音源调用降噪设置。包内也有表达耳机所连接音源的卡片资源。这为受支持的生态设备之间远程调降噪提供了实现依据。它没有证明普通桌面软件能将自己持有的耳机发布进该中心，也没有验证任何特定两台小米设备之间的一次实际操作。系统包 SHA-256 为 `0dd14e0ccd0448e50536efd148605bbb084e9575f6fd06553e040395de2f98d3`。REA 包版本记录为 `ev_88c0689b5b3aa70af6c23ec001a4c1fd9ce98e24d4ab5d56e1510a623e056815`，安装包快照为 `ev_198242871919e7053f624e3c33416312eec592d8184be2aa075247fb7b95acdc`。界面和蓝牙状态来自 ADB、BlueZ 与 PipeWire 实时观测。

小米列出了支持融合设备中心的部分电脑。小米电脑管家说明了在电脑上连接和控制耳机，面向其他 Windows 电脑的互联说明列出文件互传、电脑操作手机等能力。在本次查阅的公开说明中，没有找到让普通 Windows／Linux 应用发布「这副耳机正由我使用」并承接手机卡片操作的公开接口。[融合设备中心](https://os.mi.com/continuity/abilities/ab0000) · [小米电脑管家](https://privacy.mi.com/PCManager/zh_CN/) · [Windows 互联](https://os.mi.com/continuity/abilities/ab0019)

## 耳机型号与卡片上的操作

OpenRose 当前向 MiLink 统一上报兼容型号 ID `01010607`。小米官方蓝牙资源包内的 `resource_record.xml` 将它列为 Xiaomi Buds 3 Pro。小米 14 安装的蓝牙扩展也将它归入 K73 型号组。资源表另列 Redmi Buds 3 Pro（`01010602`）和 Xiaomi Buds 4 Pro（`01011104`）等型号；本次仅核对这些记录，没有把 i5 切换到其他型号做对照实验。[OpenRose 兼容型号](../../androidIntegration/src/commonMain/kotlin/io/github/dohex/openrose/androidintegration/wire/NativeHeadsetSnapshot.kt) · [小米蓝牙资源包（ZIP）](https://cdn.cnbj1.fds.api.mi-img.com/miuibluetooth/fast_connect/00000000.zip)

本机 MiLink 安装包的耳机卡片资源含降噪、空间音频和响铃查找。本次检索未发现独立的游戏或低延迟开关。REDMI Buds 6 的官方说明将游戏低延迟入口放在部分手机的蓝牙设置中。第三方模块 HyperEars 为 FitBuds Turbo 在保留降噪区的同时另加了游戏模式开关；原贡献者版本有实测，维护版布局待复核。这个先例能说明卡片可以按真实耳机能力扩展，不能证明仅更换型号 ID 就会出现游戏开关。[REDMI Buds 6 常见问题，第 32 问](https://www.mi.com/tw/support/faq/details/KA-512307/) · [HyperEars 的 FitBuds Turbo 卡片](https://github.com/silverpoetry/HyperEars/blob/91d6804af40fa749bb80608a30b5bd9369a96cee/system-module/src/main/java/dev/hyperears/hook/EdifierFitBudsTurboMiLinkCardAdapter.kt)

维护者报告可以在小米手环的融合设备中心调节手机所连耳机的降噪。本次 ADB 观测只覆盖手机屏幕。小米手环 10 NFC 的产品页也明确列出腕上查看耳机电量、调节音量和切换降噪，并附有兼容耳机清单。腕上能否控制由 OpenRose 电脑端持有的 i5，仍需独立实测。[小米手环 10 NFC 产品说明](https://www.mi.com/tw/product/xiaomi-smart-band-10/)

## 小米电脑管家和手环 9 NFC 的补充核对

在同一台小米 14 上重新确认：i5 连手机时原生卡片显示降噪和通透。将 i5 切到 Linux 后，BlueZ 确认 A2DP 已连接，手机与 i5 的经典蓝牙、LE 链路均已断开，手环 9 NFC 仍连接手机。此时手机「可用设备」中没有 i5，维护者打开手环的融合设备中心也找不到 i5，因此没有发出腕端降噪操作。随后 i5 已接回手机，原生卡片恢复。此项实测仅覆盖普通 Linux 电脑。

手机端已安装的 MiLink `18.2.2.0.2609141543` 在其耳机运行时注册两个远端音源发现器。MiuiPlus 分支接受 Android 手机、平板，MiPlay 分支接受支持耳机控制的 Android 电视。这里没有将 PC 注册为持有耳机的音源。运动健康 `3.59.1` 会根据融合设备服务构建腕端的耳机列表，其耳机控制处理程序能向已发现的耳机服务提交降噪变更。这些证据说明了已经有卡片时腕端怎样控制耳机，仍需另外证明 PC 所持 i5 会进入该列表。运动健康安装包 SHA-256 为 `46100649d47000e8886d94cfbb34c18706e492a5d3aaa7f34cd4785841ffc254`，REA 取包记录为 `ev_a5ec555e1ff30c4431bbcfc3913ba62418c06834affcf781174766de8f441aea`。

### Windows 安装包中的耳机能力

在小米官方电脑管家 `5.8.1.130` 中，`MiSmartShareDLL.dll` 接收跨设备耳机消息并分派给 `libMiPods.dll`。耳机成为活动设备时，后者会主动上报名称、标识和左右耳及充电盒电量，也能受理远端的模式变更请求。模式发生变化且耳机服务就绪时，代码会通过小米 MMA 控制链路向实际耳机发送设置。电脑管家界面及其包装层分别处理降噪、通透、关闭。以上来自安装包静态调用关系；本次没有 Windows 真机的收包或耳机读回记录。[小米电脑管家官方帮助](https://pc.mi.com/pc-manager-help) · [官方 5.8.1.130 安装包](https://cdn.cnbj1.fds.api.mi-img.com/ota-packages/TS4L_XiaomiPCManager_feature_p52_5.8.1.130_b5a04836.exe)

内置耳机身份表有 101 条记录、33 个不同的显示名称，没有匹配 ROSE、EarFree 或 EarFeel。另一张表将降噪、通透和两类流转分别配置：

| 电脑管家中的型号 | 降噪／通透 | `paired_flow`／`unpaired_flow` |
|---|---|---|
| Xiaomi Buds 3 Pro（K73，含 `01010607`） | 开／开 | 关／关 |
| Redmi Buds 5 Pro（N76） | 开／开 | 开／开 |
| ROSE EarFree i5 | 未找到型号记录 | 未找到型号记录 |

OpenRose 手机上报的兼容 ID 不改变真实 i5 在电脑上的型号和协议。OpenRose 对 i5 的原生控制使用 ROSE ZT 协议。本次还核对到电脑管家读写云端耳机记录的调用，这说明云同步参与设备记录管理；每次点按降噪经过哪条网络路径仍未知。[i5 型号配置](../../core/src/commonMain/kotlin/io/github/dohex/openrose/core/models/EarFreeI5.kt) · [小米智能蓝牙服务](https://developers.xiaoai.mi.com/voiceservice/Bluetooth/index)

复查了两份 6.x 原厂发布物。`6.0.0.39` 来自 REDMI Book 16 2027 机型驱动包，与 `5.8.1.130` 的型号和能力表逐字段一致。`6.0.2.30` 的底层耳机库保留活动属性上报和远端模式处理函数，表中仍有 101 条身份记录、31 条能力配置。两个 6.x 版本都能定位电脑本机界面的降噪写入；由于外层互联组件经过重构，本次只在 5.8.1.130 追通了从收到跨设备消息到耳机写入的完整静态调用链。三个版本都没有 i5 型号记录，K73 和 N76 在上表中的四项取值也一致。[REDMI Book 16 2027 驱动](https://www.mi.com/service/notebook/drivers/TM2429) · [6.0.2.30 原厂安装包](https://cdn.cnbj1.fds.api.mi-img.com/ota-packages/ZOj6_XiaomiPCManager_hotfix_19_6.0.2.30_acb3c61c.exe)

小米官方的 HyperOS 4 切换示意图里，同屏可见 REDMI Book Pro 16，但耳机的连接目标菜单只列出了手机、平板和电视。能看到笔记本与能控制笔记本所连的 i5 需要分别验证。[小米官方切换示意图](https://cia.hyperos.mi.com/connect-os3/os4-earbuds-manual-switch.png)

复核材料：5.8.1.130 整包 SHA-256 为 `d812cf30805d172474eb33b2f97f37129593f9d50f09d4224f73d530a2cb40e9`，`libMiPods.dll` SHA-256 为 `eb2994ca44d6680e4e941e0d09d3ea7476723a05ba6c0d96777fe692d6f19ce2`。REA 的耳机组件和控制中心组件记录分别为 `ev_4aae181bd35203cab7ac84f59e1d290fee7ff5709a46fe0298d520d4d7c5bc3a`、`ev_c89079df3b9dd3ae9de23fecd6110b390b499a2ac4748d4a3f8995adbe73a28f`。

区分版本时可核对 Windows 可执行安装包的 SHA-256：`6.0.0.39` 为 `6bb93f05e7029026c53149847f06f01aacee54feaa9582a83685fc777d7d99dd`，`6.0.2.30` 为 `6a9c1b3cbffe3dd93e65285764d503956fbd5c62807637c5765b6bba4a16b9cc`。本次在 Linux 上静态检查这些发布物。要补上 Windows 实机证据，可在同一台受支持的小米笔记本上先用 N76 作对照，再换 i5，分别核对手机和手环的卡片、耳机的模式回读及电脑的持续播放。
