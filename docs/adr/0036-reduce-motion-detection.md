---
status: accepted
---

# 减少动态效果的平台检测

部分界面自带动画：添加耳机流程的扫描脉冲、Material 3 悬浮底栏的指示器、Miuix 液态玻璃底栏的拖动与按压形变、快捷面板的入场。系统要求减少动态效果时，这些动画应直接跳到终态。共享界面通过 `ReduceMotion.kt` 的 `expect fun rememberReduceMotion()` 询问平台。

## 决策

- **Android 以动画时长缩放为准**：系统设置中的“移除动画”会把动画时长缩放设为 0。`ReduceMotion.android.kt` 读取 `Settings.Global.ANIMATOR_DURATION_SCALE`（缺省 1），值为 0 时视为减少动态效果。
- **桌面端不检测**：`ReduceMotion.jvm.kt` 始终返回 `false`。桌面端没有一个 Compose 能在 Linux 与 Windows 上以低成本、统一方式读取的系统设置。
- **消费方式**：减少动态效果时，`AddDeviceContent` 的脉冲停在静止值，`MaterialAppShell` 的悬浮底栏把空间与颜色动画换成 `snap`，`LiquidGlassBottomBar` 取消按压放大并直接跳到目标位置，`QuickPanel` 以已入场状态开始。

## 后果

Android 上的值在界面进入组合时读取一次（以 `ContentResolver` 为键记住），应用运行中修改系统设置后，已显示的界面不会随之改变，要等界面重新进入组合（例如重新打开页面）才生效。桌面用户无法通过系统设置关闭这些动画；若将来要支持，需要分别读取各桌面环境与 Windows 的设置。
