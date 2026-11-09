<!--
PR 标题须遵循 Conventional Commits（英文），例如 `feat(desktop): add tray panel`、`fix(android): ...`。
release-please 依据提交信息生成发布 PR 与 CHANGELOG.md。
PR title must follow Conventional Commits (in English).
-->

## 概述 / Summary

<!-- 这个 PR 改变了什么行为，为什么？ -->

## 关联 Issue / Linked issue

<!-- 例如 Closes #123；没有则写“无” -->

## 验证 / Validation

- 离线检查（`./gradlew offlineCheck`）：<!-- 通过 / 说明 -->
- 真机验证：<!-- 已验证 / 未验证。已验证请写明耳机型号、设备或桌面环境、平台与版本；未验证请写明原因 -->

## 截图 / Screenshots

<!-- 涉及界面变化时附上前后对比（Miuix 与 Material 3 两种风格），否则删除本节 -->

## 检查清单 / Checklist

- [ ] PR 标题符合 Conventional Commits
- [ ] `./gradlew offlineCheck` 通过
- [ ] 已运行 `./gradlew spotlessApply`
- [ ] 没有新增 detekt / lint 基线条目；如有，已在 PR 描述中说明理由
- [ ] 涉及领域术语或架构决策时，已更新 `GLOSSARY.md` 或 `docs/adr/`
- [ ] 修改了面向用户的行为或说明时，`README.md` 与 `README.en.md` 已同步
- [ ] 已在上方“验证”中如实写明真机验证的有无（设备与平台）
- [ ] 界面变化已附截图
