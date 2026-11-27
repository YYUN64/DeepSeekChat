package com.deepseek.chat.data

/**
 * 把会话消息转成可粘贴的 Markdown。默认不包含思考过程（太长），
 * 图片用 `[图片]` 占位，工具步骤用引用行 `> 调用工具 name(args)`。
 */
object ConversationMarkdown {

    fun render(messages: List<Msg>): String =
        messages.mapNotNull { block(it) }.joinToString("\n\n")

    private fun block(message: Msg): String? {
        if (message.role == Role.TOOL_STEP) {
            val name = message.toolName?.takeIf { it.isNotBlank() } ?: "tool"
            val args = message.toolArgs?.takeIf { it.isNotBlank() } ?: "{}"
            val line = StringBuilder("> 调用工具 ").append(name).append('(').append(args).append(')')
            val error = message.error
            if (!error.isNullOrBlank()) {
                line.append(" → 失败：").append(error.replace('\n', ' '))
            } else if (message.text.isNotBlank()) {
                line.append(" → ").append(message.text.replace('\n', ' ').take(200))
            }
            return line.toString()
        }
        val label = when (message.role) {
            Role.USER -> "**用户**"
            Role.ASSISTANT -> "**助手**"
            else -> return null
        }
        val parts = mutableListOf<String>()
        if (message.text.isNotBlank()) parts.add(message.text.trim())
        for (image in message.images) {
            if (image.fileName.isNotBlank()) parts.add("[图片]")
        }
        // 附件只写「名字 + 大小」，不把内容（msg.fileBlocks）导出去
        for (item in message.attachments) {
            parts.add("【附件：${item.name}（${TextFiles.sizeLabel(item.sizeBytes)}）】")
        }
        if (parts.isEmpty()) return null
        return "$label：" + parts.joinToString("\n\n")
    }
}
