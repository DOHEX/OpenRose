---
status: accepted
---

# Android 普通控制后台唤起的平台分层与可验证前置条件

[ADR 0011](0011-dual-host-single-control-session.md) 要求普通控制在应用进程消失后，由耳机后续的音频连接自动唤起，具体机制只写了“按 Android 版本及设备验证”。各 Android 版本提供的公开唤起机制并不相同，ROM 的自启动与电池策略又无法查询。若把“已开启后台控制”显示成“一定会被唤起”，就是在声称 OpenRose 无法验证的事。

## 决策

- **按平台的真实差异分三档，不当作同一种机制**（`WakeBand`：`ANDROID_8_11`、`ANDROID_12_13`、`ANDROID_14_PLUS`；`CompanionWake` 以 SDK 31、34 为界）：
  - Android 12 前没有设备存在 API。唯一路径是清单中接收音频连接广播的接收器，不需要关联。
  - Android 12 起，唤起需要伴生设备关联：关联加上设备存在观察会唤起 `OpenRoseCompanionService`。这个关联同时提供后台启动前台服务的豁免（`CompanionAssociation`）。
  - Android 14 在此基础上增加有类型的 `connectedDevice` 前台服务。
- **只记录可验证的事实**：`WakeFacts` 只包含平台能验证的内容，即版本档、伴生关联与存在观察。系统自启动与电池策略无法查询，有意不放进去，由这些事实推导的任何状态都不声称某个 ROM 一定会唤醒应用。`BackgroundRecovery.Armed` 表示所有可验证前置条件均已满足，ROM 的自启动与电池限制仍未验证；缺少的条件按 `WakePrerequisite`（蓝牙权限、伴生关联、存在观察）逐项列出。
- **能否启动服务**：唤起触发（开机、音频连接、设备存在）或用户入口能否立即启动服务，由 `mayStartBackgroundService` 判定，只看已保存的后台控制意图、首次解锁与蓝牙权限。后台控制关闭、尚未解锁或缺少权限时不启动。缺少伴生关联从不阻止启动尝试，系统拒绝时如实上报（`BackgroundServiceFact.StartRejected`）。
- **蓝牙权限**：权限缺失或被撤销时，`connectedDevice` 服务无法启动，状态为 `BackgroundControlBlock.BLUETOOTH_PERMISSION`。
- **每个启动请求都进入 `startForeground`**：`ControlService.onStartCommand` 对重复的启动请求也是如此。
- **自启动与电池限制交给用户检查**：这两项无法查询，`MainActivity.openAppSettings` 把用户带到应用的系统详情页。

## 考虑过的方案

- **把各版本的唤起当作同一种机制**：与平台实际差异不符。Android 12 前没有存在 API，Android 12 起才需要关联。
- **缺少伴生关联时不尝试启动**：不采用。照常尝试，系统若拒绝，就把拒绝如实上报。

## 后果

- 设置页最多显示“可验证的前置条件均已满足”，不显示“一定会唤起”。
- `OrdinaryRecoveryTest` 验证的是由控制状态与唤起事实推导出的普通后台恢复状态。其中模拟的生命周期事件只是契约证据，不说明 ROM 真的会唤醒进程。首次解锁门限是核心规则，另有测试覆盖。
