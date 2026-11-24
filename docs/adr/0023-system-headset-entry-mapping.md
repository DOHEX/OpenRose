---
status: accepted
---

# 系统耳机入口的数据映射与点击合并

模块控制下，融合设备中心（MiLink）与系统耳机 Binder 这两个系统入口读取 OpenRose 的状态，并提交降噪操作。两者的数据格式由厂商固定，有些读数状态无法表达。一次系统界面点击还可能经过多个同步的厂商入口。映射规则集中在 `SystemHeadsetMapping`，点击合并由 `SystemRequestGate` 负责。

## 决策

### 数据映射

- **降噪模式码**：系统入口的模式码在 MiLink 与系统耳机 Binder 中相同（`SystemHeadsetMapping.noiseModeFromSystem`）：0 为 `normal`，1 为 `anc`，2 为 `transparency`，其余值不映射。
- **MiLink 电量列表**（`miLinkBattery`）的布局是 `[case, left, right, caseCharging, leftCharging, rightCharging]`，-1 是它自己的“不可用”。
  - 列表里没有表示“充电状态未知”的值。某侧电量是真实读数、充电状态却未知时，这个读数无法表示，整个列表不发布（null），不把 0 当作“未充电”发送。系统耳机 Binder 路径的处理相同（`binderStatus` 返回 `Unrepresentable`）。
  - 充电盒没有充电读数，所以即使有真实的盒电量也不发布，写 -1。
  - 没有可显示的电量时为 null。
- **系统耳机 Binder 状态**（`binderStatus`）是逗号分隔的字符串。位置 0 为左耳，1 为右耳，2 为充电盒，7 为通透；其他位置一律留空，特别是 8（佩戴状态），因为没有真实读数支撑。
- 状态串没有能区分 `normal` 与基础降噪的值，因此只发送通透。

### 一次点击合并为一次用户操作

- 一次系统界面点击可能到达多个同步的厂商入口，`SystemRequestGate.submit` 把它们合并为一次用户操作。它不持有队列：同一 key 的请求仍在交接中，或已被接受且未超过 `windowMillis`（默认 250 ms），后来的请求都算同一次操作。交接被拒绝时立即释放 key。
- 系统耳机入口（`NativeHeadsetCallbacks`）以 `SystemClock.elapsedRealtime` 作为它的时钟。

## 考虑过的方案

- **充电状态未知时按 0（未充电）发送**：MiLink 与 Binder 会把它显示成确定的“未充电”，因此整份电量不发布。

## 后果

- 某侧充电状态未知时，系统入口暂时看不到电量，但不会看到错误的“未充电”。
- 佩戴状态在系统耳机 Binder 中始终为空；ROSE 的普通模式与基础降噪在该状态串中无法区分。
