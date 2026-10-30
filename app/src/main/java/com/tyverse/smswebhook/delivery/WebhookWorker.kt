package com.tyverse.smswebhook.delivery

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tyverse.smswebhook.RelayApplication
import com.tyverse.smswebhook.AppContainer
import com.tyverse.smswebhook.data.DeliveryState
import com.tyverse.smswebhook.webhook.HttpOutcome
import com.tyverse.smswebhook.webhook.WebhookProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RelayApplication
        val c = app.container
        val id = inputData.getString("message_id") ?: return Result.failure()
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        c.settings.workerRan(now)
        val existing = c.repository.dao.get(id) ?: return Result.success()
        if (existing.source != "test" && !c.settings.current().enabled) {
            c.repository.dao.pauseSms()
            return Result.success()
        }
        if (existing.state !in listOf(DeliveryState.PENDING, DeliveryState.RETRY_WAIT)) return Result.success()
        if (existing.nextAttemptAt > now) return Result.retry()
        val deadline = SystemClock.elapsedRealtime() + 95_000
        val preflightEndpoint = runCatching { c.repository.endpoint(existing) }.getOrNull()
        val preflightError = preflightEndpoint?.let { WebhookProtocol.validationError(it.url, com.tyverse.smswebhook.BuildConfig.DEBUG) }
            ?: if (preflightEndpoint == null) "无法读取机器人地址，请检查密钥后重试" else null
        if (preflightError == null && !DeliveryPolicy.expired(existing.retryWindowStartedAt, now)) {
            if (!awaitSlot(c, preflightEndpoint!!.url, deadline)) return Result.retry()
        }
        val claimedAt = System.currentTimeMillis()
        val row = c.database.withTransaction {
            if (c.repository.dao.claim(id, token, claimedAt, claimedAt + DeliveryPolicy.LEASE_MS,
                    SystemClock.elapsedRealtime() + DeliveryPolicy.LEASE_MS, c.repository.bootCount()) != 1) null
            else c.repository.dao.get(id)
        } ?: return Result.success()
        val endpoint = runCatching { c.repository.endpoint(row) }.getOrNull()
        val endpointError = endpoint?.let { WebhookProtocol.validationError(it.url, com.tyverse.smswebhook.BuildConfig.DEBUG) }
            ?: if (endpoint == null) "无法读取机器人地址，请检查密钥后重试" else null
        val outcome = try {
            val parts = c.repository.payloads(row)
            require(row.nextPartIndex in 0..parts.size)
            when {
                row.nextPartIndex == parts.size -> HttpOutcome(true, code = 200, businessCode = 0)
                endpointError != null -> HttpOutcome(false, error = endpointError)
                DeliveryPolicy.expired(row.retryWindowStartedAt, claimedAt) -> HttpOutcome(false, error = "自动投递期限已到，请手动处理")
                row.attempts > DeliveryPolicy.MAX_ATTEMPTS -> HttpOutcome(false, error = "自动重试次数已到上限")
                row.source != "test" && !c.settings.current().enabled -> {
                    c.repository.dao.pauseSms()
                    return Result.success()
                }
                else -> {
                    val target = requireNotNull(endpoint)
                    var result = HttpOutcome(true, code = 200, businessCode = 0)
                    var part = row.nextPartIndex
                    while (part < parts.size) {
                        if (row.source != "test" && !c.settings.current().enabled) {
                            c.repository.dao.pauseSms()
                            return Result.success()
                        }
                        if (deadline - SystemClock.elapsedRealtime() < 22_000 ||
                            ((part > row.nextPartIndex || target.url != preflightEndpoint?.url) &&
                                !awaitSlot(c, target.url, deadline))) {
                            result = HttpOutcome(false, true, error = "等待后续分段发送，已确认的分段不会重复提交")
                            break
                        }
                        val lease = c.repository.dao.get(id)
                        if (lease?.leaseToken != token || lease.state != DeliveryState.IN_FLIGHT) return Result.success()
                        result = c.http.send(target, parts[part])
                        if (!result.success) {
                            if (result.businessCode == 45009) c.settings.deferRobot(target.url,
                                result.retryAfterAt ?: System.currentTimeMillis() + 60_000)
                            break
                        }
                        val ack = c.repository.dao.acknowledgePart(id, token, part, part + 1,
                            System.currentTimeMillis() + DeliveryPolicy.LEASE_MS,
                            SystemClock.elapsedRealtime() + DeliveryPolicy.LEASE_MS)
                        if (ack != 1) return Result.success()
                        part++
                    }
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HttpOutcome(false, error = "无法读取任务配置或密钥，请检查应用数据和设置")
        }
        val nextCandidate = DeliveryPolicy.nextAttempt(System.currentTimeMillis(), row.attempts, outcome.retryAfterAt)
        val canRetry = outcome.retryable && row.attempts < DeliveryPolicy.MAX_ATTEMPTS &&
            !DeliveryPolicy.expired(row.retryWindowStartedAt, nextCandidate)
        val state = when { outcome.success -> DeliveryState.SUCCEEDED
            canRetry -> DeliveryState.RETRY_WAIT
            else -> DeliveryState.NEEDS_ATTENTION }
        val error = if (!outcome.success && outcome.retryable && !canRetry) "自动重试已停止：${outcome.error}" else outcome.error
        val nextAt = if (canRetry) nextCandidate else 0
        c.repository.dao.setBusinessCode(id, token, outcome.businessCode)
        val changed = c.repository.dao.finish(id, token, state, outcome.code, error, nextAt)
        if (changed == 1 && state == DeliveryState.NEEDS_ATTENTION) {
            c.settings.error(error)
            c.notifications.failure("有通知需要处理，请打开应用查看投递记录")
        }
        return if (changed == 1 && canRetry) Result.retry() else Result.success()
    }

    private suspend fun awaitSlot(c: AppContainer, url: String, deadline: Long): Boolean {
        while (deadline - SystemClock.elapsedRealtime() >= 22_000) {
            val wait = c.settings.reserveRobotSlot(url)
            if (wait == 0L) return true
            if (wait > deadline - SystemClock.elapsedRealtime() - 22_000) return false
            delay(wait)
        }
        return false
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as RelayApplication).container.repository.recover()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { Result.retry() }
}
