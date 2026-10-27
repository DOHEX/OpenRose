# 界面优化规格：Miuix / Material 3 默认样式

状态：已实现 · 2026-10-26 · [验证记录](../verification/ui-default-styles.md)
范围：`composeApp` 共享界面；`androidApp` / `desktopApp` 只做必要的宿主适配。
读者：负责实现的 agent。动手前先读完本文第 1 节的硬约束。

---

## 0. 目标与非目标

**目标**

1. 两套**界面风格**分别呈现各自设计体系的**库默认外观**：Miuix 像 HyperOS 设置应用，Material 3 像标准 Android M3 应用。切换风格后，用户能一眼看出整套组件变了。
2. 导航外壳（顶栏、底栏、侧栏、返回）、列表行、开关、单选和多选都改用库自带组件，删除手写的替代实现。
3. 精简页面文字，统一信息层级。
4. 行为、语义和"事实边界"（见 1.3）**全部不变**。

**非目标**

- 不改 `core`、`ControlSession`、协议、`UserSettings` / `UserSettingsCodec`、宿主持有会话的方式。
- 不新增功能或设置项，不新增演示设备或演示模式。
- 不做动态取色（Material You / Miuix Monet），也不新增强调色设置。列为后续可选项，见第 9 节。

---

## 1. 硬约束（必须遵守）

### 1.1 必读

- [GLOSSARY.md](../../GLOSSARY.md)：界面文案使用其中的术语（界面风格、型号配置、型号能力、降噪模式、降噪档位、通透档位、超级岛模式、焦点通知开关等）。
- [ADR 0001](../adr/0001-preserve-control-session-on-style-switch.md)：切换风格时保持控制会话。
- [ui-redesign.md](../verification/ui-redesign.md)、[interface-styles.md](../verification/interface-styles.md)：已有验证结论，不能回退。

### 1.2 架构约束

