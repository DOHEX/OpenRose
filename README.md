# OpenRose

[![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/DOHEX/OpenRose/actions/workflows/ci.yml)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](sharedUI/build.gradle.kts)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#安装与使用)
[![Status: In development](https://img.shields.io/badge/status-in%20development-orange)](#支持的耳机)

**简体中文** | [English](README.en.md)

OpenRose 是 ROSE / ROSESELSA（弱水时砂）耳机的第三方控制客户端，可以替代官方 RoseLink 完成日常设置。在 HyperOS 上，它还能把耳机接入超级岛、焦点通知和融合设备中心，体验接近小米自家耳机。

项目基于 Kotlin Multiplatform 与 Compose Multiplatform，提供 Miuix 和 Material 3 两套界面风格。Android 是主力平台；Linux 桌面版通过 BlueZ 提供 BLE 控制，Windows 桌面版目前只有界面。

> [!WARNING]
> 项目仍在开发中，大部分功能尚未在真机上完整验证，详见下方的[支持的耳机](#支持的耳机)。遇到问题欢迎[反馈](#反馈与参与)。

## 支持的耳机

| 耳机 | 协议 / 控制通道 | 验证情况 |
| --- | --- | --- |
| EarFree i5（EarFeel i5） | ZT / BLE | 已在真机上读取状态；设置写入尚未在 Android 上验证 |
| EarFeel i7 | RS Common V2 / BLE 或 SPP | 协议实现与离线测试完成，尚未接触真机 |
| BudsFeel MK2 | RS Common V2 / BLE 或 SPP | 依据静态协议适配；实机未验证，能力边界见证据账本 |
| OpenFeel MK2 | RS Common V2 / BLE 或 SPP | 依据静态协议适配；实机未验证，能力边界见证据账本 |
| 踏浪 Swift 2 | RS Common V2 / BLE 或 SPP | 单体电量、游戏模式与多点；实机未验证 |
| Jasmine | RS Common V2 / BLE 或 SPP | 依据静态协议适配；实机未验证，商品中文名未确认 |

同一零售名称下可能有不同的硬件版本。上表对应六个型号配置；其余 37 个分支只有能力清单，缺少足够的型号字节级证据，尚未适配。新增配置的逐项依据、未实现能力和原因见[型号适配证据账本](docs/protocols/model-adaptation-ledger.md)，完整研究见 [docs/protocols/](docs/protocols/)。

## 功能

**耳机控制**（可用项随型号和固件版本变化）

- 查看左右耳、充电盒电量和固件版本
- 切换降噪模式，分别调节降噪档位和通透档位；设置触控时参与轮换的降噪模式
- 游戏模式、预设 EQ、触控按键绑定、触控开关、佩戴检测开关、提示音音量
- 音频编码偏好与多点连接（i5 上这两项是联动的）
- i7 自定义十段 EQ；i7 在固件满足条件时、OpenFeel MK2 可调增益和听力保护；BudsFeel MK2 / OpenFeel MK2 低音增强；Jasmine 中英文提示音语言

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
- “状态”页右上角可以通过 Root 重启作用域进程，让模块重新加载：弹窗里可勾选要重启的进程（默认全选）。确认后仍是同一个弹窗，说明文字换成当前步骤（请求 Root 授权、终止进程、等待新进程出现并倒计时），每个勾选框原地变成该进程的状态：等待、进行中（转圈）和结果；未勾选的进程留在原处并标为“未选择”。所选进程同时终止、共用一次等待，哪个进程回来了就先显示哪个的结果；失败原因在全部结束后显示。只发送终止信号（SIGTERM），不使用强制停止；发出后最多等待 8 秒，看到同名的新进程出现才显示“已重启”。进程已终止但没有在时限内回来（按需启动的进程不一定会自己起来）显示“已终止，等待系统拉起”，不算失败；进程未运行时不会被拉起。重启时蓝牙可能短暂断开。“已重启”只表示新进程出现，不代表模块已生效。
- 模块控制期间，应用每 5 秒向系统蓝牙宿主询问一次状态；连续 3 次、每次 2 秒内无应答，“状态”页显示“系统集成无响应”，并撤下耳机读数，不再把旧读数当作当前值。宿主重新应答后会重新读取。该判断目前只有离线测试，未经真机验证。

**验证情况**：融合设备中心的附加设置跳转已在 i5 + 小米 14（OS4.0.0.27.XNCCNXM、MiLink 18.2.2.0.2609141543、LSPosed 2.2.0-it / API 102）上验证。模块控制、控制方式切换、模块自治（开机恢复、强行停止、宿主重载）、超级岛与焦点通知等其余链路只有离线测试，i7 和 HyperOS 3 也都还没有完整的真机测试。

## 平台支持

| 平台 | 状态 |
| --- | --- |
| Android 8.0+ | 蓝牙扫描、BLE 控制、i7 的 SPP 控制均已实现 |
| HyperOS 3 / 4 | 系统集成代码已完成，待真机验证 |
| Linux x64 | BlueZ BLE 扫描与控制、已配对设备、系统音频驱动控制生命周期；桌面端 i5 完整实机验收待完成 |
| Windows | 界面和偏好设置可用；蓝牙控制尚未实现 |

Linux 使用系统 BlueZ 和 D-Bus，不申请 Android 式蓝牙权限，也不开关适配器。状态页会说明 BlueZ 缺失、适配器关闭或 D-Bus 拒绝访问的原因；可用时提供“打开蓝牙设置”（依次探测 GNOME、KDE、Blueman）。应用单实例运行，再次启动会唤起已有窗口；系统音频连接由系统管理。

桌面端提供托盘图标：点击弹出托盘面板，查看控制连接状态与电量、切换降噪模式，未连接时可重新连接；托盘菜单提供同样的快捷控制，以及“托盘面板”“打开 OpenRose”“退出”。设置页的“后台控制”默认开启：托盘可用时关闭窗口只隐藏界面、保留控制连接，从托盘“退出”才结束控制连接；托盘不可用（例如 GNOME 未启用 AppIndicator 扩展）时关闭窗口即退出，状态页说明原因。“登录时启动”默认关闭，开启后台控制且托盘可用时登录后只显示托盘图标。Linux 托盘基于 StatusNotifierItem，需要 `libsystemd`；GNOME 与 KDE 依据库文档支持，未经实机验证。详见 [ADR 0014](docs/adr/0014-desktop-tray-and-background-control.md)。

niri、Hyprland、Sway 等未识别的桌面上，托盘面板是一个由合成器摆放的普通窗口，标题固定为“OpenRose 托盘面板”；鼠标移入后再移出、焦点离开或按 Esc 时收起。可以用窗口规则让它浮动到托盘附近，例如 niri：

```kdl
window-rule {
    match title="^OpenRose 托盘面板$"
    open-floating true
    default-floating-position x=12 y=12 relative-to="top-right"
}
```

Linux 当前只实现 BLE，不含 SPP、OTA 或 Android 系统集成。i5 复用共享协议的 13 项初始化读取及设置回读确认；经典蓝牙已连接但没有 GATT 服务时，补连另一条 BLE 链路并等待服务发布，不删除配对记录。i7 尚无实机验证。离线假 D-Bus 测试覆盖平台事件与错误映射；此前 Linux / Bleak 的 i5 读取证据见[协议说明](docs/protocols/models/earfeel-i5-protocol.md)，不能代替本桌面实现的实测。i5 扫描到控制就绪、13 项读数对照、每项设置回包、音频连接／断开、双模恢复，以及 DEB／RPM／Pacman 在干净系统上的安装运行，仍需维护者验收。

Linux 自动控制依据 BlueZ `MediaTransport1` 的 A2DP 音频连接；只有 HFP 通话连接时不会自动建立控制连接，可从添加耳机流程或已配对设备中手动连接。

## 从源码构建

**环境要求**

- JDK 21（`.mise.toml` 已指定 Temurin 版本，可用 [mise](https://mise.jdx.dev/) 安装）
- Android SDK Platform 37、Build Tools 37.0.0
- 设置 `ANDROID_HOME`，或在 `local.properties` 中写入 `sdk.dir=/path/to/android-sdk`
- Linux 运行桌面版还需要已运行的 BlueZ、可访问的系统 D-Bus、已开启且支持 BLE 的适配器，以及 GTK 3、EGL / OpenGL 和 fontconfig；DEB、RPM、Pacman 声明 `bluez` 依赖，AppImage 也依赖宿主的 BlueZ

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

运行不依赖耳机硬件的检查（协议、控制会话、BlueZ 假 D-Bus 驱动与事件映射、共享界面、Android / 桌面偏好持久化测试，两端与界面沙盒的编译检查，以及格式、静态分析、Android Lint、覆盖率下限和中英文 README 结构一致性检查）：

```bash
./gradlew offlineCheck
```

CI 运行的完整检查：

```bash
./gradlew check offlineCheck
```

`offlineCheck` 仍然需要 Android SDK。依赖下载完后，可以加 `--offline` 离线运行。格式不符时运行 `./gradlew spotlessApply`；各项检查的说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

界面测试跑在虚拟时钟上，按测试类并行（分叉数为可用核数的一半）。导航、跨页流程、风格切换连续性和主页面滑动在整应用级验证，其余在页面或段落级配假表现层模型覆盖两种界面风格；Android 专属段落的测试位于 `androidIntegrationUI/`。

调试界面时运行界面沙盒，不需要耳机或蓝牙。一个窗口切换平台（Android / Linux / Windows）、型号配置、固件、控制连接阶段、电量、平台状况、设置写入结果与界面风格，另一个窗口按这些条件运行共享界面与该平台的段落，桌面平台另开托盘面板。读数由所选型号在该固件下的能力推导，设置写入经该型号的编解码器准备，编解码器拒绝的值在沙盒里同样失败。`hotRun` 启用 Compose Hot Reload，修改 `sharedUI/`、`androidIntegrationUI/` 等界面代码后，运行中的窗口直接刷新：

```bash
./gradlew :uiSandbox:run
./gradlew :uiSandbox:hotRun --autoReload
```

沙盒只覆盖应用内界面，属于离线验证：Android 端的背景模糊与返回手势、系统超级岛、焦点通知、融合设备中心和桌面托盘菜单都不经过它。

打包桌面版。Linux 产出 DEB、RPM、Pacman 与 AppImage（x64），Windows 产出 MSI，各平台只构建本机支持的格式，产物在 `desktopApp/build/compose/binaries/main/`：

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

CI 只在 Linux 上运行测试；Linux 产出 Android APK 与上述 4 种 Linux 包，Windows 产出 MSI，保留 14 天。发布由 release-please 完成：合并它根据提交记录开出的发布 PR 后，会打上 `vX.Y.Z` 标签，并生成附带签名 APK 和各平台安装包的草稿 Release。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `core/` | 型号能力、协议编解码、控制会话、统一控制入口（`ControlRuntime`）、共享 GATT 连接、已配对设备与音频推断、状态与控制设置（强制使用 BLE） |
| `androidIntegration/` | Android 集成（纯 Kotlin，在 JVM 上测试）：控制方式与所有者交接、模块宿主与客户端、IPC 编码、系统入口、系统呈现、融合设备中心映射、后台控制及其设置 |
| `sharedUI/` | 共享界面、表现层模型与平台插槽、Miuix / Material 3 组件、外观设置与界面测试 |
| `androidIntegrationUI/` | Android 专属界面段落：控制方式、后台控制、系统集成状态与诊断、权限和作用域重启；KMP Android / JVM |
| `androidApp/` | Android 入口、GATT 原语驱动与 SPP 传输、音频协议代理与广播、libxposed 模块与 HyperOS 系统桥接 |
| `transportBluez/` | Linux BlueZ D-Bus GATT 原语、扫描、配对设备与音频事实；复用共享 GATT 与音频推断 |
| `desktopApp/` | Windows / Linux 桌面入口、窗口与偏好设置 |
| `uiSandbox/` | 开发用界面沙盒：在假状态上运行共享界面、各平台段落与托盘面板，不打包、不发布；只有它同时依赖桌面端与 Android 集成界面 |
| `build-logic/` | 各模块共用的 Gradle 约定插件：工具链、JVM 字节码目标、Android 与桌面打包配置、静态分析与覆盖率、README 结构检查 |
| `config/` | detekt 规则与各模块的既有问题基线 |
| `docs/` | 协议研究与型号规范（`docs/protocols/`）、工程实现证据（`docs/protocols.md`）、架构决策与规范 |

用户设置由归属层分别编解码，共用各平台的键值存储：控制设置用 `core.` 前缀，外观设置用 `sharedUI.`，Android 集成设置用 `androidIntegration.`。模块宿主只接收控制和集成设置。旧的无前缀键不再读取，不做迁移；使用新版本时需重新选择偏好。

两端入口统一持有 `RuntimeOpenRoseModel`、控制入口、设置存储和宿主协程作用域，再将模型与 `PlatformUi` 传给 `OpenRoseApp`。平台通过插槽提供段落及系统动作，共享界面不按平台名称分支。桌面端只在取得单实例所有权后创建运行时，Linux 接入 `transportBluez`，状态页和添加耳机流程显示实时传输可用性及原因。

## 文档

| 文档 | 内容 |
| --- | --- |
| [协议实现说明](docs/protocols.md) | OpenRose 对 ZT / RS Common V2 的具体实现：型号配置、帧格式、字段编码、设置命令和测试覆盖 |
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

参与开发前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)，以及[术语表](GLOSSARY.md)和[架构决策记录](docs/adr/)。新增型号或系统适配时，请分别记录协议依据、离线测试结果和真机测试结果。

## 许可

OpenRose 以 [GPL-3.0-or-later](LICENSE) 授权。

## 致谢

HyperOS 系统集成参考了 [OppoPods](https://github.com/1812z/OppoPods) 的实现思路。
