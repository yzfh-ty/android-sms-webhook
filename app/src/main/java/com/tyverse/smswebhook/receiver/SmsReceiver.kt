package com.tyverse.smswebhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.tyverse.smswebhook.RelayApplication
import com.tyverse.smswebhook.data.UserInputException
import com.tyverse.smswebhook.sms.SmsFingerprint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        val c = (context.applicationContext as RelayApplication).container
        c.scope.launch {
            try {
                withTimeout(8_000) {
                    if (!c.settings.current().enabled) return@withTimeout
                    val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    require(!parts.isNullOrEmpty())
                    val sender = parts.first().displayOriginatingAddress ?: "未知发送方"
                    require(parts.all { (it.displayOriginatingAddress ?: "未知发送方") == sender })
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    require(body.length <= 65_536)
                    @Suppress("DEPRECATION")
                    val pdus = (intent.extras?.get("pdus") as? Array<*>)?.mapNotNull { it as? ByteArray }.orEmpty()
                    val sub = when {
                        intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX) ->
                            intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
                        intent.hasExtra("subscription") -> intent.getIntExtra("subscription", -1)
                        else -> -1
                    }.takeIf { it >= 0 }
                    c.settings.initialize()
                    val fingerprint = SmsFingerprint.create(c.settings.current().deviceId, sub, pdus)
                    c.repository.capture(sender, body, parts.first().timestampMillis, sub, fingerprint)
                }
            } catch (e: Exception) {
                val error = if (e is UserInputException) e.message.orEmpty() else "短信保存失败，请检查存储、密钥和应用配置"
                runCatching { c.settings.error(error) }
                c.notifications.failure("短信保存失败，请打开应用检查")
            } finally { pending.finish() }
        }
    }
}
