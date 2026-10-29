package com.tyverse.smswebhook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.tyverse.smswebhook.ui.RelayViewModel
import com.tyverse.smswebhook.data.DeliveryState
import com.tyverse.smswebhook.data.EndpointDraft
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RelayUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsTestButtonPersistsAndDeliversNotification() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val c = (compose.activity.application as RelayApplication).container
        val saved = runBlocking { c.repository.draft() }
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Webhook 地址").fetchSemanticsNodes().isNotEmpty() }
            val model = ViewModelProvider(compose.activity)[RelayViewModel::class.java]
            compose.waitUntil(10_000) { model.feedback.value.ready && !model.feedback.value.busy }
            compose.onNodeWithText("Webhook 地址").performTextReplacement(server.url("/hooks/sms").toString())
            compose.onNodeWithText("保存并发送测试").performScrollTo().performClick()
            val request = server.takeRequest(30, TimeUnit.SECONDS)
            assertNotNull("The WorkManager test notification did not reach the receiver", request)
            val id = request!!.getHeader("Idempotency-Key")!!
            compose.waitUntil(10_000) { runBlocking { c.repository.dao.get(id)?.state == DeliveryState.SUCCEEDED } }
            compose.onNodeWithText("记录", useUnmergedTree = true).performClick()
            compose.onAllNodesWithText("测试通知").onFirst().assertExists()
        } finally {
            runBlocking {
                if (saved.url.isNotEmpty()) c.repository.saveEndpoint(saved)
                else c.settings.saveEndpoint("", "", "")
            }
            server.shutdown()
        }
    }
}
