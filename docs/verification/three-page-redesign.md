# 耳机、系统、设置三页与平台显隐

规格：[GitHub #33](https://github.com/DOHEX/OpenRose/issues/33)。实现分支：`integration/spec-33-ui`。本记录覆盖页面、状态契约与离线验证；#33 替代旧界面规格中的页面归属和连接流程安排。

## 实现

- [#34](https://github.com/DOHEX/OpenRose/issues/34)：宿主明确提供平台身份、功能适用性和当前就绪事实。Android 识别放在适配器；Windows/Linux 当前没有控制传输，不显示无作用的连接偏好。
- [#35](https://github.com/DOHEX/OpenRose/issues/35)：默认耳机页直接展示附近设备，手动扫描、停止、连接、取消和重试。初次渲染和风格切换不自动扫描。
- [#36](https://github.com/DOHEX/OpenRose/issues/36)：已连接首页压缩插图，优先型号、控制连接、电量、降噪与能力入口；型号配置进入详情。右上角菜单的“断开控制连接”复用现有回调，断连后回附近列表。
- [#37](https://github.com/DOHEX/OpenRose/issues/37)：系统页提供状态、原因和固定诊断入口；设置页集中适用的应用及 HyperOS 偏好。保存反馈与实际系统呈现分开，隐藏设置不清空保存值或显式空触发集合。
- [#38](https://github.com/DOHEX/OpenRose/issues/38)：普通页面保留简明反馈与错误详情入口；请求编号、写入/确认阶段、EQ 分步确认及已结束会话记录保留在诊断。

设备选择始终来自已确认状态；未知值保持未知，待确认设备控件禁用。公开 `OpenRoseApp` 入口与宿主会话所有权保持不变。风格切换保留导航、滚动、EQ 草稿和受控选择状态。两套界面使用库默认外观，保留 680dp 双列和 900dp 侧栏断点。

## 验证

源码验证点：`162256211a7e9120531ac93a2f3b8ac4e6d1680c`。

```sh
./gradlew --offline :composeApp:jvmTest :core:jvmTest :androidApp:testDebugUnitTest :desktopApp:createDistributable :androidApp:assembleDebug
```

Linux 本地验证：共享 UI 35 项、核心 176 项、Android 适配器 2 项，零失败；Android debug APK 和 Linux 桌面分发构建成功。UI 行为通过公开 `OpenRoseApp`、合成状态与真实语义操作捕获回调；平台识别单元场景只检验适配器规则。

代码审查分别检查项目规范与规格符合性。审查指出的未识别构建适用性处理和重复测试辅助代码已修正；键盘验收发现的 Material 3 菜单 Escape 问题也有回归覆盖。

## 截图和键盘

[截图索引](../../output/ui-review/2026-10-27/spec-33/index.html) · [矩阵说明](../../output/ui-review/2026-10-27/spec-33/README.md) · [键盘记录](../../output/ui-review/2026-10-27/spec-33/keyboard.tsv)

矩阵包含两套风格 × 浅/深色 × 耳机/系统/设置的 420×900 页面、360×900 附近设备、1280×900 首页，以及普通 Android、Windows、Linux 和 679/680、899/900dp 边界。键盘通过合成 KeyEvent 验证 Tab/Shift+Tab、Enter、Space、Escape 的焦点、回调及返回行为。

## 证据边界

Android 平台事实来自公开 [Build](https://developer.android.com/reference/android/os/Build) 和 [Build.VERSION](https://developer.android.com/reference/android/os/Build.VERSION) 字段；构建标识缺失、未知或不能识别时不冒充已支持。尚未在真实 HyperOS 3/4 手机核实这些字段的具体值，适用性不等于 Hook、系统兼容或实际呈现已通过。

本轮没有真实蓝牙连接、耳机写入、HyperOS 展示或 Windows 运行验收。离屏截图、键盘事件与构建分别只证明对应范围。桌面真实传输继续由 [Linux #31](https://github.com/DOHEX/OpenRose/issues/31) 和 [Windows #32](https://github.com/DOHEX/OpenRose/issues/32) 跟踪。

原有未提交工作保存为基线 `43d75f3`；原始 `main` 工作区保留，当前任务增量可用 `git diff 43d75f3...integration/spec-33-ui` 查看。
