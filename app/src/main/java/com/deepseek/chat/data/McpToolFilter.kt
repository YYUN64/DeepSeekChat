package com.deepseek.chat.data

/** 按设置里的关闭集合过滤工具（关闭的工具不发给模型）。 */
object McpToolFilter {

    fun enabledTools(tools: List<McpTool>, disabled: Set<String>): List<McpTool> =
        tools.filter { it.name !in disabled }
}
