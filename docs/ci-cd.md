# CI / CD

GitHub Actions 在拉取请求、`main` 分支推送、`v*` 标签推送以及手动运行时执行。标签必须符合 `vMAJOR.MINOR.PATCH`（例如 `v0.1.0`）；其他 `v*` 标签会使构建失败。普通 CI 不需要签名密钥。

工作流安装 JDK 21.0.11+10、SDK Platform 37（官方包标识为 `platforms;android-37.0`）和 Build Tools 37.0.0，使用仓库中的 Gradle Wrapper。

Linux 和 Windows 分别运行 `check offlineCheck`、构建 Android 调试 APK 和当前平台的桌面发行目录。构建成功后，Actions 页面提供 `packages-linux-x64` 和 `packages-windows-x64`：前者包含 Android APK 和 Linux tar.gz，后者包含 Windows zip；桌面压缩包均包含 Java 运行时。测试和 Lint 报告也作为运行产物上传，产物保留 14 天。这些检查不代表应用已在真实耳机或目标系统上完成运行验收。

## 发布

发布前，在 GitHub 仓库的 Actions secrets 中配置已有 Android 发布签名密钥。已有安装的后续版本应使用同一签名密钥，以便升级。需要四个 repository secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 密钥库文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

可通过已登录的 GitHub CLI 设置；下面的密钥库路径替换为本机现有文件。其余命令会交互式读取值，避免将密码写入 shell 历史：

```bash
openssl base64 -A -in /path/to/existing-keystore.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

推送版本标签开始发布构建：

```bash
git tag v0.1.0
git push origin v0.1.0
```

标签构建从标签设置 Android 和桌面版本号，并以该工作流的运行编号作为 Android `versionCode`；重新运行同一次工作流时编号不变。Linux 会使用上述密钥构建并验证签名后的 Android release APK。Linux 和 Windows 构建均成功后，标签推送会创建包含 Android APK 和两个桌面压缩包的 GitHub Release 草稿，供检查后发布。手动运行不会创建 Release 草稿。

本地可用 Gradle 属性覆盖版本，例如 `./gradlew :androidApp:assembleRelease -PreleaseVersion=0.1.0 -PreleaseVersionCode=1`。如需本地签名，设置 `OPENROSE_KEYSTORE_PATH`、`OPENROSE_KEYSTORE_PASSWORD`、`OPENROSE_KEY_ALIAS` 和 `OPENROSE_KEY_PASSWORD`；未设置密钥库路径时，release APK 不会签名。
