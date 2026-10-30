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
import org.junit.rules.ExternalResource
import android.os.ParcelFileDescriptor
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class RelayUiTest {
    @get:Rule(order = 0) val awakeDevice = object : ExternalResource() {
        override fun before() {
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard").forEach { command ->
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cardEditorAndRenewalRequireConfirmationAndPreserveNotes() {
        val c = (compose.activity.application as RelayApplication).container
        val name = "UI-card-${UUID.randomUUID().toString().take(8)}"
        try {
            val model = ViewModelProvider(compose.activity)[RelayViewModel::class.java]
            compose.waitUntil(10_000) { model.feedback.value.ready && !model.feedback.value.busy }
            compose.onNodeWithText("卡片", useUnmergedTree = true).performClick()
            compose.onNodeWithText("添加卡片").performClick()
            compose.onNodeWithText("卡片名称").performTextInput(name)
            compose.onNodeWithText("备注（选填，仅保存在应用内）").performScrollTo().performTextInput("用途备注\n第二行")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("保存卡片").performClick()
            compose.waitUntil(15_000) { !model.feedback.value.busy && runBlocking { c.esim.dao.all().any { it.name == name } } }
            // The transient saved snackbar can cover the bottom action in the shorter, permission-granted layout.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("卡片已保存").fetchSemanticsNodes().isEmpty() }
            val original = runBlocking { c.esim.dao.all().first { it.name == name } }
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("卡片详情").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("已续期"))
            compose.onNodeWithText("已续期").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("确认已续期？").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("确认已续期？").assertExists()
            compose.onNodeWithText("卡片：$name").assertExists()
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/esim-renewal.png")).use { it.readBytes() }
            compose.onNodeWithText("取消").performClick()
            assertEquals(original.expiresAt, runBlocking { c.esim.dao.get(original.id)!!.expiresAt })
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("已续期"))
            compose.onNodeWithText("已续期").performClick()
            compose.onNodeWithText("确认已续期").performClick()
            compose.waitUntil(10_000) { !model.feedback.value.busy && runBlocking { c.esim.dao.get(original.id)!!.revision == original.revision + 1 } }
            val updated = runBlocking { c.esim.dao.get(original.id)!! }
            assertNotNull(updated.lastRenewedAt)
            assertEquals(original.ruleVersion + 1, updated.ruleVersion)
            assertEquals("用途备注\n第二行", runBlocking { c.esim.cards.first().first { it.card.id == original.id }.info.notes })
        } catch (failure: Throwable) {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/esim-ui-failure.png")).use { it.readBytes() }
            compose.onRoot(useUnmergedTree = true).printToLog("ESIM_UI_FAILURE")
            throw failure
        } finally {
            runBlocking { c.esim.dao.all().firstOrNull { it.name == name }?.let { c.esim.delete(it.id, it.revision) } }
        }
    }

    @Test fun settingsTestButtonPersistsAndDeliversNotification() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val c = (compose.activity.application as RelayApplication).container
        val saved = runBlocking { c.repository.draft() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"errcode\":0,\"errmsg\":\"ok\"}"))
            compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("机器人地址").fetchSemanticsNodes().isNotEmpty() }
            val model = ViewModelProvider(compose.activity)[RelayViewModel::class.java]
            compose.waitUntil(10_000) { model.feedback.value.ready && !model.feedback.value.busy }
            compose.onNodeWithText("机器人地址").performTextReplacement(
                server.url("/cgi-bin/webhook/send?key=ui-${UUID.randomUUID()}").toString())
            compose.onNodeWithText("保存并测试").performScrollTo().performClick()
            val request = server.takeRequest(30, TimeUnit.SECONDS)
            assertNotNull("The WorkManager test notification did not reach the receiver", request)
            val content = JSONObject(request!!.body.readUtf8()).getJSONObject("text").getString("content")
            assertTrue(content.startsWith("【测试通知】"))
            val prefix = content.lineSequence().first { it.startsWith("编号：") }.substringAfter("编号：")
            compose.waitUntil(10_000) { runBlocking {
                c.repository.dao.observeRecent().first().firstOrNull { it.id.startsWith(prefix) }?.state == DeliveryState.SUCCEEDED
            } }
            compose.onNodeWithText("记录", useUnmergedTree = true).performClick()
            compose.onAllNodesWithText("测试通知").onFirst().assertExists()
        } finally {
            runBlocking {
                if (saved.url.isNotEmpty()) c.settings.saveEndpoint(saved.url, saved.senderAllowlist, saved.includeKeywords, saved.excludeKeywords)
                else c.settings.saveEndpoint("")
            }
            server.shutdown()
        }
    }
}
