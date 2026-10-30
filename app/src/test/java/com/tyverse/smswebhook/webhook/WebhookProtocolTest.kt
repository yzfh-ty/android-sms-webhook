package com.tyverse.smswebhook.webhook

import org.junit.Assert.*
import org.junit.Test

class WebhookProtocolTest {
    private val valid = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key"
    @Test fun acceptsOfficialRobot() { assertNull(WebhookProtocol.validationError(valid, false)) }
    @Test fun rejectsInvalidUrl() { assertNotNull(WebhookProtocol.validationError("example", true)) }
    @Test fun rejectsEmbeddedCredentials() { assertNotNull(WebhookProtocol.validationError(valid.replace("https://", "https://user:password@"), false)) }
    @Test fun rejectsFragment() { assertNotNull(WebhookProtocol.validationError("$valid#secret", false)) }
    @Test fun releaseRejectsMockServer() { assertNotNull(WebhookProtocol.validationError("http://127.0.0.1:8765/cgi-bin/webhook/send?key=test", false)) }
    @Test fun debugAllowsWecomMock() { assertNull(WebhookProtocol.validationError("http://10.0.2.2:8765/cgi-bin/webhook/send?key=test", true)) }
    @Test fun rejectsGenericWebhookEvenInDebug() { assertNotNull(WebhookProtocol.validationError("http://10.0.2.2:8765/hooks/sms", true)) }
    @Test fun rejectsOtherProviders() { assertNotNull(WebhookProtocol.validationError("https://example.com/cgi-bin/webhook/send?key=test", false)) }
    @Test fun rejectsLookalikeHost() { assertNotNull(WebhookProtocol.validationError(valid.replace("weixin.qq.com", "weixin.qq.com.evil.test"), false)) }
    @Test fun requiresSendPath() { assertNotNull(WebhookProtocol.validationError(valid.replace("/send?", "/upload_media?"), false)) }
    @Test fun requiresKey() { assertNotNull(WebhookProtocol.validationError(valid.substringBefore('?'), false)) }
    @Test fun rejectsEmptyKey() { assertNotNull(WebhookProtocol.validationError(valid.replace("test-key", ""), false)) }
    @Test fun rejectsDuplicateKey() { assertNotNull(WebhookProtocol.validationError("$valid&key=second", false)) }
    @Test fun rejectsExtraParameters() { assertNotNull(WebhookProtocol.validationError("$valid&target=other", false)) }
    @Test fun requiresDefaultTlsPort() { assertNotNull(WebhookProtocol.validationError(valid.replace(".com/", ".com:8443/"), false)) }
    @Test fun displayMasksKey() { assertFalse(WebhookProtocol.maskedUrl(valid).contains("test-key")) }
    @Test fun botPacingIdentityIsIndependentOfUrlEncoding() {
        assertEquals(WebhookProtocol.robotId(valid), WebhookProtocol.robotId(valid.replace("test-key", "%74est-key")))
        assertNotEquals(WebhookProtocol.robotId(valid), WebhookProtocol.robotId(valid.replace("test-key", "other-key")))
    }
    @Test fun transientBusinessCodesAreExplicit() {
        assertTrue(WebhookProtocol.retryableBusinessCode(45009)); assertTrue(WebhookProtocol.retryableBusinessCode(-1))
        assertFalse(WebhookProtocol.retryableBusinessCode(93000)); assertFalse(WebhookProtocol.retryableBusinessCode(0))
    }
}
