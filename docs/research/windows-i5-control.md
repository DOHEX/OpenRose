# Windows 本机 i5 控制验收

2026-11-21，[工单 #109](https://github.com/DOHEX/OpenRose/issues/109)。

| 环境 | 结果 |
| --- | --- |
| Linux x64，Temurin 21，`:transportWindows:test` | 平台快照中已配对的 i5 在音频端点可用后触发连接。真实共享控制会话通过模拟 WinRT 子进程读到左耳 100% 和抗风噪，切换通透取得耳机模拟回读后生效。撤下音频端点清除读数，重新接入后一次未获回读的操作返回失败。 |
| Windows 目标编译 | Microsoft 的 `windows` Rust 投影通过 `x86_64-pc-windows-msvc` 类型检查和 Clippy。在 Linux 交叉链接生成了 x64 Windows PE 蓝牙组件。 |
| Windows 实机，关闭多点的 i5 | 本轮没有可用的 Windows 蓝牙电脑。系统音频、实际出声、托盘电量与降噪回读、关窗驻留、断连重连、休眠恢复和控制重试均待验证。 |

平台通过已配对的经典蓝牙设备与活动播放端点的容器 ID 判断音频连接。GATT 服务发现从设备读取，写入经共享控制会话等候耳机确认。平台契约见 [Windows 音频端点容器 ID](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/audio-endpoint-container-id) 和 [Microsoft GATT 客户端说明](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client)。
