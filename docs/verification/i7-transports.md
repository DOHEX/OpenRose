# i7 SPP / BLE 控制连接：实现与离线验证

日期：2026-10-19。对应 [#15](https://github.com/DOHEX/OpenRose/issues/15)，固定审查基线 `de74008`。i7 实机不可用；下列平台事实、广播和 RX 均为可控合成输入，不是设备抓包。i5 与小米 14 也尚未完成 Android 真实耳机验收。

## 选择依据

`ControlSession` 继续是唯一控制所有者。`ControlTransport` 的 BLE / RFCOMM endpoint 和音频 profile 事实都不依赖某个蓝牙库。仅在 i7 广播提供有效 BR 地址、平台支持 SPP、未强制 BLE，且目标已连接传统音频 profile 时选择 SPP；已连接 LE Audio 或无法确认音频类型时选择 BLE。i5 保持 BLE。

广告 BR 标志、地址和专用 UUID 的静态来源及 Android 公开接口见 [协议来源](../protocols.md#i7-控制传输选择15)。扫描身份和 BR 地址分别保留。音频配置 LC3 不表示实际协商为 LE Audio，A2DP 是否正在播放也不参与选择。

Android 对 A2DP、HEADSET 和 API 33+ LE Audio 使用公开 profile 代理。Android 12 / 12L（API 31–32）缺少同一公开 profile 常量，保守返回未知并使用 BLE，不依赖隐藏 profile ID。代理未就绪、身份不完整、查询失败及无法关联目标的 LE 设备均保守返回未知，并释放已取得和晚到的代理。Android 8–11 没有该公开 LE Audio profile，仍需目标传统 profile 的肯定连接证据；没有匹配不能推导为传统连接。

## 会话与资源

连接开始时保存强制 BLE 偏好快照，一次只选择并创建一个 endpoint；更改偏好或界面风格不重连、不切换在途链路、不重复发送命令。界面显示本次选择及原因。强制 BLE 偏好持久保存，仅影响下次连接。

SPP 使用 `0cf12d31-fac3-4553-bd80-d6832e7b3931` 的安全 RFCOMM socket。后台连接和流读写可通过关闭 socket 解除阻塞。订阅开始单个流读取器，使用有界通知队列；完整帧写入接受与设备确认分开。连接失败、取消、EOF、溢出和断开清理对应连接，不自动转到 BLE 或重放设置。

两种连接共享 codec、100ms 写间隔、设置队列和新回读确认。SPP 可发送完整 35 字节初始化；20 字节 BLE 容量继续生成四条完整查询。协议流接收处理分段及合并，断开清理该会话序号与缓冲区。

## 真实共享界面检查

任务本地 `.scratch/transport-ui-smoke.sh` 使用真实 `OpenRoseApp` 和公开 `ControlSession`，通过实际指针点击完成以下流程：

- 合成 i7 广告和已知传统音频事实选中专用 SPP，初始化发送两条完整帧；点击通透后，在等待新回读时打开强制 BLE，并从 Miuix 切到 Material 3。设备、会话及当前 SPP 连接保持不变，设置与查询各发送一次，合成新回复到达后确认。
- 断开并建立下一个会话，保存的强制 BLE 偏好使其跳过 profile 探测，使用 BLE；20 字节容量下发送四条完整初始化查询。
- 在当前 BLE 会话关闭强制偏好不重连；再次连接时，合成 LE Audio 事实仍选择 BLE。切回 Miuix 保持当前会话，最后断开清除选择及原因。

三个顺序会话共 12 次发送（4 + 4 + 4），没有并行控制连接或设置重放。偏好编码 / 解码保留强制 BLE 值。截图、语义树、逐会话 TX 和结果在 `.scratch/transport-ui-smoke/`；已检查等待确认时的 Material 3 布局。全部广播、平台事实与回复明确为合成输入，产品没有加入演示设备。

## 公共边界与构建检查

新增 13 项 `ControlSessionTransportSelectionTest`，从公开会话验证：广告边界与无效地址、型号 / 偏好 / 平台 / profile 选择理由、专用 UUID、两种传输初始化与同一游戏设置确认、分段 RX、10 字节容量下完整查询分组、100ms 写间隔、偏好快照、调用者取消与宿主取消、事实查询期限、初始化无有效包、SPP 失败不另开 BLE、BLE 回读超时及 SPP 远端断开。补充偏好编码测试，保留原有读取和设置回归。

JVM 与 Android host JVM 各执行 147 项测试，零失败 / 错误；Android host JVM 不是设备测试。`--offline check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable` 共 122 项任务通过。Android 原生音频事实核查修正后也单独通过编译 / lint：无关的传统音频设备不会被错误地视为目标 i7 的查询失败；身份无效或无法关联的 LE 设备仍保守处理。SPP 的读取器启动 / 关闭竞态位于异常捕获内，取消能关闭 socket 并结束阻塞等待。

最终全套构建日志位于 `.scratch/i7-transports-full-check-final.log`，界面验证结果位于 `.scratch/transport-ui-smoke/result.txt`。这些本地证据不将合成 transport 升格为 Android 蓝牙栈或硬件验收。

## 独立双轴审查

固定基线 `de74008`，两个未参与本阶段实现的 Luna Max 代理分别检查规范和规格。

- **Standards**：没有硬性规范问题。一个低优先级建议指出仅被测试使用的公开 `setForceBle` 与既有 `updateSettings` 重复；已删除该入口，测试改用生产界面相同的设置更新接口。原审查者复核后确认解决，没有新增问题。
- **Spec**：未发现可执行缺陷或范围外行为；选择证据、偏好快照、专用 UUID、两传输及单一会话清理符合 #15。明确保留 i7 实机待验收状态。

## Android 安装与偏好

API 26 x86_64 任务专用模拟器安装和启动成功，继续如实显示 BLE 硬件不可用，扫描按钮禁用，崩溃日志为空。实际点击强制 BLE、等待界面更新后重启进程，开关仍保持开启，持久存储中的 `force_ble` 为 `true`；检查后已恢复原关闭值。这验证应用界面与偏好，不代表音频 profile 服务或蓝牙控制连接通过硬件验收。截图、界面树与偏好证据保存在 `.scratch/emulator/i7-transports*`。

## 验证边界

共享公开行为检查和实际界面点击不能验证 Android profile 服务、系统配对、SDP 服务发现、RFCOMM 阻塞取消在具体 ROM 上的行为或耳机真实回读。两种 i7 真实链路、LE Audio / 传统音频切换及设置效果仍需专门实测。Linux / Windows 的原生传输及 Android 连接恢复另属后续阶段。

最终 APK SHA-256：`a67010ddacfd46028efd098b5b03da271b9d96a2e823e9affbb3a808c0cdce84`。审查修正后重跑的 JVM / Android host JVM 各 147 项和 122 项构建任务均通过。
