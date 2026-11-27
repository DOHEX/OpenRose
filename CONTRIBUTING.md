# 参与贡献

感谢你对 OpenRose 的关注。本文说明如何搭建开发环境、提交修改，以及合入前需要满足的检查。项目介绍见 [README.md](README.md)（[English](README.en.md)），使用说明见[文档网站](https://dohex.github.io/OpenRose/)（源码在 [`website/`](website/README.md)）。

## 反馈问题与提出建议

在 [GitHub Issues](https://github.com/DOHEX/OpenRose/issues) 中选择“问题反馈”或“功能建议”模板。新建的 Issue 会带 `needs-triage` 标签，由维护者评估后再调整状态（标签含义见 [docs/agents/triage-labels.md](docs/agents/triage-labels.md)）。

报告设备问题时请填写耳机型号与固件、平台与系统版本、OpenRose 版本和复现步骤；涉及 HyperOS 系统集成时，还需要 Xposed 框架版本和“状态”页进入诊断后的桥接状态。

## 开发环境

- **JDK 21**：`.mise.toml` 固定 Temurin 的精确版本，CI 也读取它安装 JDK；可用 [mise](https://mise.jdx.dev/) 安装同一版本。构建和 Gradle 守护进程都要求 Temurin 21，机器上没有时，`settings.gradle.kts` 中的 foojay 工具链解析插件会自动下载。Gradle 不会自动发现 mise 安装的 JDK，只有它正好是启动 Gradle 的 JVM 时才会被选中；想让终端和 IDE 用同一个 JDK，在 `~/.gradle/gradle.properties` 中把它加入 `org.gradle.java.installations.paths`。
- **Android SDK 与 NDK**：Platform 37、Build Tools 37.0.0、NDK `28.2.13676358`（版本固定在 `gradle/libs.versions.toml`）。设置 `ANDROID_HOME`，或在 `local.properties` 中写入 `sdk.dir=/path/to/android-sdk`。`offlineCheck` 需要 Android SDK；首次执行 `:androidApp:assembleDebug` 时，AGP 会按固定版本自动下载 NDK。
- **Rust 与 Android 交叉编译**：`cargo xtask check` 与主机 JVM FFI 测试需要 Rust stable 工具链；构建 Android APK 还需要 `aarch64-linux-android`、`armv7-linux-androideabi`、`x86_64-linux-android` 三个目标和 `cargo-ndk` `4.1.2`。APK 为 `arm64-v8a`、`armeabi-v7a`、`x86_64` 各带一份 Rust 核心（`libopenrose_ffi.so`），不含 32 位 x86（`build-logic` 的 `androidRustAbis`，打包排除项在 `androidApp/build.gradle.kts`）；CI 逐个 ABI 检查 `libopenrose_ffi.so` 与 `libjnidispatch.so` 以未压缩（`Stored`）方式存放：

  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install --locked cargo-ndk --version 4.1.2
  ```
- **Linux 运行库**：运行或测试桌面版需要 GTK 3、EGL / OpenGL 和 fontconfig。CI 在 Ubuntu 24.04 上安装的是：

  ```bash
  sudo apt-get install --yes libgtk-3-0t64 libegl1 libgl1 libfontconfig1 rpm libarchive-tools
  ```

  其中 `rpm` 和 `libarchive-tools` 用于打包 RPM / Pacman。真实运行桌面版控制耳机还需要已运行的 BlueZ、可访问的系统 D-Bus 和已开启且支持 BLE 的适配器；DEB、RPM、Pacman 声明 `bluez` 依赖，AppImage 依赖宿主的 BlueZ。Linux 托盘需要 `libsystemd`。
- Windows 桌面构建需要 Rust stable（MSVC 工具链）和 Visual Studio C++ Build Tools。Gradle 会编译 WinRT 蓝牙组件并随 MSI 分发。
- 仓库自带 Gradle Wrapper。Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
./gradlew :desktopApp:run              # 运行桌面版
./gradlew :androidApp:assembleDebug    # 构建 Android 调试 APK
```

## 模块结构

两个应用入口在根目录，通用能力在 `shared/`，操作系统适配在 `platform/`，开发工具在 `tools/`。[模块清单](settings.gradle.kts) · [架构决策](docs/adr/0020-client-and-platform-modules.md)

| 模块 | 职责 |
| --- | --- |
| `:androidApp` | Android 应用进程、Activity、服务、权限与组装。运行时将系统模块装入同一个 APK |
| `:desktopApp` | Linux 和 Windows 共用的桌面入口、窗口、原生托盘、登录启动与打包 |
| `:shared:headset` | 型号、协议、共享 GATT、控制连接与设置确认。调用方通过同一个控制入口提交耳机命令 |
| `:shared:ui` | 共享页面、快捷面板、托盘面板和外观。桌面展示段落在 `jvmMain`，由应用或沙盒提供平台动作 |
| `:shared:circulation` | 跨端流转契约、型号资格、配对与远端会话引用 |
| `:shared:circulation-lan` | 局域网发现、配对认证、网络收发、流转与远端控制的 JVM 实现 |
| `:platform:android:integration` | 应用／模块控制与所有者交接、系统入口及呈现规则，在 JVM 上测试 |
| `:platform:android:bluetooth-ipc` | 应用与模块共用的 Android 蓝牙驱动、音频连接事实、AIDL 与 Binder 编解码 |
| `:platform:android:system-module` | LSPosed 在作用域进程内加载的模块、模块宿主、耳机系统集成和手机流转端点 |
| `:platform:android:ui` | 手机与 JVM 沙盒共用的 Android 设置、状态和诊断界面段落 |
| `:platform:linux` | BlueZ D-Bus 驱动、音频接入与让出、桌面声音设置 |
| `:platform:windows` | WinRT BLE、Core Audio 系统操作以及 Rust 原生助手 |
| `:tools:ui-sandbox` | 使用合成状态预览真实的 Android 和桌面界面 |
| `:tools:ui-test-support` | 界面测试共用的假模型、场景与操作 |
| `build-logic/` | Gradle 约定插件（独立 included build） |

## 新增型号

每个型号配置是 `shared/headset/.../models/` 下的一个文件（`shared/headset/...` 即 `shared/headset/src/commonMain/kotlin/io/github/dohex/openrose/core`），集中声明识别前缀、名称规则、协议族、控制传输、电量布局、EQ 预设名称，以及按固件推导的型号能力。新增时：

1. 在 `ModelProfile` 增加枚举项，并在其 `spec` 中映射到新的 `ModelSpec`；缺少映射无法编译。枚举名跨进程传递，确定后不要改名。
2. 写全 `ModelSpec` 的各项。能力只列有研究证据的设置：未声明的设置不会出现在界面，也不会被写入。
3. 在 `createProtocolCodec` 查到现有协议族编解码器时直接复用；族内型号的帧、字段或枚举有差异时，在 `ModelSpec` 增加对应字段，由该族编解码读取。新协议族需要新增 `ProtocolFamily` 项与编解码。
4. 单体型号（`BatteryLayout.SINGLE_UNIT`）在应用和托盘里显示一行“电量”；在有实机证据之前，超级岛、焦点通知和融合设备中心不发布其电量。
5. 在 `platform/android/integration` 测试夹具的 `initializationReplies`、`roseAdvertisement` 中登记该型号的回包与广播。`ModelProfileTest` 会检查每个型号都能凭自己的广播前缀和名称被识别。
6. 在界面沙盒中选中该型号，按有能力差异的固件分别检查各页面；这是离线验证，不能代替真机验证。

## 界面调试

`./gradlew :tools:ui-sandbox:run` 打开两个窗口，不需要耳机或蓝牙：控制台切换平台（Android / Linux / Windows）、型号配置、固件、控制连接阶段、电量、平台状况、设置写入结果、界面风格与预览尺寸，Android 下还有系统集成、所有者与后台服务状态，桌面下还有托盘与登录时启动状态；预览窗口用这些条件运行 `OpenRoseApp` 与该平台真实的 `PlatformUi`，桌面平台另开 `TrayPanel`。窗口由桌面端同一窗口后端（Nucleus Tao）承载，与正在运行的 OpenRose 互不影响。

- 型号能力与真实会话共用同一推导规则，输入包括固件和已确认的读数，电量按型号的电量布局给出；固件框留空表示未读取，控制台下方列出当前可写的设置，便于看出固件门槛。
- 应用内的操作照常生效：连接按阶段推进，断开后回到未连接看板，添加耳机流程能扫描到每个型号。设置写入经该型号的 `createProtocolCodec(...).prepareSetting(...)` 准备，确认后写回编解码器预期的读回值；编解码器拒绝的值以失败结束，界面传错值时能直接看到。
- `./gradlew :tools:ui-sandbox:hotRun --autoReload` 启用 Compose Hot Reload，修改 `:shared:ui`、`:platform:android:ui` 或沙盒自身后，运行中的窗口直接刷新，当前状态保留。
- 沙盒只运行 JVM 实现：Android 的背景模糊与返回手势、超级岛、焦点通知、融合设备中心和桌面托盘菜单需要在真机或真实桌面环境上检查。

## 日常命令

| 命令 | 作用 |
| --- | --- |
| `./gradlew spotlessApply` | 格式化全部 Kotlin 与 Gradle Kotlin DSL（Spotless + ktlint，规则在 `.editorconfig`） |
| `./gradlew spotlessCheck` | 检查格式 |
| `./gradlew detekt` | 产品模块和构建约定的 Kotlin 静态分析，配置为 `config/detekt/detekt.yml`，自定义规则在 `tools/detekt-rules` |
| `./gradlew koverHtmlReportCoverage` | 生成根项目聚合的覆盖率 HTML 报告 |
| `./gradlew koverVerifyCoverage` | 校验行覆盖率不低于下限 |
| `./gradlew offlineCheck` | 运行 Gradle 侧全部不依赖硬件的测试，以及 `spotlessCheck`、`detekt`、lint 与 `koverVerifyCoverage`（不含 Rust 侧检查） |
| `cargo xtask check` | 全仓离线检查门禁：版本一致性校验、`cargo test --locked --workspace`、`cargo clippy --locked --workspace --all-targets -- -D warnings`、`cargo xtask no-comments`，以及 Gradle `check offlineCheck`。CI 执行的就是它 |

**提 PR 前必须通过 `cargo xtask check`。**

约束：

- Kotlin 编译器警告会使构建失败（`allWarningsAsErrors`），请直接修复警告。
- Android lint 的警告按错误处理。上游发布新版本的提示列为信息，依赖升级由 Dependabot 每周提出。detekt 与 Android lint 直接检查源码。必须依赖宿主私有 API 或实现固定 AIDL 契约时，将例外限定到相应调用或适配器，原因写进相关 ADR 或 issue。
- detekt 不检查测试源码的行宽（`MaxLineLength`），`ReturnCount` 不计开头的守卫语句；规则见 `config/detekt/detekt.yml`。
- 源码不写注释（[ADR 0021](docs/adr/0021-no-code-comments.md)）：决策写进 ADR，绕路实现登记为 issue。遗留注释的预算在 `config/detekt/comment-budget.txt` 中，只降不升；清理流程用 `/delete-comment` 技能。
- Spotless 不会把 `.editorconfig` 的改动当作输入变化：修改 `.editorconfig` 后请运行 `./gradlew spotlessApply --rerun-tasks`。
- 全仓格式化提交记录在 `.git-blame-ignore-revs` 中。GitHub 的 blame 会自动跳过它；本地运行一次 `git config blame.ignoreRevsFile .git-blame-ignore-revs` 即可同样跳过。

CI 还会构建 Android 调试 APK 并打包桌面版。Windows 作业对 WinRT 助手运行 `cargo fmt -- --check`、`cargo clippy --locked --all-targets -- -D warnings` 和 `cargo test --locked`。影响 Android 安装包的 PR 会运行 release 变体的完整 lint、构建未签名的 release APK，并核对 R8 后的模块入口和系统呈现资源。文档 PR 在部署前运行站点构建与站内链接检查。

本地构建命令：

```bash
cargo xtask check
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:packageDistributionForCurrentOS
```

## 提交规范

提交信息使用英文，遵循 [Conventional Commits](https://www.conventionalcommits.org/)：`feat`、`fix`、`refactor`、`docs`、`ci`、`build`、`test` 等类型，可带范围，例如 `feat(desktop):`、`fix(android):`。正文描述行为变化；如果做过验证，用一段 `Validation:` 说明跑了哪些检查、在什么设备上验证，以及哪些仍未验证。示例：

```text
feat(desktop): add tray panel and background control

<describe the behaviour change>

Validation: :shared:ui:jvmTest and :desktopApp:test pass; cargo xtask check
passes. GNOME and KDE are not verified on hardware.
```

PR 标题同样遵循这一格式，并由 `.github/workflows/pr-title.yml` 检查（类型见 `release-please-config.json`，另允许 `revert`）。PR 以 squash 方式合并，标题会成为 main 上的提交信息，发布内容和版本号都由它决定。

### 发布

发布由自动化完成，贡献者不需要手动打标签：

1. main 分支上的 release-please 根据 Conventional Commits 开出发布 PR，改写 `version.txt` 并维护 `CHANGELOG.md`。
2. 合并发布 PR 后，main 上的 CI 调用 release-please 创建 `vX.Y.Z` 标签和草稿发布。
3. 同一次 CI 构建安装包，校验 Android 模块入口及资源，再把安装包、来源证明和校验和关联到此次发布。

因此提交类型要如实反映变更性质：`feat` 对应新功能，`fix` 对应缺陷修复。

版本号只来自 `version.txt`，不要手动修改它，也不要在别处写版本号。`build-logic` 的 `AppVersion` 由它推导各平台的版本：发布构建为 `X.Y.Z`，其他构建为 `X.Y.Z-dev+<短提交号>`；Android `versionCode` 为 `MAJOR×1000000 + MINOR×1000 + PATCH`；桌面安装包只用 `X.Y.Z`。CI 构建发布标签时会检查标签与 `version.txt` 一致。依据见 [ADR 0015](docs/adr/0015-single-version-source.md)。

每个发布附带 `SHA256SUMS`，所有安装包还带有由发布工作流签名的构建来源证明（SLSA provenance）。下载后可以这样校验：

```bash
sha256sum --check --ignore-missing SHA256SUMS
gh attestation verify openrose-X.Y.Z-android.apk --repo DOHEX/OpenRose
```

## 领域文档

- 引入或改变领域术语时，更新 [GLOSSARY.md](GLOSSARY.md)，并使用其中已有的用语；术语表中的 _Avoid_ 条目是应避免的说法。
- 架构决策记录在 `docs/adr/NNNN-kebab-title.md`（编号顺延，标题为中文）。文件以 `status: accepted` 的 YAML front matter 开头，随后是标题、说明背景的段落，以及“决策”“考虑过的方案”“后果”等小节，可参考 [ADR 0012](docs/adr/0012-shared-multiplatform-architecture.md) 和 [ADR 0014](docs/adr/0014-desktop-tray-and-background-control.md)。
- 修改前先阅读涉及区域的术语和 ADR。若修改与某条 ADR 冲突，请在 PR 中指出是哪一条，并说明为什么需要重新讨论。
- 面向用户的行为或说明变化时，更新文档网站 `website/src/content/docs/` 下的中文页面和 `en/` 下对应的英文页面。README 只保留概要：涉及支持的耳机表、功能概要或安装步骤时，同步更新 `README.md` 和 `README.en.md`。

## 验证与真机测试

OpenRose 区分两类验证，请在 PR 描述和文档中分别如实记录，不要把离线测试当作真机验证：

- **离线验证**：协议编解码、控制会话、BlueZ 假 D-Bus 驱动、界面测试、Rust 核心测试与静态检查等，由 `cargo xtask check` 覆盖，不需要耳机。
- **真机验证**：在真实耳机、真实设备或桌面环境上执行。请写明耳机型号、设备 / 系统（或桌面环境与 BlueZ 版本）、OpenRose 版本，以及具体验证了哪些功能。

新增型号或系统适配时，请分别记录协议依据、离线测试结果和真机测试结果；没有测过的部分明确写“未验证”。网站“支持的耳机”和“平台支持”页（以及 README 的支持表）中的状态列就是这种写法：已在真机验证的部分和只有离线测试的部分分开标注。型号协议研究位于 [docs/protocols/](docs/protocols/)。

## 许可

OpenRose 以 GPL-3.0-or-later 授权（见 [LICENSE](LICENSE)）。提交贡献即表示你同意你的贡献以同一许可发布。
