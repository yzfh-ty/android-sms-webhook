package com.tyverse.smswebhook.sms

import org.junit.Assert.*
import org.junit.Test

class SmsFingerprintTest {
    @Test fun duplicatePdusAreStable() {
        assertEquals(SmsFingerprint.create("device", 1, listOf(byteArrayOf(1, 2))),
            SmsFingerprint.create("device", 1, listOf(byteArrayOf(1, 2))))
    }
    @Test fun differentSubscriptionsAreNotMerged() {
        assertNotEquals(SmsFingerprint.create("device", 1, listOf(byteArrayOf(1))),
            SmsFingerprint.create("device", 2, listOf(byteArrayOf(1))))
    }
    @Test fun deviceInstancesAreIsolated() {
        assertNotEquals(SmsFingerprint.create("a", null, listOf(byteArrayOf(1))),
            SmsFingerprint.create("b", null, listOf(byteArrayOf(1))))
    }
    @Test fun encodingPreservesPartBoundaries() {
        assertNotEquals(SmsFingerprint.create("device", 1, listOf(byteArrayOf(1), byteArrayOf(2, 3))),
            SmsFingerprint.create("device", 1, listOf(byteArrayOf(1, 2), byteArrayOf(3))))
    }
    @Test fun distinctPdusAreNotCollapsed() {
        assertNotEquals(SmsFingerprint.create("device", null, listOf(byteArrayOf(1))),
            SmsFingerprint.create("device", null, listOf(byteArrayOf(2))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingPdus() {
        SmsFingerprint.create("device", 1, emptyList())
    }
}
