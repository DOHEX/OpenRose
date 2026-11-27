---
status: accepted
---

# 构建约定：统一 JDK 工具链、lint 与覆盖率下限

各模块的构建由 `build-logic` 的约定插件统一配置，`offlineCheck` 汇总格式、lint、detekt 与覆盖率检查。[ADR 0021](0021-no-code-comments.md) 禁止在 `*.kt`、`*.kts` 中写注释，原先写在构建脚本、`config/detekt/detekt.yml`、`.editorconfig` 与 `gradle/libs.versions.toml` 注释里的理由集中记录在这里。

## 决策

### JDK 工具链

- **全部模块使用同一个 JDK**：`Conventions.kt` 的 `openRoseJdk` 固定 Java 21、供应商 Adoptium（Temurin）。`configureKotlinJvm` 用它设置 Kotlin 工具链与 `jvmTarget`，并开启 `allWarningsAsErrors`；Android 模块的 `compileOptions` 取同一 `javaVersion`。选 Temurin 是因为桌面打包会捆绑这个 JDK，需要它自带的 `jpackage`，而 IDE 自带的运行时没有。
- **桌面打包显式指定 JDK**：`DesktopApplicationConventionPlugin` 把 Nucleus 的 `application.javaHome` 设为 `openRoseJdkHome()`。不设置时，Nucleus 会用启动 Gradle 守护进程的那个 JVM 来运行和打包。
- **约定插件只编译、不加载第三方插件**：`build-logic/build.gradle.kts` 以 `compileOnly` 依赖 Kotlin Multiplatform、Android 应用、Nucleus、detekt 与 Kover 插件。根构建已用 `apply false` 把这些插件放上类路径，`build-logic` 只针对它们编译，所有项目因此只加载同一份插件。

### Rust 工具链

- **本地与 CI 用同一个 Rust 版本**：根目录的 `rust-toolchain.toml` 固定 Rust 版本，并带上 clippy 与 rustfmt；rustup 在仓库内自动安装它，CI 不另装 Rust。不固定时，CI runner 的 stable 比本地新，新版 clippy 的 lint（例如 1.100.0 的 `chunks_exact_to_as_chunks`）在 `-D warnings` 下让 CI 失败，而本地 `cargo xtask check` 照常通过。升级 Rust 时改这一个文件，并在同一次提交里修复新 lint。
- **Android 目标不写进工具链文件**：Android ABI 列表只在 `gradle/libs.versions.toml` 的 `android-abis`，CI 按它在固定的工具链上安装 Rust 目标。

### lint、格式与 detekt

- **不使用 baseline**：f91b9ae 撤掉了 baseline 文件与比较插件；`DetektConventionPlugin` 调用 `baseline.unsetConvention()`。`build-logic` 自身用与产品模块相同的 detekt 约定检查（根构建的 `detekt` 指向 `build-logic/src`）。
- **Android lint**：`AndroidApplicationConventionPlugin` 开启 `warningsAsErrors` 与 `checkDependencies`，但把 `AndroidGradlePluginVersion`、`GradleDependency`、`NewerVersionAvailable` 设为 `informational`。依赖升级由 Dependabot 提出；上游发布新版本不应让版本已固定的构建失败。
- **ktlint 与 IDE 格式一致**：Spotless 以 ktlint 检查全部 `*.kt` 与 `*.gradle.kts`，读取根目录 `.editorconfig`。`ktlint_code_style = intellij_idea`，与 `gradle.properties` 中 `kotlin.code.style=official` 下的 IDE 格式化器布局相同。
- **行宽 140，由两个工具分工**：`.editorconfig` 的 `max_line_length` 与 detekt 的 `MaxLineLength` 都是 140。ktlint 的换行规则会把代码重排到 140 列以内，因此关闭 ktlint 的 `max-line-length` 规则；它拆不开的行交给 detekt 的 `MaxLineLength`。测试与测试夹具源码（`src/test`、`src/*Test`、`src/*TestFixtures`）不检查行宽：断言和夹具写在一行更易读，ktlint 的换行规则仍会格式化它们。
- **允许通配符导入**：IDE 会把导入折叠成通配符，因此 ktlint 关闭 `no-wildcard-imports`，detekt 关闭 `WildcardImport`，三者保持一致。
- **detekt 在默认配置上覆盖**（`buildUponDefaultConfig`）：
  - `ReturnCount` 排除守卫子句：校验输入后提前返回是协议解析器的惯用写法。
  - 关闭 `MagicNumber`：协议帧、界面尺寸和测试中到处是字面字节与尺寸。
  - Composable 是 PascalCase 函数：detekt 的 `FunctionNaming` 与 ktlint 的函数命名规则都忽略 `@Composable`。
  - Composable 通过参数接收状态与回调：`LongParameterList` 与 `LongMethod` 忽略 `@Composable`；`TooManyFunctions` 忽略 `@Composable` 与 `@Test` 函数。
  - `TopLevelPropertyNaming` 的常量命名模式为 `[A-Z][A-Za-z0-9_]*`。
