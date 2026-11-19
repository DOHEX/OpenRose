---
status: accepted
---

# Android 端按运行进程拆分：模块、平台适配与应用

[ADR 0012](0012-shared-multiplatform-architecture.md) 把全部 Android API 胶水放在 `:androidApp`。同一 APK 里的代码却运行在两类进程：模块的代码由 LSPosed 加载进作用域内的系统进程（`com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`），其余代码在 OpenRose 应用进程。`:androidApp` 依赖共享界面与 Compose，模块代码引用界面、`Application` 或应用组件都能编译通过，要到系统进程加载时才出错；两侧边界只靠包名约定。`androidApp` 中的 `integration` 包还与 `:androidIntegration` 同名，含义相混。

## 决策

- **按运行进程拆成三个 Android 模块**，依赖方向为 `:androidApp` →（仅运行时）`:androidModule` → `:androidPlatform` → `:androidIntegration` → `:core`：
  - `:androidPlatform`：两类进程共用的 Android 适配：控制传输（BLE、SPP、音频连接事实）、平台事实读取、模块宿主 IPC 的 AIDL 与 Bundle 编码、状态通知点击目标的系统查询。不依赖界面，也不依赖 libxposed。
  - `:androidModule`：只在作用域进程内运行的模块代码：模块入口、模块宿主运行时与端点、原生耳机适配（包 `module.nativeheadset`）、系统呈现渲染与官方连接提示，以及 `META-INF/xposed` 描述与入口的 R8 保留规则。libxposed API 仅编译期依赖。
  - `:androidApp`：应用进程：Activity、服务、唤醒、设置存储、与 LSPosed 框架及模块宿主的连接（包 `framework`）和界面组装。
- **应用只在运行时依赖模块**：`runtimeOnly(projects.androidModule)` 把模块代码打进同一 APK，应用源码却引用不到模块的类；模块侧也编译不到应用与界面。跨进程只经 AIDL、Bundle 编码与组件类名字符串。
- **两个库用普通 `com.android.library`**：Android-KMP 库插件不支持 AIDL，而这两处代码只面向 Android，不需要 JVM 目标。纯逻辑仍放 `:androidIntegration`，在 JVM 上测试。
- **包与模块一一对应**：不再有跨模块的同名包。`:androidIntegrationUI` 的段落从 `io.github.dohex.openrose.ui` 移到 `io.github.dohex.openrose.androidintegration.ui`。

## 考虑过的方案

- **维持单模块、只改包名**：成本最低，但边界仍靠约定，模块代码误引界面类的问题照旧只能在实机发现。
- **只拆出模块、共用代码留在应用**：模块宿主要用控制传输与平台事实，模块就得依赖应用模块，形成反向依赖。
- **两个库改用 Android-KMP 库插件**：不支持 AIDL；加 JVM 目标也没有收益，这里的代码全是 Android API。

## 后果

- 原先 `internal` 的共用类型（控制传输、Bridge 编码、平台事实读取）改为 `public`，供两侧模块引用。
- 模块的 R8 保留规则随 `:androidModule` 以 consumer 规则发布；`META-INF/xposed` 由库的 Java 资源并入 APK，发布前核对 APK 内描述文件与入口类仍在。
- Android 单元测试分布到各模块；`offlineCheck` 与覆盖率报告纳入两个新模块。
