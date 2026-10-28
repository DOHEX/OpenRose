# 传输不可用时的首页呈现（#39 / #41）

未连接且权限条件允许时，宿主报告 `TransportAvailability.Unavailable` 的首页显示宿主提供的原因和“查看连接与运行状态”入口。顶栏添加、附近设备及空列表、扫描与停止扫描入口均不呈现。详情沿用现有诊断页；状态恢复为可用后，添加与手动扫描入口恢复，页面渲染不主动发起操作。

权限为 REQUIRED 或 DENIED 时，优先显示权限原因及授权操作，即使宿主同时报告传输不可用。蓝牙关闭状态继续提供打开蓝牙设置。已连接首页与传输可用时的扫描、连接和重试流程沿用原有行为。

这替换此前未连接页面在所有不可扫描状态下仍展示附近设备与禁用扫描按钮的规则；不按平台名称选择此分支。旧 `docs/specs/ui-default-styles.md` 已从当前基线移除，此记录明确对应的新规则。

## 行为验证

通过公开 `OpenRoseApp` 的离屏场景，`OpenRoseHomeScanUiTest` 覆盖两套风格的不可用原因和入口、陈旧候选及扫描状态不显示、REQUIRED/DENIED 与不可用组合、蓝牙关闭恢复、详情导航及返回、风格切换、可用与不可用状态切换以及显式扫描回调。键盘检查包括 Tab/Shift+Tab、Enter、Space 和 Escape，且从已聚焦的添加入口切换到不可用时仍能到达详情入口。模拟状态及离屏测试不代表真实设备或平台传输验收。

2026-10-28：`OpenRoseHomeScanUiTest` 及 `OpenRoseConnectedHomeUiTest`、`OpenRoseNavigationUiTest`、`PlatformApplicabilityUiTest`、`SystemSettingsOwnershipUiTest` 的 JVM 测试通过。

截图矩阵、完整集成回归及 Android/Linux 构建由父规格的集成验证记录。
