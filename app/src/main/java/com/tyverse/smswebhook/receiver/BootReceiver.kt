package com.tyverse.smswebhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tyverse.smswebhook.RelayApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val c = (context.applicationContext as RelayApplication).container
        c.scope.launch {
            try { withTimeout(8_000) { c.repository.recover() } }
            catch (_: Exception) { runCatching { c.settings.error("重启恢复尚未完成，请打开应用检查队列") } }
            finally { pending.finish() }
        }
    }
}
