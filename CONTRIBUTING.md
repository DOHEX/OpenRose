# 参与贡献

感谢你对 OpenRose 的关注。本文说明如何搭建开发环境、提交修改，以及合入前需要满足的检查。项目介绍与使用说明见 [README.md](README.md)（[English](README.en.md)）。

## 反馈问题与提出建议

在 [GitHub Issues](https://github.com/DOHEX/OpenRose/issues) 中选择“问题反馈”或“功能建议”模板。新建的 Issue 会带 `needs-triage` 标签，由维护者评估后再调整状态（标签含义见 [docs/agents/triage-labels.md](docs/agents/triage-labels.md)）。

报告设备问题时请填写耳机型号与固件、平台与系统版本、OpenRose 版本和复现步骤；涉及 HyperOS 系统集成时，还需要 Xposed 框架版本和“状态”页进入诊断后的桥接状态。

## 开发环境

- **JDK 21**：`.mise.toml` 指定了 Temurin 版本，可用 [mise](https://mise.jdx.dev/) 安装。机器上没有合适的 JDK 时，`settings.gradle.kts` 中的 foojay 工具链解析插件会自动下载 JDK 21 工具链。
- **Android SDK**：Platform 37、Build Tools 37.0.0。设置 `ANDROID_HOME`，或在 `local.properties` 中写入 `sdk.dir=/path/to/android-sdk`。`offlineCheck` 同样需要 Android SDK。
- **Linux 运行库**：运行或测试桌面版需要 GTK 3、EGL / OpenGL 和 fontconfig。CI 在 Ubuntu 24.04 上安装的是：

  ```bash
  sudo apt-get install --yes libgtk-3-0t64 libegl1 libgl1 libfontconfig1 rpm libarchive-tools
  ```

  其中 `rpm` 和 `libarchive-tools` 用于打包 RPM / Pacman。真实运行桌面版控制耳机还需要已运行的 BlueZ、可访问的系统 D-Bus 和支持 BLE 的适配器（见 README 的“从源码构建”）。
- 仓库自带 Gradle Wrapper。Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。

```bash
git clone https://github.com/DOHEX/OpenRose.git
cd OpenRose
./gradlew :desktopApp:run              # 运行桌面版
./gradlew :androidApp:assembleDebug    # 构建 Android 调试 APK
```

## 模块结构

模块列表见 `settings.gradle.kts`，职责划分的依据是 [ADR 0012](docs/adr/0012-shared-multiplatform-architecture.md)。

| 模块 | 职责 |
| --- | --- |
| `:core` | 型号与能力、协议、控制会话、控制入口（`ControlRuntime`）、共享 GATT 层、已配对设备与音频连接推断、控制设置；不含 Android 概念 |
| `:androidIntegration` | Android 集成逻辑（控制方式与所有者交接、模块宿主与 IPC、系统呈现、融合设备中心映射、后台控制）；纯 Kotlin，在 JVM 上测试 |
| `:sharedUI` | 共享界面、表现层模型、平台插槽、Miuix / Material 3 组件与外观设置 |
| `:androidIntegrationUI` | Android 专属界面段落 |
| `:androidApp` | Android 入口与全部 Android API 胶水（GATT / SPP 驱动、libxposed 模块、HyperOS 系统桥接、AIDL） |
| `:desktopApp` | Windows / Linux 桌面入口：窗口、托盘与偏好设置，并装配运行时 |
| `:transportBluez` | Linux 平台驱动：BlueZ D-Bus GATT 原语、扫描、配对设备与音频事实 |
| `build-logic/` | 各模块共用的 Gradle 约定插件（独立的 included build） |

## 日常命令

| 命令 | 作用 |
| --- | --- |
| `./gradlew spotlessApply` | 格式化全部 Kotlin 与 Gradle Kotlin DSL（Spotless + ktlint，规则在 `.editorconfig`） |
| `./gradlew spotlessCheck` | 检查格式 |
| `./gradlew detekt` | 各模块静态分析，配置为 `config/detekt/detekt.yml` |
| `./gradlew detektBaseline` | 重新生成 `config/detekt/baseline/<module>.xml`。仅在有意接受既有问题时使用 |
| `./gradlew :androidApp:updateLintBaseline` | 重新生成 `androidApp/lint-baseline.xml`，同样仅在有意接受既有问题时使用 |
| `./gradlew koverHtmlReport` | 生成根项目聚合的覆盖率 HTML 报告 |
| `./gradlew koverVerify` | 校验行覆盖率不低于下限 |
| `./gradlew offlineCheck` | 运行全部不依赖硬件的测试，以及 `spotlessCheck`、`detekt`、lint 与 `koverVerify`。CI 执行的就是它 |

**提 PR 前必须通过 `./gradlew offlineCheck`。**

约束：

- Kotlin 编译器警告会使构建失败（`allWarningsAsErrors`），请直接修复警告。
- Android lint 的警告按错误处理；既有问题冻结在 `androidApp/lint-baseline.xml`。
- detekt 的既有问题冻结在 `config/detekt/baseline/<module>.xml`。不要为了让检查通过而向基线添加新条目；确有必要时请在 PR 中说明理由。
- Spotless 不会把 `.editorconfig` 的改动当作输入变化：修改 `.editorconfig` 后请运行 `./gradlew spotlessApply --rerun-tasks`。
- 全仓格式化提交记录在 `.git-blame-ignore-revs` 中。GitHub 的 blame 会自动跳过它；本地运行一次 `git config blame.ignoreRevsFile .git-blame-ignore-revs` 即可同样跳过。
- 增加或升级依赖后，运行 `./gradlew --write-verification-metadata sha256 help`，以及会解析该依赖的任务，然后检查 `gradle/verification-metadata.xml` 的差异再提交。

完整的 CI 还会构建 Android 调试 APK 并打包桌面版：

```bash
./gradlew check offlineCheck :androidApp:assembleDebug :desktopApp:packageDistributionForCurrentOS
```

## 提交规范

提交信息使用英文，遵循 [Conventional Commits](https://www.conventionalcommits.org/)：`feat`、`fix`、`refactor`、`docs`、`ci`、`build`、`test` 等类型，可带范围，例如 `feat(desktop):`、`fix(android):`。正文描述行为变化；如果做过验证，用一段 `Validation:` 说明跑了哪些检查、在什么设备上验证，以及哪些仍未验证。示例：

```text
feat(desktop): add tray panel and background control

<describe the behaviour change>

Validation: :sharedUI:jvmTest and :desktopApp:test pass; check offlineCheck
passes. GNOME and KDE are not verified on hardware.
```

PR 标题同样遵循这一格式，并由 `.github/workflows/pr-title.yml` 检查（类型见 `release-please-config.json`，另允许 `revert`）。PR 以 squash 方式合并，标题会成为 main 上的提交信息，发布内容和版本号都由它决定。

### 发布

发布由自动化完成，贡献者不需要手动打标签：

1. main 分支上的 release-please 根据 Conventional Commits 开出发布 PR，并维护 `CHANGELOG.md`。
2. 合并该发布 PR 会创建 `vX.Y.Z` 标签。
3. 标签触发 CI 打包，并生成草稿 GitHub Release。

因此提交类型要如实反映变更性质：`feat` 对应新功能，`fix` 对应缺陷修复。

每个发布附带 `SHA256SUMS`，所有安装包还带有由发布工作流签名的构建来源证明（SLSA provenance）。下载后可以这样校验：

```bash
sha256sum --check --ignore-missing SHA256SUMS
gh attestation verify OpenRose-android.apk --repo DOHEX/OpenRose
```

## 领域文档

- 引入或改变领域术语时，更新 [GLOSSARY.md](GLOSSARY.md)，并使用其中已有的用语；术语表中的 _Avoid_ 条目是应避免的说法。
- 架构决策记录在 `docs/adr/NNNN-kebab-title.md`（编号顺延，标题为中文）。文件以 `status: accepted` 的 YAML front matter 开头，随后是标题、说明背景的段落，以及“决策”“考虑过的方案”“后果”等小节，可参考 [ADR 0012](docs/adr/0012-shared-multiplatform-architecture.md) 和 [ADR 0014](docs/adr/0014-desktop-tray-and-background-control.md)。
- 修改前先阅读涉及区域的术语和 ADR。若修改与某条 ADR 冲突，请在 PR 中指出是哪一条，并说明为什么需要重新讨论。
- 面向用户的行为或说明变化时，同步更新 `README.md` 和 `README.en.md`。

## 验证与真机测试

OpenRose 区分两类验证，请在 PR 描述和文档中分别如实记录，不要把离线测试当作真机验证：

- **离线验证**：协议编解码、控制会话、BlueZ 假 D-Bus 驱动、界面测试等，由 `offlineCheck` 覆盖，不需要耳机。
- **真机验证**：在真实耳机、真实设备或桌面环境上执行。请写明耳机型号、设备 / 系统（或桌面环境与 BlueZ 版本）、OpenRose 版本，以及具体验证了哪些功能。

README 的各“验证情况”段落就是这种写法：已在真机验证的链路和只有离线测试的链路分开说明。新增型号或系统适配时，请分别记录协议依据、离线测试结果和真机测试结果；没有测过的部分明确写“未验证”。型号协议研究位于 [docs/protocols/](docs/protocols/)。

## 许可

OpenRose 以 GPL-3.0-or-later 授权（见 [LICENSE](LICENSE)）。提交贡献即表示你同意你的贡献以同一许可发布。
