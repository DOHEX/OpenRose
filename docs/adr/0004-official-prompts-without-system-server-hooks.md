---
status: accepted
---

# 官方连接提示直接使用小米蓝牙进程的系统服务

2026-10-28，决定移除 `android` 作用域及 `system_server` 中的强提示归属守卫。系统集成保留 `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service` 三个作用域；官方连接提示按固定 OppoPods 参考，在小米蓝牙进程直接调用 `setStatus(1, "strong_toast_action", bundle)`，不再经过应用宿主和系统进程转发。

官方提示的素材准备完成后，通过模块宿主的认证端点向唯一的控制会话原子领取当前会话的连接提示（仅小米蓝牙进程在官方模式下可以领取），再调用系统服务。保留应用内部的设备、会话、事件去重和旧回调隔离；删除守卫专用的 AIDL、签名票据、就绪状态及诊断。正常展示时长交给系统处理，本地到期任务只释放对应媒体。

用户选择沿用 OppoPods 的立即撤销方式：断开、关闭超级岛或切换模式时，仍在有效期内的官方提示通过小米蓝牙进程发送 `hide_strong_toast`。该接口没有设备或会话标识；移除守卫后不观察其他系统提示是否覆盖了它，因此不能承诺提前隐藏绝不影响后出现的提示。这是移除系统进程 Hook 后接受的行为边界，更新 ADR 0003 中相应的隔离约束。

宿主侧连接提示到期只表示不能再被领取，不触发 `hide_strong_toast`；只有断开、关闭超级岛、切换模式或会话更替才提前撤销。2026-11-09 实机在提示到期前约 60ms 观察到一次隐藏调用，与小米蓝牙进程在提示从快照消失时执行清理的代码路径吻合，违背“展示时长交给系统”的约定，据此修正。

这一选择避免为提示发送和撤销引入系统进程 Hook 依赖。小米蓝牙进程的调用权限、SystemUI 实际展示、媒体授权以及其他提示覆盖后的撤销行为，仍须在目标 ROM 实测；调用返回不代表卡片已显示或已撤销。

## 素材与线上字段（2026-11 补充）

- （2026-11 补充）**素材来源**：`SystemPromptMedia` 优先使用小米蓝牙应用自带的耳机视频，让提示看起来与原生一致；ROM 缺少该视频时，退回 OpenRose 内置的视频。不按连接状态挑选入耳或未入耳的画面：这条线路没有佩戴观测，因此使用普通的 `earphone_*` 视频。
- （2026-11 补充）**素材目录**：目标的 FileProvider（`com.xiaomi.bluetooth.fileprovider`）把 `internal_files` 映射到 `<filesDir>/my_internal_files/`（见其 `res/xml/file_paths.xml`），而不是 `filesDir` 本身。视频必须放在这个目录，否则 provider 返回 `FileNotFoundException`。
- （2026-11 补充）**图标字段**：HyperOS SystemUI（17.03.260226）的岛模型读取 `iconFormat`/`iconResName`，不读 `fileType`/`iconName`。缺少前两者时，`toIconInfo` 记录 “Unknown icon type: null”，整个岛被丢弃（“Failed to parseDynamicIslandParam”）。旧版本忽略这两个额外的键，继续读旧的那一对，因此 `SystemToastWire` 两组字段都写。
