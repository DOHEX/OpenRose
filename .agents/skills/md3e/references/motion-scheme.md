# Material Design 3 Expressive 弹簧动效方案规范 (MotionScheme & Spring Physics)

在 Material Design 3 Expressive (MD3E) 中，机械、线性的固定时长动画（如 `tween(300, FastOutSlowInEasing)`）被全面废弃，统一演进为符合真实世界物理特性的**弹性弹簧动效方案 (Spring MotionScheme)**。

在 Compose Multiplatform 1.12 (`org.jetbrains.compose.material3:1.12.0-alpha03`) 中，`MotionScheme` 是一等公民 API，被原生整合进 `MaterialTheme`。

---

## 1. 核心设计原则：空间与效果二元分离 (Spatial vs Effects)

MD3E 严格将界面微交互区分为两类截然不同的物理属性，分别赋予不同的阻尼比（Damping Ratio）和刚度（Stiffness）：

```
                         ┌───────────────────────────────────────────┐
                         │          MotionScheme.expressive()        │
                         └─────────────────────┬─────────────────────┘
                                               │
                       ┌───────────────────────┴───────────────────────┐
                       ▼                                               ▼
         ┌───────────────────────────┐                   ┌───────────────────────────┐
         │     空间动画 (Spatial)    │                   │     效果动画 (Effects)    │
         ├───────────────────────────┤                   ├───────────────────────────┤
         │ • 位移、尺寸、外形 Morphing│                   │ • 颜色渐变 (Color)        │
         │ • 边界展开、胶囊扩张      │                   │ • 透明度 (Alpha/Fade)     │
         │ • 欠阻尼 (Damping 0.6~0.8)│                   │ • 阴影高度 (Elevation)    │
         │ • 具有轻微弹性回弹反馈    │                   │ • 严格临界阻尼 (Damping 1)│
         │ • 视觉生命力与动感        │                   │ • 绝不发生数值越界/过冲   │
         └───────────────────────────┘                   └───────────────────────────┘
```

### 1.1 空间动效 (Spatial Specs)
- **用途**：控件尺寸变化、滑动偏移、抽屉展开、互联按钮组选择形变（Corner Morphing）。
- **物理特性**：采用**欠阻尼（Under-damped, Damping 0.6f–0.8f）**。物理系统允许在达到最终位置时有极轻微的能量回弹，带来“果冻”般的触觉响应，模拟真实物体的质量与弹性。

### 1.2 效果动效 (Effects Specs)
- **用途**：背景色切换、文字内容淡入淡出、遮罩 Scrim 透明度变化。
- **物理特性**：必须采用**临界阻尼（Critically Damped, Damping 1.0f）**。色彩和透明度动画一旦欠阻尼，其数值会突破目标范围（例如 Alpha > 1.0f 或溢出色域），在屏幕上产生刺眼的白闪或闪烁假死。因此效果动效绝不反弹。

---

## 2. 官方参数矩阵与数值规范 (Expressive Motion Tokens)

在 `org.jetbrains.compose.material3:1.12.0-alpha03` 中，Expressive 动效的底层真实物理参数如下：

| 动效档位 (Token Tier) | 对应场景与交互 | 空间阻尼比 (Spatial Damping) | 空间刚度 (Spatial Stiffness) | 效果阻尼比 (Effects Damping) | 效果刚度 (Effects Stiffness) |
|---|---|---|---|---|---|
| **Fast** | 微小元件点击反馈、开关拨动、紧凑互联按钮状态切换 | `0.6f` | `800.0f` | `1.0f` (无回弹) | `3800.0f` |
| **Default** | 卡片展开收起、导航条指示胶囊位移、页面级浮层进出 | `0.8f` | `380.0f` | `1.0f` (无回弹) | `1600.0f` |
| **Slow** | 大面积容器形变、全屏对话框升降、复杂多元素级联转场 | `0.8f` | `200.0f` | `1.0f` (无回弹) | `800.0f` |

对比标准方案（`MotionScheme.standard()`）：标准方案的空间阻尼普遍设定在 `0.9f`，刚度在 `700f`–`1400f`，表现为沉稳克制、几乎无弹跳；而 Expressive 的空间阻尼下调至 `0.6f`–`0.8f`，刚度更低，使得动画更加舒展有张力。

---

## 3. Compose Multiplatform 1.12 代码实战

### 3.1 主题注入

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

@Composable
fun ProvideExpressiveMotionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
```

### 3.2 消费主题 MotionScheme

在自定义交互组件中，应优先通过 `MaterialTheme.motionScheme` 获取动画规格：

```kotlin
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveInteractivePill(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme

    // 空间动画：胶囊宽度/圆角采用 defaultSpatialSpec
    val targetCorner: Dp by animateDpAsState(
        targetValue = if (selected) 24.dp else 8.dp,
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "pillCornerSpatial",
    )

    // 效果动画：背景色彩采用 defaultEffectsSpec
    val targetColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "pillColorEffects",
    )

    Box(
        modifier = modifier
            .height(40.dp)
            .background(color = targetColor, shape = RoundedCornerShape(targetCorner))
    )
}
```

### 3.3 直接声明 Spring 规范（无上下文或解耦场景）

当需要在独立原子组件、测试环境或解耦的工具类中直接应用 MD3E 物理参数时，可采用对应的 `spring(...)` 调用：

```kotlin
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp

object Md3eSpringSpecs {
    // 快速空间弹簧（互联按钮选中、开关滑块）
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.6f,
        stiffness = 800.0f,
    )

    // 默认空间弹簧（胶囊指示器、导航条切换、卡片大小变化）
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.8f,
        stiffness = 380.0f,
    )

    // 默认效果弹簧（色彩、透明度渐变，严格临界阻尼）
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy, // 1.0f
        stiffness = 1600.0f,
    )
}
```

---

## 4. 迁移与排查守则 (Migration & Anti-patterns)

- ❌ **严禁散落 `tween(durationMillis = ...)`**：
  - *错误*：`animateDpAsState(target, animationSpec = tween(200))`
  - *正确*：`animateDpAsState(target, animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())`
- ❌ **严禁对 Color / Alpha 使用有弹跳的阻尼**：
  - *错误*：`animateColorAsState(target, animationSpec = spring(dampingRatio = 0.6f))`
  - *后果*：颜色分量计算越界导致画面闪屏。
  - *正确*：必须使用 `motionScheme.defaultEffectsSpec()` 或 `dampingRatio = 1.0f`。
- ❌ **严禁使用带平台依赖的 View 动画工具**：
  - 在 CMP 中只使用 Compose 原生 `androidx.compose.animation.core.*`。
