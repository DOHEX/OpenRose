---
status: accepted
---

# 代码不写注释

> 规则扩展到 Rust 与 TSX，改由 xtask 检查，见 [ADR 0038](0038-rust-core-and-nsh-desktop.md)；新结构接管前仍由 detekt 执行。

2026-11 清理前，Kotlin 源码中约有 1,730 条注释，占约 2,560 行。逐条归类后，约六成只是复述代码；约一成指向已不存在的行为，或重复了已有 ADR 和词汇表的内容；另有 59 条在为绕路实现开脱，例如泛捕获异常、固定次数重试、复制粘贴的标签。真正的决策记录散落在各处源码中，评审时看不到，修改时也很少有人回头核对。

## 决策

- **Kotlin 源码（`*.kt`、`*.kts`）不写注释**，KDoc、`/* */`、`//` 和行尾注释都算在内。代码看不出的信息各有归处：
  - 决策、协议事实与实机证据写进 `docs/adr/`，并注明对应的模块与符号；
  - 绕路实现登记为 GitHub issue（见 [issue 约定](../agents/issue-tracker.md)）；
  - 领域术语写进 `GLOSSARY.md`。
- **规则由 detekt 强制执行**：`tools/detekt-rules` 提供 `openrose:NoComments`，`DetektConventionPlugin` 把它接入每个模块的 `detekt` 任务，`offlineCheck` 随之覆盖。
- **遗留注释按预算逐步收紧**：`config/detekt/comment-budget.txt` 每行写 `<注释数> <相对路径>`，未列出的文件预算为 0。注释数超过预算会报错；低于预算也会报错，要求把预算改成实际值，降到 0 时删掉这一行。预算因此只降不升。detekt 本身仍不使用 baseline（见 [ADR 0028](0028-build-conventions.md)）。
- **许可证署名保留在源码中**：从 Apache-2.0 项目改编的代码，按许可证要求保留署名注释，并计入预算。目前只有 `shared/ui` 的 `LiquidGlassBottomBar.kt`，改编自 Kyant 的 Backdrop 示例。
- 清理流程写成仓库技能 [`/delete-comment`](../../.agents/skills/delete-comment/SKILL.md)，用于逐条归类、迁移、收紧预算。

## 考虑过的方案

- **只禁 `//`，保留 KDoc**：清理时近半数被删的同义反复正是 KDoc。OpenRose 不对外发布 API 文档，KDoc 没有读者。
- **重新启用 detekt baseline**：baseline 按问题签名记录，删一条注释不会迫使它收紧，也和“detekt 不用 baseline”的既有约定冲突。

## 后果

清理删去约 2,600 行。读代码时看不到行内说明，需要从符号回查 ADR，所以 ADR 要写明符号名。必须用 `@Suppress` 的例外不能再在行尾写理由，理由写进 ADR 或对应 issue。第三方改编代码按许可证保留署名，并计入预算。
