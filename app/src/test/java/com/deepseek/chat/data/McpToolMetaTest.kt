package com.deepseek.chat.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolMetaTest {

    private fun tool(name: String) = McpTool(name, "desc of $name", "{}")

    @Test
    fun `disabled tools are filtered out`() {
        val tools = listOf(tool("get_time"), tool("run_command"), tool("read_file"))
        val enabled = McpToolFilter.enabledTools(tools, setOf("run_command"))
        assertEquals(listOf("get_time", "read_file"), enabled.map { it.name })
    }

    @Test
    fun `no disabled tools keeps everything`() {
        val tools = listOf(tool("a"), tool("b"))
        assertEquals(tools, McpToolFilter.enabledTools(tools, emptySet()))
    }

    @Test
    fun `parses meta with code and duration`() {
        val meta = ToolCallMeta.parse(
            JSONObject("{\"dispatched\":true,\"code\":2,\"durationMs\":123}")
        )
        assertTrue(meta!!.dispatched)
        assertEquals(2, meta.code)
        assertEquals(123L, meta.durationMs)
        assertFalse(meta.timedOut)
    }

    @Test
    fun `parses timeout meta with null code`() {
        val meta = ToolCallMeta.parse(
            JSONObject("{\"dispatched\":true,\"code\":null,\"durationMs\":5007,\"timeout\":true,\"timeoutSeconds\":5}")
        )
        assertTrue(meta!!.timedOut)
        assertNull(meta.code)
        assertEquals(5, meta.timeoutSeconds)
    }

    @Test
    fun `formats duration`() {
        assertEquals("123ms", ToolStatus.formatDuration(123))
        assertEquals("1.2s", ToolStatus.formatDuration(1234))
        assertNull(ToolStatus.formatDuration(null))
    }

    @Test
    fun `formats success failure and timeout labels`() {
        val ok = ToolCallMeta(dispatched = true, code = 0, durationMs = 123, timedOut = false, timeoutSeconds = null)
        assertEquals("完成 · 123ms", ToolStatus.label(ok, false))

        val failed = ToolCallMeta(dispatched = true, code = 2, durationMs = 45, timedOut = false, timeoutSeconds = null)
        assertEquals("失败 · 退出码 2 · 45ms", ToolStatus.label(failed, true))

        val timedOut = ToolCallMeta(dispatched = true, code = null, durationMs = 5007, timedOut = true, timeoutSeconds = 5)
        assertEquals("超时（5s）", ToolStatus.label(timedOut, true))

        assertEquals("完成", ToolStatus.label(null, false))
        assertEquals("失败", ToolStatus.label(null, true))
    }
}
