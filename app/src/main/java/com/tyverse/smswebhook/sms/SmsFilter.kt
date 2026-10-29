package com.tyverse.smswebhook.sms

/** Literal matching keeps intake bounded; user-defined regex is intentionally unsupported. */
object SmsFilter {
    fun entries(text: String): List<String> = text.lineSequence().map(String::trim)
        .filter(String::isNotEmpty).distinct().toList()

    fun validationError(vararg fields: String): String? = when {
        fields.any { it.length > 6_000 || entries(it).size > 50 } -> "每组过滤规则最多 50 行、6000 个字符"
        fields.any { entries(it).any { line -> line.length > 128 } } -> "每条过滤条件最多 128 个字符"
        else -> null
    }

    fun rejectionReason(sender: String, body: String, senders: String, includes: String,
        excludes: String): String? {
        val denied = entries(excludes)
        if (denied.any { body.contains(it, ignoreCase = true) }) return "命中排除关键词"
        val allowed = entries(senders)
        if (allowed.isNotEmpty() && allowed.none { sender.trim().equals(it, ignoreCase = true) })
            return "发送方不在白名单"
        val required = entries(includes)
        if (required.isNotEmpty() && required.none { body.contains(it, ignoreCase = true) })
            return "未命中包含关键词"
        return null
    }
}
