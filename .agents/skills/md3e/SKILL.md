---
name: md3e
description: "Material Design 3 Expressive (MD3E) Compose Multiplatform 专家。用于 MD3E UI 构建、审查、重构与迁移；在提及 Material 3 Expressive、MD3E、MotionScheme、ButtonGroup、Connected Button Group、FloatingToolbar、SplitButton、MaterialExpressiveTheme、Surface Container 角色、弹性动效、形变圆角阶梯时触发。"
version: 1.0.0
---

# Material Design 3 Expressive (MD3E) Compose Multiplatform Skill

> **Compose Multiplatform 1.12 硬性兼容约束 (CMP 1.12 Boundaries)**:
> 
> 1. **依赖与运行环境**：本技能包所有规范与代码示例严格基于 `org.jetbrains.compose.material3:material3:1.12.0-alpha03`（对应 Compose Multiplatform 1.12.0），目标平台包括 Desktop (JVM) 与 Android，代码编写位置为共享模块 `sharedUI/src/commonMain/`。
> 2. **桌面端 JVM 对齐（Desktop JVM Parity）**：严禁在 `commonMain` 中引用任何 Android 专有类或 API（如 `android.content.Context`、`LocalContext.current`、`dynamicLightColorScheme(context)`、`android.view.*`、`HapticFeedbackConstants`）。所有颜色、动效与组件必须在纯 JVM 无头测试（`runDesktopComposeUiTest`）与桌面应用中完全自洽、无缝编译运行。
> 3. **实验性注解要求（Opt-In Policy）**：MD3E 新增组件（如 `ButtonGroup`、`FloatingToolbar`、`SplitButton`、`MaterialExpressiveTheme`）处于 `@ExperimentalMaterial3ExpressiveApi` 注解保护下。调用处或文件头必须显式声明 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。
> 4. **物理弹簧全局统一（Spring MotionScheme）**：全面废弃硬编码时长曲线（`tween`），统一使用 `MaterialTheme.motionScheme` 或直接基于 Spring 物理参数的 `spring(...)` 规范。空间位移采用欠阻尼弹性弹簧（Damping 0.6f–0.8f），色彩/透明度采用临界阻尼弹簧（Damping 1.0f）。
> 5. **零兼容层与干净切换（Clean Cutover, Zero Shims）**：演进升级必须全量原地替换，严禁保留废弃代码分支、垫片封装（Shims）或 `if (legacy)` 运行时开关；必须严格继承原有组件的标准 Compose 语义节点（`Role.RadioButton`、`Role.Switch`、`Role.Tab`、`selected`、`disabled`、文本），确保跨样式无头自动化测试矩阵零回归通过。

---

## 核心概念 (Core Concepts)

### 1. 物理弹簧动效方案 (Spring MotionScheme)
Material Design 3 Expressive 用物理弹簧（Spring Dynamics）彻底取代了上一代固定时长的贝塞尔缓动（`tween(durationMillis, easing)`）：
- **空间动画 (Spatial Specs)**：用于位置、尺寸、形状外形（Morphing）、边界扩展等。在 Expressive 方案下具有轻微弹跳感（Damping 0.6f–0.8f, Stiffness 200f–800f）。
- **效果动画 (Effects Specs)**：用于颜色、透明度、高度阴影等。严格保持临界阻尼（Damping 1.0f, Stiffness 800f–3800f），绝不发生数值过冲（Overshoot）。
- **主题装配**：通过 `MaterialExpressiveTheme` 或 `MaterialTheme(motionScheme = MotionScheme.expressive())` 提供全局动效上下文。组件内通过 `MaterialTheme.motionScheme.defaultSpatialSpec()` / `defaultEffectsSpec()` 消费。

### 2. 容器阶梯与色彩角色 (Surface Container Roles)
MD3E 弱化了传统的强阴影高度（Shadow Elevation），引入了 5 级表面容器阶梯（Surface Container Hierarchy），利用色调对比度明确界面层级：
- **`surfaceContainerLowest`**：最低容器阶梯（浅色：纯白 Neutral100；深色：深黑 Neutral4），用于高对比底板或基底卡片。
- **`surfaceContainerLow`**：低容器阶梯（浅色：Neutral96；深色：Neutral10），用于悬浮卡片、看板背景。
- **`surfaceContainer`**：基础容器（浅色：Neutral94；深色：Neutral12），列表项、对话框、标准卡片默认底色。
- **`surfaceContainerHigh`**：高容器阶梯（浅色：Neutral92；深色：Neutral17），突出展示的控制板、导航栏、顶部栏。
- **`surfaceContainerHighest`**：最高容器阶梯（浅色：Neutral90；深色：Neutral22），输入框、选中状态背景、强调卡片。
- **`surfaceDim` / `surfaceBright`**：在明暗交替中维持对比度的极值表面变体。

### 3. 互联按钮组 (Connected Button Group)
解决零散按钮视觉割裂与触控容错率低的问题：
- **容器与间距**：整组统一高度（40.dp），子项间距极小（`ConnectedSpaceBetween = 2.dp`）。
- **形状拓扑**：首项外侧全圆角（`CornerFull`）、内侧小圆角（8.dp）；中间项四周小圆角（8.dp）；末项内侧小圆角（8.dp）、外侧全圆角（`CornerFull`）。
- **交互形变 (Morphing)**：点按（Pressed）时内侧圆角压缩至 4.dp；选中（Checked/Selected）时整项形变弹跳展开为独立完整胶囊（`CornerFull`）。

