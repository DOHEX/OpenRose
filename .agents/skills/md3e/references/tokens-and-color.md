# Material Design 3 Expressive 色彩与容器阶梯规范 (Tokens & Color Roles)

Material Design 3 Expressive (MD3E) 在延续 Material You 色彩系统（M3 Color Roles）的基础上，对容器层级与色彩张力进行了核心重构。本规范指导如何在 Compose Multiplatform 1.12 (`org.jetbrains.compose.material3:1.12.0-alpha03`) 中正确运用 Expressive 色彩角色与表面容器阶梯。

---

## 1. 表面容器阶梯 (Surface Container Hierarchy)

MD3E 彻底弱化了对纯投射阴影（Shadow Elevation）的依赖，改用**色调明度对比度（Tonal Contrast）**构建界面纵深。`ColorScheme` 提供了 5 级容器阶梯与 2 个表面微调变体：

| 容器角色 (Container Role) | 视觉语义与建议用途 | 浅色模式调色板 (Light Tones) | 深色模式调色板 (Dark Tones) |
|---|---|---|---|
| **`surfaceContainerLowest`** | 最低阶梯，用于在普通页面底板上提供最强对比度下陷或基础衬底 | `Neutral 100` (纯白 `#FFFFFF`) | `Neutral 4` (最深黑 `#0F0E11`) |
| **`surfaceContainerLow`** | 低阶梯，用于大面积悬浮卡片、看板卡片（Dashboard Card）、列表背景 | `Neutral 96` (`#F7F2FA`) | `Neutral 10` (`#1D1B20`) |
| **`surfaceContainer`** | 标准基础容器，列表项、对话框、常规卡片默认底色 | `Neutral 94` (`#F3EDF7`) | `Neutral 12` (`#211F26`) |
| **`surfaceContainerHigh`** | 高阶梯，用于突出显示的控件栏、顶部操作栏、导航栏、浮层面板 | `Neutral 92` (`#ECE6F0`) | `Neutral 17` (`#2B2930`) |
| **`surfaceContainerHighest`**| 最高阶梯，用于强调组件、文本输入框容器、高亮卡片、互联按钮组底槽 | `Neutral 90` (`#E6E0E9`) | `Neutral 22` (`#36343B`) |
| **`surfaceDim`** | 暗调微调变体，在浅色下比 `surface` 略暗，提供沉浸感 | `Neutral 87` (`#DED8E1`) | `Neutral 6` (`#141218`) |
| **`surfaceBright`** | 亮调微调变体，在深色下比 `surface` 略亮，提升反射质感 | `Neutral 98` (`#FEF7FF`) | `Neutral 24` (`#3B383E`) |

### 明暗模式对比度演化规律
- **浅色模式 (Light Theme)**：数值越大的 Neutral 越明亮。`surfaceContainerLowest` 最亮（100，纯白），`surfaceContainerHighest` 最深（90）。卡片在浅色背景上通过“更深/更厚重”的色块突显层级。
- **深色模式 (Dark Theme)**：数值越大的 Neutral 越明亮。`surfaceContainerLowest` 最深（4），`surfaceContainerHighest` 最亮（22）。卡片在深色背景上通过“由暗到亮”的光源映射突显层级。
- **两端对齐**：无论浅色还是深色，从 `Lowest` 到 `Highest` 均表示**物理或视觉高度递增**的逻辑层级。

---

## 2. Expressive 色彩增强 (Expressive Color Roles)

MD3E 对彩色容器及其内容文字/图标的对比度进行了调优，官方内置了 `@Material3ExpressiveApi fun expressiveLightColorScheme()`：

```kotlin
// Compose Material3 1.12 源码定义：
@Material3ExpressiveApi
fun expressiveLightColorScheme() =
    lightColorScheme(
        onPrimaryContainer = PaletteTokens.Primary30,
        onSecondaryContainer = PaletteTokens.Secondary30,
        onTertiaryContainer = PaletteTokens.Tertiary30,
        onErrorContainer = PaletteTokens.Error30,
    )
```

相比标准 Material 3 的 `Primary10` / `Primary20` 映射，Expressive 调高了彩色容器内部内容的明度反差与色彩饱和度，使按钮胶囊、状态角标在紧凑空间内极具辨识度。

### 核心色彩角色对照

1. **Primary 系列**：
   - `primary`：主强调色（主要操作按钮、激活切换开关底色）。
   - `onPrimary`：主色之上的文字与图标。
   - `primaryContainer`：主色容器（胶囊指示器背景、选中档位底色）。
   - `onPrimaryContainer`：主色容器之上的内容（Expressive 下对比度更加鲜明）。
2. **Secondary & Tertiary 系列**：
   - `secondary` / `secondaryContainer`：辅助控件、互联按钮组副项、次级选框。
   - `tertiary` / `tertiaryContainer`：中立强调、设备电量/连接状态高亮。
3. **Outline 系列**：
   - `outline`：主边界轮廓色（输入框外框、高对比度分割线）。
   - `outlineVariant`：弱化轮廓（卡片微边框、卡片内部条目分割线，透明度或明度适度弱化）。

---

## 3. 代码实战与多平台接入 (Compose Multiplatform Usage)

### 3.1 主题级配置 (Theming Setup)

在 CMP 1.12 的 `commonMain` 中，推荐直接使用 `MaterialExpressiveTheme` 包装组件树：

```kotlin
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppExpressiveTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            // 支持显式覆写表面容器角色
            // surfaceContainerLow = Color(0xFF1D1B20),
            // surfaceContainer = Color(0xFF211F26),
        )
    } else {
        expressiveLightColorScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
```

### 3.2 消费表面容器角色 (Consuming Surface Containers)

在自定义组件或卡片中，一律通过 `MaterialTheme.colorScheme` 获取对应的容器色：

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .background(
                color = containerColor,
                shape = RoundedCornerShape(20.dp), // MD3E LargeIncreased 圆角
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(16.dp)
    ) {
        content()
    }
}
```

---

## 4. 常见误区与防错守则 (Antipatterns)

- ❌ **严禁写死十六进制色值**：例如直接写 `Color(0xFFEEEEEE)` 或 `Color.White` 作为卡片底色。这样会导致暗色模式下界面无法自适应反转，或破坏无头色彩断言。
- ❌ **严禁在 Desktop JVM 上调用 `dynamicLightColorScheme`**：动态色彩是 Android 12+ API 31 的平台私有能力，在 `commonMain` 中会导致编译或运行时异常。
- ❌ **避免纯黑色大面积硬编码**：深色模式下使用 `MaterialTheme.colorScheme.surfaceContainer` 系列，避免直接使用纯黑 `#000000` 导致无法呈现阴影与多级深度。
- ❌ **不要遗漏 `outlineVariant`**：在极窄边框或分割线上，优先选用 `MaterialTheme.colorScheme.outlineVariant` 而非通用灰色。
