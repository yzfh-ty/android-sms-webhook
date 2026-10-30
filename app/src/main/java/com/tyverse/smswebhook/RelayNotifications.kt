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
import androidx.core.app.NotificationManagerCompat
import android.net.Uri

class RelayNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel("delivery-alerts", "投递故障", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("esim-expiry", "eSIM 到期提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun cardNotificationsAvailable(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (manager.getNotificationChannel("esim-expiry")?.importance ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE

    fun cardReminder(id: String, name: String, text: String): Boolean {
        if (!cardNotificationsAvailable()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("smswebhook://card/$id")).putExtra("card_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val launch = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify("esim-$id", 0, NotificationCompat.Builder(context, "esim-expiry")
                .setSmallIcon(R.drawable.ic_sms_notification).setContentTitle("eSIM · $name")
                .setContentText(text).setContentIntent(launch).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }
    fun cancelCard(id: String) { manager.cancel("esim-$id", 0) }
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
