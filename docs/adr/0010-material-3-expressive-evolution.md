---
status: accepted
---

# Material 3 全量原地演进至 Material Design 3 Expressive (MD3E)

## 背景

OpenRose 采用双界面风格架构，并列提供 Miuix（小米 HyperOS 规范）与 Material 3（Android 原生规范）。
Google 于 2025-2026 年推出了 Material Design 3 Expressive (MD3E)，通过弹性动效（Spring MotionScheme）、更具张力的色彩容器阶梯（Surface Container Roles）、形变圆角阶梯以及连贯互联的组件群（如 `ButtonGroup`、`SplitButton`、`FloatingToolbar`），解决了初期 Material You 视觉平淡、触控层级模糊的问题。

为使 OpenRose 的原生 Material 3 风格达到现代 Android 的最高体验标准，需对现有的 Material 3 体系进行全量升级。

## 架构决策

1. **原地演化而非派生新风格（Domain & Codec Cleanliness）**：
   - 维持 `InterfaceStyle.MATERIAL3` 枚举与 `UserSettingsCodec` 中的持久化键 `"material3"` 不变，不新增冗余枚举（如 `MATERIAL3_EXPRESSIVE`），防止全仓 39+ 个遍历 `InterfaceStyle.entries` 的跨样式 UI 测试矩阵无序膨胀 50%。
   - 在用户交互呈现层（如设置选项文案），将展示标签从 `"Material 3"` 升级为 `"Material 3 Expressive"`。

2. **Compose Multiplatform 跨平台可用性为硬性红线（CMP Parity）**：
   - 所有 Expressive 改造均严格基于共享模块（`composeApp/commonMain`）及当前依赖 `org.jetbrains.compose.material3:1.12.0-alpha03`，杜绝任何无法在 Desktop JVM 编译运行的 Android 专有私有类。
   - 优先采用 Compose Multiplatform 内置的正式或已验证实验性组件（如 `ButtonGroup`、`FloatingToolbar`、`MotionScheme`）。

3. **物理弹簧动效全局收拢（Spring MotionScheme）**：
   - 全面移除 Material 3 组件内散落的硬编码时间曲线（如 `tween` 或固定缓动参数），统一收拢为基于物理弹簧（Spring Physics）的阻尼与刚度规范，与 Miuix 的物理回弹形成品质呼应。

4. **语义零回归与无技术债务（Clean Cutover, Zero Shims）**：
   - 遵循干净切分原则（Clean Cutover）：全量替换旧实现，彻底删除过时代码与旧样式分支，严禁引入兼容层包装（Shim）、历史包袱代码或 `if (legacy)` 运行时开关。
   - 所有新增的 Expressive 组件直接以原生 API 暴露标准的 Compose 语义节点（`Role.RadioButton`、`Role.Tab`、`Role.Switch`、文本及 `disabled` 状态），保证现有自动化测试套件零修改通过。

5. **AI 技能包支持**：
   - 在 `.agents/skills/md3e/` 引入经过 CMP 约束适配的 `md3e` 技能包，指导智能体遵循最新的官方 Token 与组件范式。

## 结果与影响

- **积极影响**：
  - Material 3 风格在视觉分量、单手点按容错率和微动效反馈上得到质的飞跃。
  - 核心领域模型和测试用例免受破坏，开发维护成本维持在极低水平。
- **验证机制**：
  - 离线全量检查 `./gradlew offlineCheck`（包含所有 39+ 项跨样式 UI 测试与 JVM/Android 编译）必须 100% 通过。
