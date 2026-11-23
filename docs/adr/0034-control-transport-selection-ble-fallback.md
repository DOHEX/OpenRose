---
status: accepted
---

# 控制传输自动选择与回退到 BLE

部分型号的控制连接既可走 BLE，也可走蓝牙串口（SPP）。用户不为当前连接指定控制传输（见词汇表“控制传输”），每次建立控制连接时由 `ConnectionAttempt.selectTransport` 自动选择。SPP 只有在确知可用时才更好；判断错了会让连接失败，而 BLE 对所有支持的型号都可用。

## 决策

- **按顺序判定，任何一步不满足都走 BLE**：型号配置没有 SPP 控制 UUID（`MODEL_BLE_ONLY`）；用户开启了“强制使用 BLE”（`FORCED_BLE`）；没有经典蓝牙地址（`CLASSIC_ADDRESS_UNAVAILABLE`）；平台报告 SPP 不可用（`SPP_UNAVAILABLE`）；音频协议未知（`AUDIO_PROFILE_UNKNOWN`）或为 LE Audio（`LE_AUDIO`）。只有确认当前是经典蓝牙音频连接（`CLASSIC_AUDIO`）时才走 SPP。
- **平台事实不可得时回退 BLE**：`connectionFacts` 查询失败或超时，不能证明 SPP 可用。`transportOperation` 此时返回默认的 `TransportFacts()`（`sppAvailable = false`、音频协议未知），选择结果为 BLE。

## 后果

平台查询偶发失败时，连接仍可通过 BLE 建立，只是本次不使用 SPP；选择理由（`TransportSelectionReason`）记录在控制状态中，诊断时可区分是型号、偏好还是平台事实导致回退。
