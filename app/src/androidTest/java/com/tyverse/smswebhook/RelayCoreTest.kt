package com.tyverse.smswebhook

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyverse.smswebhook.data.DeliveryState
import com.tyverse.smswebhook.data.RelayDatabase
import com.tyverse.smswebhook.data.RelayMessage
import com.tyverse.smswebhook.security.CryptoStore
import com.tyverse.smswebhook.webhook.EndpointSnapshot
import com.tyverse.smswebhook.webhook.WebhookClient
import com.tyverse.smswebhook.webhook.WebhookProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RelayCoreTest {
    private lateinit var db: RelayDatabase
    @Before fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            RelayDatabase::class.java).build()
    }
    @After fun closeDatabase() { db.close() }
    private fun message(id: String = "one", fingerprint: String = "fingerprint") = RelayMessage(
        id, fingerprint, "10086", "cipher-body", "cipher-payload", "cipher-endpoint",
        1_000, 900, null, "sms_broadcast")

    @Test fun duplicateFingerprintDoesNotCreateAnotherDelivery() = runBlocking {
        assertNotEquals(-1L, db.messages().insert(message()))
        assertEquals(-1L, db.messages().insert(message("two")))
        assertEquals(1, db.messages().outstandingCount())
    }
    @Test fun concurrentClaimsHaveExactlyOneOwner() = runBlocking {
        db.messages().insert(message())
        val claims = coroutineScope {
            (0..11).map { i -> async(Dispatchers.IO) {
                db.messages().claim("one", "token-$i", 2_000, 122_000, 130_000, 1)
            } }.awaitAll()
        }
        assertEquals(1, claims.sum())
        assertEquals(1, db.messages().get("one")!!.attempts)
    }
    @Test fun staleWorkerCannotOverwriteNewState() = runBlocking {
        db.messages().insert(message())
        db.messages().claim("one", "owner", 2_000, 122_000, 130_000, 1)
        assertEquals(0, db.messages().finish("one", "stale", DeliveryState.SUCCEEDED, 200, "", 0))
        assertEquals(1, db.messages().finish("one", "owner", DeliveryState.SUCCEEDED, 200, "", 0))
    }
    @Test fun rebootRecoversLeaseEvenWhenWallClockHasNotExpired() = runBlocking {
        db.messages().insert(message())
        db.messages().claim("one", "owner", 2_000, 122_000, 130_000, 1)
        assertEquals(1, db.messages().recoverLeases(3_000, 123_000, 1_000, 2))
        assertEquals(DeliveryState.RETRY_WAIT, db.messages().get("one")!!.state)
    }
    @Test fun pauseInvalidatesAnInflightLease() = runBlocking {
        db.messages().insert(message())
        db.messages().claim("one", "owner", 2_000, 122_000, 130_000, 1)
        db.messages().pauseSms()
        assertEquals(DeliveryState.PAUSED, db.messages().get("one")!!.state)
        assertEquals(0, db.messages().finish("one", "owner", DeliveryState.SUCCEEDED, 200, "", 0))
        db.messages().resumeSms()
        assertEquals(DeliveryState.PENDING, db.messages().get("one")!!.state)
    }
    @Test fun manualRetryRestartsTheWindowWithoutChangingIdentity() = runBlocking {
        db.messages().insert(message().copy(state = DeliveryState.NEEDS_ATTENTION, attempts = 20))
        assertEquals(1, db.messages().retry("one", 999_000))
        val row = db.messages().get("one")!!
        assertEquals("one", row.id)
        assertEquals(0, row.attempts)
        assertEquals(999_000, row.retryWindowStartedAt)
        assertEquals(1_000, row.receivedAt)
    }
    @Test fun retentionNeverDeletesFailedMessages() = runBlocking {
        db.messages().insert(message().copy(state = DeliveryState.NEEDS_ATTENTION))
        db.messages().insert(message("two", "second").copy(state = DeliveryState.SUCCEEDED, lastAttemptAt = 1_000))
        assertEquals(1, db.messages().deleteOldCompleted(2_000))
        assertNotNull(db.messages().get("one"))
    }
    @Test fun oldHttpOnlySuccessIsNotCountedAsConfirmedWecomDelivery() = runBlocking {
        db.messages().insert(message().copy(state = DeliveryState.SUCCEEDED))
        db.messages().insert(message("two", "second").copy(state = DeliveryState.SUCCEEDED, lastBusinessCode = 0))
        assertEquals(1, db.messages().observeStats().first().succeeded)
    }
    @Test fun encryptedMessagesRoundTripWithRandomNonces() {
        val crypto = CryptoStore()
        val first = crypto.encrypt("正文 123456\n第二行")
        val second = crypto.encrypt("正文 123456\n第二行")
        assertNotEquals(first, second)
        assertFalse(first.contains("123456"))
        assertEquals("正文 123456\n第二行", crypto.decrypt(first))
    }
    @Test fun webhookSendsOnlyTheWecomTextContract() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"errcode\":0,\"errmsg\":\"ok\"}"))
            val payload = "{\"msgtype\":\"text\",\"text\":{\"content\":\"中文换行\\n测试\"}}"
            val result = WebhookClient().send(EndpointSnapshot(server.url("/cgi-bin/webhook/send?key=test-key").toString()), payload)
            assertTrue(result.success)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(payload, request.body.readUtf8())
            assertEquals(0, result.businessCode)
            assertNull(request.getHeader("Idempotency-Key")); assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-SMS-Signature"))
            assertEquals("test-key", request.requestUrl!!.queryParameter("key"))
        } finally { server.shutdown() }
    }
    @Test fun redirectDoesNotForwardSensitiveRequest() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            val result = WebhookClient().send(EndpointSnapshot(server.url("/cgi-bin/webhook/send?key=test-key").toString()), "{}")
            assertFalse(result.success)
            assertFalse(result.retryable)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun rateLimitPreservesRetryAfter() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
            val before = System.currentTimeMillis()
            val result = WebhookClient().send(EndpointSnapshot(server.url("/cgi-bin/webhook/send?key=test-key").toString()), "{}")
            assertTrue(result.retryable)
            assertTrue(result.retryAfterAt!! >= before + 120_000)
        } finally { server.shutdown() }
    }
}
