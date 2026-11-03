# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](composeApp/build.gradle.kts)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#安装与使用)
[![Status: In development](https://img.shields.io/badge/status-in%20development-orange)](#支持的耳机)

**简体中文** | [English](README.en.md)

OpenRose 是 ROSE / ROSESELSA（弱水时砂）耳机的第三方控制客户端，可以替代官方 RoseLink 完成日常设置。在 HyperOS 上，它还能把耳机接入超级岛、焦点通知和融合设备中心，体验接近小米自家耳机。

项目基于 Kotlin Multiplatform 与 Compose Multiplatform，提供 Miuix 和 Material 3 两套界面风格。Android 是主力平台；Windows / Linux 桌面版目前只有界面，还不能控制耳机。

> [!WARNING]
> 项目仍在开发中，大部分功能尚未在真机上完整验证，详见下方的[支持的耳机](#支持的耳机)。遇到问题欢迎[反馈](#反馈与参与)。

## 支持的耳机

| 耳机 | 协议 / 控制通道 | 验证情况 |
| --- | --- | --- |
| EarFree i5（EarFeel i5） | ZT / BLE | 已在真机上读取状态；设置写入尚未在 Android 上验证 |
| EarFeel i7 | RS Common V2 / BLE 或 SPP | 协议实现与离线测试完成，尚未接触真机 |

同一零售名称下可能有不同的硬件版本，OpenRose 目前只实现了上面两种型号配置。其他型号的协议研究（BudsFeel MK2、OpenFeel MK2、踏浪 Swift 2、Jasmine 等，共 43 款型号的能力清单）见 [docs/protocols/](docs/protocols/)，但客户端还没有接入。

## 功能

**耳机控制**（可用项随型号和固件版本变化）

- 查看左右耳、充电盒电量和固件版本
- 切换降噪模式，分别调节降噪档位和通透档位；设置触控时参与轮换的降噪模式
- 游戏模式、预设 EQ、触控按键绑定、触控开关、佩戴检测开关、提示音音量
- 音频编码偏好与多点连接（i5 上这两项是联动的）
- i7 独有：自定义十段 EQ；固件满足条件时可调增益和听力保护

**外观**

- Miuix / Material 3 Expressive 两套界面风格，切换时不会断开耳机
- 浅色 / 深色、标准或悬浮底栏、背景模糊、页面转场动效

**HyperOS 系统集成**（需要 Xposed 框架，见[下文](#hyperos-系统集成)）

- 连接提示和耳机状态通知，由系统超级岛 / 焦点通知呈现
- 融合设备中心显示耳机与充电盒电量，并可切换降噪模式
- 融合设备中心的“附加设置”可直接跳转到 OpenRose

## 安装与使用

系统要求：Android 8.0（API 26）及以上。

1. 从 [Releases](https://github.com/DOHEX/OpenRose/releases) 下载正式版 APK；想尝鲜可以在 [GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) 的构建产物里下载开发版（需登录 GitHub）。
2. 先在**系统蓝牙设置**里配对并连接耳机。OpenRose 不负责音频配对。
3. 打开 OpenRose，授予蓝牙权限。Android 8–11 扫描蓝牙需要定位权限，并且要打开系统定位服务。
4. 已配对的耳机会出现在“我的耳机”页。耳机连上系统音频后，OpenRose 会自动建立控制连接；也可以手动点“连接”。新耳机通过右上角的 **+** 进入“添加耳机”搜索。

OpenRose 与耳机之间的**控制连接**，和系统用来听歌、通话的**音频连接**是两回事。应用里的“断开控制连接”只断开 OpenRose 自己，不影响播放。

## HyperOS 系统集成

只用蓝牙控制的话，这部分可以跳过。

**前提条件**

- HyperOS 3 或 4
- 支持 libxposed API 102 的框架（例如 LSPosed 2.2.0 或更新版本）
- 在框架中启用 OpenRose 模块，作用域为 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`

启用后，在 OpenRose 的“系统集成”页查看模块和系统桥接是否就绪。

**设置项**（位于“设置”页）

| 设置 | 选项 | 说明 |
| --- | --- | --- |
| 超级岛 | 不显示 / 官方 / 模块 | 官方：沿用小米耳机的连接提示；模块：使用 OpenRose 的样式和触发时机。两者都由系统超级岛渲染 |
| 焦点通知 | 开 / 关 | 连接期间在通知栏保留一条耳机状态通知 |
| 附加设置入口 | 系统详情页 / OpenRose | 选 OpenRose 后，融合设备中心卡片上的 Additional Settings 会打开 OpenRose 并连接对应耳机 |

**已知限制**

- 系统只有“关闭 / 降噪 / 通透”三种模式。耳机处于抗风噪时，融合设备中心显示为“关闭”，实际模式不变，可以在 OpenRose 里看到。
- 耳机没有上报充电状态时，系统按“未充电”显示。
- “系统集成”页右上角可以通过 Root 重启三个作用域进程，让模块重新加载。重启时蓝牙可能短暂断开；未运行的进程不会被拉起。显示“已执行”只代表命令已发出。

**验证情况**：融合设备中心的附加设置跳转已在 i5 + 小米 14（OS4.0.0.27.XNCCNXM、MiLink 18.2.2.0.2609141543、LSPosed 2.2.0-it / API 102）上验证。超级岛、焦点通知等其余链路，以及 i7 和 HyperOS 3，都还没有完整的真机测试。

## 平台支持

| 平台 | 状态 |
| --- | --- |
| Android 8.0+ | 蓝牙扫描、BLE 控制、i7 的 SPP 控制均已实现 |
| HyperOS 3 / 4 | 系统集成代码已完成，待真机验证 |
| Windows / Linux | 界面和偏好设置可用；蓝牙控制尚未实现 |

## 从源码构建

**环境要求**

- JDK 21（`.mise.toml` 已指定 Temurin 版本，可用 [mise](https://mise.jdx.dev/) 安装）
- Android SDK Platform 37、Build Tools 37.0.0
- 设置 `ANDROID_HOME`，或在 `local.properties` 中写入 `sdk.dir=/path/to/android-sdk`
- Linux 运行桌面版还需要 GTK 3、EGL / OpenGL 和 fontconfig，安装命令可参考 [CI 配置](.github/workflows/ci.yml)

仓库自带 Gradle Wrapper。Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
```

构建 Android APK，产物在 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`：

```bash
./gradlew :androidApp:assembleDebug
```

运行桌面版：

```bash
./gradlew :desktopApp:run
```

运行不依赖耳机硬件的测试（协议、控制会话、共享界面测试，以及 Android / 桌面编译检查）：

```bash
./gradlew offlineCheck
```

完整检查，额外包含 Android 单元测试与 Lint：

```bash
./gradlew check offlineCheck
```

`offlineCheck` 仍然需要 Android SDK。依赖下载完后，可以加 `--offline` 离线运行。

CI 在 Linux 和 Windows 上构建并测试，产出 Android APK、Windows ZIP 和 Linux tar.gz，保留 14 天。推送 `vX.Y.Z` 标签会生成签名的正式版草稿 Release。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `core/` | 平台无关的核心：型号能力、协议编解码、控制会话、用户设置 |
| `composeApp/` | 共享界面（Miuix / Material 3）及界面测试 |
| `androidApp/` | Android 入口、BLE / SPP 传输、Xposed 模块、HyperOS 系统桥接 |
| `desktopApp/` | Windows / Linux 桌面入口 |
| `docs/` | 协议文档与架构决策记录 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [协议实现说明](docs/protocols.md) | OpenRose 对 i5 / i7 协议的具体实现：帧格式、字段编码、设置命令和测试覆盖 |
| [协议研究](docs/protocols/) | 基于官方 RoseLink 逆向整理的全型号协议规范、能力清单和 OTA 研究 |
| [架构决策记录](docs/adr/) | 重要设计决策及其理由 |
| [术语表](GLOSSARY.md) | 项目中统一使用的领域术语 |

## 反馈与参与

问题和需求请提交到 [GitHub Issues](https://github.com/DOHEX/OpenRose/issues)。报告设备问题时请附上：

- 耳机型号和固件版本
- 手机型号和系统版本
- OpenRose 版本
- 复现步骤
- 涉及系统集成时，还请附上 Xposed 框架版本和“系统集成”页显示的桥接状态

参与开发前建议先读[术语表](GLOSSARY.md)和[架构决策记录](docs/adr/)。新增型号或系统适配时，请分别记录协议依据、离线测试结果和真机测试结果。

## 致谢

HyperOS 系统集成参考了 [OppoPods](https://github.com/1812z/OppoPods) 的实现思路。
