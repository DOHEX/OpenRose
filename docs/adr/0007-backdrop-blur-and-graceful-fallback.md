---
status: accepted
---

# 背景模糊与平滑降级架构

## 背景

随着 OpenRose 核心交互升级为主页面 `HorizontalPager` 与多 Tab 独立顶栏（ADR 0006），页面内容在上下滚动时会频繁穿过顶栏与底部导航栏。传统的纯色实体遮挡缺乏现代视效层次感，但在 Compose Multiplatform 环境下引入毛玻璃视效面临以下技术制约与设计挑战：

1. **术语与概念统一**：词汇表明确定义了「背景模糊」，并严格限制将模糊效果与特定界面风格（如仅限 Miuix）强绑定，要求在支持的环境生效，其余情况平滑回退为预设底色。
2. **多平台与 SDK 基线兼容**：`androidApp` 与 `composeApp` 的基准为 `minSdk = 26`（Android 8.0），而 Android 原生硬件加速的着色器背景模糊（`RuntimeShader` / `RenderEffect`）强制要求 API 33+（Android 13+）。直接提升 minSdk 会导致存量低版本设备无法安装应用。
3. **双界面风格对称性**：ADR 0001 要求 Miuix 与 Material 3 风格享有同等地位与无缝切换能力，背景模糊不能专属于 Miuix 容器，需跨风格通用。
4. **文字可读性与滚动穿透风险**：半透明顶底栏若在不支持模糊的环境中强行透视，会导致前景文字与背景滚动文字层叠混淆，严重破坏可用性；而内容若不穿透至屏幕边缘，模糊又将失去视觉依托。
5. **GPU 开销与功耗控制**：多通道实时着色器采样会增加 GPU 填充率负担，低电量与省电模式下需要主动降级。

## 架构决策

### 1. miuix-blur 依赖引入与 overrideLibrary 兼容

- 引入跨平台着色器库 `top.yukonga.miuix.kmp:miuix-blur:0.9.4`，利用其在 Android API 33+ 的 `RuntimeShader` 实现与桌面端（Desktop JVM）的 Skia 着色器管线。
- 在 Android 清单中声明 `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur" />`，维持应用整体 `minSdk = 26` 的发布要求不变，通过运行时硬件与平台特性探测实现安全门禁。

### 2. 统一修饰符封装与形态分流（Progressive 与 Uniform）

在 `composeApp` 应用层抽象统一度量的 `Modifier.appBackdropBlur(...)`：
- **顶栏与底栏（单向滚动穿透）**：采用边缘渐进模糊（`ProgressiveBlur.Top` / `ProgressiveBlur.Bottom`）。利用渐变着色器将视效边缘向内容区域平滑过渡淡出，消除内容进出栏底时的几何生硬切边。
- **浮层弹窗与菜单（独立悬浮）**：理想形态是在 Dialog、OverlayListPopup 等浮层上使用全域均匀模糊。当前未实现：Miuix 0.9.4 的 `OverlayListPopup` 在 `popupModifier` 之后固定绘制不透明的 `surfaceContainer` 底色且无颜色参数，模糊会被完全遮住；Material 3 的 `DropdownMenu` 与 `AlertDialog` 在 Android 上是独立窗口，背景图层按窗口坐标采样会错位。待组件支持容器颜色与跨窗口坐标后再启用。
- **常规内容列表卡片排除**：常规卡片严禁启用背景模糊，保持实体卡片底色，避免深层重叠引发的 GPU 填充率雪崩与文本对比度受损。

### 3. 布局穿透与表面透明度动态绑定

- **模糊激活状态**：顶底栏的 `surface` 半透明着色（`BlurTokens.BarTint`）混入渐进模糊内部，随模糊一同淡出，不另加会留下硬边的背景层。Scaffold 内容区域填充穿透至窗口边缘（固定底栏同悬浮胶囊一样，页面在栏底之下，列表末尾追加底栏高度的留白），使滚动列表自然垫入栏底。
- **降级/关闭状态**：当环境不支持或用户关闭模糊时，顶底栏表面立即恢复为预设实体底色（`alpha = 1.0f`），页面回到栏之上，阻断穿透视效，确保低版本设备上的文字排版与对比度绝对清晰。
- **统一取值**：模糊半径与各表面的着色不透明度集中在 `AppBlur.kt` 的 `BlurTokens`。渐进模糊在栏自身图标与文字所在的外缘 70% 保持满强度，其余部分淡出。只有当前可见的页面录入背景，Pager 预加载但不在屏幕上的页面不录入。

### 4. 省电模式感知与自适应降级矩阵

建立统一的运行时能力判定流：
1. **用户开关**：`UserSettings.backgroundBlurEnabled` 为 `false` 则直接降级。
2. **省电模式**：系统开启 Power Saver（Android `PowerManager.isPowerSaveMode`）时自动降级。桌面端目前只识别 Linux：每 30 秒在后台线程读取 sysfs，`/sys/firmware/acpi/platform_profile` 为 `low-power`，或电池正在放电且电量不高于 20% 时视为省电；其他桌面系统与任何读取失败都视为非省电。
3. **平台与硬件门禁**：Android < API 33 或无着色器支持环境自动降级为实体预设底色。
4. **生效平台**：仅在 Android API 33+ 及桌面端非省电状态下执行着色器多通道采样。

### 5. 用户配置持久化与设置入口

- `core/UserSettings` 增加 `backgroundBlurEnabled: Boolean = true` 属性，并在编解码器（`UserSettingsCodec`）中完成持久化与向后兼容适配。
- 在「设置」页的「显示模式」分组中增加「背景模糊」独立开关，附带生效平台与降级说明。

### 6. 栏的着色细节（2026-11 补充）

- **着色在模糊内部混合**：`AppBlur.kt` 的 `barBackdropBlur` 把 `BlurTokens.BAR_TINT` 的表面着色作为 `BlurColors` 的混合色交给渐进模糊，使着色随模糊一同淡出；另加一层背景会在模糊淡出处留下硬边。
- **Material 3 顶栏的容器色保持透明**：模糊生效时，`MaterialAppShell.kt` 的 `materialTopAppBarColors` 把 `containerColor` 与 `scrolledContainerColor` 都设为透明，只让模糊着色显示。折叠中的顶栏会把容器色向滚动后的颜色插值，从透明（全透明的黑色）插值到不透明的滚动色会经过半透明的灰，使顶栏在折叠途中变暗。未启用模糊时使用默认配色。
- **Material 3 悬浮工具栏**（`MaterialFloatingTabs`）：工具栏默认容器色与 `surfaceContainer` 列表行相同，悬浮在列表上会“消失”，因此提高一级到 `surfaceContainerHigh`。不透明时使用 MD3 悬浮阴影（Level3，6dp）。在模糊上方时容器半透明（`BlurTokens.MATERIAL_FLOATING_TINT`），阴影会铺满整个形状并透过着色，使它变暗；因此模糊时阴影设为 0，改用 1dp、半透明的 `outlineVariant` 细线分隔。

## 验证与影响

1. **核心模型测试**：扩展 `UserSettingsCodecTest`，验证新增字段的序列化、反序列化以及缺省容错。
2. **UI 降级测试**：在 JVM 测试环境下验证着色器能力探测、降级底色与双风格下修饰符调用的一致性。
3. **平台兼容性**：Android 构建验证清单合并（Manifest Merger）与 `minSdk = 26` 编译通过；Android 13+ 与 Desktop 端分别验证着色器效果与省电模式回退。
