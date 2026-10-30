package com.tyverse.smswebhook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyverse.smswebhook.data.*
import com.tyverse.smswebhook.esim.*
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
class EsimWorkerTest {
    @Test fun phoneNotificationsAreDistinctAndRenewalCancelsOnlyItsCard() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val c = (context.applicationContext as RelayApplication).container
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val prefix = "phone-${UUID.randomUUID()}"
        val created = mutableListOf<EsimCard>()
        try {
            for (label in listOf("A", "B")) {
                c.esim.save(EsimDraft(name = "$prefix-$label", notes = "PRIVATE_LOCAL_NOTE",
                    expiresAt = System.currentTimeMillis() - 1000, leadMinutes = 0))
                created.add(c.esim.dao.all().first { it.name == "$prefix-$label" })
            }
            val tags = created.map { "esim-${it.id}" }.toSet()
            val posted = manager.activeNotifications.filter { it.tag in tags }
            assertEquals(2, posted.size)
            assertTrue(posted.all { it.notification.contentIntent != null })
            assertTrue(posted.none { it.notification.extras.toString().contains("PRIVATE_LOCAL_NOTE") })
            assertTrue(created.all { c.esim.dao.latest(it.id, it.ruleVersion)!!.localState == "POSTED" })
            c.esim.renew(created[0].id, created[0].revision)
            val remaining = manager.activeNotifications.filter { it.tag in tags }
            assertEquals(1, remaining.size)
            assertEquals("esim-${created[1].id}", remaining.single().tag)
        } finally {
            for (card in created) c.esim.dao.get(card.id)?.let { c.esim.delete(it.id, it.revision) }
        }
    }

    @Test fun cardReminderUsesWecomWhileSmsPausedAndExcludesNotes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val c = (context.applicationContext as RelayApplication).container
        val saved = c.settings.current()
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val name = "card-${UUID.randomUUID()}"
        var card: EsimCard? = null
        try {
            server.enqueue(MockResponse().setBody("{\"errcode\":0}"))
            c.repository.saveEndpoint(EndpointDraft(server.url("/cgi-bin/webhook/send?key=esim-test").toString()))
            c.repository.setEnabled(false)
            c.esim.save(EsimDraft(name = name, notes = "NEVER_SEND_PRIVATE_NOTES", identifier = "PRIVATE_NUMBER",
                expiresAt = System.currentTimeMillis() - 1000, leadMinutes = 0, localEnabled = false, wecomEnabled = true))
            card = c.database.cards().all().first { it.name == name }
            c.repository.recover()
            val request = server.takeRequest(30, TimeUnit.SECONDS)
            assertNotNull("Card reminder was not delivered with SMS paused", request)
            val body = JSONObject(request!!.body.readUtf8()).getJSONObject("text").getString("content")
            assertTrue(body.contains(name)); assertTrue(body.contains("已到期"))
            assertFalse(body.contains("NEVER_SEND_PRIVATE_NOTES")); assertFalse(body.contains("PRIVATE_NUMBER"))
            val event = c.database.cards().latest(card.id, card.ruleVersion)!!
            withTimeout(15_000) { while (c.database.messages().get(event.id)?.state != DeliveryState.SUCCEEDED) delay(100) }
            c.esim.recover()
            assertEquals(1, server.requestCount)
        } finally {
            card?.let { current -> c.database.cards().get(current.id)?.let { c.esim.delete(it.id, it.revision) } }
            c.settings.saveEndpoint(saved.url, saved.senderAllowlist, saved.includeKeywords, saved.excludeKeywords)
            c.settings.setEnabled(saved.enabled)
            server.shutdown()
        }
    }
}
