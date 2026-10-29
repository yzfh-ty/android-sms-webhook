package com.tyverse.smswebhook.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.tyverse.smswebhook.BuildConfig
import com.tyverse.smswebhook.delivery.DeliveryPolicy
import com.tyverse.smswebhook.delivery.DeliveryScheduler
import com.tyverse.smswebhook.security.CryptoStore
import com.tyverse.smswebhook.sms.SmsFilter
import com.tyverse.smswebhook.webhook.EndpointSnapshot
import com.tyverse.smswebhook.webhook.WebhookProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class UserInputException(message: String) : IllegalArgumentException(message)
data class EndpointDraft(val url: String = "", val token: String = "", val secret: String = "",
    val senderAllowlist: String = "", val includeKeywords: String = "", val excludeKeywords: String = "")
data class MessageDetail(val message: RelayMessage, val body: String, val target: String)

class RelayRepository(private val context: Context, val database: RelayDatabase,
    val settings: SettingsStore, private val crypto: CryptoStore,
    val scheduler: DeliveryScheduler) {
    val dao = database.messages()
    private val mutation = Mutex()

    suspend fun saveEndpoint(draft: EndpointDraft) = withContext(Dispatchers.IO) {
        val url = draft.url.trim()
        WebhookProtocol.validationError(url, BuildConfig.DEBUG)?.let { throw UserInputException(it) }
        val token = draft.token.trim()
        if (token.any { it.code !in 32..126 }) throw UserInputException("Token 只能包含可打印 ASCII 字符，不能包含换行")
        SmsFilter.validationError(draft.senderAllowlist, draft.includeKeywords, draft.excludeKeywords)
            ?.let { throw UserInputException(it) }
        settings.saveEndpoint(url, if (token.isEmpty()) "" else crypto.encrypt(token),
            if (draft.secret.isEmpty()) "" else crypto.encrypt(draft.secret),
            draft.senderAllowlist.trim(), draft.includeKeywords.trim(), draft.excludeKeywords.trim())
    }

    suspend fun draft(): EndpointDraft = withContext(Dispatchers.IO) {
        val s = settings.current()
        EndpointDraft(s.url, s.tokenCipher.takeIf { it.isNotEmpty() }?.let(crypto::decrypt) ?: "",
            s.secretCipher.takeIf { it.isNotEmpty() }?.let(crypto::decrypt) ?: "",
            s.senderAllowlist, s.includeKeywords, s.excludeKeywords)
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutation.withLock {
            if (enabled) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                    throw UserInputException("请先授予接收短信权限")
                WebhookProtocol.validationError(settings.current().url, BuildConfig.DEBUG)?.let { throw UserInputException(it) }
                draft() // Verify encrypted credentials are readable before enabling intake.
                settings.setEnabled(true)
                dao.resumeSms()
            } else {
                settings.setEnabled(false)
                dao.pauseSms()
                scheduler.cancelSms()
            }
        }
        recover()
    }

    suspend fun capture(sender: String, body: String, smsAt: Long, subscriptionId: Int?,
        fingerprint: String, test: Boolean = false): String? = withContext(Dispatchers.IO) {
        mutation.withLock {
            settings.initialize()
            val config = settings.current()
            if (!test && !config.enabled) return@withLock null
            if (!test) {
                val reason = SmsFilter.rejectionReason(sender, body, config.senderAllowlist,
                    config.includeKeywords, config.excludeKeywords)
                if (reason != null) {
                    settings.filtered(System.currentTimeMillis(), reason)
                    return@withLock null
                }
            }
            WebhookProtocol.validationError(config.url, BuildConfig.DEBUG)?.let { throw UserInputException(it) }
            val endpoint = EndpointSnapshot(config.url,
                config.tokenCipher.takeIf(String::isNotEmpty)?.let(crypto::decrypt) ?: "",
                config.secretCipher.takeIf(String::isNotEmpty)?.let(crypto::decrypt) ?: "")
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val source = if (test) "test" else "sms_broadcast"
            val payload = JSONObject().put("schema_version", 1).put("event_id", id)
                .put("delivery_id", id).put("device_id", config.deviceId).put("source", source)
                .put("sender", sender).put("body", body)
                .put("sms_timestamp", Instant.ofEpochMilli(smsAt).toString())
                .put("received_at", Instant.ofEpochMilli(now).toString())
                .put("subscription_id", subscriptionId ?: JSONObject.NULL).toString()
            val row = RelayMessage(id, fingerprint, sender, crypto.encrypt(body),
                crypto.encrypt(payload), crypto.encrypt(endpoint.encode()), now, smsAt, subscriptionId, source)
            val inserted = database.withTransaction {
                if (dao.findFingerprint(fingerprint) != null) return@withTransaction false
                if (dao.outstandingCount() >= 10_000) throw UserInputException("待处理队列已达上限，请先处理失败任务")
                dao.insert(row) != -1L
            }
            if (!inserted) return@withLock null
            if (!test) settings.received(now)
            // A crash here is repaired by the persistent recovery worker or next app start.
            scheduler.enqueue(ScheduledMessage(id, 0, source))
            id
        }
    }

    suspend fun sendTest(): String? = capture("SMS Webhook", "这是一条测试通知，短信转发链路已连接。",
        System.currentTimeMillis(), null, "test:${UUID.randomUUID()}", test = true)

    suspend fun recover() = withContext(Dispatchers.IO) {
        mutation.withLock {
            val now = System.currentTimeMillis()
            dao.recoverLeases(now, now + DeliveryPolicy.LEASE_MS, SystemClock.elapsedRealtime(), bootCount())
            if (settings.current().enabled) dao.resumeSms() else dao.pauseSms()
            dao.expireOutstanding(now - DeliveryPolicy.MAX_AGE_MS)
            dao.redactCompleted(now - 24 * 60 * 60 * 1000L)
            dao.deleteOldCompleted(now - DeliveryPolicy.MAX_AGE_MS)
            dao.schedulable().forEach(scheduler::enqueue)
            scheduler.ensureRecovery()
        }
    }

    suspend fun retry(id: String, currentEndpoint: Boolean = false) = withContext(Dispatchers.IO) {
        mutation.withLock {
            val row = dao.get(id) ?: throw UserInputException("记录不存在")
            if (row.source != "test" && !settings.current().enabled) throw UserInputException("请先开启转发，再重试短信任务")
            database.withTransaction {
                if (currentEndpoint) {
                    val draft = draft()
                    WebhookProtocol.validationError(draft.url, BuildConfig.DEBUG)?.let { throw UserInputException(it) }
                    dao.replaceEndpoint(id, crypto.encrypt(EndpointSnapshot(draft.url, draft.token, draft.secret).encode()))
                }
                if (dao.retry(id, System.currentTimeMillis()) == 0) throw UserInputException("此状态不能手动重试")
            }
            scheduler.enqueue(ScheduledMessage(id, 0, row.source), replace = true)
        }
    }

    suspend fun detail(id: String): MessageDetail = withContext(Dispatchers.IO) {
        val row = dao.get(id) ?: throw UserInputException("记录不存在")
        MessageDetail(row, if (row.bodyCipher.isEmpty()) "正文已按保留策略清理" else crypto.decrypt(row.bodyCipher),
            if (row.endpointCipher.isEmpty()) "目标信息已清理" else EndpointSnapshot.decode(crypto.decrypt(row.endpointCipher)).url)
    }

    fun payload(row: RelayMessage): String = crypto.decrypt(row.payloadCipher)
    fun endpoint(row: RelayMessage): EndpointSnapshot = EndpointSnapshot.decode(crypto.decrypt(row.endpointCipher))
    fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
}
