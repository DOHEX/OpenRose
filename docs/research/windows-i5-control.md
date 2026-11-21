# Windows 本机 i5 控制验收

2026-11-21，[工单 #109](https://github.com/DOHEX/OpenRose/issues/109)。

| 环境 | 结果 |
| --- | --- |
| Linux x64，Temurin 21，`:transportWindows:test` | 平台快照中已配对的 i5 在音频端点可用后触发连接。真实共享控制会话通过模拟 WinRT 子进程读到左耳 100% 和抗风噪，切换通透取得耳机模拟回读后生效。撤下音频端点清除读数，重新接入后一次未获回读的操作返回失败。 |
| Windows 目标编译 | Microsoft 的 `windows` Rust 投影通过 `x86_64-pc-windows-msvc` 类型检查和 Clippy。在 Linux 交叉链接生成了 x64 Windows PE 蓝牙组件。 |
| Windows 实机，关闭多点的 i5 | 本轮没有可用的 Windows 蓝牙电脑。系统音频、实际出声、托盘电量与降噪回读、关窗驻留、断连重连、休眠恢复和控制重试均待验证。 |

平台通过已配对的经典蓝牙设备与活动播放端点的容器 ID 判断音频连接。GATT 服务发现从设备读取，写入经共享控制会话等候耳机确认。平台契约见 [Windows 音频端点容器 ID](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/audio-endpoint-container-id) 和 [Microsoft GATT 客户端说明](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client)。

## 手机快捷面板控制 Windows 所连 i5

2026-11-21，[工单 #114](https://github.com/DOHEX/OpenRose/issues/114)。两端在 Linux 测试主机上通过回环 TCP 完成流转配对与双向 TLS 认证。持有端运行 Windows 平台事实适配器、GATT 驱动与真实共享控制会话，WinRT 子进程边界使用既有 i5 读数和可控回读。手机侧从快捷面板使用的互联命令入口读取 Windows PC 的身份、左耳 100%、右耳 98%、未知盒电量及抗风噪模式。

切换通透后，Windows 端从 GATT 通知读回通透，随后手机读取到相同结果。音频端点消失时读数撤下，再出现后读取到新会话的初始模式。重连前的请求被拒绝，未获耳机回读的降噪操作返回未确认，Windows 的系统音频连接事实仍在。用例位于 `WindowsSessionTest`，随 `offlineCheck` 运行。

Windows 蓝牙实机验收仍待进行。关窗与休眠唤醒、实际持续出声、默认输出保持，以及手机快捷面板显示与耳机物理状态一致，均需在 Windows 主机、模块控制手机与关闭多点的 i5 上记录。
