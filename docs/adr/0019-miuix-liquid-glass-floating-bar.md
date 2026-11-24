---
status: accepted
---

# Miuix 悬浮底栏的液态玻璃材质

## 背景

ADR 0009 规定 Miuix 风格的悬浮胶囊使用原生 `FloatingNavigationBar`，背景模糊生效时以 `miuix-blur` 的均匀模糊加玻璃描边呈现毛玻璃质感。社区流行的“液态玻璃”底栏（透镜折射、色散、可拖动形变的选中指示器）超出 `miuix-blur` 0.9.4 的能力：它只提供模糊、混色与高光，不提供折射。用户希望在 Miuix 风格下自选毛玻璃或液态玻璃。

## 决策

1. **独立的材质偏好，而非第三种底栏样式**：`UiSettings` 新增 `floatingBarMaterial: FloatingBarMaterial`（`FROSTED`、`LIQUID_GLASS`，默认 `FROSTED`），持久化键 `sharedUI.floating_bar_material`。底栏样式仍只描述形态（固定或悬浮），材质只修饰 Miuix 悬浮胶囊。个性化页仅在 Miuix、窄屏且选中悬浮胶囊时呈现“悬浮底栏材质”；Material 3 忽略该值，切换界面风格保留已选材质。
2. **效果库**：引入 Kyant 的 `io.github.kyant0:backdrop`（Apache-2.0，Compose Multiplatform，覆盖 Android 与桌面 JVM，`minSdk` 21，无需 `overrideLibrary`）。它与 `miuix-blur` 是两套互不相通的 backdrop：液态玻璃模式下主页面 Pager 只录入 Backdrop 的图层，不再录入 `miuix-blur` 的图层；各页顶栏的模糊不受影响。
3. **对 ADR 0009 的例外**：液态玻璃底栏不使用 `FloatingNavigationBar`，而是应用自有的 `MiuixLiquidGlassBottomBar`，移植自 Backdrop 示例应用的 `LiquidBottomTabs` 及其拖拽、高光工具（保留出处与许可声明）。它沿用 ADR 0009 的纯图标、居中胶囊与 `FloatingNavigationBar` 相同的底部避让规则，配色取 `MiuixTheme` 的 `primary`、`onSurfaceContainer`、`surfaceContainer`。指示器可拖动跨标签切换主页面；点按、拖动结束与 Pager 滑动都经由 `MainTabsPagerState` 同一个选中页。
4. **降级与背景模糊共用门禁**：液态玻璃同样依赖运行时着色器，因此复用背景模糊的判定（用户开关、省电模式、`isRuntimeShaderSupported`）。不满足时回退为 ADR 0009 的实体悬浮胶囊，不单独提供“无折射的液态玻璃”。
5. **指示器目标值立即记录**（2026-11 补充）：`LiquidGlassBottomBar.kt` 的 `DampedDragAnimation` 在拖动或选择请求移动时（`updateValue`、`animateToValue`）立即记下 `targetValue`，拖动增量累加在它上面。动画自身的目标要等启动的协程运行后才更新，若以它为准，同一帧内的多次拖动事件会丢失位移。

## 影响

- 个性化页多一项条件呈现的选择；`UiSettingsCodec` 对缺失或未知值回到 `FROSTED`。
- 液态玻璃每帧多出主体、着色副本与指示器三次 backdrop 绘制，GPU 开销高于毛玻璃；默认仍为毛玻璃。
- 移植代码归应用维护；Backdrop 升级时需核对 `drawBackdrop`、`lens`、`rememberCombinedBackdrop` 等 API。
