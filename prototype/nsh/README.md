# OpenRose NSH 一次性原型（#142 / ADR 0038 门槛验证）

> **一次性原型分支说明**：本目录位于独立原型分支（`prototype/139-nsh` / `ticket/142`），**不会合入 `main`**。目的仅用于验证 [ADR 0038](../../docs/adr/0038-rust-core-and-nsh-desktop.md) 采用 Native Shell（NSH）混合模式的前提，为人工验收门槛 #144 提供可运行原型与核查记录。

## 原型包含的验证内容

采用 NSH 混合模式（TSX 界面 + 本地 Rust nshm crate `ext/openrose-prototype-native`）：

- **Rust 扩展（`ext/openrose-prototype-native`）**：`#[native_module("openrose-prototype-native")]`，暴露 `ProtoHost`（返回进程 `PID` 与显示会话环境变量）和 `MockHeadset`（模拟 ROSE EarFree i5 电量与降噪模式状态，验证 Rust ↔ TSX 状态同步与 `nsh test` 联动）。
- **主窗口（`id="main"`）**：展示 PID、系统明暗主题、`pixelRatio`、显示器分辨率与缩放因子、模拟耳机状态与事件日志；`closeOnPress: false` 拦截关闭请求并隐藏窗口，应用继续驻留托盘（`application.quitMode = "explicit"`）。
- **托盘图标与菜单（`Tray.set`）**：单色 SVG 模板图标 `tray.svg`（随深浅色自动着色；Linux 走 StatusNotifierItem + `com.canonical.dbusmenu`，Windows 走通知区域，macOS 走菜单栏）；菜单包含「打开／关闭托盘面板」「打开主窗口」「降噪模式（子菜单）」「退出」。
- **托盘面板窗口（`id="tray-panel"`，`kind="popup"`）**：
  - Linux：固定标题 `OpenRose 托盘面板`（可供 niri 等合成器用 `window-rule` 定位与浮动），无边框浮动，`closeOnEscape: true`、`closeOnBlur: true`。
  - Windows / macOS：点击托盘图标时利用 `Tray.onClick({ bounds })` 与 `displays()` 计算锚点位置，贴着菜单栏或任务栏图标弹出（详见 `src/panelPosition.ts`）。
  - 含 `blur` 后的 250ms 防抖保护，避免点击切换按钮／托盘图标先触发 `blur` 关闭再立刻重开。
- **单实例（`singleInstance: true`）**：再次启动已打包程序时直接退出新进程，并经 `reopen` / `secondInstance` 唤回原进程（同一 PID）的主窗口。
- **退出**：托盘菜单「退出」调用 `Application.quit()`；Linux 原生通过 GLib `g_unix_signal_add_full` 响应 `SIGTERM` 干净退出。

## 版本与工具链前提（重要）

- **NSH 版本：`0.7.0`（不可用 `0.3.0`）**
  - 本地旧版 `nsh 0.3.0 (76fa8a7)` 尚无 `ext/` nshm 依赖、`Tray`、多窗口 `<Window>`、`Application` 退出模式、`singleInstance` 及 Linux `SIGTERM` 处理器；上述桌面能力均在 **`nsh 0.7.0`** 发布。
  - 本目录附带 [`mise.toml`](./mise.toml)，将原型目录局部固定到 `github:longbridge/nsh = "0.7.0"` 与 `RUSTUP_TOOLCHAIN = "stable"`，无需修改全局 `mise` 配置。
- **Rust 版本：`rustc >= 1.97`**（`nsh-core@0.7.0` 与 `nsh-macros@0.7.0` 的 MSRV 为 1.97；本地 `stable` 1.100.0 可通过，1.96.0 会报错）。

---

## Linux 上 Wayland vs XWayland 与分数缩放结论

### 结论

