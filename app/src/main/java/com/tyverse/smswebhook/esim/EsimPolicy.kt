package com.tyverse.smswebhook.esim

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object EsimPolicy {
    const val SOURCE = "esim_reminder"
    const val MINUTE = 60_000L
    fun renewAt(now: Long, count: Int, unit: String, zoneId: String): Long {
        require(count in 1..if (unit == "MONTHS") 120 else 3650)
        val start = Instant.ofEpochMilli(now).atZone(ZoneId.of(zoneId))
        return when (unit) {
            "DAYS" -> start.plusDays(count.toLong())
            "MONTHS" -> start.plusMonths(count.toLong())
            else -> error("Invalid period unit")
        }.toInstant().toEpochMilli()
    }
    fun status(expiresAt: Long, leadMinutes: Long, now: Long): String = when {
        now >= expiresAt -> "已到期"
        now >= expiresAt - leadMinutes * MINUTE -> "即将到期"
        else -> "正常"
    }
    fun nextAfterEvent(now: Long, repeatMinutes: Long): Long? =
        if (repeatMinutes == 0L) null else Math.addExact(now, Math.multiplyExact(repeatMinutes, MINUTE))
    fun format(at: Long, zone: String): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(at))
}
