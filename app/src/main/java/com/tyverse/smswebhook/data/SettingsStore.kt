package com.tyverse.smswebhook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore("relay-settings")

data class RelaySettings(
    val enabled: Boolean = false,
    val url: String = "",
    val tokenCipher: String = "",
    val secretCipher: String = "",
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

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    val flow = store.data.map { p ->
        RelaySettings(p[ENABLED] ?: false, p[URL] ?: "", p[TOKEN] ?: "",
            p[SECRET] ?: "", p[DEVICE] ?: "", p[RECEIVED] ?: 0,
            p[WORKER] ?: 0, p[ERROR] ?: "", p[ROOT_ADDED] ?: false,
            p[SENDERS] ?: "", p[INCLUDES] ?: "", p[EXCLUDES] ?: "", p[FILTER_COUNT] ?: 0,
            p[FILTER_REASON] ?: "")
    }
    suspend fun current(): RelaySettings = flow.first()
    suspend fun initialize() {
        store.edit { if (it[DEVICE].isNullOrBlank()) it[DEVICE] = UUID.randomUUID().toString() }
    }
    suspend fun saveEndpoint(url: String, tokenCipher: String, secretCipher: String,
        senders: String = "", includes: String = "", excludes: String = "") {
        store.edit {
            it[URL] = url; it[TOKEN] = tokenCipher; it[SECRET] = secretCipher
            it[SENDERS] = senders; it[INCLUDES] = includes; it[EXCLUDES] = excludes
        }
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
        private val URL = stringPreferencesKey("url")
        private val TOKEN = stringPreferencesKey("token_cipher")
        private val SECRET = stringPreferencesKey("secret_cipher")
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
