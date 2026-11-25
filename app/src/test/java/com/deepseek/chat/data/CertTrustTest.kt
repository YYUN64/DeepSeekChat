package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CertTrustTest {

    @Test
    fun `untrusted certificate needs confirmation`() {
        assertEquals(CertDecision.NEEDS_CONFIRM, CertTrust.evaluate("", "35:69:FB:33"))
    }

    @Test
    fun `trusted certificate with same fingerprint is ok`() {
        assertEquals(CertDecision.OK, CertTrust.evaluate("35:69:FB:33", "35:69:FB:33"))
        assertEquals(CertDecision.OK, CertTrust.evaluate("3569fb33", "35:69:FB:33"))
    }

    @Test
    fun `trusted certificate with a different fingerprint is a mismatch not a confirmation`() {
        assertEquals(CertDecision.MISMATCH, CertTrust.evaluate("35:69:FB:33", "F9:1C:1E:17"))
        // 关键：mismatch 不能被当成首次确认
        assertEquals(false, CertTrust.evaluate("35:69:FB:33", "F9:1C:1E:17") == CertDecision.NEEDS_CONFIRM)
    }

    @Test
    fun `after trusting the new fingerprint the same certificate is ok`() {
        val actual = "F9:1C:1E:17"
        val pinned = actual // 信任并重试时存下的 pin
        assertEquals(CertDecision.OK, CertTrust.evaluate(pinned, actual))
    }
}
