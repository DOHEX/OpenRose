# Material Design 3 Expressive 组件群与代码范式 (Components Catalog)

本参考文档收录 Material Design 3 Expressive (MD3E) 在 Compose Multiplatform 1.12 (`org.jetbrains.compose.material3:1.12.0-alpha03`) 下的核心组件 API 签名、参数配置及最佳实战代码。

> **注解提示**：Expressive 专属组件均需在调用作用域或文件头标注：
> `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`

---

## 1. 互联按钮组 (Connected Button Group)

Connected Button Group 是 MD3E 的标志性组件之一，将多个离散的按钮整合入一个连续容器槽中，通过连续外边框与动态形变（Corner Morphing）传达选择状态。

### 1.1 结构与形变原则
- **高度与间距**：统一高度为 `40.dp`，按钮之间间隙为 `ButtonGroupDefaults.ConnectedSpaceBetween` (`2.dp`)。
- **静态圆角规范**：
  - 首项：外侧为胶囊圆角（`CornerFull`），内侧为小圆角（`ShapeTokens.CornerValueSmall = 8.dp`）。
  - 中间项：四周均为小圆角（`8.dp`）。
  - 末项：内侧为小圆角（`8.dp`），外侧为胶囊圆角（`CornerFull`）。
- **动态交互形变**：
  - 点击按下（Pressed）：内侧小圆角进一步收紧为 `4.dp`。
  - 选中激活（Selected）：该项整体形变为独立完整胶囊（`CornerFull`），背景呈现高亮容器色。

### 1.2 高保真单选互联按钮组代码示例

```kotlin
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedButtonGroup(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemLabel: @Composable (T) -> String,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = item == selectedItem
            val isFirst = index == 0
            val isLast = index == items.lastIndex

            // 动态弹性形变：未选中时保持组合拓扑，选中时弹变为完整独立胶囊
            val innerCorner by animateDpAsState(
                targetValue = if (isSelected) 22.dp else 6.dp,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
                label = "connectedInnerCorner",
            )
            val outerCorner = 22.dp

            val itemShape = RoundedCornerShape(
                topStart = if (isFirst || isSelected) outerCorner else innerCorner,
                bottomStart = if (isFirst || isSelected) outerCorner else innerCorner,
                topEnd = if (isLast || isSelected) outerCorner else innerCorner,
                bottomEnd = if (isLast || isSelected) outerCorner else innerCorner,
            )

            val backgroundColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.surfaceContainer
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f),
                label = "connectedBgColor",
            )

            val contentColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f),
                label = "connectedContentColor",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(itemShape)
                    .background(backgroundColor, itemShape)
                    .semantics {
                        this.role = Role.RadioButton
                        this.selected = isSelected
                        if (!enabled) this.disabled()
                    }
                    .clickable(
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onItemSelected(item) }
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = itemLabel(item),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}
```

---

## 2. 分段按钮 (SegmentedButton)

MD3E 官方内置了具有弹性指示器的分段按钮控件：

```kotlin
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveSingleChoiceSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelectIndex(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            ) {
                Text(option)
            }
        }
    }
}
```

---

## 3. 悬浮工具栏 (FloatingToolbar)

用于界面底部操作胶囊栏或轻量导航面板，具备自动跟随滚动折叠展开与弹性位移特性。

```kotlin
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveBottomFloatingBar(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = expanded,
        modifier = modifier,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        content = content,
    )
}
```

---

## 4. 宽屏导航栏与导航侧栏 (WideNavigationRail & NavigationRail)

在宽屏桌面（Desktop）或平板设备上，MD3E 提供 `WideNavigationRail` 与带有弹性胶囊指示器的 `NavigationRail`：

```kotlin
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

data class NavRailItemData(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun ExpressiveNavigationRail(
    items: List<NavRailItemData>,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        items.forEach { item ->
            val isSelected = item.route == currentRoute
            NavigationRailItem(
                selected = isSelected,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            )
        }
    }
}
```

---

## 5. 卡片族 (Card Roles)

MD3E 对卡片角色的圆角与背景色阶梯进行了重新分配：

| 卡片类型 (Card Variant) | 容器色角色 (Container Role) | 建议外轮廓圆角 (Shape) | 边框与阴影规范 |
|---|---|---|---|
| **`Card`** (Filled) | `surfaceContainerHighest` | `RoundedCornerShape(20.dp)` | 默认无边框，0 elevation |
| **`ElevatedCard`** | `surfaceContainerLow` | `RoundedCornerShape(20.dp)` | Level 1 浅阴影，无边框 |
| **`OutlinedCard`** | `surface` 或 `surfaceContainerLowest` | `RoundedCornerShape(20.dp)` | `outlineVariant` 细边框，0 elevation |

### 仪表盘/功能卡片范式

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveDashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp), // MD3E LargeIncreased 圆角
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
```

---

## 6. 滑动条与切换开关 (Sliders & Switches)

### 6.1 滑动条 (Slider)
在 MD3E 中，Slider 的轨道粗度与触点均支持更具质感的外观表现：

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    )
}
```

### 6.2 切换开关 (Switch)
Switch 原生采用物理弹簧驱动拇指（Thumb）滑动：

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    )
}
```
