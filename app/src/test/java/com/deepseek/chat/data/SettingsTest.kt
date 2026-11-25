package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {

    @Test
    fun `port parser accepts only 1 to 65535`() {
        assertEquals(1, Settings.parsePort("1"))
        assertEquals(8765, Settings.parsePort("8765"))
        assertEquals(65535, Settings.parsePort("65535"))
        // 空格容忍
        assertEquals(8080, Settings.parsePort(" 8080 "))
    }

    @Test
    fun `port parser rejects empty zero too large and non numeric`() {
        // 空串是“允许清空”的关键：必须返回 null，而不是回退到旧值
        assertNull(Settings.parsePort(""))
        assertNull(Settings.parsePort("   "))
        assertNull(Settings.parsePort("0"))
        assertNull(Settings.parsePort("65536"))
        assertNull(Settings.parsePort("99999"))
        assertNull(Settings.parsePort("12a3"))
        assertNull(Settings.parsePort("-1"))
        assertNull(Settings.parsePort("999999999999999999999"))
    }

    @Test
    fun `mcp token and rounds defaults`() {
        val settings = Settings()
        assertEquals("", settings.mcpToken)
        assertEquals(4, settings.mcpMaxRounds)
        assertEquals(4, settings.effectiveMcpRounds())
    }

    @Test
    fun `effective mcp rounds is clamped to 1 through 64`() {
        assertEquals(2, Settings(mcpMaxRounds = 2).effectiveMcpRounds())
        assertEquals(64, Settings(mcpMaxRounds = 64).effectiveMcpRounds())
        assertEquals(1, Settings(mcpMaxRounds = 0).effectiveMcpRounds())
        assertEquals(64, Settings(mcpMaxRounds = 999).effectiveMcpRounds())
    }

    @Test
    fun `mcp token maps to bearer header only when non blank`() {
        assertNull(McpClient.authorizationHeader(""))
        assertNull(McpClient.authorizationHeader("   "))
        assertEquals("Bearer secret", McpClient.authorizationHeader(" secret "))
    }
}
