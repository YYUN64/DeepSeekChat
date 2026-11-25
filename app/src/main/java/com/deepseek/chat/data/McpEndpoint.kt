package com.deepseek.chat.data

/** MCP 端点拼装：http/https 由设置里的开关决定，端口非法时回退默认值。 */
object McpEndpoint {

    fun hostOnly(host: String): String = host.trim()
        .trimEnd('/')
        .removePrefix("http://")
        .removePrefix("https://")

    fun url(host: String, port: Int, https: Boolean): String {
        val clean = hostOnly(host)
        val effectivePort = if (port in 1..65535) port else Settings.DEFAULT_MCP_PORT
        val scheme = if (https) "https" else "http"
        return "$scheme://$clean:$effectivePort/mcp"
    }
}
