---
status: accepted
---

# 主页面 HorizontalPager 容器与独立 TopAppBar 架构

> 注：主页面“系统集成”已更名为“状态”，只读展示运行状态，偏好统一在“设置”；下文沿用原页名。

## 背景

在 ADR 0005 中，应用根路由 `Route.Main` 承载常驻 Scaffold，底部 Tab 切换在原地直接替换页面内容。用户期望能够在三大主页面（“我的耳机”、“系统集成”、“设置”）之间通过水平手势流畅滑动切换（类似 OppoPods 的多标签滑动体验）。

然而，直接在现有根级 Scaffold 的内容区放置 `HorizontalPager` 会带来严重的状态冲突：
1. Miuix 与 Material 3 的 `TopAppBar` 均重度依赖 `nestedScroll` 连接实现大标题向小标题折叠（`MiuixScrollBehavior` 与 `TopAppBarDefaults.exitUntilCollapsedScrollBehavior`）。若仅在内容区域使用 Pager 并共享外层单一顶栏，当某一页面向下滑动折叠后，横滑切到另一未滚动页面时，顶栏折叠量与标题无法同时适配两页，产生视觉与滚动状态污染。
2. “我的耳机”主页拥有专属的右侧操作按钮（未连接时为添加按钮，已连接时为更多菜单），而“系统集成”与“设置”无此类操作。若顶栏置于外层，横滑切页期间操作按钮的淡入淡出及弹窗归属难以自然过渡。

## 架构决策

参考并借鉴 OppoPods 实机验证方案，对主页面容器与页面架构进行双层重构：

1. **外层 Scaffold 纯化与 HorizontalPager 承载**：
   - 根级 `Route.Main` 的外层 Scaffold 仅负责承载常驻底部导航栏（`NavigationBar`）以及宽屏模式下的侧边栏（`NavigationRail`）；
   - 主体区域直接承载 `HorizontalPager`，通过 `MainTabsPagerState` 控制器管理 `PagerState` 与底部导航栏的联动；
   - 启用 `beyondViewportPageCount = 1` 预排版相邻页面，消除左右滑动手势切页时的卡顿；
   - 引入动态像素距离与 `EaseInOut` 缓动滚动的 `animateToPage`，并在点击滚动期间设置 `isNavigating` 防重入锁定，保证点击与拖拽手势互不干扰。

2. **顶栏下沉至各 Tab 内部（独立 TopAppBar 与 ScrollBehavior）**：
   - 将 `TopAppBar` 与独立的 `MiuixScrollBehavior`（或 Material 3 滚动行为）移入各个主页面自身的内部 Scaffold；
   - 横向滑动时，各页面的顶栏标题、动作按钮与页面卡片一同平滑横移进出屏幕；
   - 各页面的垂直滚动进度（`ScrollState`）与大标题折叠量完全隔离，互不污染。
   - 顶栏动作按钮（添加/更多菜单、状态页重启作用域）与页面反馈条只取决于页面自身，不取决于 `MainTabsPagerState.selectedPage`：后者在点击标签的瞬间即变化，若以它控制可见性，切页动画期间动作按钮会在页面仍在屏幕上时提前消失。`selectedPage` 仅用于导航栏高亮、返回键判断以及非选中页的语义/焦点隔离。

3. **双界面风格（Miuix / Material 3）对称实现**：
   - Miuix 与 Material 3 分别提供对应的 `MainShell` 实现，两者保持完全对称的 Pager 容器与顶底栏拓扑；
   - 共享统一的 `MainTabsPagerState` 与标签生命周期，风格切换时保持当前激活主页面连续。
   - 手势接线是库层面的有意差异：Miuix 用 `userScrollEnabled = false` 加 `pagerGestureOverride`（宽屏关闭）接管手势；Material 3 使用 Pager 原生滑动（`userScrollEnabled = !wide`）。两者拓扑与可见行为一致。

4. **导航与返回栈保真**：
   - 继续严格保持 ADR 0005 的回退准则：非耳机主页（系统集成、设置）按下系统返回时平滑滚回“我的耳机”，在耳机主页返回时退出应用；
   - 二级页面（如降噪控制、均衡器等）继续作为全屏独立路由在 `NavDisplay` 中推入，保留边缘滑动返回。
