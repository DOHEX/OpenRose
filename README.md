# OpenRose

[![CI](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml/badge.svg)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/DOHEX/OpenRose?include_prereleases)](https://github.com/DOHEX/OpenRose/releases)
[![License](https://img.shields.io/github/license/DOHEX/OpenRose)](LICENSE)

**简体中文** | [English](README.en.md)

OpenRose 是 ROSE / ROSESELSA（弱水时砂）耳机的第三方控制客户端，可以替代官方 RoseLink 完成日常设置。在 HyperOS 上，它还能把耳机接入超级岛、焦点通知和融合设备中心。界面提供 Miuix 和 Material 3 两种风格，支持 Android，以及 Linux 和 Windows 桌面（桌面版见[平台支持](#平台支持)）。

> [!WARNING]
> 项目仍在开发中，大部分功能尚未在真机上完整验证，详见[支持的耳机](#支持的耳机)。遇到问题欢迎[反馈](#反馈)。

## 支持的耳机

| 耳机 | 连接 | 状态 |
| --- | --- | --- |
| EarFree i5（EarFeel i5） | BLE | 真机读取已验证，Android 上的设置写入未验证 |
| EarFeel i7 | BLE 或 SPP | 仅有离线测试，未经真机验证 |
| BudsFeel MK2 | BLE 或 SPP | 未经真机验证 |
| OpenFeel MK2 | BLE 或 SPP | 未经真机验证 |
| 踏浪 Swift 2 | BLE 或 SPP | 未经真机验证 |
| Jasmine | BLE 或 SPP | 未经真机验证 |
| EarFree i3 | BLE | 未经真机验证 |
| HeadFeel（HeadFree） | BLE 或 SPP | 单体电量；未经真机验证 |
| AGATE、AGATE X、CERAMICS | BLE | 未经真机验证 |
| CERAMICS-X | BLE | 未经真机验证 |
| CERAMICS-X 7034、EXPLORER | BLE 或 SPP | 未经真机验证 |
| BudsFree i、SWIFT | BLE | 未经真机验证 |
| Cambrian、OpenFun、SportFeel（SportFree） | BLE 或 SPP | 未经真机验证 |
| OpenFeel（OpenFree，LDAC / LHDC / 双编码分支） | BLE | 未经真机验证 |

同名硬件分支通过广播前缀识别，名称不足以确定分支时不自动选择。其余型号尚未适配。各型号的适配依据与未实现的能力见[型号适配证据账本](docs/protocols/model-adaptation-ledger.md)，完整协议研究见 [docs/protocols/](docs/protocols/)。

## 功能

**耳机控制**（可用项随型号和固件版本变化）

- 查看左右耳、充电盒电量和固件版本；切换降噪模式，分别调节降噪与通透档位
- 游戏模式、预设 EQ、触控按键绑定、佩戴检测、提示音音量、多点连接与音频编码偏好
- 部分型号提供自定义 EQ、增益与听力保护、低音增强

**外观**

- Miuix / Material 3 Expressive 两种界面风格，切换时不会断开耳机
- 浅色 / 深色、标准或悬浮底栏、背景模糊

**后台控制**（无需 root，见[后台控制](#后台控制)）

- 关闭界面后保持控制连接，并显示运行通知
- 应用进程被回收或手机重启后，在耳机再次连接时自动恢复

**HyperOS 系统集成**（需要 Xposed 框架，见[HyperOS 系统集成](#hyperos-系统集成)）

- 由系统超级岛和焦点通知呈现连接提示与耳机状态
- 融合设备中心显示耳机电量、切换降噪模式，“附加设置”可直接跳转到 OpenRose
- 模块控制：由系统蓝牙进程连接耳机，无需打开 OpenRose

## 安装与使用

要求 Android 8.0（API 26）及以上。

1. 从 [Releases](https://github.com/DOHEX/OpenRose/releases) 下载 APK；开发版可在 [GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) 的构建产物中下载（需登录 GitHub）。
2. 先在**系统蓝牙设置**里配对并连接耳机，OpenRose 不负责音频配对。
3. 打开 OpenRose 并授予蓝牙权限（Android 8–11 还需要定位权限，并打开系统定位服务）。已配对的耳机出现在“我的耳机”页，耳机连上系统音频后会自动建立控制连接；新耳机点右上角的 **+** 添加。

OpenRose 与耳机之间的**控制连接**，和系统用来听歌、通话的**音频连接**是两回事。应用里的“断开控制连接”只断开 OpenRose 自己，不影响播放。

## 后台控制

“设置”页的“后台控制”开关默认关闭。开启后，关闭界面或划掉任务后控制连接仍然保留，通知栏有一条运行通知，可以直接从通知里停用。

- Android 8–11：进程被回收或重启手机后，无需额外设置，耳机连上音频时会自动唤起 OpenRose。
- Android 12 及以上：需要在“设置”页点“关联耳机”；Android 14 及以上还需要已授予蓝牙权限。
- HyperOS 等系统需要手动允许 OpenRose 自启动，并取消电量限制；OpenRose 无法检测这两项。
- 在系统设置里“强行停止” OpenRose 会结束后台控制，不会自行恢复。

## HyperOS 系统集成

**前提条件**

- HyperOS 3 或 4
- 支持 libxposed API 102 的框架（例如 LSPosed 2.2.0 或更新版本）
- 在框架中启用 OpenRose 模块，作用域为 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`

启用后，在 OpenRose 的“状态”页查看模块是否就绪。

“设置”页可选择控制方式：默认的普通控制由 OpenRose 应用自己连接耳机；模块控制由系统蓝牙进程连接，开机解锁后即可使用，不需要打开 OpenRose。

**已知限制**

- 系统只有“关闭 / 降噪 / 通透”三种模式。耳机处于抗风噪时，融合设备中心显示为“关闭”，实际模式不变；可在“设置”页开启“关闭降噪使用抗风噪”，让点“关闭”时写入抗风噪而不是标准模式。
- 耳机电量已知但充电状态未知时，融合设备中心不显示该电量；充电盒电量也不显示。

## 平台支持

| 平台 | 状态 |
| --- | --- |
| Android 8.0+ | BLE 与 SPP（i7）控制已实现；后台控制只有离线测试 |
| HyperOS 3 / 4 | 附加设置跳转已在 i5 + 小米 14 上验证，其余链路只有离线测试 |
| Linux x64 | 已实现 BLE 控制，桌面端 i5 的完整真机验收待完成 |
| Windows | 界面和偏好设置可用，蓝牙控制尚未实现 |

Linux 版通过系统 BlueZ 和 D-Bus 提供 BLE 控制，不含 SPP、OTA 和系统集成。托盘图标需要 `libsystemd`，GNOME 还需启用 AppIndicator 扩展（GNOME 与 KDE 上的托盘未经实机验证）。托盘可用时，关闭窗口只隐藏界面、保留控制连接，从托盘“退出”才结束；托盘不可用时，关闭窗口即退出。

niri、Hyprland、Sway 等桌面上，托盘面板是由合成器摆放的普通窗口，可以用窗口规则让它浮动到托盘附近，例如 niri：

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```

## 从源码构建

需要 JDK 21 和 Android SDK（Platform 37、Build Tools 37.0.0，设置 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`）。Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。开发环境、各项检查与提交规范见 [CONTRIBUTING.md](CONTRIBUTING.md)。

```bash
./gradlew :androidApp:assembleDebug   # Android 调试 APK
./gradlew :desktopApp:run             # 运行桌面版
./gradlew offlineCheck                # 不依赖耳机硬件的全部检查
```

## 反馈

问题和需求请提交到 [GitHub Issues](https://github.com/DOHEX/OpenRose/issues)，并附上：

- 耳机型号和固件版本
- 手机型号和系统版本
- OpenRose 版本（见“设置”→“关于”）
- 复现步骤
- 涉及系统集成时，Xposed 框架版本和“状态”页进入诊断后显示的桥接状态

参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)。文档：[协议实现说明](docs/protocols.md)、[协议研究](docs/protocols/)、[架构决策记录](docs/adr/)、[术语表](GLOSSARY.md)。

## 许可

OpenRose 以 [GPL-3.0-or-later](LICENSE) 授权。

## 致谢

- [OppoPods](https://github.com/1812z/OppoPods) — HyperOS 系统集成参考了它的实现思路
- [Miuix](https://github.com/compose-miuix-ui/miuix) — Miuix 界面风格使用的组件库
- [LSPosed](https://github.com/LSPosed/LSPosed) — 运行系统集成模块的 Xposed 框架
- [libxposed API](https://github.com/libxposed/api) — 模块使用的 Xposed API
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — 跨平台界面框架
