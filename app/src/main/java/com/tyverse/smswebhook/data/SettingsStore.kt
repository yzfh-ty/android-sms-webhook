package com.tyverse.smswebhook.data

import android.content.Context
import com.tyverse.smswebhook.BuildConfig
import com.tyverse.smswebhook.security.CryptoStore
import com.tyverse.smswebhook.webhook.WebhookProtocol
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore("relay-settings")

data class RelaySettings(
    val enabled: Boolean = false,
    val url: String = "",
    val deviceId: String = "",
    val lastReceivedAt: Long = 0,
    val lastWorkerAt: Long = 0,
    val healthError: String = "",
    val rootWhitelistAdded: Boolean = false,
    val senderAllowlist: String = "",
    val includeKeywords: String = "",
    val excludeKeywords: String = "",
    val filteredCount: Long = 0,
    val lastFilterReason: String = "",
)

class SettingsStore(context: Context, private val crypto: CryptoStore,
    private val store: DataStore<Preferences> = context.applicationContext.settingsDataStore) {
    val flow = store.data.map { p ->
        val url = p[URL_CIPHER]?.let(crypto::decrypt) ?: p[LEGACY_URL].orEmpty()
        RelaySettings(enabled = p[ENABLED] ?: false, url = url,
            deviceId = p[DEVICE] ?: "", lastReceivedAt = p[RECEIVED] ?: 0,
            lastWorkerAt = p[WORKER] ?: 0, healthError = p[ERROR] ?: "",
            rootWhitelistAdded = p[ROOT_ADDED] ?: false, senderAllowlist = p[SENDERS] ?: "",
            includeKeywords = p[INCLUDES] ?: "", excludeKeywords = p[EXCLUDES] ?: "",
            filteredCount = p[FILTER_COUNT] ?: 0, lastFilterReason = p[FILTER_REASON] ?: "")
    }.flowOn(Dispatchers.IO)
    suspend fun current(): RelaySettings = flow.first()
    suspend fun initialize() {
        store.edit {
            if (it[DEVICE].isNullOrBlank()) it[DEVICE] = UUID.randomUUID().toString()
            val legacy = it[LEGACY_URL].orEmpty()
            if (it[URL_CIPHER] == null && legacy.isNotBlank()) {
                it[URL_CIPHER] = crypto.encrypt(legacy)
                if (WebhookProtocol.validationError(legacy, BuildConfig.DEBUG) != null) {
                    it[ENABLED] = false
                    it[ERROR] = "通知通道已改为企业微信，请重新保存群机器人地址后开启转发"
                }
            }
            it.remove(LEGACY_URL); it.remove(LEGACY_TOKEN); it.remove(LEGACY_SECRET)
        }
    }
    suspend fun saveEndpoint(url: String, senders: String = "", includes: String = "", excludes: String = "") {
        store.edit {
            if (url.isEmpty()) it.remove(URL_CIPHER) else it[URL_CIPHER] = crypto.encrypt(url)
            it.remove(LEGACY_URL); it.remove(LEGACY_TOKEN); it.remove(LEGACY_SECRET)
            it[SENDERS] = senders; it[INCLUDES] = includes; it[EXCLUDES] = excludes
            it[ERROR] = ""
        }
    }
    /** Atomic, persistent pacing shared by all workers for the same bot key. */
    suspend fun reserveRobotSlot(url: String): Long {
        val key = longPreferencesKey("robot_next_${WebhookProtocol.robotId(url)}")
        var wait = 0L
        store.edit {
            val now = System.currentTimeMillis()
            val next = it[key] ?: 0
            if (next <= now) it[key] = now + 3_100 else wait = next - now
        }
        return wait
    }
    suspend fun deferRobot(url: String, until: Long) {
        val key = longPreferencesKey("robot_next_${WebhookProtocol.robotId(url)}")
        store.edit { it[key] = maxOf(it[key] ?: 0, until) }
    }
    suspend fun setEnabled(enabled: Boolean) { store.edit { it[ENABLED] = enabled } }
    suspend fun received(at: Long) { store.edit { it[RECEIVED] = at; it[ERROR] = "" } }
    suspend fun workerRan(at: Long) { store.edit { it[WORKER] = at } }
    suspend fun error(message: String) { store.edit { it[ERROR] = message.take(180) } }
    suspend fun setRootAdded(value: Boolean) { store.edit { it[ROOT_ADDED] = value } }
    suspend fun filtered(at: Long, reason: String) {
        store.edit { it[RECEIVED] = at; it[FILTER_COUNT] = (it[FILTER_COUNT] ?: 0) + 1; it[FILTER_REASON] = reason }
    }

    companion object {
        private val ENABLED = booleanPreferencesKey("enabled")
        private val URL_CIPHER = stringPreferencesKey("wecom_url_cipher")
        private val LEGACY_URL = stringPreferencesKey("url")
        private val LEGACY_TOKEN = stringPreferencesKey("token_cipher")
        private val LEGACY_SECRET = stringPreferencesKey("secret_cipher")
        private val DEVICE = stringPreferencesKey("device_id")
        private val RECEIVED = longPreferencesKey("last_received_at")
        private val WORKER = longPreferencesKey("last_worker_at")
        private val ERROR = stringPreferencesKey("health_error")
        private val ROOT_ADDED = booleanPreferencesKey("root_whitelist_added")
        private val SENDERS = stringPreferencesKey("sender_allowlist")
        private val INCLUDES = stringPreferencesKey("include_keywords")
        private val EXCLUDES = stringPreferencesKey("exclude_keywords")
        private val FILTER_COUNT = longPreferencesKey("filtered_count")
        private val FILTER_REASON = stringPreferencesKey("last_filter_reason")
    }
}
