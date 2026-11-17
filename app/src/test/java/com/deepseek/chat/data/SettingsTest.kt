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
}
