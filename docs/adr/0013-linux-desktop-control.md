---
status: accepted
---

# 桌面端 Linux 控制：BLE、BlueZ D-Bus 与音频驱动生命周期

按 [ADR 0002](0002-shared-kotlin-core-and-platform-adapters.md) 的分阶段交付，桌面端先在 Linux 上建立控制连接，Windows 第二阶段另行决策，macOS 不在范围内。首版只实现 BLE：i5 只走 BLE；i7 尚无实机验证，SPP 待 i7 有实机后再做。架构边界见 [ADR 0012](0012-shared-multiplatform-architecture.md)。

## 决策

- **蓝牙栈**：用 dbus-java 5.x 访问 BlueZ D-Bus，使用 bluez-dbus 的接口定义（MIT，纯 Java）。同一套栈覆盖 GATT、已配对设备（`Device1.Paired`）、音频连接观察（`Device1.UUIDs` 与 `MediaTransport1`），以及将来经 `Profile1` 的 RFCOMM。只依赖接口定义，不绑定其高层封装，降低迁移到 dbus-java 6 的成本。
- **生命周期**：遵循 [ADR 0005](0005-miuix-nav-and-audio-bound-control-lifecycle.md)，系统音频连接驱动自动建立控制连接，音频断开时释放，保留手动连接与断开。桌面端没有控制方式与后台控制，关闭窗口即结束控制连接。应用单实例运行，第二次启动只唤起已有窗口，避免两个进程各自持有同一副耳机的控制连接。
  > 关闭窗口与后台控制部分已由 [ADR 0014](0014-desktop-tray-and-background-control.md) 取代：托盘可用且开启后台控制时，关闭窗口只隐藏界面。
- **平台动作**：打开蓝牙设置时探测已知的设置程序，找不到就不提供该入口；应用不开关适配器；BlueZ 缺失、适配器关闭或 D-Bus 拒绝访问，都呈现为“传输不可用”并附原因；Linux 上没有权限请求。
- **打包**：使用 Nucleus Gradle 插件，产出 DEB、RPM、Pacman（均声明依赖 `bluez`）与 AppImage，仅 x64。Flatpak 与 Snap 分别需要访问 `org.bluez` 系统总线和通过 `bluez` 插槽审核，等上架商店时再定。

## 考虑过的方案

- **Kable（JVM，基于 btleplug）**：跨平台，但不能列出已配对设备、不能观察音频连接，JVM 写入上限固定为 20 字节；Linux 上仍需另一套 D-Bus。
- **SimpleBLE**：BUSL-1.1 许可，商业使用需付费，且不能观察音频连接。

## 后果

dbus-java 5.x 处于停止维护前的过渡期，6.x 尚未发布，需要跟进迁移。Windows 选型虽推迟，共享 GATT 层的驱动原语须能由 WinRT（`GattDeviceService`、`WriteClientCharacteristicConfigurationDescriptorAsync`）满足。实机验收由维护者在 i5 上执行。