1. **默认走原生 Wayland（不走 XWayland）**：NSH 0.7.0 的 Linux 后端基于 GTK 4（要求 GTK 4.14+），**不设置也不覆盖 `GDK_BACKEND`**，在 Wayland 会话中直接使用 GTK 4 默认的原生 Wayland 后端；仅在 NVIDIA 环境下且未显式指定 `GSK_RENDERER` 时将其设为 `ngl`。
2. **原生支持分数缩放（Fractional Scaling）**：Linux 后端全程使用 GTK 4 的浮点表面缩放 `gdk::Surface::scale() -> f64`（并在窗口 `realize` 后监听 `scale-notify`）及 `gdk::Monitor::scale() -> f64`，同步驱动 `pixelRatio()`、`WindowState.scaleFactor`、`displays()`、像素对齐（`snap(v, scale)`）、SVG／图片／Canvas 光栅化与 Pango 文本绘制。

### 依据与实测来源

- **源码与官方文档**（详见团队共享笔记 `notes/nsh.md`）：
  - `longbridge/nsh@v0.7.0`：`crates/nsh-platform-linux/Cargo.toml`（`gtk4` `v4_14` feature）、`src/app.rs:815-825`（`prefer_gl_on_nvidia`，无 `GDK_BACKEND` 覆盖）、`src/window.rs:235-239,688-717,1282-1290`（`gdk::Surface::scale()` 与 `connect_scale_notify`）、`src/view.rs:412-432`（`snap`）、`src/surface.rs`、`src/image.rs`、`src/text.rs`。
  - 官方文档：`https://nsh.dev/docs/linux.md`、`https://nsh.dev/modules/nsh-desktop.md`。
- **本机 niri 实测（2026-11-25，DP-1 2560×1440 @ `Scale: 1.25`，Noctalia 状态栏）**：
  - `ss -xp` 显示进程直接连接 `/run/user/1000/wayland-1`（`niri`），**无任何 X11 / XWayland 套接字**。
  - 主窗口实时显示 `pixelRatio 1.25` 与 `DP-1 2048×1152 @1.25x`，经 `grim` 截图确认文字与边框在 1.25× 下锐利无模糊。
  - 额外观察：旋转 90° 的 `eDP-1`（逻辑 800×1280，niri `Scale: 2`）经 GTK `gdk::Monitor::scale()` 上报 `@3.2x`，后续 #152 如需展示显示器列表需留意该 GTK/Wayland 读数差异。
  - 打包产物：`nsh package --target linux` 自动裁剪掉未使用的 `webview`（`ldd` 确认不链接 `libwebkitgtk`），`.tar.gz` 仅 **17 MB**（主程序 51 MB）。

---

## 各平台安装、构建与检查步骤

> **注意**：`singleInstance: true` 仅在 **`nsh package` 产出的发布二进制** 中生效，`nsh run` 开发宿主不会启用单实例；因此单实例、关窗驻留与退出验收**务必运行打包产物**。

### 1. Linux（niri 原生 Wayland）

#### 安装依赖与 NSH 0.7.0

```sh
# 1) 系统开发依赖（Arch / CachyOS）
sudo pacman -S --needed gtk4 clang gstreamer gst-plugins-base
# （Ubuntu 24.04+ / Debian: sudo apt install libgtk-4-dev clang libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev）
# （Fedora: sudo dnf install gtk4-devel clang gstreamer1-devel gstreamer1-plugins-base-devel）

# 2) 安装 NSH 0.7.0（与旧版并存，不改全局默认；或用 curl -fsSL https://nsh.dev/install.sh | NSH_VERSION=v0.7.0 sh）
mise install github:longbridge/nsh@0.7.0

# 3) 确认 Rust >= 1.97（如默认 toolchain < 1.97，执行 rustup update stable）
rustup run stable rustc --version
```

#### 检查、测试与打包

```sh
cd prototype/nsh
RUSTUP_TOOLCHAIN=stable mise exec github:longbridge/nsh@0.7.0 -- nsh check
RUSTUP_TOOLCHAIN=stable mise exec github:longbridge/nsh@0.7.0 -- nsh test
RUSTUP_TOOLCHAIN=stable mise exec github:longbridge/nsh@0.7.0 -- nsh package --target linux
```

