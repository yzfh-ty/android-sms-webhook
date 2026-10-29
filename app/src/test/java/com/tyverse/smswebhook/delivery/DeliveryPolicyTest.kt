package com.tyverse.smswebhook.delivery

import org.junit.Assert.*
import org.junit.Test

class DeliveryPolicyTest {
    @Test fun serverFailuresAndRateLimitsRetry() {
        listOf(408, 429, 500, 502, 503, 599).forEach { assertTrue(DeliveryPolicy.retryable(it)) }
    }
    @Test fun permanentErrorsDoNotRetry() {
        listOf(200, 301, 400, 401, 403, 404, 413).forEach { assertFalse(DeliveryPolicy.retryable(it)) }
    }
    @Test fun firstRetryWaitsThirtySeconds() {
        assertEquals(31_000, DeliveryPolicy.nextAttempt(1_000, 1, jitter = 0.0))
    }
    @Test fun retryDoubles() {
        assertEquals(61_000, DeliveryPolicy.nextAttempt(1_000, 2, jitter = 0.0))
    }
    @Test fun baseBackoffIsCapped() {
        assertEquals(3_601_000, DeliveryPolicy.nextAttempt(1_000, 20, jitter = 0.0))
    }
    @Test fun jitterDoesNotRunEarly() {
        assertEquals(37_000, DeliveryPolicy.nextAttempt(1_000, 1, jitter = 1.0))
    }
    @Test fun honorsLongRetryAfter() {
        assertEquals(9_000_000, DeliveryPolicy.nextAttempt(1_000, 1, 9_000_000, 0.0))
    }
    @Test fun shortRetryAfterCannotDefeatBackoff() {
        assertEquals(31_000, DeliveryPolicy.nextAttempt(1_000, 1, 2_000, 0.0))
    }
    @Test fun parsesRetryAfterSeconds() { assertEquals(61_000L, DeliveryPolicy.retryAfter("60", 1_000)) }
    @Test fun rejectsNegativeRetryAfter() { assertNull(DeliveryPolicy.retryAfter("-1", 1_000)) }
    @Test fun rejectsMalformedRetryAfter() { assertNull(DeliveryPolicy.retryAfter("not-a-date", 1_000)) }
    @Test fun hugeRetryAfterDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, DeliveryPolicy.retryAfter(Long.MAX_VALUE.toString(), 1_000))
    }
    @Test fun parsesHttpDate() {
        assertEquals(1_445_412_480_000L, DeliveryPolicy.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", 1_000))
    }
    @Test fun expiryHasExactBoundary() {
        assertFalse(DeliveryPolicy.expired(1_000, DeliveryPolicy.MAX_AGE_MS + 999))
        assertTrue(DeliveryPolicy.expired(1_000, DeliveryPolicy.MAX_AGE_MS + 1_000))
    }
}
