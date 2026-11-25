package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTlsTest {

    @Test
    fun `fingerprint is sha256 in colon-separated uppercase hex`() {
        // SHA-256(32 个 0 字节) = 66687aad...（已知向量）
        val zeros = ByteArray(32)
        assertEquals(
            "66:68:7A:AD:F8:62:BD:77:6C:8F:C1:8B:8E:9F:8E:20:" +
                "08:97:14:85:6E:E2:33:B3:90:2A:59:1D:0D:5F:29:25",
            McpTls.formatFingerprint(zeros)
        )
        val formatted = McpTls.formatFingerprint(ByteArray(32) { 0xAB.toByte() })
        val groups = formatted.split(":")
        assertEquals(32, groups.size)
        assertTrue(groups.all { it.length == 2 && it == it.uppercase() })
    }

    @Test
    fun `matching ignores case and separators`() {
        val actual = "AA:BB:CC:DD"
        assertTrue(McpTls.matches("AA:BB:CC:DD", actual))
        assertTrue(McpTls.matches("aa:bb:cc:dd", actual))
        assertTrue(McpTls.matches("aabbccdd", actual))
        assertFalse(McpTls.matches("", actual))
        assertFalse(McpTls.matches("AA:BB:CC:DE", actual))
    }

    @Test
    fun `settings default to https with no pinned certificate`() {
        val settings = Settings()
        assertTrue(settings.mcpHttps)
        assertEquals("", settings.mcpCertFingerprint)
    }
}
