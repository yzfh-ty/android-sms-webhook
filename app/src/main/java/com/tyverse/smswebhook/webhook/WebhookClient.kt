package com.tyverse.smswebhook.webhook

import com.tyverse.smswebhook.BuildConfig
import com.tyverse.smswebhook.delivery.DeliveryPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

data class EndpointSnapshot(val url: String, val token: String, val secret: String) {
    fun encode(): String = JSONObject().put("url", url).put("token", token)
        .put("secret", secret).toString()
    companion object {
        fun decode(json: String): EndpointSnapshot = JSONObject(json).let {
            EndpointSnapshot(it.getString("url"), it.getString("token"), it.getString("secret"))
        }
    }
}

data class HttpOutcome(val success: Boolean, val retryable: Boolean = false,
    val code: Int? = null, val error: String = "", val retryAfterAt: Long? = null)

class WebhookClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun send(endpoint: EndpointSnapshot, payload: String, id: String): HttpOutcome {
        WebhookProtocol.validationError(endpoint.url, BuildConfig.DEBUG)?.let {
            return HttpOutcome(false, error = it)
        }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val request = Request.Builder().url(endpoint.url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Idempotency-Key", id)
            .header("X-SMS-Timestamp", timestamp)
            .apply {
                if (endpoint.token.isNotEmpty()) header("Authorization", "Bearer ${endpoint.token}")
                if (endpoint.secret.isNotEmpty()) header("X-SMS-Signature",
                    "v1=${WebhookProtocol.signature(endpoint.secret, timestamp, payload)}")
            }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        if (e is SSLException) HttpOutcome(false, error = "TLS 证书或安全连接校验失败")
                        else HttpOutcome(false, true, error = "连接失败或超时，等待重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val code = it.code
                        val result = HttpOutcome(it.isSuccessful, DeliveryPolicy.retryable(code), code,
                            when {
                                it.isSuccessful -> ""
                                code == 401 || code == 403 -> "认证失败，请检查此任务绑定的 Token"
                                code in 300..399 -> "接收端返回重定向，请配置最终地址"
                                else -> "接收端返回 HTTP $code"
                            }, DeliveryPolicy.retryAfter(it.header("Retry-After"), System.currentTimeMillis()))
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            })
        }
    }
}
