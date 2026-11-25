package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpKeyStatusTest {

    @Test
    fun `status label shows generated state with short key id`() {
        assertEquals("未生成", McpKeyStatus.label(null))
        assertEquals("未生成", McpKeyStatus.label(""))
        assertEquals("已生成 · key id 73b3e492…", McpKeyStatus.label("73b3e492882ec5ad"))
    }

    @Test
    fun `short id keeps short values and truncates long ones`() {
        assertEquals("abc", McpKeyStatus.shortId("abc"))
        assertEquals("12345678", McpKeyStatus.shortId("12345678"))
        assertEquals("12345678…", McpKeyStatus.shortId("1234567890abcdef"))
    }

    @Test
    fun `fingerprint groups key id by four`() {
        assertEquals("73b3 e492 882e c5ad", McpKeyStatus.fingerprint("73b3e492882ec5ad"))
        assertEquals("", McpKeyStatus.fingerprint(null))
        assertEquals("", McpKeyStatus.fingerprint("  "))
    }

    @Test
    fun `settings never exposes key material for persistence`() {
        // Settings 是要落盘的对象：里面不允许出现任何 mcp 密钥/token 字段
        val names = Settings::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { name ->
            name.startsWith("mcp") && (
                name.contains("key") || name.contains("token") || name.contains("pub")
                )
        })
        assertTrue(names.contains("mcphost"))
        assertTrue(names.contains("mcpmaxrounds"))
    }
}
