---
status: accepted
---

# 桌面端 Linux 控制：BLE、BlueZ D-Bus 与音频驱动生命周期

> 平台范围与 dbus-java 蓝牙栈已由 [ADR 0038](0038-rust-core-and-nsh-desktop.md) 取代：macOS 纳入范围，Linux 驱动改用 Rust（bluer、zbus）。

按 [ADR 0002](0002-shared-kotlin-core-and-platform-adapters.md) 的分阶段交付，桌面端先在 Linux 上建立控制连接，Windows 第二阶段另行决策，macOS 不在范围内。首版只实现 BLE：i5 只走 BLE；i7 尚无实机验证，SPP 待 i7 有实机后再做。架构边界见 [ADR 0012](0012-shared-multiplatform-architecture.md)。

## 决策

- **蓝牙栈**：用 dbus-java 5.x 访问 BlueZ D-Bus，使用 bluez-dbus 的接口定义（MIT，纯 Java）。同一套栈覆盖 GATT、已配对设备（`Device1.Paired`）、音频连接观察（`Device1.UUIDs` 与 `MediaTransport1`），以及将来经 `Profile1` 的 RFCOMM。只依赖接口定义，不绑定其高层封装，降低迁移到 dbus-java 6 的成本。
  - （2026-11 补充）**BlueZ 对象模型**：同一地址被多个适配器见过时，BlueZ 为每个适配器各暴露一个 `Device1`（`BluezBus` 的 `devicePath` 从中挑选）。同一副耳机可能在一个适配器上有经典音频，在另一个上只有控制链路，因此 `BluezAudioProfiles.connectedPaths` 收集所有已上电适配器上的连接路径。
  - （2026-11 补充）**版本差异**：较旧 BlueZ 只暴露 `Device1.Connected`，没有 `org.bluez.Bearer.BREDR1`／`org.bluez.Bearer.LE1`。读不到连接状态不能证明已释放，`hasConnectedBearer` 此时报错，不当作已断开。
  - （2026-11 补充）**拒绝访问**：BlueZ 与总线守护进程用不同的错误表示被拒绝的操作（BlueZ 的 `NotAuthorized`、`NotPermitted`，总线的 `AccessDenied`），`isBluezAccessDenied` 三者都认。
  - （2026-11 补充）**GATT 的关闭与就绪**：`Device1.Disconnect` 会断开所有协议，包括音频，因此 `BluezGattDriver.close` 只用 `StopNotify` 释放本应用的通知租约。BlueZ 重启会销毁旧守护进程上的订阅，即使链路观察已经停止，所以 `releaseSubscription` 只在 BlueZ 的总线所有者未变时调用 `StopNotify`。经典音频保持连接时，`ServicesResolved` 与 GATT 表可能在 LE 链路断开后仍然存在，因此存在 LE bearer 时，`gattServicesReady` 还要求它已连接。
- **生命周期**：遵循 [ADR 0005](0005-miuix-nav-and-audio-bound-control-lifecycle.md)，系统音频连接驱动自动建立控制连接，音频断开时释放，保留手动连接与断开。桌面端没有控制方式与后台控制，关闭窗口即结束控制连接。应用单实例运行，第二次启动只唤起已有窗口，避免两个进程各自持有同一副耳机的控制连接。
  > 关闭窗口与后台控制部分已由 [ADR 0014](0014-desktop-tray-and-background-control.md) 取代：托盘可用且开启后台控制时，关闭窗口只隐藏界面。
  - （2026-11 补充）**初始化期间的降噪准入**：大多数设置要求就绪会话；桌面端 i5 的降噪模式可在初始化期间准入（`ControlState.sessionFor`、`noiseModeSession`）。这个音频会话一报告降噪模式，桌面端就能修改它。准入条件：桌面平台、型号配置为 `I5_ZT`、设备有系统音频连接、连接处于 `INITIALIZING` 阶段且对应同一设备与会话、已确认的降噪模式在可选模式之内。
- **平台动作**：打开蓝牙设置时探测已知的设置程序，找不到就不提供该入口；应用不开关适配器；BlueZ 缺失、适配器关闭或 D-Bus 拒绝访问，都呈现为“传输不可用”并附原因；Linux 上没有权限请求。
- **打包**：使用 Nucleus Gradle 插件，产出 DEB、RPM、Pacman（均声明依赖 `bluez`）与 AppImage，仅 x64。Flatpak 与 Snap 分别需要访问 `org.bluez` 系统总线和通过 `bluez` 插槽审核，等上架商店时再定。

## 考虑过的方案

- **Kable（JVM，基于 btleplug）**：跨平台，但不能列出已配对设备、不能观察音频连接，JVM 写入上限固定为 20 字节；Linux 上仍需另一套 D-Bus。
- **SimpleBLE**：BUSL-1.1 许可，商业使用需付费，且不能观察音频连接。

## 后果

dbus-java 5.x 处于停止维护前的过渡期，6.x 尚未发布，需要跟进迁移。Windows 选型虽推迟，共享 GATT 层的驱动原语须能由 WinRT（`GattDeviceService`、`WriteClientCharacteristicConfigurationDescriptorAsync`）满足。实机验收由维护者在 i5 上执行。
