# 连接恢复：实现与离线验证

日期：2026-10-20。对应 [#16](https://github.com/DOHEX/OpenRose/issues/16)，固定审查基线 `decf4d6`。i5、小米 14 HyperOS 4 及 i7 的真实恢复仍待实机验证；可控传输事件只能验证共享会话行为，不能证明目标蓝牙栈可以恢复。

## 恢复范围

恢复仅用于明确支持该策略的 BLE 传输，在服务发现 / 订阅或首条只读初始化查询阶段至多重新建立一次连接。物理连接建立错误结束本次尝试，由用户重新连接，不添加无证据的连接重试；权限、参数和不支持结果直接失败。首条查询已取得可用协议状态、只是部分字段缺失时仍保留已知字段并报告缺项，不无谓补连。健康连接没有额外连接或初始化发送。

重新建立前关闭旧物理连接和通知观察者；重新创建 codec、序号、协议流缓冲区、读取进度、能力和字段，按新连接实际写入容量重新完成初始化。一次用户连接使用同一个端点选择及偏好快照，不在恢复期间改走另一种传输。物理连接各自有独立标识，旧通知、阶段回调和清理不能污染新连接。界面显示恢复次数与原因，风格切换不重建会话。

READY 后的断连、设置超时或写入不确定继续明确终止本次链路，不自动重连或重放旧队列。用户手工重连产生新的会话，重新发现、订阅、读取；旧操作保留其断开 / 超时事实。SPP 本阶段只支持同一套手工重连和隔离语义，不添加无证据的自动恢复策略。

## Android 服务与订阅

空服务表或目标特征尚未出现时，同一个 GATT 对象至多再发现一次；每次均等待实际服务回调后按准确 UUID 和属性取得新引用。持续缺失时向共享会话报告可恢复原因，受一次新连接预算限制。不调用隐藏 `refresh()`，也不宣称重新发现保证刷新系统缓存。

API 31+ `onServiceChanged` 表示数据库已不同步，必须丢弃旧服务 / 特征引用。订阅或写入开始后不继续使用旧对象，完成待处理错误并结束通知流；初始化期可由共享会话有限恢复，READY 后结束会话。订阅失败后尽力停用本地通知，再关闭旧连接；不会仅重写 CCCD 并宣称链路恢复。

公开 SDK 依据：[discoverServices](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#discoverServices())、[onServiceChanged](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback#onServiceChanged(android.bluetooth.BluetoothGatt))。这些 API 支持异步服务发现与失效处理方式，空表 / 缓存 / 延迟发布在目标手机上的真实恢复结果仍须记录。

## Linux 证据的适用边界

既有 `/home/dohex/Projects/rose_reverse/examples/i5_ble_client.py` 通过 BlueZ `Device1.Connect` 与 Bleak 私有服务读取处理另一 bearer、缓存表和延迟发布，只对首条只读 GET 允许恢复。`tests/test_i5_connection.py` 验证空表、缓存、订阅失败、首条 GET、健康路径及退出清理。这些记录支持“只读恢复、有限预算、旧连接清理”的客户端约束，不能证明 Android 有相同缓存机制或可以使用同一私有接口。

## 真实共享界面场景

`.scratch/recovery-ui-smoke.sh` 通过真实 `OpenRoseApp` 点击连接与设置，只替代系统传输边界。合成的第一次 BLE 订阅失败，旧物理连接关闭后才进入第二条连接；恢复等待期间从 Miuix 切到 Material 3，保留逻辑会话与恢复次数。第二条连接按 20 字节容量发送四条完整初始化查询。

READY 后点击通透，等待设备确认时合成断连，没有后台重连，操作保留 DISCONNECTED。实际点击手工重连得到新的会话与从零开始的序号，按新容量重新发送两条初始化查询，状态来自本次读取；旧通透命令没有自动发送，旧失败记录不改为成功。再次点击通透并切回 Miuix，只有新的合成回复到达后确认新操作。

共三个顺序物理连接、两个用户会话、10 次协议发送（0 + 6 + 4）；截图、语义树、逐链路 TX 和结果位于 `.scratch/recovery-ui-smoke/`。这些输入明确为合成故障与回复，产品没有加入演示耳机。

## 公共边界与完整离线检查

新增 16 项 `ControlSessionRecoveryTest`，包含两型号同一断连 / 手工重连场景、typed 订阅及通知流失败、首条读取无回复 / 写入失败 / 自身 deadline、匹配但无意义的回复继续等待、有效状态及时到达后保持原链路、恢复预算耗尽、后续查询失败不重试、Security / Terminal 不升级为恢复、SPP 明确不自动恢复、恢复期间取消、旧通知隔离和临近 READY 的断链竞态。已有会话、协议、固件能力及偏好回归继续执行。

JVM 与 Android host JVM 各 163 项测试通过，零失败 / 错误；Android host JVM 不是手机测试。`--offline check offlineCheck :androidApp:assembleDebug :desktopApp:createDistributable` 全部通过，共 122 项任务，日志为 `.scratch/recovery-full-check-final.log`。最终源码的恢复 UI 场景再次通过 10 TX；旧双传输 UI 场景再次通过 12 TX，选链和偏好行为保持一致。

最终调试 APK SHA-256 为 `15e43285552a54021fadcf3a8d40a27413de0232ad91508c2ec3ce76f00bc794`。在任务专用 API 26 模拟器重新安装、启动成功，崩溃日志为空；界面如实显示无 BLE 硬件并禁止扫描。证据为 `.scratch/emulator/recovery-final.xml`、`.scratch/emulator/recovery-final.png` 与 `.scratch/emulator/logs/recovery-final-*.txt`。此检查只证明普通客户端启动与硬件缺失处理，不能代替 Android 真机的蓝牙恢复测试。

## 独立复审

代码规范轴的两处问题已修复并复核通过：通知结束与 READY 发布使用同一锁，避免留下已断开的 Connected；Android 错误分类与文档中的恢复范围保持一致。

规格轴的三处问题已修复并复核通过：首条查询不能因匹配但无意义的回复而提前结束等待；认证、权限、参数及不支持错误不得升级为恢复；服务发现错误先于服务变更分支处理，不能因两个回调交错而绕过认证失败。两轴最终均无未解决发现。