#### （可选）niri 窗口规则（让托盘面板贴近右上角浮动）

```kdl
window-rule {
    match app-id=r#"^io\.github\.dohex\.openrose\.prototype$"# title=r#"^OpenRose 托盘面板$"#
    open-floating true
    default-floating-position x=16 y=16 relative-to="top-right"
}
```

#### 运行与逐项验证

```sh
# 1) 启动已打包程序（保留 PID）
"./.nsh/out/OpenRose Prototype/OpenRosePrototype" &
APP_PID=$!

# 2) 验证原生 Wayland（应看到 wayland-1，无 X11）与主窗口信息
ss -xp | grep "pid=$APP_PID,"
niri msg --json windows | jq --argjson pid "$APP_PID" '[.[] | select(.pid == $pid)]'

# 3) 点击状态栏托盘图标（或右键菜单「打开托盘面板」）：确认弹出标题为 "OpenRose 托盘面板" 的浮动窗
# 4) 在托盘面板内按 Escape，再重新打开并点击窗口外切换焦点：确认两种方式都能收起面板
# 5) 切换降噪模式／模拟电量，确认主窗口与托盘面板及托盘 tooltip 同步更新
# 6) 将主窗口移到其他工作区或滚出视口，反复打开／关闭托盘面板：确认无卡死、无互锁
# 7) 关闭主窗口（窗口关闭键或 Mod+Q / niri close-window）：确认主窗口消失但进程与托盘图标仍在
# 8) 主窗口隐藏时再次启动程序验证单实例：
"./.nsh/out/OpenRose Prototype/OpenRosePrototype" --second-launch
#    确认第二次启动立即退出（exit 0），且原 PID 主窗口重新出现并记录 secondInstance 事件
# 9) 托盘右键菜单点击「退出」：确认进程干净退出
# 10) 重新启动 -> 关闭主窗口驻留托盘 -> 发送 SIGTERM：
kill -TERM "$APP_PID"
#    确认进程在 1 秒内干净退出（exit 0），无须 kill -9
```

---

### 2. macOS x64（Intel Mac，留待 #144 实机验收）

#### 安装依赖与 NSH 0.7.0

```sh
# 1) Xcode Command Line Tools（如未安装）
xcode-select --install

# 2) 安装 Rust >= 1.97
rustup update stable

# 3) 安装 NSH 0.7.0（注意：Homebrew 仅提供 Apple Silicon；Intel Mac 请用官方脚本或 mise）
NSH_VERSION=v0.7.0 curl -fsSL https://nsh.dev/install.sh | sh
# 或：mise install github:longbridge/nsh@0.7.0
# 若手动下载 tar.gz 触发 Gatekeeper 隔离，执行：xattr -dr com.apple.quarantine ~/.nsh
```

#### 检查、测试与打包

```sh
cd prototype/nsh
nsh check
nsh test
nsh package --target macos --arch x86_64
```

#### 运行与逐项验证

```sh
# 必须经 Launch Services 启动 .app 以验证单实例与菜单栏生命周期
open ".nsh/out/OpenRose Prototype.app"
```

