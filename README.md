# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](sharedUI/build.gradle.kts)
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

**后台控制**（无需 root，见[下文](#后台控制)）

- 关闭界面后保持控制连接，并显示运行通知
- 应用进程被回收或手机重启后，在耳机再次连接时自动恢复

**HyperOS 系统集成**（需要 Xposed 框架，见[下文](#hyperos-系统集成)）

- 两种控制方式：普通控制由 OpenRose 应用连接耳机；模块控制由系统蓝牙进程连接，无需打开 OpenRose
- 连接提示和耳机状态通知，由系统超级岛 / 焦点通知呈现
- 融合设备中心显示耳机电量，并可切换降噪模式
- 融合设备中心的“附加设置”可直接跳转到 OpenRose

## 安装与使用

系统要求：Android 8.0（API 26）及以上。

1. 从 [Releases](https://github.com/DOHEX/OpenRose/releases) 下载正式版 APK；想尝鲜可以在 [GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) 的构建产物里下载开发版（需登录 GitHub）。
2. 先在**系统蓝牙设置**里配对并连接耳机。OpenRose 不负责音频配对。
3. 打开 OpenRose，授予蓝牙权限。Android 8–11 扫描蓝牙需要定位权限，并且要打开系统定位服务。
4. 已配对的耳机会出现在“我的耳机”页。耳机连上系统音频后，OpenRose 会自动建立控制连接；也可以手动点“连接”。新耳机通过右上角的 **+** 进入“添加耳机”搜索。

OpenRose 与耳机之间的**控制连接**，和系统用来听歌、通话的**音频连接**是两回事。应用里的“断开控制连接”只断开 OpenRose 自己，不影响播放。

## 后台控制

只在打开 OpenRose 时使用的话，这部分可以跳过。后台控制属于普通控制，无需 root 或模块。

“设置”页的“后台控制”开关默认关闭。开启后，OpenRose 以前台服务（`connectedDevice` 类型）运行：关闭界面或划掉任务后，控制连接仍然保留，通知栏有一条运行通知，可以直接从通知里停用。

- 运行通知使用独立的通知渠道，与“焦点通知”和超级岛设置无关。Android 13 及以上没有通知权限时，通知会被隐藏但服务照常运行，设置页会提供“允许通知”入口。
- 缺少蓝牙权限、系统限制后台启动或拒绝前台服务时，设置页显示具体原因，不会把开关显示为“运行中”。
- 关闭开关会结束后台运行和运行通知，但不会断开系统音频，也不会断开正在使用的控制连接。后台控制关闭时，退出界面后控制连接随之结束。
- 在系统设置里“强行停止” OpenRose 会结束后台控制，OpenRose 不会自行恢复。HyperOS 等系统还需要手动允许自启动、取消电量限制，OpenRose 无法检测这两项。

**进程被回收或手机重启后自动恢复**

| Android 版本 | 唤起方式 |
| --- | --- |
| 8–11 | 无需额外设置。耳机音频连接（A2DP / HFP）和开机广播会唤起 OpenRose |
| 12–13 | 需要在设置页点“关联耳机”，由系统伴生设备机制在耳机出现时唤起；关联同时提供后台启动前台服务的豁免 |
| 14 及以上 | 同 12–13，另外需要已授予蓝牙权限 |

- 手机重启后，首次解锁前不会自动控制；解锁后按保存的设置恢复，解锁前已经连上的耳机也会被识别。
- 关闭后台控制后，开机、音频连接和设备出现事件都不会再启动服务。
- 取消关联、缺少权限或系统拒绝时，设置页显示“自动唤起”未就绪，打开应用时仍可正常控制。“系统条件已配置”只代表能检查到的条件都满足，不保证 ROM 一定会唤起。

**验证情况**：后台控制与自动唤起目前只有模拟事件的离线测试，尚未在真机上验证，各 Android 版本的实际表现待测。

## HyperOS 系统集成

只用蓝牙控制的话，这部分可以跳过。

**前提条件**

- HyperOS 3 或 4
- 支持 libxposed API 102 的框架（例如 LSPosed 2.2.0 或更新版本）
- 在框架中启用 OpenRose 模块，作用域为 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`

启用后，在 OpenRose 的“状态”页查看模块是否就绪，在“状态”页进入诊断可查看系统桥接。

**控制方式**（位于“设置”页，默认普通控制）

- **普通控制**：由 OpenRose 应用自己连接耳机，不需要模块。此时模块提供的超级岛、状态通知和融合设备中心控制暂停，OpenRose 发布的展示会被撤销。
- **模块控制**：由模块在系统蓝牙进程（`com.android.bluetooth`）里连接耳机。开机首次解锁后即可使用，不需要打开 OpenRose；关闭界面、应用被回收或强行停止都不影响。打开 OpenRose 时，界面直接使用模块的这条连接，型号功能与普通控制一样完整。

切换时先断开旧的控制连接，再由新的一方连接，不会打断播放和通话；无法确认旧的一方已经断开时，切换不会完成，“设置”页提示未完成，“状态”页说明原因。模块暂时不可用时界面会如实显示，不会自动退回普通控制；选择普通控制后，模块恢复或重新加载也不会把控制抢回去。

**设置项**（位于“设置”页）

| 设置 | 选项 | 说明 |
| --- | --- | --- |
| 超级岛 | 不显示 / 官方 / 模块 | 官方：沿用小米耳机的连接提示；模块：使用 OpenRose 的样式和触发时机。两者都由系统超级岛渲染 |
| 焦点通知 | 开 / 关 | 连接期间在通知栏保留一条耳机状态通知 |
| 附加设置入口 | 系统详情页 / OpenRose | 选 OpenRose 后，融合设备中心卡片上的 Additional Settings 会打开 OpenRose 并连接对应耳机 |
| 关闭降噪使用抗风噪 | 开 / 关（默认关） | 在融合设备中心或系统耳机界面点“关闭”时，支持抗风噪的型号写入抗风噪模式而不是标准模式；不支持的型号仍写入标准模式。抗风噪在这些界面本来就显示为“关闭” |

超级岛、焦点通知和控制方式互不影响：隐藏超级岛或关闭焦点通知只撤销对应的展示，不会停止模块控制和融合设备中心，也不会重连耳机。

**已知限制**

- 系统只有“关闭 / 降噪 / 通透”三种模式。耳机处于抗风噪时，融合设备中心显示为“关闭”，实际模式不变，可以在 OpenRose 里看到。点“关闭”默认写入标准模式，开启“关闭降噪使用抗风噪”后写入抗风噪。
- 耳机电量已知但充电状态未知时，融合设备中心不显示该电量，而不是按“未充电”显示；充电盒电量也不显示，因为系统接口无法表示充电盒是否在充电。
- “状态”页右上角可以通过 Root 重启三个作用域进程，让模块重新加载。重启时蓝牙可能短暂断开；未运行的进程不会被拉起。显示“已执行”只代表命令已发出。
- 模块控制期间，应用每 5 秒向系统蓝牙宿主询问一次状态；连续 3 次、每次 2 秒内无应答，“状态”页显示“系统集成无响应”，并撤下耳机读数，不再把旧读数当作当前值。宿主重新应答后会重新读取。该判断目前只有离线测试，未经真机验证。

**验证情况**：融合设备中心的附加设置跳转已在 i5 + 小米 14（OS4.0.0.27.XNCCNXM、MiLink 18.2.2.0.2609141543、LSPosed 2.2.0-it / API 102）上验证。模块控制、控制方式切换、模块自治（开机恢复、强行停止、宿主重载）、超级岛与焦点通知等其余链路只有离线测试，i7 和 HyperOS 3 也都还没有完整的真机测试。

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

界面测试跑在虚拟时钟上，按测试类并行（分叉数为可用核数的一半）。

打包桌面版。Linux 产出 DEB、RPM、Pacman 与 AppImage（x64），Windows 产出 MSI，各平台只构建本机支持的格式，产物在 `desktopApp/build/compose/binaries/main/`：

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

CI 只在 Linux 上运行测试；Linux 产出 Android APK 与上述 4 种 Linux 包，Windows 产出 MSI，保留 14 天。推送 `vX.Y.Z` 标签会生成签名的正式版草稿 Release。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `core/` | 型号能力、协议编解码、控制会话、统一控制入口（`ControlRuntime`）、状态与用户设置 |
| `sharedUI/` | 共享界面、Miuix / Material 3 组件与界面测试 |
| `androidApp/` | Android 入口、BLE / SPP 传输、libxposed 模块与 HyperOS 系统桥接 |
| `desktopApp/` | Windows / Linux 桌面入口、窗口与偏好设置 |
| `build-logic/` | 各模块共用的 Gradle 约定插件：工具链、JVM 字节码目标、Android 与桌面打包配置 |
| `docs/` | 协议研究与型号规范（`docs/protocols/`）、工程实现证据（`docs/protocols.md`）、架构决策与规范 |

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
- 涉及系统集成时，还请附上 Xposed 框架版本和“状态”页进入诊断后显示的桥接状态

参与开发前建议先读[术语表](GLOSSARY.md)和[架构决策记录](docs/adr/)。新增型号或系统适配时，请分别记录协议依据、离线测试结果和真机测试结果。

## 致谢

HyperOS 系统集成参考了 [OppoPods](https://github.com/1812z/OppoPods) 的实现思路。
