# OpenRose

ROSE / ROSESELSA 耳机控制客户端，采用 Kotlin Multiplatform 共享核心和 Compose Multiplatform 界面。

产品规格：[首版 spec #1](https://github.com/DOHEX/OpenRose/issues/1)。当前工程提供可构建的 Android 客户端、Linux / Windows 共享桌面入口、界面外持有的公开控制会话、内置 Miuix / Material 3 组件，以及 Android 原生 BLE 发现、识别和状态读取链路；i7 在已确认的条件下使用专用 SPP 串口控制。各型号设置使用同一权威会话、串行队列和设备回读确认。

**首版尚未完成，Android 真实耳机验收仍待设备。** 已实现初始蓝牙进程 Hook、受校验的会话桥接，以及连接提示策略、模块岛模板和独立状态通知适配；系统操作回到同一控制队列。兼容框架中的正向调用及 HyperOS 实际展示尚未验收。官方提示所需 OEM 资源和限定撤销范围未核实，目前明确报告不可用；融合设备中心尚未实现。协议测试重放 i5 独立 Linux 读取样本，i7 RX 和设置测试使用明确标注的合成数据。界面只显示实际权限、扫描结果和设备返回值；离线验证不代表 Android 真实耳机或系统展示已通过验收。

## 当前控制范围

下表记录实现与离线验证范围，所有设置仍待真实耳机验收。

| 功能 | i5 ZT | i7 RS Common V2 |
| --- | --- | --- |
| 识别与状态读取 | 已接入 Android BLE，保留已有 Linux 读取样本 | 已接入 Android BLE，RX 为合成测试 |
| 控制连接 | BLE | 按型号、平台及实际音频 profile 证据选择 BLE / 专用 SPP；可强制 BLE |
| 降噪 | 普通 / 通透 / 抗风 / ANC，档位与轮换 | 另有自动 / 动态模式，档位与轮换 |
| 预设 EQ / 游戏 | 四套预设及游戏开关 | 四套基础预设；EAP 需已知固件整数 ≥152；游戏开关 |
| 自定义 EQ | 不提供 | 十段整数草稿，两步发送，分别确认模式和曲线 |
| 触控 / 检测 | 左右四种手势、八种动作、触控及检测功能开关 | 左右四种手势、九种动作、触控及检测功能开关 |
| 提示音 | 五档 | 基础五档；关闭需已知固件整数 ≥304 |
| 增益 / 保护 | 不提供 | 已知固件整数 ≥213 时提供型号档位及阈值标签 |
| 音频 / 多点 | AAC/SBC 与多点开启、LDAC 与多点关闭联动 | AAC/SBC / LDAC / LHDC / LC3 与多点分别设置 |

未知固件不开放受限功能。自定义 EQ 的草稿、发送进度和设备确认分开显示，部分完成与失败保留各自事实；触控绑定不立即执行动作，检测功能开关不代表真实佩戴事件，音频配置也不代表系统链路已完成对应 codec 协商。

i7 只有在广播提供可用 BR 地址、平台支持串口，并确认连接的是传统音频 profile 时才选 SPP；LE Audio 或音频类型未知时使用 BLE。两种控制连接复用同一协议、队列和确认流程。“强制使用 BLE”保存为偏好，仅影响下次连接，不切断当前连接或重放设置。SPP 的系统配对、真实连接选择和控制仍待 i7 实机验证。

Android BLE 初始化阶段遇到明确的服务、订阅或首次读取故障时，至多关闭旧链路并恢复一次，重新读取可信状态。已就绪后的断连由用户重新连接，旧队列和设置不会自动重放。恢复次数及原因在界面显示；真实手机的服务缓存、延迟发布和断连恢复仍待实机验证。

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
- `androidApp`：进程级 `Application` 持有会话，检测权限、BLE 硬件与蓝牙开关；处理 Android 8–11 的位置权限和 Android 12+ 的蓝牙权限；原生 GATT 发现准确特征、先订阅 CCCD、协商 MTU，再按共享队列发送完整查询。音频 profile 使用公开系统接口查询，SPP 使用 i7 专用 UUID 的 RFCOMM 流；取消和断开关闭对应资源。偏好通过 `SharedPreferences` 保存。
- `desktopApp`：窗口创建前持有会话，通过 Java Preferences 保存偏好。尚未接入 BlueZ 或 Windows 蓝牙，明确呈现传输不可用。

超级岛默认“官方”、焦点通知默认开启，二者独立；模块触发默认“连接”，显式空集合保留。尚无可靠型号事件时不提供佩戴、摘下或入盒选项。界面风格与浅色 / 深色外观分别设置。设置和组件切换不创建新的控制会话。请求值和已确认设备状态分别显示，提交或底层写入完成均不视为成功；超时保留明确结果并结束旧链路，重新连接不重放设置。连接期间的风格切换、实际字段语义和设备返回结果仍需真实 Android 耳机验收。

## 版本与验证

固定依赖见 `gradle/libs.versions.toml`；初始化构建与平台验证记录见 [docs/verification/bootstrap.md](docs/verification/bootstrap.md)，读取协议来源与限制见 [docs/protocols.md](docs/protocols.md)，降噪验证见 [docs/verification/noise-controls.md](docs/verification/noise-controls.md)，预设 EQ / 游戏验证见 [docs/verification/equalizer-game.md](docs/verification/equalizer-game.md)，触控与音频验证见 [docs/verification/device-controls.md](docs/verification/device-controls.md)，十段 EQ 与固件能力验证见 [docs/verification/advanced-audio.md](docs/verification/advanced-audio.md)。i7 传输选择见 [docs/verification/i7-transports.md](docs/verification/i7-transports.md)。连接恢复见 [docs/verification/connection-recovery.md](docs/verification/connection-recovery.md)。双风格控制与桌面输入见 [docs/verification/interface-styles.md](docs/verification/interface-styles.md)。系统会话桥接见 [docs/verification/system-bridge.md](docs/verification/system-bridge.md)。系统展示策略与通知见 [docs/verification/system-presentation.md](docs/verification/system-presentation.md)。领域约束见 [GLOSSARY.md](GLOSSARY.md) 和 [docs/adr](docs/adr)。