1. 确认主窗口正常显示，PID、明暗模式、`pixelRatio`、模拟耳机状态可切换。
2. 确认 macOS 顶部菜单栏右侧出现单色耳机图标，且随菜单栏深浅色自动着色；悬停 tooltip 展示模拟耳机状态。
3. **左键点击菜单栏图标**：确认无边框托盘面板**贴着菜单栏图标正下方**弹出（`kind="popup"` + `computeTrayPanelPosition`）；在面板内切换降噪模式后主窗口同步。
4. 在托盘面板内按 **Escape**，再重新打开并点击桌面／其他窗口切走焦点：确认面板自动收起。
5. **右键点击菜单栏图标**：确认弹出菜单含「打开托盘面板」「打开主窗口」「降噪模式」「退出」。
6. 点击主窗口红色关闭按钮（或 `Cmd+W`）：确认主窗口隐藏，菜单栏图标与面板继续可用。
7. 主窗口隐藏时再次执行 `open ".nsh/out/OpenRose Prototype.app"`（或在访达双击 `.app`）：确认不产生第二个进程，且原 PID 主窗口被唤回（事件记录出现 `reopen`）。
8. 通过菜单栏右键菜单「退出」退出应用，确认进程完全结束。
9. 重新启动并隐藏主窗口，终端执行 `kill -TERM <PID>`，记录退出表现（注意：`nsh-platform-macos` 无自定义 POSIX `SIGTERM` 处理器，按默认信号语义终止进程）。

---

### 3. Windows（Windows 10 1809+ / Windows 11，留待 #144 实机验收）

#### 安装依赖与 NSH 0.7.0

```powershell
# 1) 安装 Rust >= 1.97（MSVC toolchain）
rustup update stable

# 2) 构建含 Rust 扩展的混合模式需要：
#    - Visual Studio Build Tools（勾选 "Desktop development with C++" 与 Windows SDK）
#    - LLVM（提供 libclang，默认安装到 C:\Program Files\LLVM；否则设置 $env:LIBCLANG_PATH）

# 3) 安装 NSH 0.7.0（安装后重开终端使 PATH 生效）
$env:NSH_VERSION = "v0.7.0"
irm https://nsh.dev/install.ps1 | iex
```

#### 检查、测试与打包

```powershell
cd prototype\nsh
nsh check
nsh test
nsh package --target windows
```

#### 运行与逐项验证

```powershell
# 启动打包出的可执行文件（用于验证单实例与托盘）
& ".\.nsh\out\OpenRose Prototype\OpenRosePrototype.exe"
```

1. 确认主窗口正常显示，切换降噪模式与电量刷新正常。
2. 确认任务栏通知区域（或溢出托盘区）出现耳机图标，悬停 tooltip 正确，随任务栏深浅色可见。
3. **左键点击托盘图标**：确认托盘面板**贴着托盘图标上方**弹出（`Shell_NotifyIconGetRect` + `computeTrayPanelPosition`）。
4. 在托盘面板内按 **Escape**，再重新打开并点击任务栏／桌面切走焦点：确认面板自动收起。
5. **右键点击托盘图标**：确认弹出原生菜单含「打开托盘面板」「打开主窗口」「降噪模式」「退出」。
6. 点击主窗口关闭按钮（或 `Alt+F4`）：确认主窗口隐藏，应用继续驻留托盘且托盘面板可正常开关。
7. 主窗口隐藏时再次执行 `.\.nsh\out\OpenRose Prototype\OpenRosePrototype.exe --second-launch`：确认第二次启动立刻退出，原 PID 主窗口被唤回并记录 `secondInstance [--second-launch]`。
8. 通过托盘右键菜单「退出」退出应用，确认任务管理器中进程完全退出。

---

## 逐项验证检查表

### 离线检查（Linux x86_64，2026-11-25，NSH 0.7.0 + Rust 1.100.0）

| 检查项 | 状态 | 备注 |
|---|---|---|
| `nsh check`（Rust `cargo check` + TS 7.0.2） | 通过 | 零类型错误、零文档警告 |
| `nsh test`（Rust 单元测试 ×2 + Headless TSX 测试 ×4） | 通过 | 6/6 通过（含 Rust 状态同步、关窗隐藏、托盘面板 Escape 收起、blur 防抖、macOS/Windows 锚点计算） |
| `nsh package --target linux` | 通过 | 产物 `.nsh/out/OpenRose Prototype/` 与 17 MB `.tar.gz`，无 WebKitGTK 依赖 |

### niri 原生 Wayland 实机检查（2026-11-25，DP-1 2560×1440 @ 1.25×，Noctalia）

