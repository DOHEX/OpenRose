# OpenRose

ROSE / ROSESELSA 耳机控制客户端，采用 Kotlin Multiplatform 共享核心和 Compose Multiplatform 界面。

产品规格：[首版 spec #1](https://github.com/DOHEX/OpenRose/issues/1)。当前交付是 [工程初始化 #2](https://github.com/DOHEX/OpenRose/issues/2)：可构建的 Android 客户端、Linux / Windows 共享桌面入口、界面外持有的公开控制会话，以及内置 Miuix / Material 3 组件风格。

**当前尚不能连接或控制耳机，也未实现 HyperOS Hook、跨进程桥接、系统超级岛或融合设备中心。** 界面显示实际权限、平台检测结果和未连接状态，没有演示设备或虚构电量。系统呈现设置目前仅保存偏好，不产生系统展示；工程初始化不等于首版验收完成。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools 37.0.0。Android 客户端最低 API 26（Android 8.0），targetSdk 36。项目使用 JVM 17 字节码。

可用 `mise install` 安装 `.mise.toml` 固定的 JDK，或自行将 `JAVA_HOME` 指向 JDK 21。通过 `ANDROID_HOME` 或未纳入 Git 的 `local.properties` 中的 `sdk.dir` 指定 SDK。首次构建需要网络下载固定版本的依赖；Gradle Wrapper 已包含在仓库中并校验发行包 SHA-256。

```bash
# Android 调试 APK
./gradlew :androidApp:assembleDebug
# 运行当前桌面平台的客户端
./gradlew :desktopApp:run
# 为当前桌面平台生成包含运行时的发行目录
./gradlew :desktopApp:createDistributable
# 全部工程检查、公共会话测试与编译
./gradlew check offlineCheck
# 缓存依赖后，无网络重复检查
./gradlew --offline offlineCheck
# 开发时只跑单个公共边界测试
./gradlew :core:jvmTest --tests '*ControlSessionTest'
```

Windows 使用 `gradlew.bat`。桌面入口共用于 Windows / Linux；发行包必须在对应操作系统构建。Linux 构建或启动结果不代表 Windows 已验证。

Android APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。桌面发行目录：`desktopApp/build/compose/binaries/main/app/`。

## 工程边界

- `core`：公开 `ControlSession.state`、平台事实和独立的界面 / 系统呈现偏好，不依赖组件库或蓝牙库。测试从公开会话和设置读取可观察结果。
- `composeApp`：共享界面；两套风格使用各自实际的按钮、开关、卡片和文字组件。界面接收会话快照和操作，不创建或持有控制会话。
- `androidApp`：进程级 `Application` 持有会话，检测权限、BLE 硬件与蓝牙开关；处理 Android 6–11 的位置权限和 Android 12+ 的蓝牙权限。偏好通过 `SharedPreferences` 保存。
- `desktopApp`：窗口创建前持有会话，通过 Java Preferences 保存偏好。尚未接入 BlueZ 或 Windows 蓝牙，明确呈现传输不可用。

超级岛默认“官方”、焦点通知默认开启，二者独立；模块触发默认“连接”，显式空集合保留。尚无可靠型号事件时不提供佩戴、摘下或入盒选项。界面风格与浅色 / 深色外观分别设置。设置和组件切换不创建新的控制会话；真实连接与在途命令的连续性需在后续传输实现后验收。

## 版本与验证

固定依赖见 `gradle/libs.versions.toml`；构建与平台验证记录见 [docs/verification/bootstrap.md](docs/verification/bootstrap.md)。领域约束见 [GLOSSARY.md](GLOSSARY.md) 和 [docs/adr](docs/adr)。
