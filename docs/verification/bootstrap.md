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

- Java 21 下 `:core:jvmTest`：10 个公开会话 / 设置测试通过。覆盖真实未连接状态、平台证据可观察、独立设置默认值、界面风格变更、显式空触发集合、可变输入隔离、固定持久化格式和未知值处理。
- `:composeApp:compileKotlinJvm`：通过，使用发布的 Miuix / Material 3 真实组件。
- `:androidApp:assembleDebug`：通过，生成 APK。
- Linux `:desktopApp:createDistributable`：通过，生成自带 Java 运行时的发行目录。
- Linux 发行目录实际启动：在隔离 Xvfb 显示器中渲染界面，正确显示未连接、Linux 传输尚未实现、Android 专属模块不可用。Xvfb 无 GL 上下文，Skiko 回退软件渲染后正常显示。

## 待补充记录

- Android API 26 模拟器安装、启动与权限 / 设置持久化验证：进行中。
- 全套工程检查、离线重复检查与双轴代码审查：进行中。
- Windows：未在 Windows 构建、启动或实测。

## 首版验收尚未完成

本次没有真实 ROSE 蓝牙链路、设备 RX / TX、设备固件记录或 HyperOS 系统组件验收。i5 / i7 识别、协议、设置确认、恢复、Android 控制宿主与跨进程桥接、现代 libxposed Hook、HyperOS 3 / 4 岛与通知及融合设备中心、Linux / Windows 真实传输均属于后续实施与验收。模拟器不能替代小米 14 HyperOS 4、i5 / i7 或其他目标平台实测。
