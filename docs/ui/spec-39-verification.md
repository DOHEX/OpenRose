# 规格 #39：集成验收记录

2026-10-28。集成分支为 `codex/spec-39-ui`，基线为 `355a318`。规格与子票：[#39](https://github.com/DOHEX/OpenRose/issues/39)、[#40](https://github.com/DOHEX/OpenRose/issues/40)、[#41](https://github.com/DOHEX/OpenRose/issues/41)、[#42](https://github.com/DOHEX/OpenRose/issues/42)。

## 实现规则

- 未连接首页使用居中的紧凑单列、常规宽度扫描按钮和默认紧凑顶栏；完整设备信息才放入卡片。详见[布局规则](spec-39-layout.md)。
- 空闲且传输不可用时，只显示宿主原因和连接与运行状态入口。权限恢复和蓝牙关闭恢复保留。若用户已启动扫描，则保留进度与停止操作，直到用户停止；不显示添加、候选列表或新扫描入口。此处按规格用户故事 27 保留已有操作的控制，详见[状态规则](spec-39-transport-state.md)。
- Material 3 选择弹窗内的选项行使用透明容器；普通列表和 Miuix 继续使用原有默认表面。详见[默认外观例外](spec-39-dialogs.md)。

## 行为与构建检查

通过公开 `OpenRoseApp` 注入合成 `ControlState`，使用真实语义、键盘事件、可见边界和宿主回调进行验证。没有新增产品测试入口。相同场景的窗口约束从 1280dp 改为 420dp 再恢复，确认导航切换且没有权限、扫描、连接、设备设置或偏好写入回调；临时注入的扫描副作用能使此测试失败。

连接中场景使用会话标识匹配的设备和读取中字段，检查阶段进度与现有菜单的取消连接回调。扫描过程中变为不可用的场景验证停止回调只由明确点击触发。既有导航、待确认状态、未知值、草稿与风格切换连续性回归继续执行。

集成命令：

```sh
./gradlew --console=plain check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable
```

最终生产代码 `0394e17` 上，命令执行成功（3m 11s），Android lint 及两项分发构建通过。测试报告统计如下：

| 测试任务 | 用例数 | 失败／错误 |
| --- | --- | --- |
| `core:jvmTest` | 176 | 0 |
| `core:testAndroidHostTest` | 176 | 0 |
| `composeApp:jvmTest` | 49 | 0 |
| `androidApp:testDebugUnitTest` | 2 | 0 |

调试包位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，本地 Linux 应用位于 `desktopApp/build/compose/binaries/main/app/OpenRose/`。完整集成命令日志保存在 `/tmp/openrose-spec39/context/final-validation.log`。

## 离屏视觉复核

逐组查看了 76 张截图，而非仅确认图片生成成功：

| 场景 | 风格与模式 | 宽度 | 数量 |
| --- | --- | --- | --- |
| 可扫描与空闲传输不可用首页 | 两套风格 × 浅色／深色 | 360、420、1280dp | 24 |
| 组件风格与明暗模式选择器 | 两套风格 × 浅色／深色 | 360、1280dp | 16 |
| 设置与系统页 | 两套风格 × 浅色／深色 | 420、1280dp | 16 |
| 已连接首页及导航边界 | 两套风格，浅色 | 679、680、899、900dp | 8 |
| 扫描中传输变为不可用 | 两套风格 × 浅色／深色 | 360、420、1280dp | 12 |

复核结果：宽屏未连接内容在主区域内居中，内部左对齐；顶部内容紧凑，扫描与停止按钮为常规宽度。窄屏说明正常换行，未见横向溢出、控件重叠或非预期裁切。空列表说明不再形成 Miuix 细长卡片；系统就绪说明与状态处于完整分组。Material 3 弹窗选项与父表面协调，选中态与取消操作清楚。679/680dp 的已连接单列／双列和 899/900dp 的底部／侧边导航切换仍保持原有规则。

另查看了两套风格的候选设备分组，以及提示音音量选择器的 16 个修改前后场景；该设备选择器复用相同弹窗容器。Miuix 前后图像相同，Material 3 的变化位于弹窗选项区域。

原始离屏图片、语义边界及复核拼图保存在本次本地任务的 `/tmp/openrose-spec39/evidence/`。以下代表图片随分支保存：

| 复核对象 | 截图 |
| --- | --- |
| Miuix 可扫描宽屏首页 | [截图](images/spec-39/miuix-scannable-wide.png) |
| Material 3 可扫描宽屏首页 | [截图](images/spec-39/material3-scannable-wide.png) |
| Miuix 深色不可用首页 | [截图](images/spec-39/miuix-unavailable-dark.png) |
| Material 3 深色选择弹窗 | [截图](images/spec-39/material3-dialog-dark.png) |
| 已启动扫描的停止操作 | [截图](images/spec-39/active-scan-unavailable.png) |

## 本地 Linux 窗口

用隔离偏好启动真实桌面分发，检查 Miuix 浅色与 Material 3 深色。窗口在宽屏与 420dp 窄屏之间缩放，侧栏／底栏正常切换，顶部和页面背景保持完整；通过键盘打开组件风格选择器，缩放后弹窗仍可读，Escape 可以关闭并返回首页。两次客户端均正常退出。

两次运行均移除 `DISPLAY`，Wayland 日志记录了 `xdg_surface` 创建、表面与缓冲提交，确认实际使用本地 Wayland 窗口。此次操作使用隔离的 Java 偏好目录，不写入用户的应用偏好。原生证据位于 `/tmp/openrose-spec39/evidence/native-linux/`；代表图：[Miuix 首页](images/spec-39/native-miuix-home.png)、[Material 3 弹窗](images/spec-39/native-material3-dialog.png)。

## 审查修正

标准审查未发现文档规则违例；识别出的截图辅助代码重复已移除，像素回归统一使用会释放 Skia 编码数据的共享截图工具。规格审查发现的真实连接中状态覆盖不足、扫描中变为不可用后无法停止两项问题均已修正，并通过公开入口回归验证。

本记录验证界面布局、交互、当前 Android 构建和本地 Linux 分发。合成耳机数据与本地窗口不构成真实耳机连接、Windows 运行或 HyperOS 系统呈现实测；桌面真实传输继续由 #31、#32 跟踪。
