# GitHub Actions 构建与发布

工作流：[Build and release Android](../.github/workflows/android-release.yml)。当前应用版本为 0.3.3，versionCode 为 7。每个版本仅发布 ARM64 和 x86_64 两份可安装的签名 Release APK。

## 1. 手动运行

先将工作流提交并推送到仓库默认分支，再打开 GitHub → Actions → Build and release Android → Run workflow。

- 选择要构建的分支或标签。
- `publish_release` 默认勾选：自动签名、验证并发布 GitHub Release；本仓库的签名配置已完成，无需手动填写参数。
- 取消 `publish_release`：只构建并上传 Actions Artifacts，不创建标签或 Release。
- `tag` 留空时使用应用版本，例如 `v0.3.3`；填写时必须与当前源码中的 `versionName` 一致。

如果手动发布时该标签尚不存在，会将标签指向本次实际构建的提交。标签已存在但指向其他提交时，版本检查会失败，应选择对应标签运行或增加版本号。

## 2. 版本标签触发

先提交本次代码和工作流，再推送版本标签：

```powershell
git tag v0.3.3
git push origin v0.3.3
```

推送 `v*` 标签触发构建和 GitHub Release 发布。普通分支推送不自动发布。

标签必须等于 `v` 加上源码中的版本号。下一版先修改 `app/build.gradle.kts` 的 `versionName` 并递增 `versionCode`，再创建新标签，例如 `0.3.4` / `v0.3.4`。不同版本继续保留各自的 Release，并非取消版本管理。

支持 `0.3.4-rc.1` 等版本号，对应标签为 `v0.3.4-rc.1`，自动标记为 prerelease。工作流不覆盖已发布的同名 Release，重复发布应使用新版本。

## 3. 自动签名和固定证书

0.3.2 的 unsigned 包没有签名，Android 会拒绝安装。0.3.3 保持 Release 构建，由流水线自动签名，不使用 Debug 构建或临时随机证书。

经用户明确授权，专用发布密钥已生成并加密保存为仓库 Secret `ANDROID_RELEASE_SIGNING`。这是一个包含 keystore Base64、store_password、key_alias、key_password 的 JSON Secret；本仓库已完成配置，日常构建无需用户输入或粘贴这些信息，也不依赖原来的 4 项 Secrets。

流水线将密钥解码到 Runner 临时目录，使用后清理。Gradle 签名构建禁用 configuration cache。私钥、密码和本地备份不提交到 Git，不作为 Release 或 Actions 附件上传；仓库只保存公开证书指纹 `.github/release-signing.sha256`。

每次构建都校验两个 APK 的签名及证书指纹，缺少签名或证书不一致便停止发布，不自动换一个新密钥。这样后续版本能复用同一签名更新；私钥不依赖可能失效的 Actions cache。

本地受限备份目录为 `.local/release-signing/`，包含 `release.p12`、恢复配置和公开证书。请在安全位置备份整个目录；不要提交或公开其内容。新的副本或 fork 需要由维护者单独配置自己的签名 Secret 和证书指纹，不能使用无效的占位值或临时密钥替代。

已有旧 Debug/测试版时，其证书可能与本次专用 Release 证书不同，不能直接覆盖安装。应先安排资料迁移，避免为解决签名冲突而直接卸载丢失数据。

## 4. 构建和产物

使用 Ubuntu 24.04、Temurin JDK 25、项目 Gradle Wrapper，以及 Android SDK `platforms;android-37.0` / Build Tools 36.0.0。

工作流校验版本与标签，运行发布脚本的 Python 测试、Android 单元测试及 Lint，然后以 `-PsplitApks=true` 构建并自动签名 Release。ABI split 仅包含 `arm64-v8a` 和 `x86_64`，关闭 universal APK，不执行 `assembleDebug`。

签名验证通过后，打包脚本要求 metadata 中恰好有两个正常包名的 Release，分别对应上述 ABI，并核对每个 APK 内的原生库目录。缺少某一架构、多出通用包、混入 Debug/QA、unsigned 文件、版本不符或架构错标时会失败。收集时只使用 metadata 引用的文件，避免把旧构建残留一起上传。

| 文件 | 用途 |
| --- | --- |
| `android-sms-webhook-v0.3.3-arm64-v8a.apk` | ARM 64 位，自动签名 Release |
| `android-sms-webhook-v0.3.3-x86_64.apk` | x86 64 位，自动签名 Release |
| `SHA256SUMS.txt` | 两个 APK 的 SHA-256 校验和 |

发布和仅构建模式均生成上表全部文件。Artifacts 名称为 `android-apks`，测试和 Lint 报告为 `validation-reports`，保留 14 天；发布模式还会把文件附在对应 GitHub Release 中。CI 暂不运行模拟器测试，本地设备验证见[实现与验证记录](实现与验证记录.md)。

发布使用内置 `GITHUB_TOKEN`，无需额外 PAT；仅发布 job 拥有 `contents: write`，构建 job 为只读。Actions 依赖固定到已核对的提交 SHA。

## 5. 本地构建

```powershell
.\gradlew.bat --no-configuration-cache -PsplitApks=true assembleRelease
```

本地自动读取受限备份中的 `.local/release-signing/signing.properties`；CI 从已配置的 Secret 准备环境变量。签名材料缺失时停止 Release 构建，不回退生成 unsigned 包。

输出位于 `app/build/outputs/apk/release/`：`app-arm64-v8a-release.apk` 与 `app-x86_64-release.apk`。

`splitApks` 只影响显式指定该参数的构建。日常本地 Debug 与独立 QA 测试可继续使用原命令；这些开发产物不进入发布脚本，也不会上传到 GitHub Release。

`-PisolatedTestApp=true` 同样适用于 Release 的安装验证，测试包名为 `.qa`，用于保留正常应用的数据；发布脚本会拒绝把这种包当成正常版本发布。

参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[Android 按 ABI 构建多个 APK](https://developer.android.com/build/configure-apk-splits)、[GitHub Secrets 加密](https://docs.github.com/en/rest/guides/encrypting-secrets-for-the-rest-api)、[GitHub 工作流语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、[GitHub CLI Release 创建说明](https://cli.github.com/manual/gh_release_create)。
