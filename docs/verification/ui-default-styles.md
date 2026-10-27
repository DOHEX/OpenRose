# Miuix / Material 3 默认样式验证

日期：2026-10-26。对应 [界面默认样式规格](../specs/ui-default-styles.md)。

## 实现

两套界面风格分别使用 Miuix 0.9.4 和 Material 3 的默认配色、字体和形状。删除统一的 RoseColors、自绘导航、按钮墙、状态圆点及两种伪预览。共享语义组件将设置行、单选、多选、开关、滑杆、进度条分别分派给各库组件；图标改为 24×24 ImageVector，由库 Icon 使用默认内容色。

外壳使用各自的 Scaffold、TopAppBar、NavigationBar 和 NavigationRail。900dp 以上常驻侧栏，窄屏二级页面隐藏底栏；首页内容在 680dp 以上使用 0.9:1.1 双列，最大宽度为 1120dp，其余页面为 800dp。Scaffold 的 PaddingValues 应用在滚动容器的子节点，并消耗相应 Insets。

设备设置页只在未就绪时显示横幅；当前会话的设置等待、确认或失败另用简短反馈呈现，失败原因旁可进入诊断。普通首页和功能页不显示请求编号、请求值、EQ 分步确认或操作历史。设备选中状态仍由 knownValue 提供，待确认时禁用相关控件，未知开关保留显式「开启 / 关闭」入口且没有 ToggleableState。草稿编辑不发送设置，EQ 模式与曲线分别确认，断连后移除旧设备控制并在诊断保留旧会话操作记录。设置页和系统集成页删除重复的连接状态卡，诊断字段和记录保留。

公开 OpenRoseApp 签名不变，未更改 core、设置编码或宿主持有会话的方式。导航栈、各页 ScrollState、EQ 草稿与视图、左右耳视图、触控选择器和其他选择弹层的打开状态均位于风格分支外。Escape 与系统返回优先关闭选择器，然后返回上一级。

## API 核对与适配

对照 Maven Central 的 0.9.4 sources jar 核实 TextStyles、Preference、Scaffold、导航和 OverlayDialog 签名，并添加 miuix-preference 依赖。Display / Title / Body / Summary / Footnote 分别映射到 Miuix 的 title1 / title4 / main / body2 / footnote1，以及 Material 3 的对应字体角色。

Miuix OverlayDropdownPreference 提供 onExpandedChange，但没有可受控的 expanded 参数。已知值首次打开使用库下拉，未知值使用库对话框；已打开的下拉跨风格恢复到 Miuix 时使用 OverlayDialog 和 RadioButtonPreference，保持打开态及设备确认值。外部关闭下拉时重建其视觉实例。上述路径都只在实际选择后提交，不因打开、关闭或切换风格提交。

允许的组件外观参数仅用于默认主题、Miuix 主操作/选中按钮、M3 分段按钮的库 itemShape、卡片内透明 ListItem、未就绪横幅的 secondaryContainer 和低电量的 error 色。Canvas 插图与 EQ 曲线从当前主题读取颜色。

## 自动化验证

完整验证命令：

```bash
./gradlew --offline :composeApp:jvmTest :core:jvmTest :desktopApp:createDistributable :androidApp:assembleDebug
```

最终结果：共享界面 14 项、核心 176 项测试全部通过，无失败或跳过；Linux 桌面发行目录与 Android Debug APK 已生成。构建日志：`.scratch/ui-default-styles-check.log`。新增依赖的 Android AAR 首次通过联网构建缓存后，再执行上述离线验证。

共享界面测试覆盖两套风格中的未知选择无选中项、请求一次后仍显示设备确认值、外部确认更新、已知下拉选择、外部关闭选择器不提交、已知/未知弹层跨风格保持且不提交、窄屏二级隐藏底栏、宽屏二级保留侧栏，以及滚动位置保持。原有降噪与导航测试继续覆盖能力选项、确认模式、待确认禁用、未知开关、EQ 草稿编辑/导航/风格切换和断连记录。旧设置页测试新增「先打开组件风格 / 明暗模式，再选择」步骤，以匹配新选择器交互。

## 桌面键盘与断点

`.scratch/ui-default-styles-keyboard-smoke.sh` 通过 ImageComposeScene 的真实 sendKeyEvent 发送 Tab / Shift+Tab / Enter / Space / Escape。两套风格均通过开关和单选操作、选择器内 Tab 可达、第一次 Escape 关闭选择器并停留当前页、第二次 Escape 返回首页。Miuix 下拉关闭后恢复页面焦点；Material 3 对话框显式接管焦点并处理 Escape。验证按顶层 semantics owner 检查焦点，避免把 Material 3 背景 owner 保留的焦点误作弹窗焦点。

679/680dp 首页单/双列，以及 899/900dp 底栏/侧栏边界均通过。一个公开 ControlSession 与合成 i5 传输贯穿这些场景，只有 13 条初始化写入，没有设置命令或第二条连接。[结果、焦点记录与截图](../../output/ui-review/2026-10-26/default-styles-keyboard/README.md) 是 Linux 离线输入证据，不代替真实蓝牙和 Android / HyperOS 实机验证。

## 截图

[30 张合成截图及场景清单](../../output/ui-review/2026-10-26/default-styles/README.md) 由公开 OpenRoseApp 和 ImageComposeScene 渲染。脚本为 `.scratch/ui-default-styles-smoke.sh`；设备、能力、电量、连接和平台状态都是隔离的合成 ControlState，产品未增加演示模式。

覆盖两风格 × 浅色/深色 × 首页/设置/触控（420×900），两风格宽屏首页（1280×900）及窄屏断连（360×900），另含降噪、预设/自定义 EQ、音频、诊断和打开的触控动作选择器。截图不能证明真实耳机、Android 返回手势、HyperOS 实机呈现、Windows 启动或桌面真实蓝牙已经通过验收。