### 4. 形变圆角阶梯 (Shape Scale)
MD3E 扩展了圆角阶梯，为组件赋予饱满有机的“果冻感”与层次分明的情感化外观：
- `None` (0.dp)、`ExtraSmall` (4.dp)、`Small` (8.dp)、`Medium` (12.dp)、`Large` (16.dp)、`LargeIncreased` (20.dp / 24.dp)、`ExtraLarge` (28.dp)、`ExtraLargeIncreased` (32.dp)、`ExtraExtraLarge` (48.dp)、`Full` (CircleShape / 50% 胶囊)。

### 5. 跨平台边界与无头测试 (Desktop JVM & runComposeUiTest)
OpenRose 的双样式与无头测试运行于 Desktop JVM 上，基于官方 `runDesktopComposeUiTest` 与虚拟时钟（`sharedUI/src/jvmTest`）。所有 Expressive 改造必须满足：
- 渲染管线由纯 Kotlin / Skiko 驱动，不依赖 Android `Canvas` 特殊着色器。
- 组件必须正确处理 `Modifier.selectableGroup()`、`Modifier.toggleable()`、`Role.RadioButton` 等语义，确保无头断言框架（`onNodeWithTag`、`onNodeWithText`、`assertIsSelected`）直接生效。

---

## 参考文献索引 (References)

按需查阅专门模块的深入规范：

1. **色彩与容器角色**：[`references/tokens-and-color.md`](references/tokens-and-color.md)
   - 5 级表面容器色卡映射（Lowest → Highest）、高对比阶梯、明暗主题反转、`expressiveLightColorScheme()` 及自定义扩展。
2. **物理弹簧动效方案**：[`references/motion-scheme.md`](references/motion-scheme.md)
   - `MotionScheme.expressive()` 物理参数规范、空间与效果分离原则、阻尼比与刚度对照表、`animate*AsState` 与 Compose 弹簧迁移指南。
3. **MD3E 组件群与代码范式**：[`references/components.md`](references/components.md)
   - `ButtonGroup`、互联按钮组、`SegmentedButton`、`HorizontalFloatingToolbar`、`WideNavigationRail` / `NavigationRail`、`Card` 容器适配、`Slider`、`Switch` 的完整代码片段与 API 签名。
4. **Compose Multiplatform 1.12 兼容边界**：[`references/cmp-compatibility.md`](references/cmp-compatibility.md)
   - `1.12.0-alpha03` 可用 API 边界、Desktop JVM 无头测试自洽性、Android 专有 API 绝对禁令与回退方案、语义节点防回归守则。

---

## 工作流与代码实现规范 (Working Method)

1. **确认组件版本与 Opt-In 范围**：
   - 确认项目根目录下 `gradle/libs.versions.toml` 包含 `material3 = "1.12.0-alpha03"`。
   - 使用 MD3E 组件时，在文件或 Composable 上标注 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。
2. **确定主题承载上下文**：
   - 全局/页面根节点应提供 `MaterialExpressiveTheme`，或在 `MaterialTheme` 中显式指定 `motionScheme = MotionScheme.expressive()`。
   - 不要重复嵌套多个 `MaterialExpressiveTheme` 破坏上层色彩策略。
3. **选用正确的 Surface Container 阶梯**：
   - 背景大底：`MaterialTheme.colorScheme.surface` 或 `surfaceDim`。
   - 常规卡片/仪表盘：`MaterialTheme.colorScheme.surfaceContainerLow` 或 `surfaceContainer`。
   - 交互凸显/浮层/弹窗：`MaterialTheme.colorScheme.surfaceContainerHigh`。
   - 选中高亮/输入框/强调组件：`MaterialTheme.colorScheme.surfaceContainerHighest`。
4. **动效一律收拢至 Spring Physics**：
   - 严禁书写 `tween(300)` 或 `LinearEasing` 等传统数值；使用 `MaterialTheme.motionScheme.defaultSpatialSpec()` / `defaultEffectsSpec()` 或 `spring(dampingRatio, stiffness)`。
5. **保持无侵入的原生语义**：
   - 所有卡片、单选、开关、互联按钮组必须具备标准的 `Role`、`contentDescription` 和选中状态，禁止用无语义的裸 `Box.clickable {}` 代替语义节点。

---

## 交付红线与防错准则 (Guardrails)

- **禁止臆造 API**：`org.jetbrains.compose.material3:1.12.0-alpha03` 内部有些 Token 类为 `internal`（例如 `ExpressiveMotionTokens`），外部开发必须通过公共 API（如 `MotionScheme.expressive()`、`ButtonGroupDefaults`、`FloatingToolbarDefaults`）消费，切勿从内部未导出包强行 import。
- **禁止平台私有依赖**：严禁在 `commonMain` 中使用 `dynamicColor`、`android.graphics.*`、`Activity` 或任何 Android 平台包。
- **禁止兼容垫片（Zero Shims）**：不得保留旧实现并在外层包裹桥接类；不得保留废弃的未启用逻辑。
- **验证机制**：变更后需通过 `./gradlew offlineCheck`（包含 39+ 项跨样式无头 UI 测试与双端编译校验）。
