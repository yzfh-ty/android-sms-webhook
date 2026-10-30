# GitHub Actions 构建与发布

工作流：[Build and release Android](../.github/workflows/android-release.yml)。当前应用版本为 0.3.1，versionCode 为 5。

## 1. 手动运行

先将工作流提交并推送到仓库默认分支，再打开 GitHub → Actions → Build and release Android → Run workflow。

- 选择要构建的分支或标签。
- `publish_release` 默认勾选：构建、校验签名并发布 GitHub Release，需要先配置第 3 节的签名 Secrets。
- 取消 `publish_release`：只构建并上传 Actions Artifacts，不创建标签或 Release；允许不配置签名 Secrets。
- `tag` 留空时使用应用版本，例如 `v0.3.1`；填写时必须与当前源码中的 `versionName` 一致。

如果手动发布时该标签尚不存在，会将标签指向本次实际构建的提交。标签已存在但指向其他提交时，版本检查会失败，应选择对应标签运行或增加版本号。

## 2. 版本标签触发

先提交本次代码和工作流，再推送版本标签：

```powershell
git tag v0.3.1
git push origin v0.3.1
```

推送 `v*` 标签触发构建和签名 Release 发布。普通分支推送不自动发布。

标签必须等于 `v` 加上源码中的版本号。下一版先修改 `app/build.gradle.kts` 的 `versionName` 并递增 `versionCode`，再创建新标签，例如 `0.3.2` / `v0.3.2`。

支持 `0.3.2-rc.1` 等版本号，对应标签为 `v0.3.2-rc.1`，自动标记为 prerelease。工作流不覆盖已发布的同名 Release，重复发布应使用新版本。

## 3. 签名 Secrets

在仓库 Settings → Secrets and variables → Actions → Repository secrets 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式签名 keystore 文件的 Base64，不能填文件路径 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名条目的 alias |
| `ANDROID_KEY_PASSWORD` | 对应私钥密码 |

使用自己的已有签名文件；没有时，可在自己的密钥目录执行以下命令并按提示设置密码：

```powershell
keytool -genkeypair -keystore android-sms-webhook-release.jks -alias release -keyalg RSA -keysize 3072 -validity 10000
```

Windows 下将文件转为 Base64 并复制到剪贴板，随后粘贴到 `ANDROID_KEYSTORE_BASE64`：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('D:\keys\android-sms-webhook-release.jks')) | Set-Clipboard
```

备份签名文件与密码，后续版本使用相同签名才能覆盖更新。不要将密钥、密码或 Base64 提交到 Git。临时测试签名不能代替正式签名。

本地 Debug 与正式 Release 签名可能不同，首次切换前应核对签名；不要为了安装直接卸载而丢失卡片和短信资料。

发布模式缺少 Secrets 或只配置部分字段时会明确失败，不将未签名 APK 当成正式包发布。不配置 Secrets 时，可取消手动运行中的 `publish_release`，先验证构建。

## 4. 构建和产物

使用 Ubuntu 24.04、Temurin JDK 25、项目 Gradle Wrapper，以及 Android SDK `platforms;android-37.0` / Build Tools 36.0.0。

工作流校验版本与标签，运行发布脚本的 Python 测试、Android 单元测试及 Lint，然后构建 Debug / Release APK。有签名配置时校验 Release 签名，核对 APK 应用 ID 与版本，生成 `SHA256SUMS.txt`，上传构建结果。发布 job 再核对校验和并创建 Release。

| 模式 | APK 产物 |
| --- | --- |
| 发布 Release | `android-sms-webhook-v0.3.1-release.apk`，正式签名，同时附带校验和 |
| 仅构建，无签名 Secrets | `…-debug.apk` 和 `…-release-unsigned.apk`；只有 Debug 可直接安装 |
| 仅构建，完整签名 Secrets | `…-debug.apk` 和 `…-release.apk` |

Artifacts 名称为 `android-apks`，测试和 Lint 报告为 `validation-reports`，保留 14 天。正式 APK 另附在对应 Release 中。CI 暂不运行模拟器测试，本地设备验证见[实现与验证记录](实现与验证记录.md)。

签名文件解码到 Runner 临时目录并在结束时清理，签名构建禁用 Gradle configuration cache。发布使用内置 `GITHUB_TOKEN`，无需额外 PAT；仅发布 job 拥有 `contents: write`，构建 job 为只读。Actions 依赖固定到已核对的提交 SHA。

## 5. 本地签名构建

未设置签名环境变量时，`assembleRelease` 仍生成未签名包。有完整的以下环境变量时，生成 `app/build/outputs/apk/release/app-release.apk`：

- `ANDROID_KEYSTORE_PATH`：本机 keystore 的绝对路径。
- `ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`：含义同上。

```powershell
.\gradlew.bat --no-configuration-cache assembleRelease
```

本机路径变量与 GitHub 的 Base64 Secret 是不同的输入。工作流负责解码 Base64 并传入路径，无需修改或提交 `local.properties`。

参考：[GitHub 工作流语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、[GitHub CLI Release 创建说明](https://cli.github.com/manual/gh_release_create)。