| 检查项 | 状态 | 观察记录 |
|---|---|---|
| 原生 Wayland（非 XWayland） | 通过 | `ss -xp` 仅直连 `/run/user/1000/wayland-1`（`niri`），无 X11 套接字 |
| 分数缩放（1.25×）清晰度与读数 | 通过 | 主窗口显示 `pixelRatio 1.25`、`DP-1 2048×1152 @1.25x`，`grim` 截图文字与边框清晰 |
| 主窗口显示与 Rust 状态交互 | 通过 | `app_id="io.github.dohex.openrose.prototype"`，`title="OpenRose 原型"` |
| 托盘图标（StatusNotifierItem） | 通过 | 注册 `org.kde.StatusNotifierItem-<pid>-1`，`IconPixmap` 16/24/32 px，Noctalia 状态栏显示清晰黑图标与动态 tooltip |
| 托盘菜单（`com.canonical.dbusmenu`） | 通过 | `GetLayout` 含「打开托盘面板」「打开主窗口」「降噪模式」「退出」 |
| 托盘面板固定标题与浮动弹出 | 通过 | `Activate` 弹出无边框浮动窗 `title="OpenRose 托盘面板"`（`is_floating: true`，`320×320`） |
| 托盘面板失去焦点自动收起 | 通过 | 切回主窗口焦点后立即收起（日志 `#3 托盘面板收起 (blur)`） |
| 托盘面板按 Escape 自动收起 | 未验证（实机） | headless `nsh test` 已通过；实机物理按键留待 #144 人工确认 |
| 主窗口移至其他工作区（离屏）时开关托盘面板 | 通过 | 主窗口在 workspace 6 时于当前工作区反复开关托盘面板，无 ADR 0014 互锁或卡死 |
| 关闭主窗口后驻留托盘 | 通过 | `niri close-window` 后主窗口消失，进程存活、托盘图标在线、托盘面板可继续反复开关 |
| 单实例（再次启动唤回主窗口） | 通过 | 第二次启动携带 `--from-second-instance` 立即返回 `exit 0`，原 PID 主窗口重新出现并聚焦（日志 `#8 secondInstance`） |
| 托盘菜单退出 | 通过 | 触发 `com.canonical.dbusmenu`「退出」项后约 200 ms 干净退出 |
| `SIGTERM` 退出（托盘驻留态） | 通过 | 隐藏主窗口后 `kill -TERM` 约 200 ms 干净退出，退出码 `0` |

### macOS x64 实机检查（留待 #144 人工验收）

| 检查项 | 状态 | 备注 |
|---|---|---|
| `nsh check` / `nsh test` / `nsh package --target macos --arch x86_64` | 未验证 | 本机无法 SSH 到维护者 Mac，留待 #144 |
| 菜单栏图标与 tooltip（明暗自适应） | 未验证 | 留待 #144 |
| 托盘菜单（打开托盘面板／打开主窗口／退出） | 未验证 | 留待 #144 |
| 托盘面板贴着菜单栏图标弹出 + Escape／失焦收起 | 未验证 | 留待 #144 |
| 关窗驻留 + 单实例（`open` 唤回主窗口） | 未验证 | 留待 #144 |
| 托盘退出与 `SIGTERM` 退出 | 未验证 | 留待 #144 |

### Windows 实机检查（留待 #144 人工验收）

| 检查项 | 状态 | 备注 |
|---|---|---|
| `nsh check` / `nsh test` / `nsh package --target windows` | 未验证 | 留待 #144 |
| 通知区域托盘图标与 tooltip | 未验证 | 留待 #144 |
| 托盘菜单（打开托盘面板／打开主窗口／退出） | 未验证 | 留待 #144 |
| 托盘面板贴着托盘图标上方弹出 + Escape／失焦收起 | 未验证 | 留待 #144 |
| 关窗驻留 + 单实例（再次启动 `.exe` 唤回主窗口） | 未验证 | 留待 #144 |
| 托盘退出 | 未验证 | 留待 #144 |
