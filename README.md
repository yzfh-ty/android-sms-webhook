# Android SMS Webhook

使用 Kotlin 原生开发的 Android 短信转发工具：接收短信后，通过 HTTP Webhook 发送通知，支持持久化队列、失败补发和可选 Root 增强。

## 项目状态

已实现 0.1.0 开发版：短信接收、加密落盘、Webhook 配置、Bearer/HMAC、去重、失败重试、断网恢复、记录查看、过滤规则，以及 Root 授权和省电白名单诊断。包含概览、记录、设置和诊断四个页面。

已在 Android 16 模拟器完成主要链路测试。真实手机的运营商短信、双卡、Root 管理器和厂商长期熄屏表现仍需验证。尚未实现多接收端、第三方通知模板、历史短信补偿或独立 Root 看门狗。

目标设备为 Android 16。当前配置为 `minSdk = 26`、`compileSdk = 37`、`targetSdk = 36`，开发以 API 36 模拟器为主；编译 SDK 不限制应用只能在 Android 17 上安装。

## 技术方案

- Kotlin + Jetpack Compose：业务逻辑与配置界面。
- Room：短信、投递队列与执行记录。
- WorkManager：后台投递、重试与恢复检查。
- OkHttp：Webhook 请求。
- DataStore + Android Keystore：配置与密钥管理。
- 可选 Root 辅助：省电白名单、开机检查和看门狗。

开发前期使用 Android Studio 与 Android 模拟器；真实短信、Root 管理器和厂商保活策略在后期通过真机验证。

完整设计、权限、Webhook 协议、保活方案、开发步骤及验收方法见 [原生开发文档](docs/原生开发文档.md)。

实际使用和当前实现范围见 [开发版使用说明](docs/开发版使用说明.md)，源码及测试结果见 [实现与验证记录](docs/实现与验证记录.md)。原生开发文档中的完整数据模型和后续增强属于设计目标，以这两份实现说明为准。

## 快速开始

1. 用 Android Studio 打开项目根目录，等待 Gradle 同步完成，构建 JVM 使用模板要求的 JDK 25。
2. 运行到 API 36 模拟器或 Android 16 手机，在“设置”填写 Webhook。
3. 点击“保存并发送测试”，到“记录”确认状态为“已接受”。
4. 授予短信权限，开启概览页的转发开关；需要时配置过滤规则。
5. 在“诊断”检查电池优化及 Root 状态，实际手机还需设置厂商自启动。

本地联调无需外部接收端，电脑上运行：

```powershell
python tools/dev_webhook.py
```

开发版模拟器填写 `http://10.0.2.2:8765/hooks/sms`。接收端仅监听电脑本机，数据保存在内存，不用于生产部署。Release 只允许 HTTPS。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
# 启动模拟器后：
.\gradlew.bat connectedDebugAndroidTest
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Release 构建生成未签名文件，不能直接当正式发行包安装。

## 参考项目

参考了本地 `tmp/SmsForwarder` 的短信、规则、发送和省电配置设计，核心实现独立编写，比较和取舍见实现记录。该目录是参考材料，已排除在本项目构建和版本跟踪之外。

GitHub 提交信息使用英文。
