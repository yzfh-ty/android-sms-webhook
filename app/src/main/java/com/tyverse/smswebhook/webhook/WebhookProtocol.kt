package com.tyverse.smswebhook.webhook

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

object WebhookProtocol {
    private val debugHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")
    const val SEND_PATH = "/cgi-bin/webhook/send"
    const val MAX_CONTENT_BYTES = 2048
    fun validationError(url: String, debug: Boolean): String? {
        val parsed = url.toHttpUrlOrNull() ?: return "请粘贴企业微信群机器人的完整 Webhook 地址"
        val localMock = debug && parsed.scheme == "http" && parsed.host in debugHosts
        if (!localMock && (parsed.scheme != "https" || parsed.host != "qyapi.weixin.qq.com" || parsed.port != 443))
            return "仅支持 https://qyapi.weixin.qq.com 的企业微信群机器人"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null)
            return "机器人地址不能包含账号密码或 # 片段"
        if (parsed.encodedPath != SEND_PATH) return "请复制群机器人提供的 /cgi-bin/webhook/send 地址"
        if (parsed.querySize != 1 || parsed.queryParameterNames != setOf("key") ||
            parsed.queryParameter("key").isNullOrBlank()) return "机器人地址必须包含唯一且非空的 key 参数"
        return null
    }

    fun maskedUrl(url: String): String = if (url.isBlank()) "尚未配置" else
        url.toHttpUrlOrNull()?.newBuilder()?.query("key=***")?.fragment(null)?.username("")?.password("")?.build()?.toString()
            ?: "机器人地址已隐藏"

    fun robotId(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest((url.toHttpUrlOrNull()?.queryParameter("key") ?: url).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun retryableBusinessCode(code: Int): Boolean = code == -1 || code == 45009
    fun businessError(code: Int): String = when (code) {
        45009 -> "企业微信机器人发送过频，稍后自动重试（errcode 45009）"
        -1 -> "企业微信服务繁忙，稍后自动重试（errcode -1）"
        93000 -> "机器人地址无效或已被移出群，请重新复制地址（errcode 93000）"
        else -> "企业微信拒绝此消息，请检查机器人配置（errcode $code）"
    }
}
