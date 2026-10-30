package com.tyverse.smswebhook.esim

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class EsimPolicyTest {
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    @Test fun renewalStartsFromConfirmationRatherThanOldExpiry() {
        val now = at("2026-10-30T10:00:00+08:00[Asia/Shanghai]")
        assertEquals(at("2026-11-29T10:00:00+08:00[Asia/Shanghai]"), EsimPolicy.renewAt(now, 30, "DAYS", "Asia/Shanghai"))
    }
    @Test fun monthEndUsesLastDayAndLeapYear() {
        assertEquals(at("2028-02-29T10:00:00+08:00[Asia/Shanghai]"),
            EsimPolicy.renewAt(at("2028-01-31T10:00:00+08:00[Asia/Shanghai]"), 1, "MONTHS", "Asia/Shanghai"))
        assertEquals(at("2027-02-28T10:00:00+08:00[Asia/Shanghai]"),
            EsimPolicy.renewAt(at("2027-01-31T10:00:00+08:00[Asia/Shanghai]"), 1, "MONTHS", "Asia/Shanghai"))
    }
    @Test fun calendarDaysPreserveLocalTimeAcrossDaylightSaving() {
        val start = at("2026-03-07T10:00:00-05:00[America/New_York]")
        val end = EsimPolicy.renewAt(start, 1, "DAYS", "America/New_York")
        assertEquals(at("2026-03-08T10:00:00-04:00[America/New_York]"), end)
        assertEquals(23 * 3_600_000L, end - start)
    }
    @Test fun statusBoundariesAndZeroLead() {
        val deadline = 10_000_000L
        assertEquals("正常", EsimPolicy.status(deadline, 60, deadline - 3_600_001))
        assertEquals("即将到期", EsimPolicy.status(deadline, 60, deadline - 3_600_000))
        assertEquals("正常", EsimPolicy.status(deadline, 0, deadline - 1))
        assertEquals("已到期", EsimPolicy.status(deadline, 0, deadline))
    }
    @Test fun missedIntervalsProduceOnlyOneFutureOpportunity() {
        assertNull(EsimPolicy.nextAfterEvent(999_000, 0))
        assertEquals(999_000 + 86_400_000L, EsimPolicy.nextAfterEvent(999_000, 1440))
    }
    @Test(expected = IllegalArgumentException::class) fun zeroPeriodRejected() { EsimPolicy.renewAt(0, 0, "DAYS", "UTC") }
    @Test(expected = IllegalArgumentException::class) fun oversizedMonthPeriodRejected() { EsimPolicy.renewAt(0, 121, "MONTHS", "UTC") }
}
