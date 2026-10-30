package com.tyverse.smswebhook.esim

import androidx.room.withTransaction
import com.tyverse.smswebhook.BuildConfig
import com.tyverse.smswebhook.RelayNotifications
import com.tyverse.smswebhook.data.*
import com.tyverse.smswebhook.delivery.DeliveryScheduler
import com.tyverse.smswebhook.security.CryptoStore
import com.tyverse.smswebhook.webhook.EndpointSnapshot
import com.tyverse.smswebhook.webhook.WebhookProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

class EsimRepository(
    private val db: RelayDatabase,
    private val crypto: CryptoStore,
    private val settings: SettingsStore,
    private val scheduler: EsimScheduler,
    private val delivery: DeliveryScheduler,
    private val notifications: RelayNotifications,
) {
    val dao = db.cards()
    private val mutation = Mutex()
    val cards = dao.observeCards().map { rows -> rows.map { EsimCardView(it, decodeInfo(it.infoCipher)) } }
        .flowOn(Dispatchers.IO)
    val results = dao.observeResults().map { rows -> rows.associateBy { it.event.cardId } }

    suspend fun save(draft: EsimDraft) = withContext(Dispatchers.IO) {
        validate(draft)
        mutation.withLock {
            val now = System.currentTimeMillis()
            var invalidate = false
            val saved = db.withTransaction {
                val old = draft.id?.let { dao.get(it) ?: throw UserInputException("卡片已删除，请返回列表") }
                if (old != null && old.revision != draft.revision) throw UserInputException("卡片已变化，请重新打开后编辑")
                val id = old?.id ?: UUID.randomUUID().toString()
                val ruleChanged = old == null || old.expiresAt != draft.expiresAt || old.zoneId != draft.zoneId ||
                    old.reminderEnabled != draft.reminderEnabled || old.leadMinutes != draft.leadMinutes ||
                    old.repeatMinutes != draft.repeatMinutes || old.localEnabled != draft.localEnabled ||
                    old.wecomEnabled != draft.wecomEnabled
                val row = EsimCard(id, draft.name.trim(), encodeInfo(draft), draft.expiresAt, draft.zoneId,
                    draft.periodCount, draft.periodUnit, draft.reminderEnabled, draft.leadMinutes,
                    draft.repeatMinutes, draft.localEnabled, draft.wecomEnabled,
                    if (ruleChanged) draft.expiresAt - draft.leadMinutes * EsimPolicy.MINUTE else old?.nextReminderAt,
                    (old?.revision ?: 0) + 1, (old?.ruleVersion ?: 0) + if (ruleChanged) 1 else 0,
                    old?.cycleStartedAt, old?.lastRenewedAt, old?.createdAt ?: now, now)
                if (old == null) dao.insert(row) else dao.update(row)
                if (old != null && ruleChanged) { invalidate(id); invalidate = true }
                row
            }
            if (invalidate) { scheduler.cancel(saved.id); notifications.cancelCard(saved.id) }
        }
        recoverAfterChange()
    }

    suspend fun renew(id: String, expectedRevision: Long): EsimCard = withContext(Dispatchers.IO) {
        val renewed = mutation.withLock {
            val row = db.withTransaction {
                val old = dao.get(id) ?: throw UserInputException("卡片已删除")
                if (old.revision != expectedRevision) throw UserInputException("卡片已变化，请重新查看后确认续期")
                val now = System.currentTimeMillis()
                val expires = EsimPolicy.renewAt(now, old.periodCount, old.periodUnit, old.zoneId)
                val updated = old.copy(expiresAt = expires, cycleStartedAt = now, lastRenewedAt = now,
                    nextReminderAt = expires - old.leadMinutes * EsimPolicy.MINUTE,
                    revision = old.revision + 1, ruleVersion = old.ruleVersion + 1, updatedAt = now)
                dao.update(updated)
                invalidate(id)
                updated
            }
            scheduler.cancel(id)
            notifications.cancelCard(id)
            row
        }
        recoverAfterChange()
        renewed
    }

    suspend fun delete(id: String, expectedRevision: Long) = withContext(Dispatchers.IO) {
        mutation.withLock {
            db.withTransaction {
                val card = dao.get(id) ?: return@withTransaction
                if (card.revision != expectedRevision) throw UserInputException("卡片已变化，请重新查看后删除")
                invalidate(id)
                dao.deleteEvents(id)
                dao.delete(id)
            }
            scheduler.cancel(id)
            notifications.cancelCard(id)
        }
    }

    private suspend fun invalidate(id: String) {
        val card = dao.get(id)
        card?.let { dao.latest(id, it.ruleVersion - 1) }?.let { delivery.cancel(it.id) }
        db.messages().cancelCard(id)
        dao.cancelEvents(id)
    }

    // A scheduling failure after commit must not leave the form open and create a second card on retry.
    private suspend fun recoverAfterChange() {
        try { recover() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            runCatching { settings.error("卡片资料已更新，提醒调度尚未完成，请在运行诊断中重新检查") }
        }
    }

    suspend fun isActive(message: RelayMessage): Boolean {
        if (message.source != EsimPolicy.SOURCE) return true
        val card = message.cardId?.let { dao.get(it) } ?: return false
        return card.reminderEnabled && card.wecomEnabled && card.ruleVersion == message.cardRuleVersion
    }

    /** Database transactions serialize event creation and notification posting against edits/renewals. */
    suspend fun recover() = withContext(Dispatchers.IO) {
        mutation.withLock {
            scheduler.ensureRecovery()
            for (initial in dao.all()) {
                db.withTransaction {
                    var card = dao.get(initial.id) ?: return@withTransaction
                    if (!card.reminderEnabled) return@withTransaction
                    var event = dao.latest(card.id, card.ruleVersion)
                    if (event != null) deliverLocal(card, event)
                    event = dao.latest(card.id, card.ruleVersion)
                    val remote = event?.takeIf { it.wecomEnabled }?.let { db.messages().get(it.id) }
                    val outstanding = event != null && (event.localState in listOf("PENDING", "BLOCKED") ||
                        (remote != null && remote.state in listOf(DeliveryState.PENDING, DeliveryState.RETRY_WAIT, DeliveryState.IN_FLIGHT)))
                    val at = card.nextReminderAt
                    val now = System.currentTimeMillis()
                    if (!outstanding && at != null && at <= now) {
                        val id = UUID.randomUUID().toString()
                        val created = EsimReminderEvent(id, card.id, card.ruleVersion, at, now,
                            if (card.localEnabled) "PENDING" else "DISABLED", card.wecomEnabled)
                        dao.insertEvent(created)
                        if (card.wecomEnabled) createRemote(card, created)
                        card = card.copy(nextReminderAt = EsimPolicy.nextAfterEvent(now, card.repeatMinutes))
                        dao.update(card)
                        deliverLocal(card, created)
                    }
                }
                val current = dao.get(initial.id) ?: continue
                val event = dao.latest(current.id, current.ruleVersion)
                if (event?.wecomEnabled == true) {
                    db.messages().get(event.id)?.takeIf { it.state in listOf(DeliveryState.PENDING, DeliveryState.RETRY_WAIT) }
                        ?.let { delivery.enqueue(ScheduledMessage(it.id, it.nextAttemptAt, it.source)) }
                }
                // A past opportunity blocked by a pending event is repaired by periodic recovery.
                if (current.nextReminderAt?.let { it > System.currentTimeMillis() } == true) scheduler.schedule(current)
            }
        }
    }

    private suspend fun deliverLocal(card: EsimCard, event: EsimReminderEvent) {
        if (event.localState !in listOf("PENDING", "BLOCKED")) return
        val posted = notifications.cardReminder(card.id, card.name,
            "${EsimPolicy.status(card.expiresAt, card.leadMinutes, System.currentTimeMillis())} · ${EsimPolicy.format(card.expiresAt, card.zoneId)} 到期")
        dao.setLocalState(event.id, if (posted) "POSTED" else "BLOCKED")
    }

    private suspend fun createRemote(card: EsimCard, event: EsimReminderEvent) {
        val url = settings.current().url
        val error = WebhookProtocol.validationError(url, BuildConfig.DEBUG)
        val body = "eSIM 到期提醒\n卡片：${card.name}\n状态：${EsimPolicy.status(card.expiresAt, card.leadMinutes, event.createdAt)}\n" +
            "到期时间：${EsimPolicy.format(card.expiresAt, card.zoneId)}（${card.zoneId}）\n请续期后在应用内点击“已续期”并确认。"
        val payload = JSONObject().put("protocol", "wecom-text-v1").put("messages", JSONArray().put(
            JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", body)))).toString()
        if (db.messages().outstandingCount() >= 10_000) throw UserInputException("待处理队列已达上限")
        db.messages().insert(RelayMessage(event.id, "esim:${card.id}:${card.ruleVersion}:${event.scheduledAt}",
            "eSIM · ${card.name}", crypto.encrypt(body), crypto.encrypt(payload),
            crypto.encrypt(EndpointSnapshot(url).encode()), event.createdAt, event.createdAt, null, EsimPolicy.SOURCE,
            state = if (error == null) DeliveryState.PENDING else DeliveryState.NEEDS_ATTENTION,
            lastError = if (error == null) "" else "请配置企业微信机器人后，改用当前配置重试",
            cardId = card.id, cardRuleVersion = card.ruleVersion))
    }

    private suspend fun validate(draft: EsimDraft) {
        fun check(condition: Boolean, text: String) { if (!condition) throw UserInputException(text) }
        check(draft.name.trim().length in 1..50, "卡片名称需为 1～50 字")
        check(draft.provider.length <= 100 && draft.identifier.length <= 100, "运营商和识别信息最多各 100 字")
        check(draft.notes.length <= 2000, "备注最多 2,000 字")
        check(draft.periodUnit in listOf("DAYS", "MONTHS"), "请选择周期单位")
        check(draft.periodCount in 1..if (draft.periodUnit == "MONTHS") 120 else 3650, "续期周期超出范围")
        check(draft.leadMinutes in 0..5_256_000 && draft.repeatMinutes in 0..5_256_000, "提醒时间超出范围")
        check(draft.repeatMinutes == 0L || draft.repeatMinutes >= 60, "重复提醒最短为 1 小时")
        check(runCatching { ZoneId.of(draft.zoneId) }.isSuccess, "时区无效")
        check(draft.expiresAt in 0..253_402_214_400_000L, "到期时间超出范围")
        if (draft.reminderEnabled) {
            check(draft.localEnabled || draft.wecomEnabled, "请至少选择一种提醒渠道")
            if (draft.wecomEnabled) check(WebhookProtocol.validationError(settings.current().url, BuildConfig.DEBUG) == null,
                "请先在设置中保存有效的企业微信机器人地址")
        }
    }
    private fun encodeInfo(draft: EsimDraft): String = crypto.encrypt(JSONObject().put("provider", draft.provider.trim())
        .put("identifier", draft.identifier.trim()).put("notes", draft.notes).toString())
    private fun decodeInfo(cipher: String): EsimInfo = JSONObject(crypto.decrypt(cipher)).let {
        EsimInfo(it.getString("provider"), it.getString("identifier"), it.getString("notes"))
    }
}
