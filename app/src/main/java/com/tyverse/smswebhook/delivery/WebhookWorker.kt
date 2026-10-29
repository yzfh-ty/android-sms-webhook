package com.tyverse.smswebhook.delivery

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tyverse.smswebhook.RelayApplication
import com.tyverse.smswebhook.data.DeliveryState
import com.tyverse.smswebhook.webhook.HttpOutcome
import kotlinx.coroutines.CancellationException
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
        val row = c.database.withTransaction {
            if (c.repository.dao.claim(id, token, now, now + DeliveryPolicy.LEASE_MS,
                    SystemClock.elapsedRealtime() + DeliveryPolicy.LEASE_MS, c.repository.bootCount()) != 1) null
            else c.repository.dao.get(id)
        } ?: return Result.success()
        val outcome = try {
            when {
                DeliveryPolicy.expired(row.retryWindowStartedAt, now) -> HttpOutcome(false, error = "自动投递期限已到，请手动处理")
                row.attempts > DeliveryPolicy.MAX_ATTEMPTS -> HttpOutcome(false, error = "自动重试次数已到上限")
                row.source != "test" && !c.settings.current().enabled -> {
                    c.repository.dao.pauseSms()
                    return Result.success()
                }
                else -> c.http.send(c.repository.endpoint(row), c.repository.payload(row), id)
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
        val changed = c.repository.dao.finish(id, token, state, outcome.code, error, nextAt)
        if (changed == 1 && state == DeliveryState.NEEDS_ATTENTION) {
            c.settings.error(error)
            c.notifications.failure("有通知需要处理，请打开应用查看投递记录")
        }
        return if (changed == 1 && canRetry) Result.retry() else Result.success()
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
