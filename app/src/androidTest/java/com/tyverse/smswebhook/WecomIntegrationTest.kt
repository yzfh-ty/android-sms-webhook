package com.tyverse.smswebhook

import android.content.ContentValues
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyverse.smswebhook.data.RelayDatabase
import com.tyverse.smswebhook.webhook.EndpointSnapshot
import com.tyverse.smswebhook.webhook.HttpOutcome
import com.tyverse.smswebhook.webhook.WebhookClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WecomIntegrationTest {
    private fun response(body: String, status: Int = 200): HttpOutcome = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            WebhookClient().send(EndpointSnapshot(server.url("/cgi-bin/webhook/send?key=response-test").toString()),
                "{\"msgtype\":\"text\",\"text\":{\"content\":\"test\"}}")
        } finally { server.shutdown() }
    }

    @Test fun http200WithRobotErrorIsNotSuccess() {
        val result = response("{\"errcode\":93000,\"errmsg\":\"sensitive-key-must-not-be-logged\"}")
        assertFalse(result.success); assertFalse(result.retryable); assertEquals(93000, result.businessCode)
        assertFalse(result.error.contains("sensitive-key"))
    }
    @Test fun onlyErrcodeZeroIsSuccess() { assertTrue(response("{\"errcode\":0,\"errmsg\":\"ok\"}").success) }
    @Test fun missingErrcodeIsNotSuccess() { assertFalse(response("{\"errmsg\":\"ok\"}").success) }
    @Test fun stringErrcodeIsRejected() { assertFalse(response("{\"errcode\":\"0\"}").success) }
    @Test fun malformedJsonIsNotSuccess() { assertFalse(response("<html>error</html>").success) }
    @Test fun emptySuccessResponseIsNotAccepted() { assertFalse(response("", 204).success) }
    @Test fun oversizedResponseIsRejected() { assertFalse(response("x".repeat(17_000)).success) }
    @Test fun rateLimitedRobotWaitsAtLeastAMinute() {
        val before = System.currentTimeMillis()
        val result = response("{\"errcode\":45009}")
        assertFalse(result.success); assertTrue(result.retryable)
        assertTrue(result.retryAfterAt!! >= before + 60_000)
    }
    @Test fun busyRobotCanRetry() { assertTrue(response("{\"errcode\":-1}").retryable) }
    @Test fun cancellationDoesNotLeaveTheRequestWaiting() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{\"errcode\":0}"))
            var cancelled = false
            try { withTimeout(150) {
                WebhookClient().send(EndpointSnapshot(server.url("/cgi-bin/webhook/send?key=cancel").toString()), "{}")
            } } catch (_: TimeoutCancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { server.shutdown() }
    }

    @Test fun databaseUpgradePreservesVersionOneMessages() = migrateFrom(1)
    @Test fun databaseUpgradePreservesVersionTwoMessages() = migrateFrom(2)
    private fun migrateFrom(version: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = instrumentation.context.assets.open("com.tyverse.smswebhook.data.RelayDatabase/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, 0, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
            val values = ContentValues().apply {
                put("id", "legacy-id"); put("fingerprint", "legacy-fingerprint"); put("sender", "10086")
                put("bodyCipher", "encrypted-body"); put("payloadCipher", "encrypted-payload"); put("endpointCipher", "encrypted-endpoint")
                put("receivedAt", 1_000L); put("smsTimestamp", 900L); putNull("subscriptionId")
                put("source", "sms_broadcast"); put("retryWindowStartedAt", 1_000L); put("state", "PENDING")
                put("attempts", 0); put("nextAttemptAt", 0L); putNull("leaseToken"); put("leaseUntil", 0L)
                put("leaseElapsedUntil", 0L); put("leaseBoot", 0); put("lastAttemptAt", 0L)
                putNull("lastHttpStatus"); put("lastError", "")
                if (version >= 2) { put("nextPartIndex", 1); putNull("lastBusinessCode") }
            }
            legacy.insertOrThrow("messages", null, values)
            legacy.version = version
        }
        val upgraded = Room.databaseBuilder(context, RelayDatabase::class.java, name)
            .addMigrations(RelayDatabase.MIGRATION_1_2, RelayDatabase.MIGRATION_2_3).build()
        try {
            val row = upgraded.messages().get("legacy-id")!!
            assertEquals("encrypted-body", row.bodyCipher)
            assertEquals(if (version == 1) 0 else 1, row.nextPartIndex); assertNull(row.lastBusinessCode)
            assertNull(row.cardId); assertNull(row.cardRuleVersion)
            assertTrue(upgraded.cards().all().isEmpty())
            assertEquals(3, upgraded.openHelper.writableDatabase.version)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
