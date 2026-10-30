package com.tyverse.smswebhook

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyverse.smswebhook.data.SettingsStore
import com.tyverse.smswebhook.security.CryptoStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WecomSettingsTest {
    private fun settingsTest(block: suspend (SettingsStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, File) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try { block(SettingsStore(context, CryptoStore(), store), store, file) }
        finally { job.cancelAndJoin(); file.delete() }
    }
    @Test fun botUrlIsEncryptedAtRestAndRoundTrips() = settingsTest { settings, _, file ->
        val url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=private-fixture-key"
        settings.saveEndpoint(url)
        assertEquals(url, settings.current().url)
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains("private-fixture-key"))
    }
    @Test fun oldPlaintextSettingsAreMigratedAndGenericForwardingIsPaused() = settingsTest { settings, store, file ->
        val old = "https://example.invalid/hooks?key=legacy-fixture-key"
        store.edit {
            it[stringPreferencesKey("url")] = old; it[booleanPreferencesKey("enabled")] = true
            it[stringPreferencesKey("token_cipher")] = "obsolete-token"
            it[stringPreferencesKey("secret_cipher")] = "obsolete-secret"
        }
        settings.initialize()
        assertEquals(old, settings.current().url); assertFalse(settings.current().enabled)
        assertNull(store.data.first()[stringPreferencesKey("url")])
        assertNull(store.data.first()[stringPreferencesKey("token_cipher")])
        assertNull(store.data.first()[stringPreferencesKey("secret_cipher")])
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains("legacy-fixture-key"))
    }
    @Test fun allWorkersShareAPersistentPacingSlot() = settingsTest { settings, _, _ ->
        val url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=pacing-test"
        val waits = coroutineScope { (0..5).map { async { settings.reserveRobotSlot(url) } }.awaitAll() }
        assertEquals(1, waits.count { it == 0L })
        assertTrue(waits.filter { it > 0 }.all { it <= 3_100 })
    }
}
