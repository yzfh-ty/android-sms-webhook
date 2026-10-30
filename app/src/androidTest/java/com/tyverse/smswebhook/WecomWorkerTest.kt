package com.tyverse.smswebhook

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.tyverse.smswebhook.data.DeliveryState
import com.tyverse.smswebhook.data.EndpointDraft
import com.tyverse.smswebhook.data.RelayMessage
import com.tyverse.smswebhook.data.ScheduledMessage
import com.tyverse.smswebhook.webhook.WecomMessageFormatter
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class WecomWorkerTest {
    @Test fun fullyAcknowledgedMessageDoesNotResendAtRetryLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val c = (context.applicationContext as RelayApplication).container
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val id = UUID.randomUUID().toString()
        try {
            val payload = JSONObject().put("protocol", "wecom-text-v1").put("messages", JSONArray().put(
                JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", "already acknowledged")))).toString()
            val endpoint = com.tyverse.smswebhook.webhook.EndpointSnapshot(
                server.url("/cgi-bin/webhook/send?key=completed-$id").toString()).encode()
            val now = System.currentTimeMillis()
            c.repository.dao.insert(RelayMessage(id, "completed:$id", "10086", c.crypto.encrypt("already acknowledged"),
                c.crypto.encrypt(payload), c.crypto.encrypt(endpoint), now, now, null, "test",
                state = DeliveryState.RETRY_WAIT, attempts = 20, nextPartIndex = 1, lastBusinessCode = 0))
            c.scheduler.enqueue(ScheduledMessage(id, 0, "test"))
            withTimeout(15_000) { while (c.repository.dao.get(id)?.state != DeliveryState.SUCCEEDED) delay(100) }
            assertEquals(0, server.requestCount)
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("delivery-$id").result.get(5, TimeUnit.SECONDS)
            c.database.withTransaction {
                c.database.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE id=? AND source='test'", arrayOf(id))
            }
            server.shutdown()
        }
    }

    @Test fun partialDeliveryResumesAfterTheLastConfirmedPart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val c = (context.applicationContext as RelayApplication).container
        val saved = c.repository.draft()
        val receivedParts = Collections.synchronizedList(mutableListOf<Int>())
        val failSecond = AtomicBoolean(true)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val content = JSONObject(request.body.readUtf8()).getJSONObject("text").getString("content")
                val part = Regex("分段：(\\d+)/").find(content)!!.groupValues[1].toInt()
                receivedParts.add(part)
                return MockResponse().setBody(if (part == 2 && failSecond.getAndSet(false)) "{\"errcode\":-1}" else "{\"errcode\":0}")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        var id: String? = null
        try {
            c.repository.saveEndpoint(EndpointDraft(server.url("/cgi-bin/webhook/send?key=parts-${UUID.randomUUID()}").toString()))
            val body = "长短信😀\n".repeat(250)
            id = c.repository.capture("10086", body, System.currentTimeMillis(), 1, "parts:${UUID.randomUUID()}", test = true)!!
            withTimeout(30_000) { while (c.repository.dao.get(id)?.state != DeliveryState.RETRY_WAIT) delay(100) }
            assertEquals(1, c.repository.dao.get(id)!!.nextPartIndex)
            c.repository.retry(id)
            withTimeout(40_000) { while (c.repository.dao.get(id)?.state != DeliveryState.SUCCEEDED) delay(100) }
            val row = c.repository.dao.get(id)!!
            val count = WecomMessageFormatter.format(row.sender, body, row.receivedAt, row.subscriptionId, row.id, true).size
            assertEquals(count, row.nextPartIndex)
            assertEquals(1, receivedParts.count { it == 1 })
            assertEquals(2, receivedParts.count { it == 2 })
            assertEquals(0, row.lastBusinessCode)
        } finally {
            if (id != null) {
                WorkManager.getInstance(context).cancelUniqueWork("delivery-$id").result.get(5, TimeUnit.SECONDS)
                c.database.withTransaction {
                    c.database.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE id=? AND source='test'", arrayOf(id))
                }
            }
            c.settings.saveEndpoint(saved.url, saved.senderAllowlist, saved.includeKeywords, saved.excludeKeywords)
            server.shutdown()
        }
    }
}
