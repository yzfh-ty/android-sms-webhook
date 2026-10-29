package com.tyverse.smswebhook.webhook

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object WebhookProtocol {
    private val debugHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")
    fun validationError(url: String, debug: Boolean): String? {
        val parsed = url.toHttpUrlOrNull() ?: return "请输入完整的 HTTPS Webhook 地址"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "请使用 Token 字段，不要在 URL 中包含账号密码"
        if (parsed.fragment != null) return "Webhook 地址不能包含 # 片段"
        if (parsed.scheme != "https" && !(debug && parsed.host in debugHosts)) {
            return "请使用 HTTPS；调试版仅允许通过 HTTP 访问模拟器宿主机或本机"
        }
        return null
    }
    fun signature(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
