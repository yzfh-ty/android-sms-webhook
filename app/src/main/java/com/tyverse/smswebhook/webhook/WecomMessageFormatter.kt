package com.tyverse.smswebhook.webhook

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WecomMessageFormatter {
    fun format(sender: String, body: String, receivedAt: Long, subscriptionId: Int?,
        id: String, test: Boolean): List<String> {
        val safeSender = sender.codePoints().limit(80).toArray()
            .let { String(it, 0, it.size) }.ifBlank { "未知发送方" }
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(receivedAt))
        val header = "${if (test) "【测试通知】" else "【短信通知】"}\n发送方：$safeSender\n接收时间：$time\n订阅 ID：${subscriptionId ?: "未知"}\n编号：${id.take(8)}"
        val single = "$header\n\n$body"
        if (single.toByteArray(Charsets.UTF_8).size <= WebhookProtocol.MAX_CONTENT_BYTES) return listOf(single)
        val reserved = "$header\n分段：999/999\n\n".toByteArray(Charsets.UTF_8).size
        val chunks = splitUtf8(body, WebhookProtocol.MAX_CONTENT_BYTES - reserved)
        require(chunks.size <= 999) { "Too many message parts" }
        return chunks.mapIndexed { i, chunk -> "$header\n分段：${i + 1}/${chunks.size}\n\n$chunk" }
    }

    /** Never cut a surrogate pair; joining the chunks reproduces the original SMS. */
    fun splitUtf8(text: String, maxBytes: Int): List<String> {
        require(maxBytes >= 4)
        if (text.isEmpty()) return listOf("")
        val parts = mutableListOf<String>()
        var start = 0
        var offset = 0
        var bytes = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val chars = Character.charCount(cp)
            val size = text.substring(offset, offset + chars).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) { parts += text.substring(start, offset); start = offset; bytes = 0 }
            bytes += size
            offset += chars
        }
        parts += text.substring(start)
        return parts
    }
}