- `OpenRoseApp(...)` 的公开签名**不变**（[OpenRoseApp.kt](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/OpenRoseApp.kt#L38-L50)）。
- 以下状态必须放在**风格分支之外**（`ProvideInterfaceStyle` 之上），切换风格后保留：
  - 导航返回栈 `backStack`
  - 每页的 `ScrollState`
  - 自定义 EQ 草稿与「预设 / 自定义」视图选择
  - 触控槽选择（改版后表示"哪个手势的动作选择器处于打开状态"）
  - 新增的选择对话框打开状态（见 4.4）
- 风格切换只允许触发 `onSettingsChange(settings.copy(interfaceStyle = …))`，不能调用 `onSet`、`onConnect`、`onDisconnect`、`onStartScan`。
- 界面不创建、不持有会话或传输。

### 1.3 事实边界（行为不变）

| 规则 | 现有实现位置 |
| --- | --- |
| 选中状态只来自设备已确认的值（`knownValue`），绝不使用请求值 | 各 `*Card.kt` |
| 有待确认设置时，设备相关控件一律 disabled（`isReady && !hasPendingSetting`） | `NoiseControlCard.kt#isReady/hasPendingSetting` |
| 未知开关不能显示成"关闭"：不渲染 Switch，不暴露 `ToggleableState`，只提供显式的「开启 / 关闭」请求入口 | [DeviceControlCard.kt#SettingToggleChoices](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/DeviceControlCard.kt#L173-L219) |
| 可选项只来自型号能力（`capabilities`、`writableSettings`），不硬编码 | 各页 |
| 请求值与设备确认状态分开显示；提交或底层写入完成都不算成功 | `SettingOperationsCard` |
| 断连后保留旧会话的操作记录，但不再呈现旧设备的详情和控制 | `activeDevice()` |
| 自定义 EQ 的模式和曲线分别确认；草稿按会话隔离；编辑草稿不会调用 `onSet` | `CustomEqualizerCard.kt` |
| 点击已确认的选项不重复发送 | 各 `if (x != confirmed) onSet(...)` |

### 1.4 测试锚点（不能破坏）

[jvmTest](../../composeApp/src/jvmTest/kotlin/io/github/dohex/openrose/ui/) 依赖以下语义，改版后必须保持，或在同一提交里同步更新测试并说明原因：

- 文案：`降噪档位`、`通透档位`、`1 档`/`2 档`/`3 档`/`4 档`/`5 档`、`当前状态未知`、`设备已确认：关闭`、`连接你的 ROSE 耳机`、`附近设备`、`扫描耳机`、`组件风格`、`强制使用 BLE`、`超级岛`、`焦点通知`、`自定义十段 EQ`、`创建平直曲线草稿`、`当前连接`、`设备状态`、`耳机已断开`、`均衡器 / 音效`、`请求 #N：…`、`已结束会话 N 的操作记录`。
- contentDescription：`返回`、`添加耳机`。
- 主导航项的 `Role.Tab`（Miuix 与 M3 的 `NavigationBarItem` / `NavigationRailItem` 自带）。
- 未知开关所在页面不存在任何 `ToggleableState` 节点。
- 断点：宽度 ≥ 900dp 用侧栏，耳机首页内容宽度 ≥ 680dp 用双列。断点保持不变。

---

## 2. 现状问题

截图见 `output/ui-review/2026-10-26/implementation/`。对比 `synthetic-01-home-light` 与 `synthetic-12-home-material3-light`，两套风格几乎没有差别。

| # | 问题 | 位置 |
| --- | --- | --- |
| P1 | 两套主题都被同一套 `RoseColors` 覆盖了颜色；卡片圆角 24dp、内边距 20dp、字号 32/20/15/13sp 全部手写，库默认的配色、形状和字体体系都没生效。 | [StyleComponents.kt](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/StyleComponents.kt#L57-L245) |
| P2 | 导航外壳全部手写：标题行用文本按钮 `‹`、`＋`；底栏和侧栏是自绘 `Box + selectable`；没有 TopAppBar，也没有滚动折叠；根节点直接套 `safeDrawingPadding`。 | [OpenRoseApp.kt](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/OpenRoseApp.kt#L73-L242) |
| P3 | 设置行手写（`PreferenceToggleRow`、`PreferenceActionRow`、`PageLink`、`FieldRow`），没有使用 Miuix Preference 或 M3 `ListItem`。 | [PreferencesPages.kt](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/PreferencesPages.kt#L506-L627)、[DevicePanel.kt](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/DevicePanel.kt#L174-L187) |
| P4 | 所有单选都是一排按钮，包括触控动作这种 8–9 个选项的 `FlowRow`，视觉很重，换行后也很乱。 | `TouchControlCard.kt`、`DeviceControlCard.kt`、`EqualizerControlCard.kt` |
| P5 | 说明文字过多：几乎每张卡片都有 1–3 段次要文字；`SettingControlsSummary` 在每张卡片里重复出现。 | 各 `*Card.kt` |
| P6 | 电量条和状态圆点自绘，并使用库配色里没有的 success / warning 色。 | `DevicePanel.kt#BatteryStrip`、`PreferencesPages.kt#StatusLabel` |
| P7 | 图标用 Canvas 绘制，无法传给需要 `ImageVector` 的库导航组件。 | [AppArtwork.kt](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/AppArtwork.kt#L22-L66) |
| P8 | "组件风格预览"和"超级岛样式预览"是伪造的示意图，并带硬编码颜色。 | `PreferencesPages.kt#ComponentStylePreview/IslandPreview` |

---

## 3. 设计原则

1. **默认值优先**：除 3.1 的白名单外，库组件不传 `colors`、`shape`、`cornerRadius`、`insideMargin`、`fontSize`、`contentPadding` 等外观参数。
2. **分层**：外壳（Scaffold、顶栏、导航）按风格各写一份；页面内容共用一份，通过"语义组件"（第 5 节）在内部按风格分派。
3. **信息层级**：页面 → 分组（分组标题）→ 行（标题 / 摘要 / 值）→ 页脚说明（每组最多一条）。
4. **两套风格允许布局不同**：Miuix 用"分组标题 + 卡片"，M3 设置类页面用"分组标题 + 平铺 ListItem"。这样的差异是预期效果。

### 3.1 允许的外观覆盖白名单

- M3 `ListItem` 放在 `Card` 内部时，设置 `ListItemDefaults.colors(containerColor = Color.Transparent)`。
- 错误信息和电量 ≤ 20% 使用当前主题的 `error` 色。错误卡片可使用 M3 `errorContainer`。
- Canvas 绘制（EQ 曲线、耳机插图）的颜色从当前主题的 colorScheme 读取，不再写死颜色。
- 页面级布局尺寸：内容最大宽度、分组间距、页面水平边距（见 4.5）。

---

## 4. 外壳与主题

### 4.1 主题

替换 [ProvideInterfaceStyle](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/StyleComponents.kt#L105-L197)：

```kotlin
// dark 的计算方式保持不变（Appearance.SYSTEM 时使用 isSystemInDarkTheme()）
when (style) {
    InterfaceStyle.MIUIX -> MiuixTheme(
        colors = if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme()
                 else top.yukonga.miuix.kmp.theme.lightColorScheme(),
        content = content,
    )
    InterfaceStyle.MATERIAL3 -> MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content, // typography、shapes 使用默认值
    )
}
```

- 删除 `RoseColors`、`LightRoseColors`、`DarkRoseColors`、`appColors()`、`pageBackground()`。
- 如需读取颜色，新增一个只做映射的访问器 `appColor(role)`，角色限定为：`Primary`、`OnSurface`、`Summary`、`Error`、`Divider`。映射关系：Miuix 分别对应 `primary` / `onSurface` / `onSurfaceVariantSummary` / `error` / `dividerLine`；M3 分别对应 `primary` / `onSurface` / `onSurfaceVariant` / `error` / `outlineVariant`。
- `StatusTone` 去掉 GOOD / CAUTION 的自定义颜色：GOOD → `Primary`，CAUTION 和 NEUTRAL → `Summary`，ERROR → `Error`。删除状态圆点，只保留文字。

### 4.2 字体

`AppText` 的 `title / large / small / secondary` 布尔参数改为语义角色枚举，并映射到各库的默认字体：

| 角色 | Miuix `MiuixTheme.textStyles` | M3 `MaterialTheme.typography` |
| --- | --- | --- |
| `Display`（首页型号名） | `title1` | `displaySmall` |
| `Title`（卡片标题） | `title4` | `titleMedium` |
| `Body` | `main` | `bodyLarge` |
| `Summary` | `body2`，颜色 `Summary` | `bodyMedium`，颜色 `onSurfaceVariant` |
| `Footnote` | `footnote1`，颜色 `Summary` | `bodySmall`，颜色 `onSurfaceVariant` |

上表里的样式名请先对照 0.9.4 源码中的 `TextStyles.kt` 核实。heading 语义保留在 `Display` 和 `Title` 上。

### 4.3 图标

- 把 `RoseIcon` 的 Canvas 绘制改写成 `ImageVector` 集合 `RoseIcons`：24×24 viewport，2dp 描边，圆角端点，线性风格。保留现有图标的语义集合。
- 两套风格都用各自库的 `Icon(imageVector, contentDescription)` 渲染，tint 使用默认的 `LocalContentColor`。
- 不引入 material-icons 依赖。`EarbudsArtwork` 保持 Canvas 绘制，只把颜色改为从主题读取。

### 4.4 外壳：两份实现

新建 `MiuixAppShell.kt` 和 `MaterialAppShell.kt`，两者接收同一组参数：`backStack`、`page`、`wide`、`onNavigate`、`onBack`、`scrollState`、`pageContent: @Composable () -> Unit`。

| 元素 | Miuix 0.9.4 | Material 3 |
| --- | --- | --- |
| 根容器 | `Scaffold(topBar, bottomBar)`，Overlay 弹层依赖它的 `popupHost` | `Scaffold(topBar, bottomBar)` |
| 主页面顶栏 | `TopAppBar(title, largeTitle, scrollBehavior = MiuixScrollBehavior())`，大标题可折叠 | `LargeTopAppBar` + `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` |
| 二级页面顶栏 | 同样使用 `TopAppBar`，`navigationIcon = IconButton { Icon(RoseIcons.Back, "返回") }` | `TopAppBar`，`navigationIcon` 同左 |
| 首页右上角 | `actions = { IconButton { Icon(RoseIcons.Plus, "添加耳机") } }` | 同左（M3 `IconButton`） |
| 窄屏导航（< 900dp） | `NavigationBar { NavigationBarItem(selected, onClick, icon, label) }`，只在 3 个主页面显示 | `NavigationBar { NavigationBarItem(...) }`，只在主页面显示 |
| 宽屏导航（≥ 900dp） | `NavigationRail(header) { NavigationRailItem… ; Spacer(weight) ; 诊断项 }`，所有页面常驻 | `NavigationRail(header) { NavigationRailItem… ; Spacer(weight) ; 诊断项 }` |
| 滚动折叠 | `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`；可选 `overScrollVertical()` | `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` |
| Insets | 删除根节点的 `safeDrawingPadding()`，交给 Scaffold 和各 bar 处理 | 同左 |

具体要求：

- 窄屏进入二级页面（添加耳机、降噪、均衡器等）时**隐藏底栏**，只保留顶栏返回。
- 顶栏标题：主页面使用 `AppPage.title`；首页在窄屏显示 `OpenRose`。删除现在各页手写的副标题，二级页面不再显示型号名副标题。
- 把 Scaffold 的 `PaddingValues` 应用在滚动容器的**子节点**上（Miuix 文档要求如此）。`verticalScroll(scrollStates[page])` 继续使用外部传入的 `ScrollState`。
- 顶栏折叠状态属于风格内的视觉状态，切换风格后可以重置，但滚动位置必须保留。
- Escape 返回（[OpenRoseApp.kt#L77-L81](../../composeApp/src/commonMain/kotlin/io/github/dohex/openrose/ui/OpenRoseApp.kt#L77-L81)）和 `PlatformBackHandler` 保持不变。

### 4.5 页面边距

共享页面内容**不加**全局水平 padding，由分组组件自行处理：

- Miuix：`SmallTitle` 使用默认 inset；`Card` 外加 `padding(horizontal = 12.dp)`，组间距 12dp，与 Miuix 示例一致。
- M3：`ListItem` 通铺（自带 16dp 内边距）；`Card` 外加 `padding(horizontal = 16.dp)`。
- 内容最大宽度不变：首页 1120dp，其他页 800dp。

---

## 5. 语义组件映射

替换 `StyleComponents.kt` 中的 `AppCard`、`AppButton(choice=…)`、`AppSwitch` 和各个手写行组件。共享页面**只调用以下组件**，组件内部按 `LocalInterfaceStyle` 分派。

依赖：`gradle/libs.versions.toml` 新增 `miuix-preference = { module = "top.yukonga.miuix.kmp:miuix-preference", version.ref = "miuix" }`，并加入 `composeApp` 的 commonMain。下表中的 Preference 组件都来自这个模块，签名请对照 0.9.4 的 sources jar 核实。

| 语义组件 | 用途 | Miuix | Material 3 |
| --- | --- | --- | --- |
| `AppGroup(title?, footnote?) { rows }` | 一组相关设置 | `SmallTitle(title)` + `Card { rows }` + 页脚 `Text(footnote1)` | 分组标题 `Text(titleSmall, primary)`，padding 16dp；行平铺；页脚 `bodySmall`；组间 `HorizontalDivider` |
| `AppDashboardCard { }` | 首页卡片（电量、降噪快捷） | `Card`（默认） | `ElevatedCard` 或 `Card`（默认） |
| `AppArrowRow(title, summary?, value?, icon?, onClick)` | 跳转 | `ArrowPreference(title, summary, startAction = Icon, endActions = { Text(value) }, onClick)` | `ListItem(headlineContent, supportingContent, leadingContent = Icon, trailingContent = Text(value))` + `Modifier.clickable(role = Button)` |
| `AppInfoRow(label, value)` | 只读字段 | `BasicComponent(title = label, endActions = { Text(value) })`；值较长时改用 `summary` | `ListItem(headlineContent = label, supportingContent = value)` |
| `AppSwitchRow(title, summary?, checked, enabled, onChange)` | 已知开关 | `SwitchPreference` | `ListItem(trailingContent = Switch(checked, onCheckedChange = null))`，整行 `Modifier.toggleable(value, enabled, role = Switch)` |
| `AppUnknownToggleRow(title, summary, enabled, onRequest)` | 未知开关 | `BasicComponent(title, summary = "当前状态未知", endActions = { TextButton("开启"); TextButton("关闭") })` | `ListItem(supporting = "当前状态未知", trailing = Row { OutlinedButton("开启"); OutlinedButton("关闭") })` |
| `AppRadioGroup(options, selected?, enabled, onSelect)` | 2–8 项单选，平铺展示 | 逐项 `RadioButtonPreference(title, selected, onClick, enabled)` | 逐项 `ListItem(leadingContent = RadioButton(selected, onClick = null))`，整行 `Modifier.selectable(role = RadioButton)`，外层 `selectableGroup()` |
| `AppChoiceRow(title, valueText, options, selected?, enabled, onSelect)` | 多项单选，折叠成一行显示当前值 | `selected` 已知时用 `OverlayDropdownPreference(items, selectedIndex, title, enabled, onSelectedIndexChange)`；`selected == null` 时改用 `ArrowPreference(summary = "当前状态未知")`，点开 `OverlayDialog` + `RadioButtonPreference` 列表（无选中项） | `ListItem(headline = title, supporting = valueText)`，点开 `AlertDialog`，内含 RadioButton 列表；选中即提交并关闭 |
| `AppSegmented(options, selected?, enabled, onSelect)` | ≤ 4 个短选项的即时切换 | 一排 `TextButton`：选中项用 `ButtonDefaults.textButtonColorsPrimary()`，其余用 `ButtonDefaults.textButtonColors()`，均为默认参数 | `SingleChoiceSegmentedButtonRow { SegmentedButton(selected, onClick, shape = SegmentedButtonDefaults.itemShape(i, n), enabled) }` |
| `AppViewTabs(tabs, index, onSelect)` | 纯视图切换（预设/自定义、左耳/右耳） | `TabRowWithContour(tabs, selectedTabIndex, onTabSelected)` | `SingleChoiceSegmentedButtonRow`（同上） |
| `AppCheckGroup(options, checked, enabledFor, onToggle)` | 多选 | 逐项 `CheckboxPreference` | 逐项 `ListItem(leadingContent = Checkbox(onCheckedChange = null))` + `Modifier.toggleable(role = Checkbox)` |
| `AppSliderRow(label, valueText, value, range, steps, enabled, onChange)` | EQ 频段 | `SliderPreference(title, valueText, value, valueRange, steps, enabled)` | `Text` 行 + `Slider`（默认） |
| `AppButton(text, onClick, enabled, kind = Primary/Secondary)` | 动作按钮 | Primary：`TextButton(colors = ButtonDefaults.textButtonColorsPrimary())`；Secondary：`TextButton()` | Primary：`Button`；Secondary：`OutlinedButton` |
| `AppProgress(fraction?)` | 电量 | `LinearProgressIndicator(progress)`；未知时不渲染 | `LinearProgressIndicator(progress = { p })`；未知时不渲染 |
| `AppBusyIndicator()` | 扫描中 | `InfiniteProgressIndicator` | `CircularProgressIndicator`（small） |
| `AppDivider()` | 分隔线 | `HorizontalDivider()` | `HorizontalDivider()` |

关键注意：

- **Miuix `TabRow` / `TabRowWithContour` 没有 `enabled` 参数，也不支持"无选中"**（`selectedTabIndex = -1` 会导致指示器偏移和 `scrollToItem(-1)`）。因此它只能用于纯视图切换，不能用于设备设置。
- `AppSegmented`、`AppRadioGroup` 和 `AppChoiceRow` 必须支持 `selected == null`（设备值未知），此时不显示任何选中项。
- `AppChoiceRow` 的对话框和下拉都是受控组件：选择后只调用 `onSelect`，行上显示的值仍然是设备已确认的值。待确认期间 `enabled = false`。
- 选中的项保留 `semantics { selected = true }`。

---

## 6. 逐页规格

除非特别注明，文案保留现有字符串（尤其是 1.4 中列出的）。"页脚"指 `AppGroup.footnote`。

### 6.0 页面级状态横幅（新增，替代各卡片里重复的 `SettingControlsSummary`）

在设备设置页（降噪、均衡器、触控、音频与设备）的顶部，显示未就绪提示或当前会话最新设置结果：

- 未就绪：`控制会话尚未就绪，暂不能设置。`
- 等待设备确认：简短显示正在处理设置，并保留设备已确认的控件值。
- 设备已确认：简短显示确认结果。
- 失败、超时或连接中断：就近显示结果和原因，并提供 `查看详情` 进入诊断。

完整请求值、请求编号、阶段、分步确认和已结束会话记录仅在诊断页显示；普通页面不显示操作历史块。样式沿用 Miuix 与 Material 3 默认组件。
页面底部保留一条页脚：`选择表示设备已确认的状态；提交后等待设备回报确认。`

### 6.1 我的耳机（首页）

**已连接，窄屏**

1. `AppDashboardCard`（设备卡）：`EarbudsArtwork`（高 140dp）→ 型号短名（`Display`）→ 型号配置（`Summary`）→ 连接阶段（就绪时用 `Primary`，否则 `Summary`）→ 电量三列：标签、数值、`AppProgress`。盒电量未知时只显示"未知"，不画进度条。
2. `AppDashboardCard`（降噪快捷）：标题行"降噪模式"，右侧摘要 `已确认：X` 或 `等待设备确认`；`AppSegmented(标准/降噪/通透)`，只列出能力中存在的模式；确认模式为 ANC 或通透、且档位可写时，下方再放一行 `AppSegmented` 档位（文字 `1`/`3`/`5`，contentDescription 为 `降噪档位 3 档`）；最后一行 `AppArrowRow("更多降噪设置")`。
3. `AppGroup`（功能）：`AppArrowRow` 依次为 均衡器（值为当前预设名）、触控操作、音频与设备（值为音频模式）、设备详情（值为型号配置）。显示条件与现在相同。**取代现在两列小卡片**，解决"左右耳独立设置"换行的问题。
4. 当前会话最新设置仅显示简短等待或结果反馈；错误旁提供 `查看详情` 进入诊断。普通首页不展示请求编号或操作历史块。

**已连接，宽屏（内容宽度 ≥ 680dp）**：左列设备卡（插图高 260dp），右列放 2–3 项；两列比例 0.9 : 1.1，与现状一致。

**未连接**：设备卡显示插图、`连接你的 ROSE 耳机` 或 `正在连接耳机`、摘要；按钮为 Primary `添加耳机`，连接中改为 Secondary `取消连接`。其下 `AppGroup { AppArrowRow("连接与运行状态", value = 原因) }`，再往下只显示当前错误及其诊断入口，不展示旧会话操作记录。

### 6.2 添加耳机

- 删除 128dp 的装饰蓝牙圆。顶部只保留一行 `Footnote`：`让耳机保持开启，并靠近手机`。
- 不能扫描时：`AppGroup` 中一行说明，加一个 Primary 按钮（`授予蓝牙权限` / `打开蓝牙设置`）。
- 连接中或已连接时：`AppGroup { AppInfoRow(阶段) ; AppButton(取消连接/断开连接, Secondary) }`。
- 分组标题为 `附近设备`，扫描时标题右侧放 `AppBusyIndicator`。每个候选设备一行：
  - Miuix：`BasicComponent(title = 名称, summary = "已识别 · ZT · -60 dBm", endActions = { TextButton("连接") })`
  - M3：`ListItem(headline, supporting, trailing = FilledTonalButton("连接"))`
  - 未识别型号：summary 为 `未知型号配置，无法连接`，不显示连接按钮。排序规则不变。
- 列表为空时显示一行空状态说明。
- 底部放一个全宽 Primary 按钮：`扫描耳机` / `停止扫描` / `重新扫描`，enabled 规则不变。

### 6.3 降噪控制

1. `AppGroup("降噪模式")`：`AppRadioGroup`，选项按 `noiseModeOrder` 排序，选中值为确认模式；页脚 `设备已确认：X`。
2. `AppGroup("降噪档位")` / `AppGroup("通透档位")`：显示条件不变（`isVisibleIn(confirmedMode)`）。内容为 `AppSegmented`，文字 `N 档`。
3. `AppGroup("降噪轮换")`：`AppCheckGroup`。单项的 enabled 为 `enabled && cycleKnown && canRemove`；页脚说明最少保留项数，或"尚无完整状态"。
4. 页面顶部显示当前设置的简短等待、确认或失败结果；完整操作记录只在诊断页显示。

### 6.4 均衡器

- 有自定义 EQ 能力时，顶部放 `AppViewTabs(预设 | 自定义)`，状态在外部保存。
- **预设视图**：`AppGroup("均衡器 / 音效")` 内为 `AppRadioGroup`（选项为预设）。i7 没有 EAP 时加页脚 `EAP 仅在设备固件达到型号要求后开放。`；游戏模式按开关的已知 / 未知状态分别使用 `AppSwitchRow` / `AppUnknownToggleRow`。
- **自定义视图**（分组标题保持 `自定义十段 EQ` 或 `自定义 EQ · N 段`）：
  1. `AppInfoRow("设备均衡器模式", …)`、`AppInfoRow("设备曲线", …)`。
  2. 草稿区：曲线预览（Canvas 改用主题色）+ 每个频段一行 `AppSliderRow`，窄屏单列，宽屏双列。
  3. 按钮：`复制设备曲线到草稿` / `恢复设备曲线到草稿`（Secondary）、`重置`（Secondary）、`创建平直曲线草稿`（无草稿时，Primary）、`应用草稿到耳机`（Primary，全宽）。
  4. 页脚只保留一条：`草稿只保存在当前控制会话。应用时设备会分别回读确认 EQ 模式和曲线。`

### 6.5 触控操作

1. `AppGroup("功能开关")`：`触控总开关`、`佩戴检测功能`，各自按已知 / 未知选择组件；页脚 `这里只配置检测功能，不代表当前佩戴、摘下或入盒状态。`
2. `AppGroup("左右耳手势")`：`AppViewTabs(左耳 | 右耳)`，之后每个手势一行 `AppChoiceRow(title = 单击/双击/…, valueText = 当前绑定或"当前绑定未知"/"未知动作（raw）", options = 动作)`。
   - 删除这一页的插图，删除 `TouchGestureRow` 的展开式按钮墙。
   - "触控槽选择"的状态改为表示"哪个手势的选择器打开"，并保留在外部。
   - 页脚 `修改的是触控绑定，不会立即执行播放、切歌或调节音量。`
3. 页面顶部显示当前设置的简短等待、确认或失败结果；完整操作记录只在诊断页显示。

### 6.6 音频与设备

1. `AppGroup("音频")`：`AppChoiceRow("音频模式")`；多点连接用开关行；页脚合并为一条：编码协商说明，再加上 LDAC / 多点互斥说明或"分别设置"说明。
2. `AppGroup("提示与音量")`：`AppChoiceRow("提示音音量")`，0 档显示为"关闭"。
3. `AppGroup("输出与保护")`：`AppChoiceRow("增益档位")`、`AppChoiceRow("听力保护阈值")`；页脚 `阈值显示型号提供的选项，不表示测得声压或实际保护效果。`
4. `AppGroup { AppArrowRow("设备详情") ; AppArrowRow("连接方式") }`。
5. `AppButton("断开连接", Secondary)`。当前设置的简短反馈显示在页面顶部，完整操作记录只在诊断页显示。

### 6.7 设备详情 / 诊断与记录

- `ConnectionDetailsCard` 改为 `AppGroup("当前连接")`，内含若干 `AppInfoRow`；选择原因和恢复信息放在页脚，或作为 `AppInfoRow` 的 summary。
- `DeviceReadings` 改为 `AppGroup("设备状态")`，内含若干 `AppInfoRow`；"查看原始记录"改为 `AppArrowRow`，点击展开或收起（展开状态按会话保存，与现状一致）；原始记录保留 `SelectionContainer`。
- `PlatformDiagnosticsCard` 改为 `AppGroup("系统状态")`：每项一个 `AppInfoRow`，状态文字使用 4.1 的 tone 映射；需要操作时，在该行下方放一个 `AppButton`。系统桥接、系统呈现记录同样改为分组加信息行，原有字段**一项都不能删**。
- `SettingOperationsCard` 仅在诊断页显示：每条操作一个块，标题为 `请求 #N：设置名`，summary 依次列出请求值、阶段、EQ 分步确认、错误和 `已结束会话…`。设备断开或连接新会话后，旧操作仍可追溯，且不得显示为当前会话的确认结果。

### 6.8 系统集成

> 页面归属已由规格 #33 / 实施票 #37 更新：本节只记录系统页职责。超级岛、焦点通知及模块触发设置移至设置页。

1. 系统页只显示本平台状态摘要和适用 HyperOS 的集成就绪情况；不放应用偏好或系统集成配置控件。
2. `PlatformDiagnosticsCard` 的固定入口只在系统页提供，点击后打开完整诊断。设置页和宽屏侧栏不重复提供该入口。
3. 适用 HyperOS 且集成未就绪时，系统页说明当前就绪状态与原因；保存偏好或调用系统路径不作为实际系统展示成功。
4. **删除** `CurrentConnectionCard`（与首页重复）。

### 6.9 设置

1. `AppGroup("界面风格", footnote = "切换组件风格时，当前耳机控制会话保持运行。")`：`AppChoiceRow` 或 `AppRadioGroup`，标题为 `组件风格`，选项 `Miuix` / `Material 3`。**删除 `ComponentStylePreview`。**
2. `AppGroup("显示模式")`：标题 `明暗模式`，选项 跟随系统 / 浅色 / 深色。
3. `AppGroup("连接偏好", footnote = 现有说明)`：`AppSwitchRow("强制使用 BLE", "仅影响下一次连接")`。
4. 仅在 HyperOS 系统集成适用时，提供超级岛模式、独立焦点通知开关及模块模式下的可靠显示时机。默认值与显式空触发集合沿用既有语义。
5. 集成暂未就绪时，设置仍可保存，并反馈“偏好已保存，待系统就绪后生效”；系统就绪本身不等于某项系统呈现已实际生效。
6. `AppInfoRow("关于 OpenRose", "ROSE / ROSESELSA 耳机控制")` 位于设置页底部；不再放固定诊断入口。
7. **删除**底部的连接状态卡（与首页重复）。

---

## 7. 实施分期（建议拆成独立 PR / issue）

| 期 | 内容 | 完成标志 |
| --- | --- | --- |
| 0 | 添加 `miuix-preference` 依赖；对照 0.9.4 sources 核实第 4、5 节涉及的 API 签名，不一致时在 PR 中说明并按实际 API 调整 | 编译通过 |
| 1 | 主题改为默认值；删除 `RoseColors`；`AppText` 改为语义角色；新增 `appColor(role)` | 两套风格在截图中能明显区分 |
| 2 | `RoseIcon` 改为 `ImageVector`；`AppIcon` 改用库的 `Icon` | 图标可以传给库导航组件 |
| 3 | 实现两份外壳（Scaffold、顶栏、导航栏 / 侧栏、Insets）；状态提升 | 导航测试通过 |
| 4 | 实现第 5 节的语义组件（双实现）；删除手写行组件 | 组件级测试通过 |
| 5 | 按第 6 节逐页迁移，建议顺序：设置 → 系统集成 → 首页 → 添加耳机 → 降噪 → 均衡器 → 触控 → 音频 → 详情 / 诊断 | 全部测试通过 |
| 6 | 补充测试，重新生成截图，更新验证文档 | 见第 8 节 |

每期结束都要运行：

```bash
./gradlew --offline :composeApp:jvmTest :core:jvmTest :desktopApp:createDistributable :androidApp:assembleDebug
```

---

## 8. 验收标准

**行为**

- [ ] 1.3 的每条规则都有测试覆盖，或已有测试继续通过。
- [ ] 新增测试：两套风格下，`AppChoiceRow` 在值未知时没有选中项；选择后只调用一次 `onSet`，行上的值不变，直到设备确认后才更新。
- [ ] 新增测试：切换风格时，若对话框或下拉处于打开状态，不会触发 `onSet`。
- [ ] 新增测试：窄屏二级页面不显示底栏；宽屏所有页面都显示侧栏。
- [ ] ADR 0001 相关测试（草稿、滚动位置跨风格保留，不重复发送）继续通过。

**外观**

- [ ] 用代码搜索确认：库组件调用中没有白名单（3.1）以外的 `colors =`、`shape =`、`cornerRadius =`、`fontSize =`、`insideMargin =`。
- [ ] `RoseColors`、`appColors()`、`ComponentStylePreview`、`IslandPreview`、`NavigationItem`、`PageLink`、`PreferenceToggleRow`、`PreferenceActionRow`、`FieldRow`、`BatteryStrip` 的 Canvas 部分都已删除或被替换。
- [ ] 每个分组最多一条页脚说明；`SettingControlsSummary` 不再在每张卡片里重复。
- [ ] 沿用 `.scratch/ui-redesign-smoke.sh` 的方式，用合成 `ControlState` 重新生成截图，至少包含：两套风格 × 浅色 / 深色 × 首页 / 设置 / 触控（420×900）、宽屏首页（1280×900）、窄屏断连（360×900）。输出到 `output/ui-review/<日期>/default-styles/`。

**无障碍与桌面**

- [ ] 可交互控件的触控目标 ≥ 48dp（使用库默认值即可满足）。
- [ ] Tab / Shift+Tab 能依次走到每个控件；Enter / Space 可操作开关和单选；Escape 返回上一级。
- [ ] 主导航项为 `Role.Tab` 并带 `selected` 语义；单选为 `Role.RadioButton`；开关为 `Role.Switch`。

**文档**

- [ ] 新增 `docs/verification/ui-default-styles.md`：记录实现、测试结果和截图链接，并写明截图不能证明真实耳机或 HyperOS 实机效果。
- [ ] 更新 README "版本与验证"一节的链接；[interface-styles.md](../verification/interface-styles.md) 中"当前界面没有弹窗"的说法需要同步修订。

---

## 9. 风险与后续

- **Miuix Overlay 组件依赖 Miuix `Scaffold` 的 `popupHost`**：Overlay 下拉和对话框必须渲染在 Miuix Scaffold 内部；如果出现嵌套 Scaffold，要确认 `renderInRootScaffold` 的行为。
- **M3 alpha 版 API**：部分 TopAppBar 滚动行为和 SegmentedButton API 可能需要 `@OptIn(ExperimentalMaterial3Api::class)`，这是允许的。
- **默认配色变化**：M3 默认基线色为紫色，Miuix 默认为蓝色，与现在统一的 `#4D64EB` 不同。这是"使用默认样式"的预期结果。
- **后续可选**（本期不做，需另开 issue）：Android 12+ 的 M3 动态取色（`dynamicLightColorScheme`）、Miuix `ThemeController(ColorSchemeMode.MonetSystem)`。按 GLOSSARY，强调色属于外观设置，加入时需要新增独立的外观偏好，不能挂在界面风格下。
