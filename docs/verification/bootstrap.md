# 工程初始化验证记录

日期：2026-10-18（Asia/Shanghai）。范围：[工程初始化 #2](https://github.com/DOHEX/OpenRose/issues/2)，属于 [首版 spec #1](https://github.com/DOHEX/OpenRose/issues/1) 的中间里程碑。

## 固定工具链

| 项目 | 固定版本 / 设置 |
| --- | --- |
| JDK | Eclipse Temurin 21.0.11+10，Gradle/Kotlin toolchain 21 |
| JVM 字节码 | 17 |
| Gradle Wrapper | 9.7.0，固定发行包 SHA-256 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin / Compose compiler | 2.4.20 |
| Compose Multiplatform | 1.12.0 |
| Miuix | 0.9.4 |
| Material 3 | 1.12.0-alpha03，显式锁定预发布版，与该 Compose 组件矩阵一致 |
| Coroutines | 1.11.0 |
| Activity Compose | 1.13.0 |
| Android compileSdk / Build Tools | 37 / 37.0.0 |
| Android minSdk / targetSdk | 26 / 36 |

版本选择依据：[Kotlin 兼容表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)、[AGP 9.3 要求](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[Miuix 0.9.4](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4)、[Compose 1.12.0 组件矩阵](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0)。Miuix 发布 AAR 的最低 API 为 24、最低 compileSdk 为 37。当前项目主动选择 minSdk 26，为后续现代 libxposed Service 集成预留基线；当前未引入 libxposed，也不宣称模块可用。正式集成时需重新核实 API / Service 的最新正式版及目标系统兼容性。

## 已执行

- Java 21 下 `:core:jvmTest`：10 个公开会话 / 设置测试通过，`check` 也在 Android host JVM 目标执行同样的 10 个用例（不是设备测试）。覆盖真实未连接状态、平台证据可观察、独立设置默认值、界面风格变更、显式空触发集合、可变输入隔离、固定持久化格式和未知值处理。
- `:composeApp:compileKotlinJvm`：通过，使用发布的 Miuix / Material 3 真实组件。
- `:androidApp:assembleDebug`：通过，生成 APK。
- Linux `:desktopApp:createDistributable`：通过，生成自带 Java 运行时的发行目录。
- Linux 发行目录实际启动：在隔离 Xvfb 显示器中渲染界面，正确显示未连接、Linux 传输尚未实现、Android 专属模块不可用。Xvfb 无 GL 上下文，Skiko 回退软件渲染后正常显示。

- Linux 桌面交互：鼠标实际切换 Miuix → Material 3；重启后通过 5 次 Tab 和 Space 在 Miuix 选择深色，界面保持未连接状态。Java Preferences 在隔离用户目录保存；界面截图在任务本地 `.scratch/desktop/` 保留。
- `check offlineCheck`：通过，覆盖全部现有测试、共享 / 平台编译和 Android Lint。初次下载 Google Maven 工具遇到 TLS 连接中断，重试成功；没有修改仓库源或绕过检查。
- `--offline offlineCheck`：通过；在后续 Android 修复后重新执行仍通过。
- 两项修复后 `:androidApp:assembleDebug :androidApp:lintDebug`：通过。Lint 为 0 错误、6 警告（targetSdk 36 尚非最新、API 31 权限标记、备份规则、应用图标及两条 KTX 建议），未关闭检查。

## Android 最低版本安装与启动

使用官方 API 26 default x86_64 镜像，指纹 `Android/sdk_phone_x86_64/generic_x86_64:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`，480×960、KVM / SwiftShader，在隔离无窗口模拟器安装验证。它没有 BLE 硬件，不能替代任何真实耳机验收。

1. `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`：成功；`am start -W -n io.github.dohex.openrose/.MainActivity`：`Status: ok`。
2. 初始 Miuix 显示未连接、无设备数据、需要权限、没有 BLE 硬件、模块尚未接入。
3. 实际 Android 位置权限弹窗选择拒绝：显示未获得蓝牙权限；再次请求并允许后显示已授权。无 BLE 与未连接状态保持真实。
4. 切换 Material 3、手动深色后，确认设置已保存，force-stop 再启动：风格、外观和权限状态保留。页面滚动可到达系统呈现设置，并明确提示偏好尚不产生系统展示。
5. 初次发现手动深色下系统栏图标对比不足；按应用外观同步系统栏样式后重新构建安装，深色为白图标、浅色为深图标，均正常。升级安装也保留已有设置。
6. 最终 APK SHA-256：`20637a04add0f368e636619c95e3f91b74d8de1788061fe45553e17f8b20503a`；复验启动耗时 362ms，崩溃日志为空。

模拟器官方镜像下载前核对仓库元数据与 SHA-1，新增 SDK / AVD / adb 密钥和服务均隔离在任务 `.scratch/emulator/` 中。完整命令、界面层级、截图与日志在本地 `.scratch/emulator-report.md` 和 `.scratch/emulator/` 保留。来源：[模拟器命令行文档](https://developer.android.com/studio/run/emulator-commandline)、[官方系统镜像清单](https://dl.google.com/android/repository/sys-img/android/sys-img2-4.xml)。

Windows：未在 Windows 构建、启动或实测。

## Standards

无可操作的规范问题。会话归平台宿主持有，组件库留在共享界面层；设置符合领域术语与 ADR，显式空集合得到保留。未发现其他贡献 / 编码规范，也没有应当报告的启发式代码异味。

## Spec

审查 #2 发现 1 项 P2：仅在 Activity 恢复时检测蓝牙，会漏掉前台期间的开关变化。已由 Application 生命周期的 `ACTION_STATE_CHANGED` 监听修复，重新读取系统事实，复查确认解决。无剩余可操作的 #2 规格问题。模拟器没有 BLE，此监听的真实硬件广播行为仍需实机验证。

并行两轴审查基于 `1506220` 至工程初始化提交及后续修复。最终 Standards 0 项、Spec 0 项未解决。两套组件和保存偏好属于父规格授权范围；下列首版剩余工作不计作 #2 已完成能力。

## 首版验收尚未完成

本次没有真实 ROSE 蓝牙链路、设备 RX / TX、设备固件记录或 HyperOS 系统组件验收。i5 / i7 识别、协议、设置确认、恢复、Android 控制宿主与跨进程桥接、现代 libxposed Hook、HyperOS 3 / 4 岛与通知及融合设备中心、Linux / Windows 真实传输均属于后续实施与验收。模拟器不能替代小米 14 HyperOS 4、i5 / i7 或其他目标平台实测。
