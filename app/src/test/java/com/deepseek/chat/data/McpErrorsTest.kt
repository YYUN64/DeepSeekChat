package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class McpErrorsTest {

    private class Fake(message: String) : RuntimeException(message)

    @Test
    fun `clock skew gets the auto-time hint`() {
        val text = McpErrors.friendly(Fake("签名鉴权失败：时间戳过期（允许 ±300 秒）"))
        assertTrue(text.contains("自动设置时间"))
        assertTrue(text.contains("日期和时间"))
    }

    @Test
    fun `closed connection and refused are friendly`() {
        assertTrue(McpErrors.friendly(Fake("connection closed")).contains("连接被关闭"))
        assertTrue(McpErrors.friendly(ConnectException("ECONNREFUSED")).contains("端口没有服务在监听"))
        assertTrue(McpErrors.friendly(UnknownHostException("bad host")).contains("地址解析失败"))
    }

    @Test
    fun `timeout wins over generic messages`() {
        assertEquals(
            "连接超时（3 秒），请检查地址/端口/网络",
            McpErrors.friendly(SocketTimeoutException("timeout"))
        )
    }

    @Test
    fun `unknown errors keep the original text`() {
        val text = McpErrors.friendly(Fake("something odd"))
        assertTrue(text.startsWith("something odd"))
        assertTrue(text.contains("Fake"))
    }
}
