---
title: 参与开发
description: 从源码构建 OpenRose，以及开发文档、许可与致谢。
---

## 从源码构建

需要 JDK 21 和 Android SDK（Platform 37、Build Tools 37.0.0，设置 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`）。Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
./gradlew :androidApp:assembleDebug   # Android 调试 APK
./gradlew :desktopApp:run             # 运行桌面版
./gradlew offlineCheck                # 不依赖耳机硬件的全部检查
```

提 PR 前必须通过 `./gradlew offlineCheck`。开发环境、模块结构、新增型号、界面沙盒和提交规范见 [CONTRIBUTING.md](https://github.com/DOHEX/OpenRose/blob/main/CONTRIBUTING.md)。

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
- [LSPosed](https://github.com/LSPosed/LSPosed)：运行系统集成模块的 Xposed 框架
- [libxposed API](https://github.com/libxposed/api)：模块使用的 Xposed API
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)：跨平台界面框架
