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

data class EndpointSnapshot(val url: String) {
    fun encode(): String = JSONObject().put("provider", "wecom").put("url", url).toString()
    companion object {
        fun decode(json: String): EndpointSnapshot = JSONObject(json).let {
            EndpointSnapshot(it.getString("url"))
        }
    }
}

data class HttpOutcome(val success: Boolean, val retryable: Boolean = false,
    val code: Int? = null, val error: String = "", val retryAfterAt: Long? = null,
    val businessCode: Int? = null)

class WebhookClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun send(endpoint: EndpointSnapshot, payload: String): HttpOutcome {
        WebhookProtocol.validationError(endpoint.url, BuildConfig.DEBUG)?.let {
            return HttpOutcome(false, error = it)
        }
        val request = Request.Builder().url(endpoint.url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
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
                    val result = try { response.use {
                        val code = it.code
                        if (!it.isSuccessful) return@use HttpOutcome(false, DeliveryPolicy.retryable(code), code,
                            if (code in 300..399) "机器人地址返回重定向，请重新复制官方地址" else "企业微信返回 HTTP $code",
                            DeliveryPolicy.retryAfter(it.header("Retry-After"), System.currentTimeMillis()))
                        val raw = it.peekBody(16_385).bytes()
                        if (raw.size > 16_384) return@use HttpOutcome(false, code = code, error = "企业微信响应过大，无法确认发送结果")
                        val json = JSONObject(String(raw, Charsets.UTF_8))
                        val value = json.opt("errcode")
                        if (value !is Int && value !is Long) return@use HttpOutcome(false, code = code, error = "响应缺少有效 errcode，无法确认发送结果")
                        val businessCode = (value as Number).toLong()
                        if (businessCode !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                            return@use HttpOutcome(false, code = code, error = "企业微信响应错误码无效")
                        val errcode = businessCode.toInt()
                        HttpOutcome(errcode == 0, WebhookProtocol.retryableBusinessCode(errcode), code,
                            if (errcode == 0) "" else WebhookProtocol.businessError(errcode),
                            if (errcode == 45009) System.currentTimeMillis() + 60_000 else null, errcode)
                    } } catch (_: IOException) {
                        HttpOutcome(false, true, error = "响应读取失败，等待重试；群内可能出现重复消息")
                    } catch (_: Exception) {
                        HttpOutcome(false, code = response.code, error = "企业微信响应不是有效 JSON，无法确认发送结果")
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
