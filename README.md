# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](composeApp/build.gradle.kts)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#android-使用)
[![Status: In development](https://img.shields.io/badge/status-in%20development-orange)](#当前状态)

**简体中文** | [English](README.en.md)

面向 ROSE / ROSESELSA（弱水时砂）耳机的控制客户端，使用 Kotlin Multiplatform 和 Compose Multiplatform 构建。

OpenRose 将耳机协议、控制会话和应用界面分开，提供 Miuix 与 Material 3 两套界面风格，并为 Android 接入 HyperOS 系统耳机功能。当前以 Android 为主要开发平台，Windows、Linux 已有桌面界面入口。

## 功能

- 查看耳机电量、固件版本和设备状态。
- 在首页通过图标切换降噪模式：i5 四项单行，i7 六项两行；分别调节降噪档位、通透档位，通过独立多选入口配置触控降噪轮换。
- 在首页开关游戏模式；在对应功能页调整预设 EQ、触控绑定、触控与检测功能开关、提示音音量。
- 按型号能力选择音频配置和多点连接；i7 另有自定义十段 EQ，以及受固件条件限制的增益、听力保护等设置。
- 切换 Miuix / Material 3、浅色 / 深色外观；界面风格切换保持控制连接和设备状态。
- Android 系统集成包含超级岛、焦点通知和融合设备中心适配，目标为 HyperOS 3、4。

控制项按具体型号配置和已读取的固件开放。全型号协议研究与规范见 [协议文档目录](docs/protocols/)，当前客户端 i5 / i7 的工程实现与实测证据见 [协议实现文档](docs/protocols.md)。

融合设备中心可显示耳机和充电盒电量；充电状态未提供时系统按未充电显示。系统只有关闭、降噪和通透三种模式，耳机处于抗风噪时显示为关闭，但实际模式不变，可在 OpenRose 中查看真实模式。

在适用的 HyperOS 上，可通过“设置 → 融合设备中心 → 附加设置入口”选择系统耳机详情页（默认）或 OpenRose 耳机主页。选择 OpenRose 后，设备卡片中的 Additional Settings 会打开耳机主页并尝试连接卡片对应耳机；连接失败时保留目标和原因，供用户处理或重试。偏好可在模块未就绪时保存，模块就绪后按已保存选择处理后续点击，无需重启作用域。实际入口依赖目标系统组件与已加载的模块。

该入口已在 i5 + 小米 14（OS4.0.0.27.XNCCNXM、MiLink 18.2.2.0.2609141543、LSPosed 2.2.0-it / API 102）验证 OpenRose 跳转、系统入口透传及偏好切换后下一次点击生效。i7 与 HyperOS 3 尚未进行此入口的实机验证。

## 当前状态

项目仍在开发和实机验证阶段。

| 平台 | 当前实现 |
| --- | --- |
| Android 8.0+（API 26+） | 已实现蓝牙扫描、BLE 控制，以及按条件选择的 i7 SPP 控制；普通客户端可独立运行 |
| HyperOS 3 / 4 | 已包含系统桥接与呈现适配代码，需配合支持现代 libxposed API 102 的框架；完整系统链路待实机验证 |
| Windows / Linux | 已接入共享界面、窗口和偏好设置；蓝牙控制传输尚未实现 |

| 型号配置 | 协议与传输 | 实机验证状态 |
| --- | --- | --- |
| EarFree i5 | ZT / BLE | 已有单副设备在 Linux 上的 13 项初始化读取记录；Android 控制和设置写入待验收 |
| EarFeel i7 | RS Common V2 / BLE、SPP | 已有协议实现和离线测试，尚无实机验证 |

同一零售名称下可能存在不同硬件分支，目前仅实现上述型号配置。HyperOS 集成的首验目标为 i5 + 小米 14（HyperOS 4）。

除了已接入客户端的 i5 与 i7 之外，逆向研究已整理出 BudsFeel MK2、OpenFeel MK2、踏浪 Swift 2、Jasmine 等型号的协议规范及 43 款型号能力清单，详见 [协议文档目录](docs/protocols/)。

## Android 使用

1. 安装 APK，打开系统蓝牙，并在系统蓝牙设置中完成耳机的音频配对与连接。
2. 打开 OpenRose，按提示授予蓝牙权限。Android 8–11 还需授予定位权限并开启系统定位服务，以进行蓝牙扫描。
3. 在应用中扫描并选择已识别的耳机，等待状态读取完成后使用控制项。
4. 如需 HyperOS 系统集成，在兼容框架中启用 OpenRose 模块，并在应用设置中检查模块与系统桥接状态。

超级岛可选“不显示／官方／模块”，焦点通知单独设置。两种超级岛模式均由系统界面呈现。普通蓝牙控制无需启用模块。

### 系统呈现（模块控制）

模块控制期间，官方连接提示（小米蓝牙进程）、模块超级岛与耳机状态通知（蓝牙进程）直接从蓝牙宿主内唯一的控制会话读取状态，通知中的降噪操作回到同一个所有者，由耳机回包或重读确认后才更新；呈现与操作不依赖 OpenRose 应用进程，呈现一侧不建立控制连接，也没有自己的命令队列。

- 超级岛（不显示／官方／模块）、焦点通知开关与控制方式（普通控制／模块控制）是三项互不决定的设置：隐藏超级岛或关闭焦点通知只撤销对应展示，不停止模块控制与融合设备中心；改变呈现设置不会重连耳机，也不会向耳机发送命令。
- 只有拥有当前超级岛模式的系统入口可以领取连接提示（模块模式为蓝牙进程，官方模式为小米蓝牙进程，不显示时无入口），每个连接事件只领取一次；常规电量刷新、模式切换与重新选择模块控制都不会重播已领取的提示。
- 切换到普通控制、停用模块控制、断开、模块失联或宿主重启时，OpenRose 自己发布的提示、状态通知、计时器和通知操作引用被撤销；旧所有者代际的回调与操作被拒绝，不影响新会话或其他耳机。官方提示的提前撤销仍使用 `hide_strong_toast`，系统接口没有设备标识，该边界见 ADR 0004。
- 切回模块控制后按已保存的呈现偏好继续，新的耳机会话产生新的连接事件。
- 以上行为仅有离线契约测试；在 i5 + 小米 14（HyperOS 4）上的实际卡片出现、通知更新、降噪闭环、用户关闭、断开撤销与其他耳机隔离**尚未在真机上执行**。

### 控制方式（普通控制／模块控制）

设置页“系统集成”中的“控制方式”可在普通控制与模块控制间选择，默认普通控制。界面分别显示：已选择的方式、所有者状态（就绪／启动中／不可用及失败阶段／切换未完成）和耳机控制连接。模块控制由 `com.android.bluetooth` 内的模块承载共享的控制会话，OpenRose 界面作为认证客户端使用完整的型号控制；打开或关闭界面不改变该会话。切换时先释放旧所有者再连接新所有者，无法确认旧所有者已释放时不会接管并会说明原因。“停用模块控制”只停用模块，不会启用普通控制。该路径尚未在真机上验证。

### 后台控制（普通控制）

设置页的“后台控制”开关默认关闭，无需 root 或模块。启用后，OpenRose 以 `connectedDevice` 前台服务保持同一个控制运行时，关闭界面或移除任务后，已建立的控制连接仍然保留，并显示必要的运行通知（可从通知中直接停用）。

- 必要运行通知使用独立的通知渠道，与“焦点通知”和超级岛设置无关；Android 13 及以上若未授予通知权限，通知会被隐藏，服务仍在运行，设置页会提示并提供“允许通知”入口。
- 缺少蓝牙权限、系统限制后台启动或拒绝前台服务时，设置页显示具体原因，不会把已保存的开关显示为“运行中”。
- 前台服务只在你打开 OpenRose（开关、启动界面或授权返回）时启动，以满足 Android 12 及以上的后台启动限制；本版本不负责在应用进程被系统回收、手机重启后重新唤起，这部分由后续的普通控制唤起功能提供。
- 关闭开关会结束后台运行和运行通知，但不会断开系统音频连接，也不会断开你正在使用的控制连接。
- 在系统设置中“强行停止” OpenRose，或在任务管理器中停止它，会结束后台控制，OpenRose 不会自行绕过。部分系统（如 HyperOS）还需在系统设置中允许 OpenRose 自启动并取消电量限制，OpenRose 无法检测这些设置。
- 设备端观察（i5 读取与设置、界面关闭与任务移除、运行通知、用户停用）尚未在真机上执行，Android 版本相关行为尚待实测。

#### 进程消失与重启后的自动唤起

- Android 8–11：无设备在线监听接口，也无需关联耳机；耳机音频连接（A2DP/HFP）广播和开机广播通过清单接收器唤起 OpenRose，再启动后台服务。
- Android 12–13：需在设置页“关联耳机”，由系统伴生设备关联和设备在线监听在进程消失后唤起；关联同时授予后台启动前台服务的豁免。
- Android 14 及以上：同上，并要求前台服务类型 `connectedDevice` 与已授予的蓝牙权限；缺失时设置页给出原因，不会显示为就绪。
- 重启后在首次解锁前不会自动控制；首次解锁后按保存的设置恢复，耳机在此之前已音频连接也会被识别。停用后台控制后，开机、音频连接和设备在线事件都不会再启动服务，设备在线监听同时被撤销。
- 取消关联、缺少权限或系统拒绝时，设置页显示“自动唤起”未就绪；此时仍可在打开应用时使用普通前台控制，完成条件后再启用后台恢复。“系统条件已配置”只表示可验证的条件已满足，不代表 ROM 一定会唤起。
- 以上唤起行为（各 Android 版本频段、HyperOS 自启动策略、重启后首次解锁恢复、关联取消与权限失败）尚未在真机上验证，仅有模拟事件的契约测试。

Android 的“系统集成”页右上角可在确认后请求 Root 并重启三个系统集成作用域。蓝牙连接可能中断；未运行的目标不会被启动。逐项“已执行”仅表示终止命令已执行，并不保证系统进程已恢复或模块已生效。Root 与目标进程的实际行为仍需在目标设备验证。

OpenRose 的**控制连接**与系统播放、通话使用的音频连接分开管理；应用中的“断开控制连接”仅结束 OpenRose 的控制连接。

## 从源码构建

### 环境准备

- JDK 21；仓库的 `.mise.toml` 提供 Temurin 版本配置。
- Android SDK Platform 37 和 Build Tools 37.0.0。
- 设置 `ANDROID_HOME`，或在未纳入版本控制的 `local.properties` 中填写 `sdk.dir=/path/to/android-sdk`。
- Linux 桌面运行还需 GTK 3、EGL / OpenGL、fontconfig，具体安装示例见 [CI 配置](.github/workflows/ci.yml)。

仓库已包含 Gradle Wrapper，无需单独安装 Gradle。首次构建需要联网下载依赖。Windows 下将以下命令中的 `./gradlew` 替换为 `.\gradlew.bat`。

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
```

### 构建 Android APK

```bash
./gradlew :androidApp:assembleDebug
```

产物位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

### 运行桌面应用

```bash
./gradlew :desktopApp:run
```

当前桌面版可用于查看界面和调整应用偏好设置，耳机控制需使用 Android 端。

### 检查与测试

```bash
# 协议与控制会话测试、共享界面测试，以及 Android / 桌面编译检查
./gradlew offlineCheck

# 完整检查（包含 Android 单元测试与 Lint）
./gradlew check offlineCheck
```

`offlineCheck` 表示无需耳机硬件的检查，仍需要 Android SDK。依赖缓存完整后，可额外传入 `--offline` 禁止 Gradle 联网。

[GitHub Actions](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml) 配置了 Windows、Linux 构建与检查，成功后上传 Android APK、Windows ZIP 和 Linux tar.gz；开发构建产物保留 14 天。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `core/` | 型号能力、协议编解码、控制会话、统一控制入口（`ControlRuntime`）、状态与用户设置 |
| `composeApp/` | 共享界面、Miuix / Material 3 组件与界面测试 |
| `androidApp/` | Android 入口、BLE / SPP 传输、libxposed 模块与 HyperOS 系统桥接 |
| `desktopApp/` | Windows / Linux 桌面入口、窗口与偏好设置 |
| `docs/` | 协议研究与型号规范（`docs/protocols/`）、工程实现证据（`docs/protocols.md`）、架构决策与规范 |

## 参与与反馈

功能需求、问题和适配反馈统一记录在 [GitHub Issues](https://github.com/DOHEX/OpenRose/issues)。反馈设备问题时，请附上具体耳机型号与固件、手机和系统版本、OpenRose 版本、复现步骤；涉及系统集成时，也请附上框架版本与桥接状态。

参与开发前，可先阅读 [领域词汇表](GLOSSARY.md)、[架构决策](docs/adr/)、[协议文档目录](docs/protocols/) 与 [协议实现证据](docs/protocols.md)。新增型号或系统适配时，请分别记录协议证据、离线测试和实机结果。
