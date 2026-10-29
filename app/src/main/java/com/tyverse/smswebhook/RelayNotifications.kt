package com.tyverse.smswebhook

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class RelayNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun createChannel() { manager.createNotificationChannel(NotificationChannel(
        "delivery-alerts", "投递故障", NotificationManager.IMPORTANCE_DEFAULT)) }
    fun failure(text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(context, "delivery-alerts")
            .setSmallIcon(R.drawable.ic_sms_notification).setContentTitle("SMS Webhook")
            .setContentText(text).setContentIntent(launch).setAutoCancel(true).build())
    }
}
