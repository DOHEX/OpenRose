---
status: accepted
---

# 桌面安装包与 Windows 辅助程序

桌面应用由 `desktopApp` 经 Nucleus 打成 deb、rpm、pacman、AppImage 与 MSI。Windows 的蓝牙读数与控制由 Rust 编写的 WinRT 辅助程序 `openrose-winrt.exe` 完成（`platform/windows`），JVM 进程以子进程方式启动它。Linux 的 BlueZ 控制依赖 dbus-java（[ADR 0013](0013-linux-desktop-control.md)）。打包配置中有几处取值不能从代码看出原因，在此记录。

## 决策

- **辅助程序随应用资源安装**：`platform/windows` 以 `winrtHelper` 可消费配置发布 `openrose-winrt.exe`（只在 Windows 上由 `buildWinrt` 调用 `cargo build --release --locked` 构建）。`desktopApp` 的 `prepareOpenRoseAppResources`（`appResources`）把它放进应用资源的 `windows` 子目录；Nucleus 把该子目录复制进安装后的应用资源目录，并通过系统属性 `compose.application.resources.dir` 把路径传给 `desktop run` 和安装包。`WindowsHelper.kt` 的 `executable` 从这个目录取辅助程序，因此桌面运行与 MSI 安装都从应用旁边启动它，不再每次启动时解压到 `%TEMP%`（67084e8）。
- **dbus-java 的日志**：dbus-java 只通过 `slf4j-api` 记录日志，`desktopApp` 以 `runtimeOnly` 引入 `slf4j-simple`，`simplelogger.properties` 把默认级别设为 `warn`，只保留其警告与错误，输出到 stderr（9e0e424）。
- **打包的 JDK 模块**：`nativeDistributions` 的 `modules(...)` 包含 `jdk.security.auth`，因为 dbus-java 通过 `com.sun.security.auth.module.UnixSystem` 认证 Unix 套接字。
- **Linux 包依赖写全**：`debDepends`、`rpmRequires`（以及 `pacmanDepends`）是自定义列表，会整体替换 electron-builder 的默认依赖，因此除 `bluez`、PulseAudio 工具外，还要列出 JVM 与 GTK 运行所需的库（GTK 3、EGL/GL、fontconfig；`libsystemd` 见 [ADR 0014](0014-desktop-tray-and-background-control.md)）。
- **Linux 桌面入口名称**：`linux { packageName = "openrose" }` 保持小写包名与 `/opt/openrose` 安装路径；Nucleus 在 Linux 上会把 `executableName`（即 `linux.packageName`）传给 electron-builder 作为 `productName`，导致 `.desktop` 默认生成 `Name=openrose`。通过 `linux { appImage { desktopEntries = mapOf("Name" to "OpenRose") } }` 覆写 `linux.desktop.entry`（对 deb、rpm、pacman 与 AppImage 均生效），使桌面启动器与任务栏显示 `OpenRose`。
- **MSI 升级代码永久固定**：`windows { upgradeUuid }` 取 `07a3acd9-577c-4b4e-9e8e-dbca1d107e25`，此后不再更改。MSI 只有在新旧安装包带有同一升级代码时，才会替换旧安装。
- **按用户安装**：`msi { perMachine = false }`，安装到 `%LOCALAPPDATA%\Programs`，不弹 UAC，升级也从不需要提权；同时不创建桌面快捷方式。

## 考虑过的方案

- **每次启动把辅助程序解压到 `%TEMP%`**：67084e8 之前的做法。改为随应用资源安装后，CI 静默安装、卸载 MSI，并检查辅助程序落在应用旁边。

## 后果

更换 `upgradeUuid` 会让新 MSI 与已有安装并存而不是升级，因此它与包名一样视为永久标识。Linux 包依赖列表要随 JVM、GTK 与托盘原生库的实际依赖同步维护，漏写的库不会由 electron-builder 补上。辅助程序只能在 Windows 主机上构建；其他主机上 `winrtHelper` 没有产物，应用资源中不含 `windows` 子目录。
