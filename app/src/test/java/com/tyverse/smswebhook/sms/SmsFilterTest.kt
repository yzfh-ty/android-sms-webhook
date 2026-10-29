package com.tyverse.smswebhook.sms

import org.junit.Assert.*
import org.junit.Test

class SmsFilterTest {
    @Test fun emptyRulesAllowMessages() { assertNull(SmsFilter.rejectionReason("10086", "hello", "", "", "")) }
    @Test fun allowlistMatchesFullSender() { assertNotNull(SmsFilter.rejectionReason("100861", "hello", "10086", "", "")) }
    @Test fun allowedSenderPasses() { assertNull(SmsFilter.rejectionReason("10086", "hello", "10000\n10086", "", "")) }
    @Test fun senderMatchingIgnoresWhitespaceAndCase() { assertNull(SmsFilter.rejectionReason("Bank", "hello", " bank ", "", "")) }
    @Test fun anyIncludeKeywordPasses() { assertNull(SmsFilter.rejectionReason("bank", "Your CODE is ready", "", "验证码\ncode", "")) }
    @Test fun missingIncludeKeywordRejects() { assertNotNull(SmsFilter.rejectionReason("bank", "hello", "", "验证码", "")) }
    @Test fun exclusionWinsOverInclusion() { assertNotNull(SmsFilter.rejectionReason("bank", "code ADVERTISEMENT", "bank", "code", "advertisement")) }
    @Test fun ruleGroupsAreCombinedWithAnd() { assertNotNull(SmsFilter.rejectionReason("other", "code", "bank", "code", "")) }
    @Test fun regexMetacharactersAreLiteral() { assertNotNull(SmsFilter.rejectionReason("bank", "abc", "", ".*", "")) }
    @Test fun ruleCountsAreBounded() { assertNotNull(SmsFilter.validationError((1..51).joinToString("\n"))) }
    @Test fun ruleLengthIsBounded() { assertNotNull(SmsFilter.validationError("x".repeat(129))) }
    @Test fun emptyLinesAndDuplicatesAreIgnored() { assertEquals(listOf("a", "b"), SmsFilter.entries(" a \n\n b\na")) }
}
