package com.tyverse.smswebhook.delivery

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tyverse.smswebhook.data.ScheduledMessage
import java.util.concurrent.TimeUnit

class DeliveryScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)
    fun enqueue(message: ScheduledMessage, replace: Boolean = false) {
        val delay = (message.nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0)
        val builder = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setInputData(workDataOf("message_id" to message.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(if (message.source == "test") "test-delivery" else "sms-delivery")
        if (delay > 0) builder.setInitialDelay(delay, TimeUnit.MILLISECONDS)
        else if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        manager.enqueueUniqueWork("delivery-${message.id}",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, builder.build())
    }
    fun ensureRecovery() {
        manager.enqueueUniquePeriodicWork("queue-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build())
    }
    fun cancelSms() { manager.cancelAllWorkByTag("sms-delivery").result.get(10, TimeUnit.SECONDS) }
}
