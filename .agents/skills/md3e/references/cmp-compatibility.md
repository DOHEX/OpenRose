# Compose Multiplatform 1.12 跨平台边界与兼容约束 (CMP 1.12 Boundaries)

本规范阐明在 `sharedUI/src/commonMain/` 中实施 Material Design 3 Expressive (MD3E) 时，必须严格遵守的 Compose Multiplatform 1.12 (`org.jetbrains.compose.material3:1.12.0-alpha03`) 跨平台边界、Desktop JVM 无头测试要求以及平台专有 API 绝对禁令。

---

## 1. 跨平台版本与包结构基准 (Baseline & Packages)

OpenRose 工程依赖配置如下（定义于 `gradle/libs.versions.toml`）：
- `compose = "1.12.0"`
- `material3 = "1.12.0-alpha03"`
- 模块依赖：`org.jetbrains.compose.material3:material3`

### 1.1 `commonMain` 中完全可用的 MD3E API
以下特性由 JetBrains Compose Multiplatform 完整移植并提供跨平台支持，可在共享代码中直接使用：
- **主题与动效**：`MaterialExpressiveTheme`、`expressiveLightColorScheme()`、`darkColorScheme()`、`MotionScheme`、`MotionScheme.expressive()`、`MotionScheme.standard()`、`MaterialTheme.motionScheme`。
- **互联与按钮组**：`ButtonGroup`、`ButtonGroupDefaults`、`SegmentedButton`、`SingleChoiceSegmentedButtonRow`、`MultiChoiceSegmentedButtonRow`、`SplitButtonLayout`。
- **浮动与导航**：`HorizontalFloatingToolbar`、`VerticalFloatingToolbar`、`FloatingToolbarDefaults`、`WideNavigationRail`、`NavigationRail`、`NavigationBar`。
- **指示器与卡片**：`LoadingIndicator`、`WavyProgressIndicator`、`Card`、`ElevatedCard`、`OutlinedCard`、`Slider`、`Switch`。

---

## 2. 桌面端对齐与无头测试约束 (Desktop JVM & runComposeUiTest)

OpenRose 采用纯 JVM 无头测试架构（`sharedUI/src/jvmTest`）。测试工具基于官方 `runDesktopComposeUiTest`，场景时钟为虚拟时间，只在测试渲染或推进时间时前进；在此之上进行组件渲染、布局测量与语义树验证。

### 2.1 无头环境运行准则
1. **纯 Kotlin/Skiko 渲染**：
   - 依赖纯 Canvas 绘图与通用路径计算（`androidx.compose.ui.graphics.Path`、`Modifier.drawBehind`、`Modifier.clip`）。
   - 严禁依赖只在 Android GPU 硬件加速管线下才生效的特殊着色器特性或私有混合模式。
2. **虚拟时间与弹簧动效**：
   - 在测试环境中，Compose 的虚拟时间调度器（`TestMonotonicFrameClock`）会自动推进弹簧物理模拟。
   - 使用标准 `spring()` 或 `MaterialTheme.motionScheme` 的动画会在无头测试调用 `advanceTimeBy(...)` 或 `waitForIdle()` 时平滑收敛至稳定态。
3. **窗口内边距（Window Insets）跨平台一致性**：
   - 避免直接调用 Android 系统栏专有 insets 对象。使用通用的 `WindowInsets.systemBars` 或 `WindowInsets.safeDrawing`，确保在桌面端窗口与移动端均能优雅降级或返回安全默认值。

---

## 3. Android 专有 API 绝对禁令 (Android-Exclusive Prohibitions)

在 `sharedUI/src/commonMain/` 中，任何涉及以下平台专有类的代码都将导致 Desktop JVM 编译直接报错或测试崩溃，**严禁引入**：

| 禁止引用的 Android 专有 API | 危害与问题 | CMP 规范替代方案 |
|---|---|---|
| `dynamicLightColorScheme(Context)` / `dynamicDarkColorScheme(Context)` | 仅在 Android 12+ API 31 上可用，需要 `android.content.Context` | 使用静态 `expressiveLightColorScheme()` / `darkColorScheme()`，或纯 Kotlin 颜色计算 |
| `LocalContext.current` | 在 JVM Desktop / iOS 上不存在此 CompositionLocal | 通过多平台依赖注入或由平台层（`androidMain`）向外暴露配置数据 |
| `android.view.View` / `HapticFeedbackConstants` | 强制依赖 Android View 视图树与硬件触控服务 | 使用 Compose 原生 `LocalHapticFeedback.current.performHapticFeedback(...)` |
| `android.graphics.*` (如 `Bitmap`, `Canvas`, `Paint`) | 破坏跨平台纯净性 | 使用 Compose 原生 `androidx.compose.ui.graphics.*`（`ImageBitmap`, `Canvas`, `Paint`） |
| `tokens.*` 内部包中的非公开类（如 `internal ExpressiveMotionTokens`） | 依赖不可导出的私有符号，后续版本升级极易产生二进制破坏 | 使用公开暴露的 `MotionScheme.expressive()`、`ButtonGroupDefaults`、`FloatingToolbarDefaults` |

---

## 4. 零兼容层与干净切换原则 (Clean Cutover, Zero Shims)

遵循 ADR 0010 的核心决议：
1. **不得增加多余的枚举变体**：
   - 保留原有的 `InterfaceStyle.MATERIAL3` 和存储键 `"material3"`。仅在界面文字呈现层更新为 `"Material 3 Expressive"`。
2. **彻底删除废弃实现**：
   - 演进完成后，删除旧的固定缓动动画、旧卡片硬编码边距等遗留逻辑。
   - 严禁引入 `class Material3LegacyShim` 或运行时判断 `if (useExpressive) ... else ...`。
3. **严防语义回归 (Semantics Preservation)**：
   - 所有的 Expressive 组件改造必须 100% 保持既有的 Compose 语义树：
     - 单选按钮 / 选项卡：必须附加 `Role.RadioButton` / `Role.Tab`，并声明 `selected = isSelected`。
     - 开关：必须维持 `Role.Switch`。
     - 禁用状态：必须附带 `disabled()`。
     - 文本值：标签文本必须保持一致，不得随意更改或丢弃，确保现存 39+ 项跨样式无头 UI 测试开箱通过。

---

## 5. 编译与检查验证流水线 (Offline Verification)

在提交任何基于本技能包开发的代码前，务必执行离线验证：

```bash
./gradlew offlineCheck
```

该检查包含：
- `:sharedUI:jvmTest`：执行所有无头界面回归测试（包含 `:sharedUI` 的 JVM 编译，验证桌面端无 Android 污染）。
- `:core:jvmTest`：执行领域逻辑测试。
- `:desktopApp:compileKotlin`：验证桌面入口编译。
- `:androidApp:compileDebugKotlin`：验证 Android 平台编译兼容（经依赖编译 `:sharedUI` 的 Android 目标）。
