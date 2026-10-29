package com.tyverse.smswebhook.delivery

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

object DeliveryPolicy {
    const val MAX_ATTEMPTS = 20
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val LEASE_MS = 120_000L

    fun retryable(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
    fun expired(createdAt: Long, now: Long): Boolean = now - createdAt >= MAX_AGE_MS
    fun nextAttempt(now: Long, attempts: Int, retryAfterAt: Long? = null,
        jitter: Double = kotlin.random.Random.nextDouble()): Long {
        val base = (30_000L * (1L shl (attempts - 1).coerceIn(0, 7))).coerceAtMost(3_600_000)
        val delay = base + (base * 0.2 * jitter.coerceIn(0.0, 1.0)).toLong()
        return max(now + delay, retryAfterAt ?: 0)
    }

    fun retryAfter(value: String?, now: Long): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let {
            if (it < 0) return null
            return if (it > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + it * 1000
        }
        return runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli().coerceAtLeast(now)
        }.getOrNull()
    }
}
