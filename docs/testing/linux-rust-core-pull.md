# Rust 核心与 NSH 桌面端的让出与接管（#180）

本页记录 Rust 核心流转流程（`CirculationNode::pull`、`HeadsetEndpoint` 让出、`LinuxLocalAudio` 接入）在 Linux 上的验证状态。旧的 JVM 桌面端与 Kotlin 流转的实机结果见[手机与 Linux 实测](linux-phone-i5-circulation.md)，不适用于新代码。

## 离线验证

| 项目 | 覆盖 |
| --- | --- |
| 持有方先让出并给出回执，请求方再接入 | `crates/circulation/tests/circulation.rs`：`pull_receipt_then_acquire_moves_audio_and_control` |
| 通话中、麦克风占用时拒绝，原连接保留 | 同文件：`pull_refused_in_call_and_mic_in_use_keep_holder_connected` |
| 持有方读不到本机音频状态时拒绝并保持原连接，请求方不接入 | 同文件：`holder_that_cannot_read_its_audio_state_refuses_and_the_puller_never_acquires`；`crates/desktop-platform/tests/audio.rs`：`a_holder_that_cannot_read_its_bluez_state_reports_unreadable_instead_of_disconnected` |
| 让出后仍连接、接入后没有媒体连接时返回代码 | 同文件：`holder_still_connected_after_yielding_refuses_with_a_code`、`acquire_that_brings_up_no_media_fails_with_a_code` |
| 接入不因 `pactl` 或默认输出不可用而先拒绝；耳机 sink 之后抢占默认输出时恢复原默认输出 | `crates/desktop-platform/tests/audio.rs`：`acquire_puts_the_default_output_back_when_the_headset_sink_takes_over_later`、`acquire_connects_even_when_pactl_or_the_default_sink_is_unavailable` |
| 联系不上任何对端时直接尝试接入，失败与超时返回原因代码 | `pull_when_nobody_reachable_still_attempts_acquire`、`pull_acquire_failure_returns_error_and_never_switches_back`、`pull_timeout_returns_error_without_acquiring` |
| 原因代码到界面文案的映射 | `desktop/src/model/circulation.test.ts`、`androidApp` 的 `CirculationMappingTest` |

## 真机验证

| 项目 | 状态 |
| --- | --- |
| NSH 桌面端「接到这台电脑」从手机取得耳机（Rust 核心） | 未验证 |
| 电脑持有时手机「接回手机」，电脑经 Rust 核心让出 | 未验证 |
| 通话中、麦克风占用时拒绝让出并显示原因 | 未验证 |
| 接入后系统默认输出保持不变（PipeWire 晚于接入抢占默认输出的情形） | 未验证 |
| 联系不上任何对端时的说明与直接接入 | 未验证 |
| 整机休眠、唤醒后再次流转 | 未验证 |
