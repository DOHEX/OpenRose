# niri 下 Linux 客户端窗口黑区

2026-10-21，修复基线 `21fe307`。用户报告启动后只绘制左上角，窗口右侧及下方为黑区。已在同一 niri / XWayland 会话、125% 显示缩放下复现，并验证 Linux 启动入口的修复；没有更改用户已有窗口、niri 配置或显示缩放。

## 原因与范围

Linux 发行目录使用 Temurin 21.0.11、Compose 1.12.0、Skiko 0.150.1。隔离的生产 Main 窗口报告 `XToolkit`、`OPENGL`、AWT transform 1.0。niri 的实际窗口为 1265×1378 像素，而 Java 仍认为窗口为初始 920×760、客户区为 910×730；后续绘制没有覆盖新尺寸。

精确版本的 Temurin `lib/src.zip` 中，`java.desktop/sun/awt/X11/XWM.java:605–611` 将 `_JAVA_AWT_WM_NONREPARENTING` 是否存在作为非重设父窗口的判断。`XDecoratedPeer.java:796–800` 在判断不成立时可忽略已显示、带装饰且尚未 reparent 的窗口的 `ConfigureNotify`；允许事件后更新客户区尺寸，并由 `XWindowPeer` 发出组件尺寸变化事件，Compose 再据此更新场景。

因此这是当前 Java AWT X11 路径与 niri 非重设父窗口环境的兼容问题，不能概括为所有 niri 窗口或所有 Wayland 程序都有问题。主要来源为本机精确版本的 JDK 源码与 [OpenJDK XWM](https://github.com/openjdk/jdk21u/blob/master/src/java.desktop/unix/classes/sun/awt/X11/XWM.java)、[XDecoratedPeer](https://github.com/openjdk/jdk21u/blob/master/src/java.desktop/unix/classes/sun/awt/X11/XDecoratedPeer.java)。[niri 官方 Xwayland 说明](https://github.com/niri-wm/niri/blob/main/docs/wiki/Xwayland.md) 提供 X11 兼容环境背景，没有把这个 Java 变量列为通用配置要求。

## 修复

新增仓库入口 `run-linux.sh`：

```bash
./run-linux.sh                 # 使用已构建、自带运行时的 Linux 客户端
./run-linux.sh --dev --offline # 从源码启动
```

仅在检测到 niri 和 Wayland 会话时，为当前启动进程设置 `_JAVA_AWT_WM_NONREPARENTING=1`，随后执行原客户端或 Gradle。变量在 JVM 初始化 X11 toolkit 前就已生效；不在 JVM 内修改私有字段，也不切换渲染器或调整显示缩放。其他桌面环境不自动设置。已有该变量时保留原值；JDK 根据“存在”判断，值为 `0` 也不表示关闭。

现有发行目录的二进制内容没有改变。绕过此入口直接运行二进制时，仍需由 niri 用户为该次命令设置变量。已有的 JVM 不会因新入口出现而改变，需要关闭并重新启动客户端。

## 验证

先创建能检查实际症状的循环，再加入启动入口。`tools/verify-linux-window.py` 使用真实打包客户端、独立临时 Java Preferences 和 niri 按新窗口 ID 截图，检查浅色页面右侧与下边缘是否仍为黑区。它只调整及关闭本次启动的窗口，不使用 AWT Robot 的 X11 根窗口截图；后者在当前 Wayland 桌面只取得黑色，不能作为有效画面信号。

- 隔离生产 Main：基线重复两次均失败，实际 1265×1378 窗口只有左上角 910×730 内容；三个边缘采样均为 RGB(0,0,0)。
- 只设置非重设父窗口兼容变量后，仍使用 XToolkit / OpenGL；放大到相同 1265×1378 后通过，Java 窗口尺寸也随之更新，排除仅缩小窗口造成的假通过。
- 软件渲染 `SOFTWARE_COMPAT` 对照仍失败，未把切换 GPU 后端作为修复。
- `sun.java2d.uiScale=1.25` 参数对照仍失败，实际 transform 仍为 1.0；不能把这次参数尝试称作有效的分数缩放切换。保持 niri 125% 不变、只改变 AWT 兼容变量的对照已通过。
- 真实原生发行包：`python3 tools/verify-linux-window.py --raw-launcher --resize` 在加入入口前失败；经 `run-linux.sh` 的 Miuix 与 Material 3 两种风格、同尺寸窗口均通过。截图与 JSON 保存在 `.scratch/linux-window-probe/`；隔离 Main 的对照在 `.scratch/linux-render/`。
- `run-linux.sh --dev --offline` 源码启动也在相同尺寸通过；另一个临时驱动脚本在截图后正常关闭该次窗口，使 Gradle 退出，没有结束用户已有客户端。证据为 `.scratch/linux-window-probe/fixed-4ipegnox/`。这些启动与窗口检查未替代或重复运行不受本次脚本改动影响的 Android / 核心协议测试。
- 默认启动、不主动改变尺寸的打包客户端也通过，证据为 `.scratch/linux-window-probe/fixed-kx7xl1ok/`。验证工具的 JVM 偏好路径带引号；另用真实 JVM 检查含空格路径仍完整保留，避免路径被拆开而破坏隔离。

需要本机安装 Python Pillow，并在 niri 图形会话中执行：

```bash
python3 tools/verify-linux-window.py --resize
python3 tools/verify-linux-window.py --style material3 --resize
```

探针会临时打开独立窗口；niri 的窗口截图动作也会更新剪贴板。偏好与日志只写入项目 `.scratch/`。这项验证针对实际桌面窗口尺寸与绘制，不验证 Linux 蓝牙、真实耳机或 HyperOS 系统集成，也不声明其他桌面环境已经实测。

## Standards

独立 Luna Max 复审发现 1 个低优先级问题：探针通过 `JAVA_TOOL_OPTIONS` 传递的偏好路径未加引号。已修复，并由真实 JVM 的含空格路径检查确认通过；启动脚本没有其他待修标准问题。

## Spec

独立 Luna Max 复审未发现缺失、错误行为或范围扩张。原生发行包与源码启动都在进入 JVM 前处理变量；直接绕过新入口的限制和重启要求有明确记录。

审查合计：Standards 1 项已修复，Spec 0 项发现。
