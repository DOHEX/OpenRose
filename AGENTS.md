## 代码维护约定

- 改行为前，沿实际入口找到负责这条规则的代码。同一条业务规则由一处决定。
  如果多个调用方都要补同一个例外，先检查规则的归属，把修复放在那里。

- 把因同一业务原因而变化的代码放在一起。模块通过明确的输入、结果和错误协作。
  调用方应能完成自己的工作，无需了解对方的内部状态或记住隐藏的调用顺序。

- 选择从入口到结果最容易读懂的实现。顺序清楚的逻辑可以留在一个函数里。
  提取函数或引入接口时，应能说清它让调用方少理解了什么细节。
  仅为未来可能出现的需求预留的层级和参数，等需求出现时再加。

- 允许为本次需求做局部重构。如果沿用旧结构会复制规则、增加旁路或维护两份状态，
  先把受影响的路径理顺，再实现需求。新路径接管后，删除已被替代的分支和转发层。
  重构范围以本次行为涉及的调用链为界。

- 完成前，从真实入口验证改动后的行为，并检查受影响的既有行为。
  回看 diff 时问一句：“下次这条规则再变，维护者需要同时记得改哪几处？”
  如果答案里有多份相同判断，就回到规则归属处收拢。

## OpenRose 项目约定

- **型号能力**由 `shared/headset` 根据型号、固件和已确认的设备读数推导。界面使用推导结果，沙盒以模拟读数调用同一套规则。新增型号或调整型号配置时，使用 [add-model 技能](.agents/skills/add-model/SKILL.md)。模块职责见 [ADR 0020](docs/adr/0020-client-and-platform-modules.md)。
- **验证改动**时先运行受影响模块的测试，例如耳机控制模块的 `./gradlew :shared:headset:jvmTest`。提 PR 前须通过 `./gradlew offlineCheck`。涉及型号或平台适配时，分别记录离线和真机验证结果，未实测的功能标明“未验证”。见[日常命令](CONTRIBUTING.md#日常命令)和[验证与真机测试](CONTRIBUTING.md#验证与真机测试)。
- **源码不写注释**，由 detekt 的 `openrose:NoComments` 检查。代码看不出的决策、协议事实与证据写进 `docs/adr/` 并注明符号，绕路实现登记为 issue。见 [ADR 0021](docs/adr/0021-no-code-comments.md)。
- **用户可见行为或说明**变化时，同步更新网站对应的中英文页面。涉及支持型号表、功能概要或安装步骤时，同时更新 `README.md` 和 `README.en.md`。见[贡献指南的文档同步约定](CONTRIBUTING.md#领域文档)。

## Agent skills

### Issue tracker

Issues and specs use GitHub Issues in `DOHEX/OpenRose`. Read `docs/agents/issue-tracker.md` before tracker operations.

### Triage labels

Use the five default triage labels. Read `docs/agents/triage-labels.md` when interpreting or changing triage state.

### Domain docs

Use a single-context layout. Read `docs/agents/domain.md` before exploring the codebase.
