---
status: accepted
---

# 版本号只来自仓库内的 version.txt

此前版本号由 CI 从发布标签拼出（Gradle 属性 `releaseVersion` / `releaseVersionCode`），各模块再各自写死非发布构建的默认值：Android 为 `0.1.0-dev`，桌面为 `0.1.0`，`versionCode` 取 CI 运行序号。结果是本地与 CI 构建的版本号不同，开发版看不出来自哪次提交，桌面开发版看上去像正式版，默认值也不会随发布前进。

## 决策

- **单一来源**：仓库根目录的 `version.txt` 保存最近一次发布的版本 `MAJOR.MINOR.PATCH`，由 release-please 的 `simple` 策略在发布 PR 中改写，与 `.release-please-manifest.json` 同步。构建只读这一个文件；标签是发布的结果，不是版本号的输入。
- **集中推导**：`build-logic` 的 `AppVersion` 从该文件推导每个平台需要的形式，其他地方不再出现版本号：
  - 显示版本：发布构建为 `X.Y.Z`；其他构建为 `X.Y.Z-dev+<短提交号>`，不在 git 检出中时为 `X.Y.Z-dev`。
  - Android `versionCode`：`MAJOR×1000000 + MINOR×1000 + PATCH`，与版本同序，本地与 CI 一致；只有发布前的占位版本 `0.0.0` 会得到 Android 不接受的 0，此时取 1。
  - 桌面安装包版本：只用 `X.Y.Z`，因为 MSI 不接受后缀，且主、次版本号不超过 255。
- **共享代码**：`:sharedUI` 编译时生成 `AppVersion.NAME`，关于页直接读取，平台不再经 `PlatformUi` 传入版本号。
- **CI 只判断是否发布**：构建发布标签时先确认标签等于 `v` 加 `version.txt` 的内容，再设置 Gradle 属性 `releaseBuild=true`，去掉 `-dev` 后缀；不一致时构建失败。

## 考虑过的方案

- **从 git 标签推导版本**（axion-release、reckon 等）：与 release-please 职责重叠；CI 的浅克隆与没有标签的检出都需要另行兜底。
- **继续由 CI 传入版本、各模块保留默认值**：即此前的做法，问题见上文。
- **`versionCode` 继续用 CI 运行序号**：本地无法复现，换 CI 后会回退；决策时尚无任何发布，切换不会让已安装的版本无法升级。

## 后果

非发布构建的显示版本随每次提交变化，`:sharedUI` 与 Android 清单会在提交变化后重新生成。发布前 `version.txt` 为 `0.0.0`，开发版显示 `0.0.0-dev+<提交号>`，桌面安装包版本为 `0.0.0`。
