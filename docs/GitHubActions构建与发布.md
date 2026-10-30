# GitHub Actions 构建与发布

工作流：[Build and release Android](../.github/workflows/android-release.yml)。当前应用版本为 0.3.2，versionCode 为 6。每个版本仅发布 ARM64 和 x86_64 两份未签名 Release APK。

## 1. 手动运行

先将工作流提交并推送到仓库默认分支，再打开 GitHub → Actions → Build and release Android → Run workflow。

- 选择要构建的分支或标签。
- `publish_release` 默认勾选：构建并发布 GitHub Release，无需配置签名 Secrets。
- 取消 `publish_release`：只构建并上传 Actions Artifacts，不创建标签或 Release。
- `tag` 留空时使用应用版本，例如 `v0.3.2`；填写时必须与当前源码中的 `versionName` 一致。

如果手动发布时该标签尚不存在，会将标签指向本次实际构建的提交。标签已存在但指向其他提交时，版本检查会失败，应选择对应标签运行或增加版本号。

## 2. 版本标签触发

先提交本次代码和工作流，再推送版本标签：

```powershell
git tag v0.3.2
git push origin v0.3.2
```

推送 `v*` 标签触发构建和 GitHub Release 发布。普通分支推送不自动发布。

标签必须等于 `v` 加上源码中的版本号。下一版先修改 `app/build.gradle.kts` 的 `versionName` 并递增 `versionCode`，再创建新标签，例如 `0.3.3` / `v0.3.3`。不同版本继续保留各自的 Release，并非取消版本管理。

支持 `0.3.3-rc.1` 等版本号，对应标签为 `v0.3.3-rc.1`，自动标记为 prerelease。工作流不覆盖已发布的同名 Release，重复发布应使用新版本。

## 3. 无需手动签名配置

工作流已移除原先的 4 项签名 Secrets、keystore 解码与正式签名检查。手动发布和版本标签发布均不需要上传密钥或填写密码，已有的这些 Secrets 也不会被读取。

两个发布包均不签名，不能直接安装。按用户要求，发布产物不包含 Debug 包，也不采用默认开发签名替代正式签名。

## 4. 构建和产物

使用 Ubuntu 24.04、Temurin JDK 25、项目 Gradle Wrapper，以及 Android SDK `platforms;android-37.0` / Build Tools 36.0.0。

工作流校验版本与标签，运行发布脚本的 Python 测试、Android 单元测试及 Lint，然后以 `-PsplitApks=true` 构建 Release。ABI split 仅包含 `arm64-v8a` 和 `x86_64`，关闭 universal APK，不执行 `assembleDebug`。

打包脚本要求 metadata 中恰好有两个正常包名的 Release，分别对应上述 ABI，并核对每个 APK 内的原生库目录。缺少某一架构、多出通用包、混入 Debug/QA、版本不符或架构错标时会失败。收集时只使用 metadata 引用的文件，避免把旧构建残留文件一起上传。

| 文件 | 用途 |
| --- | --- |
| `android-sms-webhook-v0.3.2-arm64-v8a-unsigned.apk` | ARM 64 位，未签名 Release |
| `android-sms-webhook-v0.3.2-x86_64-unsigned.apk` | x86 64 位，未签名 Release |
| `SHA256SUMS.txt` | 两个 APK 的 SHA-256 校验和 |

发布和仅构建模式均生成上表全部文件。Artifacts 名称为 `android-apks`，测试和 Lint 报告为 `validation-reports`，保留 14 天；发布模式还会把文件附在对应 GitHub Release 中。CI 暂不运行模拟器测试，本地设备验证见[实现与验证记录](实现与验证记录.md)。

发布使用内置 `GITHUB_TOKEN`，无需额外 PAT；仅发布 job 拥有 `contents: write`，构建 job 为只读。Actions 依赖固定到已核对的提交 SHA。

## 5. 本地构建

```powershell
.\gradlew.bat -PsplitApks=true assembleRelease
```

输出位于 `app/build/outputs/apk/release/`：`app-arm64-v8a-release-unsigned.apk` 与 `app-x86_64-release-unsigned.apk`。不读取原先的签名环境变量。

`splitApks` 只影响显式指定该参数的构建。日常本地 Debug 与独立 QA 测试可继续使用原命令；这些开发产物不进入发布脚本，也不会上传到 GitHub Release。

参考：[Android 按 ABI 构建多个 APK](https://developer.android.com/build/configure-apk-splits)、[GitHub 工作流语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、[GitHub CLI Release 创建说明](https://cli.github.com/manual/gh_release_create)。
