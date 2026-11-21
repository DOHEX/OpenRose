# OpenRose 架构重设计方案

方案已采纳。[实施决策](adr/0020-client-and-platform-modules.md)

分析基于重构前的 `6afb54a`。目标是让 Android、Linux 和 Windows 共享同一套耳机行为，让平台入口负责组装，并让开发者打开仓库就能找到改动位置。旧 Gradle 路径、Kotlin 接口和 OpenRose 两端之间的消息格式可以一起替换。耳机固件协议、Android 系统入口和已交付的产品行为仍是设计输入。[KMP 推荐结构](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html) · [现有模块表](../CONTRIBUTING.md#模块结构)

## 现状里的三条调用链

| 用户动作 | 实际经过的位置 | 对设计的影响 |
| --- | --- | --- |
| 在 Android 快捷面板改降噪 | 共享界面 → 控制入口 → 所有者路由。普通控制进入应用内会话，模块控制经过一次 Binder，由蓝牙宿主校验并提交到会话的设置队列 | 界面需要指定它看到的会话和目标模式。租约、所有者代际、耳机报文和重试分别属于 Android 集成与本地会话。[控制入口](../shared/headset/src/commonMain/kotlin/io/github/dohex/openrose/core/ControlRuntime.kt) · [所有者路由](../platform/android/integration/src/commonMain/kotlin/io/github/dohex/openrose/androidintegration/SwitchingControlRuntime.kt) · [Binder 宿主](../platform/android/system-module/src/main/kotlin/io/github/dohex/openrose/module/HostEndpoint.kt) |
| 在电脑上选择「接到这台电脑」 | 界面拼字符串请求 → 桌面分派 → 流转服务 → 请求持有方让出 → 调用本机音频适配。查询本机默认输出和打开声音设置也进入同一套分派 | 音源设备之间的流转有独立的执行顺序。本机桌面操作应直接交给当前桌面环境。[界面客户端](../shared/circulation/src/commonMain/kotlin/io/github/dohex/openrose/circulation/CirculationClient.kt) · [桌面分派](../desktopApp/src/main/kotlin/io/github/dohex/openrose/desktop/DesktopCirculation.kt) · [接入顺序](../shared/circulation-lan/src/main/kotlin/io/github/dohex/openrose/circulation/HeadsetCirculation.kt) |
| 在沙盒选择 FirstAngel，查看均衡器 | 真实会话用固件和已确认的游戏模式计算型号能力。沙盒构造了游戏模式关闭的读数，却只传固件计算能力。这个型号在音乐模式下应给出 `hifi`、`pop`，在游戏模式下应给出 `game_1` | 沙盒可以模拟耳机读数。哪些控件可用应由生产与预览共用的函数推导。[会话读数](../shared/headset/src/commonMain/kotlin/io/github/dohex/openrose/core/ConnectionReadings.kt) · [沙盒状态](../tools/ui-sandbox/src/main/kotlin/io/github/dohex/openrose/sandbox/SandboxState.kt) · [型号行为测试](../shared/headset/src/commonTest/kotlin/io/github/dohex/openrose/core/protocol/RsV2ModelVariantsTest.kt) |

另一个可直接从构建图验证的问题是，沙盒只从桌面应用导入五个展示相关类型，运行类路径却包含整个 `desktopApp`、两个蓝牙驱动和局域网流转实现。Android 专属界面测试还通过相对路径把另一模块的测试源码编译进来。这些依赖会让移动目录、构建沙盒或修改测试支持都牵动无关代码。[沙盒导入](../tools/ui-sandbox/src/main/kotlin/io/github/dohex/openrose/sandbox/SandboxPlatforms.kt) · [跨目录测试配置](../platform/android/ui/build.gradle.kts)

## 目标布局与依赖

源码在根目录有五个入口。分组目录用于导航，每个叶子目录的构建类型取决于它的消费者。

```text
androidApp/                 Android 应用进程、Activity、服务、权限与组装
desktopApp/                 桌面窗口、原生托盘、启动与打包
shared/
  headset/                  KMP，无界面依赖。型号、协议、会话与控制入口
  circulation/              KMP，无界面依赖。流转资格、命令、结果与远端会话引用
  circulation-lan/          JVM。实际流转、发现、配对、对端认证及网络收发
  ui/                       CMP。共享页面、快捷面板、托盘面板与展示模型
platform/
  android/
    integration/            KMP。普通／模块控制、所有者交接、系统入口与呈现规则
    bluetooth-ipc/          Android 库。两进程共用的蓝牙操作、AIDL 与 Binder 编解码
    system-module/          Android 库。LSPosed 入口、模块宿主与手机流转端点
    ui/                     CMP。Android 界面段落，供手机与桌面沙盒使用
  linux/                    JVM。BlueZ、音频让出与接入、系统输出与设置
  windows/                  JVM ＋ Rust。WinRT、音频端点与系统设置
tools/
  ui-sandbox/               JVM。预览入口与场景生成
  ui-test-support/          JVM。两套界面测试实际共用的假模型和场景工具
```

- `shared/ui` 依赖 `headset` 与 `circulation` 的类型。共享页面在 `commonMain`，供桌面应用和沙盒共用的桌面设置段落在 `jvmMain`，由宿主传入托盘状态、登录启动操作和打开系统设置等动作。沙盒依赖共享 UI 和 Android 界面段落，UI 测试通过测试依赖取得夹具。Linux 与 Windows 共用桌面 JVM 入口。
- `circulation` 依赖 `headset` 的型号和会话类型，并定义让出、接入音频所需的平台操作。手机应用通过 Binder 实现面向界面的流转接口，桌面应用直接连接 `circulation-lan`。后者作为普通 JVM 库供桌面应用及 Android 模块宿主使用，Java 网络与 TLS 类型只出现在实现侧。这个拆分让 CMP 的 `commonMain` 能使用流转契约，同时避免把网络服务装进沙盒。Linux 和 Windows 各自实现本机音频操作，桌面入口选择驱动，并把本机输出查询及系统设置动作直接交给桌面界面。[现有 JVM 实现](../shared/circulation-lan/build.gradle.kts)
- Android 应用与系统模块都使用 `bluetooth-ipc`，应用对系统模块保留运行时打包依赖。网络发现由模块宿主拥有。手机音频适配继续复用 Android 蓝牙代理租约，租约的获取和释放封装在蓝牙库内。宿主显式依赖局域网实现，应用的编译依赖只需流转契约。Android 专属界面段落单独编译，使沙盒能预览手机设置，系统进程的代码也能在编译期与 Compose 隔离。[Android 进程拆分](adr/0018-android-module-and-platform-split.md) · [音频代理租约](../platform/android/bluetooth-ipc/src/main/kotlin/io/github/dohex/openrose/platform/PhoneCirculationAudio.kt)

这个方案有 12 个产品构建模块与 2 个开发模块。相较现有布局，多出来的是跨端流转契约和共用 UI 测试库。两者各自消除一条已经存在的依赖。Gradle 的 `build-logic`、文档、网站和检查配置继续承担仓库配套工作。

## 两个对外接口

### 耳机控制

所有入口通过同一个控制会话观察耳机、提交设置。界面模型通过 `model.set(sessionId, HeadsetCommand.BassBoost(2))` 提交低音增强，控制入口通过 `runtime.set(HeadsetCommand.NoiseMode(mode), expectedSessionId = sessionId)` 提交降噪。每项操作都携带相应类型的值。现有接口允许把布尔值与低音增强配在一起，直到运行时才被拒绝。改用类型化命令之后，型号档位范围仍须在入队时和实际发送前按当前会话校验，成功仍以耳机的新回读为准。[现有队列](../shared/headset/src/commonMain/kotlin/io/github/dohex/openrose/core/SettingQueue.kt) · [不同型号的低音增强范围测试](../shared/headset/src/commonTest/kotlin/io/github/dohex/openrose/core/ControlSessionAdvancedAudioTest.kt)

`headset` 根据型号、固件、已确认的读数推导各项控制的当前值、可选项和可操作条件。主页、快捷面板、系统入口和沙盒复用这个结果。均衡器曲线仍有专门的编辑与回读语义。完整原始字段、帧和警告通过诊断视图提供。协议族内部继续负责字节布局、响应匹配及硬件限制；型号识别测试保留独立的已知报文，避免用被测配置自动生成期望值。

### 耳机流转与远程控制

界面使用配对、读取远端耳机、写入远端降噪和「接到这里」等类型化操作。远端写入必须携带上次读取的对端、耳机和会话引用。耳机流转改变音频连接，耳机远程控制沿当前持有方的控制入口提交。对端明确拒绝让出时，发起方保留原音频连接；已让出与全部对端不可达两种情况按各自规则处理。[流转产品决策](adr/0017-headset-circulation.md) · [两端回环测试](../shared/circulation-lan/src/test/kotlin/io/github/dohex/openrose/circulation/CirculationNodeTest.kt)

流转与远程控制的型号资格由 `shared/circulation` 给出，首批沿用 i5。界面、接入、远端读写都查询同一规则。桌面 i5 在控制初始化期间优先读降噪是另一项会话时序行为，仍归 `headset`。局域网消息与 Binder 消息分别在各自边缘编码。系统入口保留按调用者角色裁剪的视图；同一设备的一次写入仍由一个所有者、一个队列完成。[现有远端读写](../shared/circulation-lan/src/main/kotlin/io/github/dohex/openrose/circulation/HeadsetEndpoint.kt) · [本地控制会话决策](adr/0011-dual-host-single-control-session.md)

## 实施顺序与验收

| 顺序 | 可独立完成的变更 | 如何判断完成 |
| --- | --- | --- |
| 1 | 在现有路径统一型号能力推导。用 FirstAngel 预设和低音增强贯通第一条类型化路径，随后迁移其余可写设置、跨进程命令和诊断视图 | 给真实会话与沙盒相同的读数，二者提供相同选项。OpenFeel MK2 拒绝第 3 档，BudsFeel MK2 接受其支持的档位。错误类型在调用处无法构造。过期会话和未确认读回仍有明确的失败结果 |
| 2 | 抽出类型化流转契约，改造桌面直连、手机 Binder 客户端和模块宿主，同时移走本机声音设置的字符串分派 | 两端回环覆盖配对撤销、过期远端会话、拒绝让出与成功接入。断开局域网流转服务仍能打开本机声音设置。共享 UI 不解析流转消息字段 |
| 3 | 拆出可在桌面 JVM 上复用的展示代码和共用测试夹具 | 沙盒仍能渲染两种界面风格、Android 段落和桌面托盘面板。其 Gradle 运行类路径没有两个平台驱动、应用入口与流转网络服务。两套 UI 测试分别运行，构建脚本无需读取相邻模块的源码目录 |
| 4 | 按目标树迁移 Gradle 路径，更新约定插件、质量任务、IDE 运行配置、CI 和打包产物路径。将 Android 手机流转、Linux 与 Windows 音频操作归到实际运行位置 | 全部叶子模块参加对应的检查。Android 发布 APK 包含系统模块入口、资源和 R8 保留项，系统模块编译图不含应用与 Compose。Linux 和 Windows 分别打包并检查本机驱动。真实蓝牙、默认音频输出和跨设备流转分别按平台实测 |

只给现有目录加父目录，可以改善浏览，却会保留沙盒的生产依赖和两套能力推导。把逻辑、界面和平台驱动全并进单个共享库，会让不显示界面的系统宿主依赖 Compose。目标布局在共享控制和进程隔离上沿用 ADR 0011、0012、0018，在 UI 所见的控制类型、流转实现的归属和测试依赖上重新划界。[多端架构决策](adr/0012-shared-multiplatform-architecture.md)

设计时已在基线运行五组测试，59 项全部通过，覆盖型号变体、高级音频设置、Android 所有者交接、耳机远程控制和局域网流转。另通过 Gradle 的 `:uiSandbox:dependencies --configuration runtimeClasspath` 核实了沙盒的运行依赖。实施后，整仓离线检查覆盖 792 项 JVM／Android 测试。Android APK 及发布包的系统模块入口通过核验；手机与 Linux 的读回、实际音频与双向流转见[实机记录](testing/linux-phone-i5-circulation.md#共享模块重构后的双机回归)。
