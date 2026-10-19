# Android i5 / i7 读取链路实现与验证

实现：2026-10-18；最终回归检查：2026-10-19（Asia/Shanghai）。范围：[i5 读取 #3](https://github.com/DOHEX/OpenRose/issues/3) 与 [i7 BLE 读取 #4](https://github.com/DOHEX/OpenRose/issues/4)。两票均保留实际 Android 耳机验收未完成；用户确认当前硬件不可用，继续实现和离线验证。完整首版 #1 未完成。

## 已实现

- 原生 Android BLE 发现设备，通过 manufacturer data 识别具体型号配置。同名设备不自动视为受支持型号；未知设备不会收到型号命令。
- Android GATT 连接、服务与特征属性检查、通知 CCCD 订阅、MTU 协商、完整报文写入和断开。先收集通知、完成订阅，再初始化；写完成只代表平台接收。
- i5 13 项初始化读取；i7 RS Common V2 信息查询、可选曲线补读、小 MTU 下的多条完整查询与接收流重组。协议来源、字面向量、字段和客户端额外约束见 [protocols.md](../protocols.md)。
- 公开会话提供扫描结果、识别、连接阶段、字段加载 / 未提供 / 未知 / 有效值、逐字段超时、能力与错误结果。保留最近原始通知、未知数据和警告，数量有界。
- 单一会话持有连接；并发连接入口共享同一操作；断开结束读取并关闭链路，重连重新读取。旧数据、旧阶段回调和旧扫描结果不能改变新会话。仅有标志或未知值不能建立协议就绪。
- 两型号均按独立参考实现保持默认至少 100ms 写间隔。初始化匹配依据实际字段，i7 opaque 头字节不充当事务 ACK；匹配进度有界，不积累无消费者队列。
- 页面显示扫描、连接、服务发现、订阅、初始化、就绪和断开，并按会话与设备过滤状态。两套真实组件使用同一状态与操作。原始记录可展开查看。
- Android 8–11 扫描检测位置权限及定位服务，Android 12+ 检测蓝牙权限；监听蓝牙与旧系统定位开关，隐藏页面时停止前台发现，不销毁控制会话。没有连接或没有 BLE 的设备不显示伪造字段。

当前能力仅开放读取；参数写入、设备确认、恢复和后台服务由后续实施票完成。SPP 与桌面真实传输尚未接入，现代 libxposed / IPC / HyperOS 系统入口尚未实现。

## 公开测试与构建

- 新增 14 项公开会话行为测试；仅替代系统传输与时钟，真实型号识别、协议、状态和会话逻辑均参与。测试验证设备端外部可观察的完整字节和状态；不会将 fake 结果记为硬件已支持。
- 新增 11 项纯协议测试；i5 TX 来自独立常量提取、RX 来自原有 Linux 实际记录，i7 RX 全部标记为合成数据。包括序号回绕、截断 / 损坏数据、嵌入 AA / DD、分包 / 合包、缺失盒数据及接收状态重置。
- 既有 10 项公开会话 / 偏好测试继续运行。合计 35 项场景，JVM 与 Android host JVM 分别执行；Android host JVM 不是设备测试。
- `check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable`：通过（122 个任务，JVM 与 Android host JVM 各 35 项测试无失败）。
- `--offline offlineCheck`：2026-10-19 复验通过。

## Android API 26 冒烟检查

APK 构建版本为 `0.1.0-dev`，在隔离 API26 x86_64 模拟器升级安装并启动：`Status: ok`，475ms。Miuix / Material 3 都正确显示已授权、没有 BLE 硬件、未连接、模块尚未接入。扫描按钮可访问性节点明确 `enabled=false`；实际点击禁用控件后没有扫描、错误或虚构读数。崩溃日志为空。截图和命令保存在任务本地 `.scratch/android-read-smoke.md` 与 `.scratch/emulator/reads/`。

该模拟器缺少 BLE，未执行真实扫描、GATT、MTU、订阅、RX 或耳机固件读取。它只证明安装、启动与不可用条件下的界面行为。已有 i5 Linux 读取记录不是本工程 Android 传输验收，i7 合成 RX 也不是实机数据。

Android 权限依据：[Android 官方蓝牙权限文档](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)。旧版 Android 定位开关约束可追溯到 [AOSP GATT 服务扫描权限判断](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/oreo-r6-release/src/com/android/bluetooth/gatt/GattService.java)。系统实际授权、广播、服务表、按型号恢复与正常断开仍需目标手机验证。

## 审查与未完成验收

### Standards

发现 2 项 P2，均已修复并复查：取消一个调用者只能结束该调用者的等待，不能取消所有入口共用的连接；获取链路时将句柄赋值放在超时块内部，保证超时竞争下的已打开链路仍可清理。两项公开回归均先观察到失败，再修复通过。

### Spec

发现 1 项 P2，已修复并复查：i7 不完整外层帧的未知 V 内可能包含完整 DD 帧样式字节，不能将其当作新状态。回归向量 `dd000899dd00020901e9aafaaa` 对完整输入、原有问题切分和所有字节边界切分结果一致；只保留未知字段，不建立协议就绪。恢复限制见协议记录。

最终 Standards 0 项、Spec 0 项未解决。

待真实 i5 + 小米14 HyperOS4 验证权限、识别、GATT、13 项读取、固件和正常断开；i7 与其他平台的实际结果分别记录。型号设置的参数范围、设备确认、恢复、系统模块和全部首版组合尚未验收，不能据此关闭完整首版 spec。
