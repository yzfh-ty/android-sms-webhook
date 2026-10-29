package com.tyverse.smswebhook.webhook

import org.junit.Assert.*
import org.junit.Test

class WebhookProtocolTest {
    @Test fun acceptsHttps() { assertNull(WebhookProtocol.validationError("https://example.com/hooks/sms", false)) }
    @Test fun rejectsInvalidUrl() { assertNotNull(WebhookProtocol.validationError("example", true)) }
    @Test fun rejectsEmbeddedCredentials() { assertNotNull(WebhookProtocol.validationError("https://user:password@example.com", true)) }
    @Test fun rejectsFragment() { assertNotNull(WebhookProtocol.validationError("https://example.com/#secret", true)) }
    @Test fun releaseRejectsHttp() { assertNotNull(WebhookProtocol.validationError("http://127.0.0.1:8765", false)) }
    @Test fun debugAllowsEmulatorHost() { assertNull(WebhookProtocol.validationError("http://10.0.2.2:8765/hooks/sms", true)) }
    @Test fun debugDoesNotAllowArbitraryCleartext() { assertNotNull(WebhookProtocol.validationError("http://example.com/hooks/sms", true)) }
    @Test fun signatureMatchesIndependentKnownVector() {
        assertEquals("4d583a269f4f276a3fa80ff31b5a01879a848096983222a17893d198418939aa",
            WebhookProtocol.signature("key", "1700000000", "hello"))
    }
    @Test fun signaturesCoverExactBytes() {
        assertNotEquals(WebhookProtocol.signature("key", "1", "{}"), WebhookProtocol.signature("key", "1", "{ }"))
        assertNotEquals(WebhookProtocol.signature("key", "1", "{}"), WebhookProtocol.signature("key", "2", "{}"))
    }
}