- **注释规则**：`DetektConventionPlugin` 给每个模块（`tools/detekt-rules` 自身除外）接入 `openrose:NoComments`，并把 `config/detekt/comment-budget.txt` 声明为 `Detekt` 任务输入，见 ADR 0021。

### 覆盖率下限

- **一份覆盖率报告**：根构建通过 `kover(...)` 汇总每个模块的 JVM 测试，生成一份报告。
- **只统计 `coverage` 变体**：`KoverConventionPlugin` 在每个模块和根构建创建 `coverage` 报告变体，包含 JVM 目标（`jvm`）、KMP 的 Android 目标（`android`）与 Android 模块的 `debug` 构建变体；`check` 只校验这个变体，关闭 total 变体的校验。total 变体包含 Android `release` 构建变体，会让 `check` 额外编译三个 Android 模块的 release 代码，而单元测试只跑 debug。报告与校验用 `koverHtmlReportCoverage`、`koverVerifyCoverage`。
- **下限只升不降**：下限设在设定时实测覆盖率之下一点，根报告设定时总行覆盖率为 56.8%，下限 `minBound(55)`。模块级下限另有 `shared/headset` 93、`platform/android/integration` 90，均在 `offlineCheck` 中经 `koverVerifyCoverage` 检查。覆盖率提高后上调下限；不得为通过检查而下调。

### 工具版本

- **detekt 用 2.x**（当前 `2.0.0-alpha.6`）：detekt 2.x 内嵌 Kotlin 2.4，1.23 系列无法解析本项目的 Kotlin 2.4 源码。
- **单独固定 Compose Hot Reload**（当前 `1.3.0-rc01`）：覆盖 Compose 插件捆绑的 Hot Reload 1.2.0，后者使用的 Gradle API 在 Gradle 10/11 中已弃用。

## 考虑过的方案

- **使用 IDE 自带的运行时或启动 Gradle 的 JVM 打包**：IDE 运行时没有 `jpackage`；Nucleus 默认取守护进程的 JVM，打包结果取决于谁启动了 Gradle。
- **detekt 1.23 系列**：无法解析 Kotlin 2.4。
- **使用 Compose 插件捆绑的 Hot Reload 1.2.0**：其 Gradle API 在 Gradle 10/11 中已弃用。
- **把依赖更新提示当作 lint 错误**：Dependabot 已负责提出升级，上游一发布新版本就会让固定版本的构建失败。

## 后果

本地与 CI 首次构建会下载 Temurin 21 工具链。新增模块应用约定插件后自动获得同一 JDK、`allWarningsAsErrors`、detekt 与 Kover；新增产品模块还需加入根构建的 `kover(...)` 与 `offlineCheck`。由于不用 baseline，任何新的 lint 或 detekt 问题都必须当场修复；需要 `@Suppress` 时，理由写进 ADR 或 issue，而不是行尾注释。
