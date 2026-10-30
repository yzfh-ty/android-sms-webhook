package com.tyverse.smswebhook

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.tyverse.smswebhook.ui.RelayApp
import com.tyverse.smswebhook.ui.RelayViewModel
import com.tyverse.smswebhook.ui.theme.SMSWebhookTheme

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[RelayViewModel::class.java] }
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshPermissions()
        model.notify(if (it) "短信接收权限已授予" else "未获得短信权限，可在应用系统设置中开启")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshPermissions()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra("card_id")?.let(model::openCard)
        setContent {
            SMSWebhookTheme {
                RelayApp(model,
                    requestSms = { smsPermission.launch(Manifest.permission.RECEIVE_SMS) },
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    openBattery = { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    openAppSettings = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"))) })
            }
        }
    }
    override fun onResume() { super.onResume(); model.refreshPermissions() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("card_id")?.let(model::openCard)
    }
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { model.notify("无法打开此系统设置，请手动进入应用设置") }
    }
}
