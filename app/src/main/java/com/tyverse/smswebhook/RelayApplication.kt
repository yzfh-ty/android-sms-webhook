package com.tyverse.smswebhook

import android.app.Application
import androidx.room.Room
import com.tyverse.smswebhook.data.RelayDatabase
import com.tyverse.smswebhook.data.RelayRepository
import com.tyverse.smswebhook.data.SettingsStore
import com.tyverse.smswebhook.delivery.DeliveryScheduler
import com.tyverse.smswebhook.root.RootController
import com.tyverse.smswebhook.security.CryptoStore
import com.tyverse.smswebhook.webhook.WebhookClient
import com.tyverse.smswebhook.esim.EsimRepository
import com.tyverse.smswebhook.esim.EsimScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val crypto = CryptoStore()
    val settings = SettingsStore(app, crypto)
    val database = Room.databaseBuilder(app, RelayDatabase::class.java, "relay.db")
        .addMigrations(RelayDatabase.MIGRATION_1_2, RelayDatabase.MIGRATION_2_3).build()
    val scheduler by lazy { DeliveryScheduler(app) }
    val repository by lazy { RelayRepository(app, database, settings, crypto, scheduler) }
    val http = WebhookClient()
    val notifications = RelayNotifications(app)
    val esimScheduler by lazy { EsimScheduler(app) }
    val esim by lazy { EsimRepository(database, crypto, settings, esimScheduler, scheduler, notifications) }
    val root = RootController(app, settings)
}

class RelayApplication : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        container.notifications.createChannel()
        container.scope.launch {
            container.esimScheduler.ensureRecovery()
            try {
                container.settings.initialize()
                container.repository.recover()
            } catch (_: Exception) {
                runCatching { container.settings.error("启动恢复检查失败，请重新打开应用检查数据和配置") }
            }
            runCatching { container.esim.recover() }
        }
    }
}
