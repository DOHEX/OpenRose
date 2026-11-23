---
status: accepted
---

# 系统呈现层的电量显示证据

系统呈现层，即融合设备中心（MiLink）、超级岛与耳机状态通知，展示左右耳与充电盒的电量。只有在某种读数在这些界面上已有经证实的呈现形式时，OpenRose 才向它们发布该读数；协议字段中表示“未知”的值不能被当成真实读数。

## 决策

- **单体耳机不发布电量**：单体耳机（`BatteryLayout.SINGLE_UNIT`）在 MiLink、超级岛与状态通知中还没有经证实的呈现形式，因此不向它们发布电量（`SystemPresentationState.publishesSystemBattery`）。
- **入盒标志**：`DeviceState.inCaseFlags` 返回左右耳各自上报的入盒标志。型号不能上报入盒时整体为 null；某一侧为 null，表示该侧未知或固件上报 `0xFF`。

## 后果

- 单体型号在系统呈现层不显示电量，直到这些界面有了可证实的单体呈现形式。
- 入盒状态的“未知”与固件的 `0xFF` 不会被当作“在盒外”。
