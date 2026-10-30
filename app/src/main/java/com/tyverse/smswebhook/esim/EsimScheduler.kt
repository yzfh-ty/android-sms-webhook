package com.tyverse.smswebhook.esim

import android.content.Context
import androidx.work.*
import com.tyverse.smswebhook.RelayApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class EsimScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)
    fun schedule(card: EsimCard) {
        val at = card.nextReminderAt ?: return
        if (!card.reminderEnabled) return
        manager.enqueueUniqueWork("esim-${card.id}-${card.ruleVersion}-$at", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EsimReminderWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .addTag("esim-${card.id}").build())
    }
    fun cancel(id: String) { manager.cancelAllWorkByTag("esim-$id") }
    fun ensureRecovery() {
        manager.enqueueUniquePeriodicWork("esim-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EsimReminderWorker>(15, TimeUnit.MINUTES).build())
    }
}

class EsimReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as RelayApplication).container.esim.recover()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
