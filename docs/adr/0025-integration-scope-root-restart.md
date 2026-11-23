---
status: accepted
---

# 系统集成作用域的 Root 重启语义

重启系统集成作用域是一项独立的用户操作，用于让模块重新加载（见 [ADR 0011](0011-dual-host-single-control-session.md)）。系统不提供“作用域已重启、模块已加载”的回执，因此结果只能根据进程的变化判断，还要说明每种结果证明了什么、没有证明什么。

## 决策

- **执行方式**：`IntegrationScopeRestarter` 通过 Root 脚本（`restartScript`）执行重启。目标只来自 `INTEGRATION_SCOPE_PACKAGES`（`com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`），所以可以安全地内联进脚本。
- **识别进程**：脚本中的 `scan()` 为所选作用域的每个进程输出 `pid:target`。先用一次 grep 扫描全部 cmdline 找出候选，只有提到某个作用域的进程才完整读取。以 NUL 分隔的 argv 第一个值，对蓝牙作用域必须恰好是包名，对其他作用域是包名或 `包名:子进程`，与 `BridgeTarget.acceptsProcess` 一致。
- **发送信号**：先为每个作用域记录进程快照，再统一发送 SIGTERM（从不 force-stop），所有作用域共用一个截止时间。剩余秒数每减少一次就输出一次。
- **结果语义**（`ScopeRestartStatus`）：
  - `RESTARTED`：发出信号后，出现了此前不存在的新 pid。
  - `TERMINATED`：旧进程已结束，但截止前没有新进程出现（按需进程只在需要时启动）。
  - 截止时旧 pid 仍然存活，说明进程忽略了信号，结果为 `FAILED`。
  - `RESTARTED` 与 `TERMINATED` 都不能证明模块已加载。

## 后果

- 界面可以如实报告进程已重启或已结束，但不能据此宣称模块已经生效。
