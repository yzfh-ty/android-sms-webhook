# Android SMS Webhook

Kotlin 原生 Android 短信通知工具。接收短信后，只通过**企业微信群机器人**发送文本通知。

## 当前功能

0.2.1 开发版，目标设备为 Android 16：

- 只需粘贴完整机器人 Webhook；地址中的 key 加密保存，默认隐藏。
- 短信先加密落盘，再由 WorkManager 投递，支持过滤、去重和断网恢复。
- 按企业微信 `errcode` 判断成功，不把 HTTP 200 直接当成功。
- 长短信按 2048 UTF-8 字节限制分段，保存确认进度，失败后从未确认的段继续。
- 每个机器人持久化发送节流；发送过频和服务繁忙时退避重试。
- 概览、投递记录、设置和运行诊断四个页面。
- 可选 Root 授权检测、省电白名单添加及撤销，开机解锁后恢复队列。

真实手机短信、双卡、Root 管理器和厂商长期熄屏表现仍需真机验证。当前不包含独立 Root 看门狗、历史收件箱补偿、多机器人路由或其他通知平台。

## 开始使用

1. 用 Android Studio 打开根目录并完成 Gradle 同步；构建 JVM 使用 JDK 25。
2. 在企业微信内部群添加群机器人，复制完整 Webhook。
3. 在应用“设置”粘贴地址，点击“保存并测试”。
4. 在“记录”确认“已发送”，然后授权接收短信并开启转发。
5. 按需要设置发送方/关键词过滤，在“诊断”检查电池优化和 Root。

正式地址格式：

```text
https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<机器人密钥>
```

地址包含发消息的凭证，不要写入源码、日志或公开截图。群机器人不提供客户端幂等保证，网络超时后的重试仍可能产生重复消息。

## 本地开发

技术栈：Kotlin、Compose Material 3、Room/KSP、WorkManager、OkHttp、DataStore、Android Keystore。

SDK 配置：`minSdk = 26`、`compileSdk = 37`、`targetSdk = 36`。AGP 9.5.0，Gradle Wrapper 9.6.0。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
# 使用独立 .qa 包运行设备测试，避免覆盖正常应用：
.\gradlew.bat -PisolatedTestApp=true connectedDebugAndroidTest
# 重新生成正常包名的开发版安装包：
.\gradlew.bat assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。Release 构建生成未签名文件，不是正式发行包。

本机企业微信模拟接口，只监听 127.0.0.1，不向真实群发送消息：

```powershell
python tools/dev_webhook.py --mode ok
```

开发版模拟器使用 `http://10.0.2.2:8765/cgi-bin/webhook/send?key=local-test`。模拟器地址仅限 Debug，Release 只接受企业微信官方地址。使用 `--mode busy-once`、`invalid-bot` 或 `rate-limit-once` 可测试失败处理。

## 文档

- [开发版使用说明](docs/开发版使用说明.md)
- [原生开发文档](docs/原生开发文档.md)
- [实现与验证记录](docs/实现与验证记录.md)

参考了本地 `tmp/SmsForwarder` 的短信、规则、企业微信发送和省电配置设计，核心实现独立编写。参考目录、IDE 设置、构建产物、日志、本地配置和密钥均已排除在版本跟踪之外。

GitHub 提交信息使用英文。
