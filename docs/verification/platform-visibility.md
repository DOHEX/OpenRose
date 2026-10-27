# 平台适用性与界面显隐

## 共享事实

`PlatformState` 将宿主身份、功能适用性和运行状态分开提供。`HostPlatform` 标识 Android、Windows 或 Linux；`PlatformApplicability` 使用 `UNKNOWN`、`NOT_APPLICABLE`、`APPLICABLE` 表示 HyperOS 系统集成和控制传输偏好是否适用于当前宿主。现有 `module`、`bridge` 和 `transport` 仍描述适用功能的运行状态与诊断细节。

适用性变化只控制界面内容，不改写 `UserSettings`。因此暂时隐藏的超级岛模式、焦点通知值及显式空显示时机集合在再次适用时继续保留。适用 HyperOS 但模块未连接时，超级岛设置仍可保存；系统状态单独报告模块就绪情况。Android 构建身份不完整时，系统页明确显示“尚未核实”；已知不适用的平台不显示 HyperOS 专属设置或桥接占位项。

适用设置（含焦点通知）集中在设置页；系统页只呈现本平台状态、HyperOS 集成就绪情况与固定诊断入口。设置页和宽屏侧栏不重复显示诊断入口。保存调用回调后，对未就绪集成显示“偏好已保存，待系统就绪后生效”；就绪状态本身不宣告实际系统展示成功。

Android 适配器从公开的 `Build.MANUFACTURER`、`Build.BRAND` 和 `Build.VERSION.INCREMENTAL` 读取平台事实。Xiaomi / Redmi / POCO 品牌的有效 `OS3`、`OS4` 增量版本标为适用；有效 `OS1`、`OS2` 及已识别的 MIUI `V14` 版本标为不适用。缺失或 `Build.UNKNOWN` 身份、未识别的版本格式及未核实的未来 OS 主版本保持 `UNKNOWN`。模块连接、错误文字和目标系统包都不参与识别。控制传输偏好根据 BLE 与经典蓝牙硬件特性标记适用性，不依赖连接会话或模块状态。

Windows 与 Linux 宿主依据 `os.name` 标识自身；当前没有实现控制传输或 HyperOS 系统集成，二者均标为不适用。桌面状态不包含 Android 权限项。

## 验证边界

共享 UI 通过公开 `OpenRoseApp` 和合成 `ControlState` 验证普通 Android、适用 HyperOS、Windows 与 Linux 的内容显隐，并在 Miuix 和 Material 3 下覆盖。Android 适配器测试覆盖有效 OS1–OS4、MIUI V14、未识别格式、`Build.UNKNOWN` 标记及未来 OS 主版本；这些单元场景仅验证分类规则，不能证明目标手机公开的增量版本格式或 HyperOS 系统集成已在真实设备上工作。尚未在 HyperOS 3 / 4 实机核实 `Build.VERSION.INCREMENTAL` 的具体值；后续设备验收仍须记录实际 Android 构建身份。
