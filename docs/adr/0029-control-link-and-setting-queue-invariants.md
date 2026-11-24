---
status: accepted
---

# 控制连接与设置队列的失效、排序与确认不变量

[ADR 0011](0011-dual-host-single-control-session.md) 规定同一设备的控制连接只有一个所有者，[ADR 0012](0012-shared-multiplatform-architecture.md) 把单次连接交给 `ControlLink`、设置交给 `SettingQueue`、音频连接推断交给 `AudioFacts`。所有者内部仍有并发：连接尝试会被替换，旧回调、迟到的通知和读回会在新会话建立后才到达，用户设置会在初始化期间提交。下列不变量约束这些部件，任何一条被破坏，都可能让旧事件改写新会话，或把没有被耳机确认的设置报告为成功。

## 决策

- **先失效，再关闭**：
  - `ControlLink.clearActive` 的调用者持有 `lifecycle` 锁。它先递增 `linkEpoch` 并清空当前尝试，使旧回调无法更新新会话，然后才关闭连接。返回值表示本会话打开过的每个连接是否都已确认关闭；平台拒绝的关闭会被记住，下次调用时重试，不会被遗忘。
  - `ControlLink.closeAttemptLink` 在取消观察协程之前先递增 `linkEpoch`，旧协程的 `finally` 因代际不符而无法清除新链路。
  - 链路终止（`progress` 的 `ended = true`）与 READY 提交在同一把锁下发布，最后一条通知不会抢先进入已经结束的“已连接”状态。
- **断开与释放**：
  - `ControlRuntime.disconnect` 只结束控制连接，不动系统音频连接。它返回控制连接是否确认关闭；关闭失败时调用方不能把连接算作已释放。
  - 断开只取消本连接自己拥有的操作（`ControlLink` 返回“连接会话已结束”），不取消其他调用者的作用域。
  - 重新核对旧添加流程的成功结果时，失败清理（`onAttemptFailed`）不能清除较新的音频驱动会话。
- **设置的受理与确认**：
  - `ControlRuntime.set` 只在耳机确认后才算成功，写入被接受不算。带 `expectedSessionId` 时，只有该会话仍是当前会话才受理；`SettingQueue.set` 在受理所用的同一把锁内核对调用者给出的引用，会话被替换后，排队中的请求不会转投新会话。
  - 初始化循环在 READY 之前独占线路。`SettingQueue.runNextDuringInitialization` 让用户命令占用下一次完整的收发；在两次写入的间隔里排入的模式更改，先于下一条初始化查询取得这个时隙（`ControlLink` 初始化循环先等 `nextWriteAt`，再让队列执行）。
  - 每条读回查询由 `writePaced` 取得自己的基线；更早到达的主动上报不能确认它。没有请求值的刷新也独占自己的回复，下一次写入要等它结束。
  - 已知协议都不把迟到的读回与具体事务关联。设置超时或失败时，`SettingQueue` 调用 `clearActive` 结束这条链路，避免下一个设置消费旧回复或旧的原生写入。
- **初始化进度**：`ConnectionAttempt` 用比较并交换提交观测结果，进度只按已提交的观测计算，并发超时改写字段错误时也一样。
- **初始化恢复**：未分类的建立超时只有在 `ControlTransport.supportsInitializationRecovery` 为真时才重试。接口默认值为否；Android、BlueZ 与 Windows 传输明确声明支持。
- **音频事实**：
  - `AudioFactsDriver.events` 在注册完成后、任何后续快照完成之前发出 `Ready`。
  - 蓝牙关闭（AdapterOff）先推进查询代际再停用，旧查询结果不会落地（`AudioFacts` 丢弃代际不符的结果）。
  - 不可读的探测不等于蓝牙关闭；查询期间可读的广播仍优先于查询结果。

## 后果

这些规则分散在 `ControlLink`、`SettingQueue`、`ConnectionAttempt` 与 `AudioFacts` 中，修改任一部件的加锁、取消或关闭顺序时，需按本 ADR 逐条核对。设置失败会结束整条控制连接，代价是一次重连；换来的是迟到的读回无法确认错误的设置。
