package com.tyverse.smswebhook.webhook

import org.junit.Assert.*
import org.junit.Test

class WecomMessageFormatterTest {
    private fun format(body: String) = WecomMessageFormatter.format("10086", body, 1_000, null, "message-id", false)
    @Test fun shortSmsHasReadableMetadata() {
        val content = format("短信正文").single()
        assertTrue(content.startsWith("【短信通知】")); assertTrue(content.contains("发送方：10086"))
        assertTrue(content.contains("订阅 ID：未知")); assertTrue(content.endsWith("\n\n短信正文"))
    }
    @Test fun testsAreClearlyLabeled() {
        assertTrue(WecomMessageFormatter.format("test", "hello", 1_000, 2, "id", true).single().startsWith("【测试通知】"))
    }
    @Test fun chineseSmsSplitsByBytesAndReconstructsExactly() {
        val body = "中文正文\n".repeat(1_000)
        val parts = format(body)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 2048 })
        assertEquals(body, parts.joinToString("") { it.substringAfter("\n\n") })
    }
    @Test fun emojiSurrogatesAreNeverSplit() {
        val body = "A😀B🚀C".repeat(600)
        val parts = format(body)
        assertEquals(body, parts.joinToString("") { it.substringAfter("\n\n") })
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 2048 })
        assertTrue(parts.none { it.contains('\uFFFD') })
    }
    @Test fun exactByteBudgetDoesNotAddAnEmptyChunk() {
        assertEquals(listOf("😀", "😀"), WecomMessageFormatter.splitUtf8("😀😀", 4))
    }
    @Test fun preservesNewlinesAndEmptyBody() { assertTrue(format("").single().endsWith("\n\n")) }
    @Test fun partNumbersAndTotalAreIncluded() {
        val parts = format("x".repeat(6_000))
        parts.forEachIndexed { i, text -> assertTrue(text.contains("分段：${i + 1}/${parts.size}")) }
    }
    @Test fun longSenderCannotExhaustTheMessageBudget() {
        val parts = WecomMessageFormatter.format("😀".repeat(300), "文".repeat(2_000), 1_000, 1, "id", false)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 2048 })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsImpossibleUtf8Budget() {
        WecomMessageFormatter.splitUtf8("hello", 3)
    }
}
