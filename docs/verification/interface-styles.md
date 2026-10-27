# 双风格界面：控制连续性与桌面输入验证

日期：2026-10-20。对应 [#17](https://github.com/DOHEX/OpenRose/issues/17)，实现基线为 `05063e7`。本次使用真实共享界面和公开控制会话，蓝牙输入由可控传输替代；i5 在 Android 真机上的在途设置切换仍待验收，i7、Windows 与 HyperOS 的实际运行结果也不能由这些离线场景代替。

## 组件和配置

`StyleComponents.kt` 分别调用 Miuix 与 Material 3 的真实文字、卡片、按钮和开关组件。两套组件均随应用打包。2026-10-26 的默认样式改版加入真实选择弹层：Miuix 使用 OverlayDropdownPreference / OverlayDialog，Material 3 使用 AlertDialog；实现与新增验证见 [默认样式验证记录](ui-default-styles.md)。

界面风格和显示模式分别设置，显示模式包含跟随系统、浅色、深色。`UserSettingsCodec` 分别编码这些偏好；Android SharedPreferences 与桌面 Java Preferences 保存同一格式。既有 Android / Linux 重启验证见 [bootstrap.md](bootstrap.md)。本阶段没有新增强调色选择器。

`OpenRoseApp` 接收宿主持有的会话快照与操作，不构造会话或传输。滚动位置、触控槽选择和自定义 EQ 草稿位于组件风格分支之外；设备相关草稿按会话隔离。

## 两型号与两套组件的同一控制场景

`.scratch/style-contract-ui-smoke.sh` 渲染真实 `OpenRoseApp`，执行 i5 / i7 × Miuix / Material 3 四个场景。每个场景均从设备已确认的抗风噪状态开始，点击标准模式，等到底层写入完成后切换到另一套组件：

- 保留同一会话、已知状态和待确认操作；风格变化不创建第二条连接，也没有额外协议发送。
- 合成回复到达前仍显示已确认的抗风噪；新回复到达后才显示标准模式与设备已确认。
- 再次请求主动降噪，设备回复抗风噪时显示实际状态与不一致结果，不将请求值写成成功状态。
- 设置等待确认时断开，显示请求未获完整确认及会话已结束，没有自动重连或重放设置。

每个 i5 场景为 19 次 TX（13 次初始化加 6 次设置 / 回读），每个 i7 场景为 10 次 TX（4 次初始化加 6 次设置 / 回读），各只有一个合成物理连接。记录、截图和语义树位于 `.scratch/style-contract-ui-smoke/`，结果日志为 `.scratch/style-contract-ui-result.log`。所有广告、回复与断连均为合成事件，产品中没有加入演示设备。

## Linux 桌面输入和窗口

在 Linux 运行 `.scratch/style-keyboard-smoke.sh`，通过当前 Compose 1.12.0 的图像场景与键盘事件渲染真实共享界面；测试工具使用该确切版本的实验性 / 内部事件工厂，产品代码没有加入测试接口。三种窗口尺寸分别运行两套组件，共六组：

| 窗口尺寸 | 内容宽度 | 检查 |
| --- | --- | --- |
| 847 × 720 | 799 dp | 单列布局 |
| 848 × 720 | 800 dp | 双列布局 |
| 360 × 720 | 312 dp | 窄窗口换行与长页滚动 |

每组通过 Tab 与 Shift+Tab 导航，使用 Enter / Space 切换两套真实组件，并以 Space 开启、Enter 关闭“强制使用 BLE”开关。逐个检查获得焦点的控件，以 `positionInRoot + size` 计算未裁剪的完整范围，等待滚动完成后要求其非空且完全位于视口内；不使用会裁剪的 `boundsInRoot` 代替完整可见性。六组均能到达提示音等页面下方控制。一次公开会话和一个合成 i5 连接贯穿六组，始终只发送 13 次初始化请求。鼠标点击的控制与错误回显由前述四场景验证；桌面进程的实际鼠标、键盘和偏好重启证据另见初始化记录。

焦点的语义可达性、控件在视口内与视觉反馈分别核对。首个按钮在 Tab 聚焦后，两套组件的截图均显示组件自身的颜色反馈；不把存在 Focused 语义视为已经证明视觉反馈。截图与逐控件范围位于 `.scratch/style-keyboard-smoke/`，日志为 `.scratch/style-keyboard-result.log`。

## 验收边界

当前已运行平台为 Linux；这些窗口与输入场景没有证明 Windows 实际启动或桌面真实蓝牙传输。完整构建与两套各 163 项共享测试见 [connection-recovery.md](connection-recovery.md)，本次只补充界面场景和验证记录，没有改动会话或协议实现。真实 i5 设置等待确认时切换风格，仍须在设备可用后记录 Android 端结果。

## 独立复审

Standards 轴未发现可执行问题；最终文档与六组未裁剪范围验证的证据一致。Spec 轴未发现错误实现、无依据的通过声明或范围外承诺；真实 i5 在途命令切换仍是明确未完成的验收项，不能将本票或首版标为全部验收。
