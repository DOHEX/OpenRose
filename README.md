# OpenRose

ROSE / ROSESELSA 耳机控制客户端，采用 Kotlin Multiplatform 共享核心和 Compose Multiplatform 界面。

产品规格：[首版 spec #1](https://github.com/DOHEX/OpenRose/issues/1)。已建立 [工程初始化 #2](https://github.com/DOHEX/OpenRose/issues/2)，并推进 [i5 读取 #3](https://github.com/DOHEX/OpenRose/issues/3) / [i7 读取 #4](https://github.com/DOHEX/OpenRose/issues/4)：可构建的 Android 客户端、Linux / Windows 共享桌面入口、界面外持有的公开控制会话、内置 Miuix / Material 3 组件，以及 Android 原生 BLE 发现、识别和状态读取链路。

**Android 真实耳机验收尚未完成，目前未开放设置写入，也未实现 HyperOS Hook、跨进程桥接、系统超级岛或融合设备中心。** Android BLE 传输已实现并接入共享会话；协议测试重放 i5 独立 Linux 读取样本，i7 RX 仅使用明确标注的合成缓冲区。这些结果不代表 Android 真实耳机已支持。界面仅显示实际权限、扫描结果和设备返回值，没有演示设备或虚构电量。系统呈现设置目前仅保存偏好，不产生系统展示；阶段实现不等于首版验收完成。

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

- `core`：公开 `ControlSession` 提供扫描、连接、状态观察、能力查询、断开与明确的不可用结果；纯 Kotlin i5 ZT / i7 RS Common V2 编解码与平台传输分开。测试从公开会话和独立协议向量读取可观察结果。
- `composeApp`：共享界面；两套风格使用各自实际的按钮、开关、卡片和文字组件。界面接收会话快照和操作，不创建或持有控制会话。
- `androidApp`：进程级 `Application` 持有会话，检测权限、BLE 硬件与蓝牙开关；处理 Android 8–11 的位置权限和 Android 12+ 的蓝牙权限；原生 GATT 发现准确特征、先订阅 CCCD、协商 MTU，再按共享队列发送完整查询。偏好通过 `SharedPreferences` 保存。
- `desktopApp`：窗口创建前持有会话，通过 Java Preferences 保存偏好。尚未接入 BlueZ 或 Windows 蓝牙，明确呈现传输不可用。

超级岛默认“官方”、焦点通知默认开启，二者独立；模块触发默认“连接”，显式空集合保留。尚无可靠型号事件时不提供佩戴、摘下或入盒选项。界面风格与浅色 / 深色外观分别设置。设置和组件切换不创建新的控制会话；连接期间的风格切换、实际字段语义和设备返回结果仍需真实 Android 耳机验收。

## 版本与验证

固定依赖见 `gradle/libs.versions.toml`；初始化构建与平台验证记录见 [docs/verification/bootstrap.md](docs/verification/bootstrap.md)，读取协议来源与限制见 [docs/protocols.md](docs/protocols.md)。领域约束见 [GLOSSARY.md](GLOSSARY.md) 和 [docs/adr](docs/adr)。
