package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class McpEndpointTest {

    @Test
    fun `http and https urls`() {
        assertEquals("http://1.2.3.4:9000/mcp", McpEndpoint.url("1.2.3.4", 9000, false))
        assertEquals(
            "https://mcp.example.com:9000/mcp",
            McpEndpoint.url("mcp.example.com", 9000, true)
        )
    }

    @Test
    fun `host with scheme or trailing slash is normalized`() {
        assertEquals("http://1.2.3.4:9000/mcp", McpEndpoint.url("https://1.2.3.4/", 9000, false))
        assertEquals("https://1.2.3.4:9000/mcp", McpEndpoint.url("http://1.2.3.4", 9000, true))
    }

    @Test
    fun `invalid port falls back to the default`() {
        assertEquals("http://1.2.3.4:8765/mcp", McpEndpoint.url("1.2.3.4", 0, false))
        assertEquals("https://1.2.3.4:8765/mcp", McpEndpoint.url("1.2.3.4", 70000, true))
    }
}
