---
status: proposed
---

# 底栏样式切换与悬浮胶囊导航架构

> 注：主页面“系统集成”已更名为“状态”，只读展示运行状态，偏好统一在“设置”；下文沿用原页名。

## 背景

OpenRose 采用 `Scaffold` 与 `HorizontalPager` 承载三大主页面（“我的耳机”、“系统集成”、“设置”）。此前主页底部导航固定为贴底全宽的标准 `NavigationBar`。为了满足不同用户对于沉浸式浏览与界面视觉个性的诉求，需支持在「标准固定」与「悬浮胶囊」两种底栏形态之间自由切换。

## 架构决策

1. **底栏样式与界面风格解耦（Domain Model）**：
   - 遵循 `GLOSSARY.md` 规范，底栏样式（`BottomBarStyle`：`FIXED`、`FLOATING`）独立于界面风格（`InterfaceStyle`）存储于 `UserSettings`，默认值为向后兼容的 `FIXED`。
   - 在“设置 - 显示模式”中提供统一的选择入口。

2. **跨界面风格对称实现（UI Components）**：
   - **Miuix 风格**：标准固定形态使用 `NavigationBar`（图文并茂）；悬浮胶囊形态使用 Miuix 原生 `FloatingNavigationBar` 与 `FloatingNavigationBarItem`，严格遵循官方规范采用纯图标与紧凑居中布局。
   - **Material 3 风格**：标准固定形态使用 Material 3 `NavigationBar`；悬浮胶囊形态采用官方内置的 `HorizontalFloatingToolbar` 对称封装为居中药丸容器，不依赖任何第三方平台专有库。
   - 宽屏与桌面扩展模式（`wide == true`）下统一继续使用 `NavigationRail`，不受底栏形态配置影响；此时个性化页隐藏“底栏样式”选项，窄屏时重新显示，已保存的取值不变。

3. **穿透式滚动与分层内边距拓扑（Layout & Insets）**：
   - 在悬浮胶囊模式下，外层 `Scaffold` 不再硬性截断 `HorizontalPager` 底部（Pager 撑满至屏幕底部，实现滑动手势与毛玻璃背景在胶囊后方的完整穿透）；
   - 胶囊高度与系统导航避让边距统筹下发至 `ShellPageContent`，在各主页面的滚动列末尾追加对应底部内边距，确保列表末端项目可完全滑出悬浮栏遮挡区域。
   - 两种界面风格的悬浮胶囊从分页内容采样并应用均匀背景模糊，导航图标保持清晰；背景模糊关闭或不可用时使用实体底色。
