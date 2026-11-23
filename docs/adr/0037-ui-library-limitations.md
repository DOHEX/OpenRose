---
status: accepted
---

# 界面组件库限制与应对

两种界面风格分别基于 Miuix（0.9.4）与 Material 3 Expressive 组件库。共享界面组件（`StyleComponents.kt`、`StatusComponents.kt`、各风格的 `AppShell`）要让两种风格呈现同样的行为，但组件库有几处限制或默认行为与此不符。本 ADR 记录这些限制和对应写法，升级组件库时应逐条复核。

## 决策

- **Miuix 回弹只在内容可滚动时生效**：滚动容器自身的过度滚动效果只在内容可以滚动时才起作用，比视口短的页面永远不会回弹。`MiuixAppShell` 的 `miuixPageScroll` 关闭 `verticalScroll` 自带的过度滚动（`overscrollEffect = null`），改用 Miuix 的 `overScrollVertical()` 接住嵌套滚动链剩下的拖动，使每个页面都有 Miuix 回弹。
- **Miuix 没有纯文字按钮**：分组标题的操作（`MiuixGroupTitle`）用主色文字实现，排版与旁边的 `SmallTitle` 一致：相同的文字样式与垂直边距让两者落在同一行，末端边距让文字止于卡片内容的边缘。Material 3 风格直接使用超小尺寸的 `TextButton`。
- **Miuix 的 `ArrowPreference` 总是自带箭头**：`AppArrowRow` 需要显示其他末端图标（`endIcon`）时，Miuix 风格改用 `BasicComponent` 自行绘制末端内容，不用 `ArrowPreference`。
- **MD3E 列表项在三行高度后顶部对齐**：Material 3 Expressive 的 `ListItem` 默认在行高达到三行时把首尾内容改为顶部对齐，手机上摘要换行就会触发。交互行（箭头行、开关行、单选行、选择行）显式传入 `verticalAlignment = Alignment.CenterVertically`（`RowAlignment`），无论文字换行多少都保持首尾内容居中，与 Miuix 的行一致。
- **Miuix 不报告显式配色方案的明暗与动态取色**：传入显式配色方案时，Miuix 既不报告当前是否深色，也不报告是否来自动态取色。状态卡片需要这两项来选择着色，因此 `ProvideInterfaceStyle` 另行提供 `LocalThemeTone`（`ThemeTone(dark, dynamicColor)`），`StatusComponents.kt` 的 `statusColors` 据此在动态取色配色与固定着色之间选择。

## 后果

这些写法依赖组件库当前的行为。升级 Miuix 或 Material 3 Expressive 时，若组件库补上了纯文字按钮、可选的箭头、对齐参数或主题信息，应改回组件库的做法并更新本 ADR。新增共享行组件时，同样要为 MD3E `ListItem` 显式指定居中对齐。
