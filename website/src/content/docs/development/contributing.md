---
title: 参与开发
description: 从源码构建 OpenRose，以及开发文档、许可与致谢。
---

## 从源码构建

需要 JDK 21 和 Android SDK（Platform 37、Build Tools 37.0.0，设置 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`）。构建 Windows 桌面版还需 Rust stable（MSVC 工具链）和 Visual Studio C++ Build Tools。Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
./gradlew :androidApp:assembleDebug   # Android 调试 APK
./gradlew :desktopApp:run             # 运行桌面版
./gradlew offlineCheck                # 不依赖耳机硬件的全部检查
```

提 PR 前必须通过 `./gradlew offlineCheck`。其中 Spotless 检查格式，detekt 和 Android lint 直接检查源码，使用零基线。开发环境、模块结构、新增型号、界面沙盒和提交规范见 [CONTRIBUTING.md](https://github.com/DOHEX/OpenRose/blob/main/CONTRIBUTING.md)。

运行 `./gradlew :tools:ui-sandbox:run` 可检查设置何时可用。选择「初始化中」时，沙盒模拟耳机尚未返回读数，普通设置要等控制连接就绪后才能写入。沙盒按提交顺序写入设置。以 FirstAngel 为例，游戏模式切换成功后，排在后面的 EQ 请求会按新的预设列表校验。把控制台拨回「初始化中」可以模拟重连，之前的设置结果只记入旧会话。

## 开发文档

| 文档 | 内容 |
| --- | --- |
| [协议实现说明](https://github.com/DOHEX/OpenRose/blob/main/docs/protocols.md) | OpenRose 如何实现各协议族 |
| [协议研究](https://github.com/DOHEX/OpenRose/tree/main/docs/protocols) | 各型号与协议族的研究资料 |
| [型号适配证据账本](https://github.com/DOHEX/OpenRose/blob/main/docs/protocols/model-adaptation-ledger.md) | 每个型号的适配依据与未实现的能力 |
| [架构决策记录](https://github.com/DOHEX/OpenRose/tree/main/docs/adr) | 架构与交互上的关键决定 |
| [术语表](https://github.com/DOHEX/OpenRose/blob/main/GLOSSARY.md) | 代码、文档和界面共用的术语 |

## 许可

OpenRose 以 [GPL-3.0-or-later](https://github.com/DOHEX/OpenRose/blob/main/LICENSE) 授权。

## 致谢

- [OppoPods](https://github.com/1812z/OppoPods)：HyperOS 系统集成参考了它的实现思路
- [Miuix](https://github.com/compose-miuix-ui/miuix)：Miuix 界面风格使用的组件库
- [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：Miuix 液态玻璃底栏使用的效果库
- [LSPosed](https://github.com/LSPosed/LSPosed)：运行系统集成模块的 Xposed 框架
- [libxposed API](https://github.com/libxposed/api)：模块使用的 Xposed API
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)：跨平台界面框架
