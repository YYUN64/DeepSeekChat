package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMarkdownTest {

    @Test
    fun `user and assistant become markdown blocks`() {
        val markdown = ConversationMarkdown.render(
            listOf(
                Msg(id = "1", role = Role.USER, text = "你好"),
                Msg(id = "2", role = Role.ASSISTANT, text = "你好，有什么可以帮你？")
            )
        )

        assertEquals("**用户**：你好\n\n**助手**：你好，有什么可以帮你？", markdown)
    }

    @Test
    fun `image message uses a placeholder`() {
        val markdown = ConversationMarkdown.render(
            listOf(
                Msg(
                    id = "1",
                    role = Role.USER,
                    text = "看图",
                    images = listOf(ImageRef(fileName = "a.jpg"))
                )
            )
        )

        assertEquals("**用户**：看图\n\n[图片]", markdown)
    }

    @Test
    fun `tool step becomes a quote line`() {
        val markdown = ConversationMarkdown.render(
            listOf(
                Msg(
                    id = "1",
                    role = Role.TOOL_STEP,
                    toolName = "calc",
                    toolArgs = "{\"expression\":\"1+1\"}",
                    text = "2"
                )
            )
        )

        assertEquals("> 调用工具 calc({\"expression\":\"1+1\"}) → 2", markdown)
    }

    @Test
    fun `empty messages are skipped`() {
        val markdown = ConversationMarkdown.render(
            listOf(
                Msg(id = "1", role = Role.ASSISTANT),
                Msg(id = "2", role = Role.USER),
                Msg(id = "3", role = Role.SYSTEM, text = "system prompt")
            )
        )

        assertEquals("", markdown)
        assertTrue(markdown.isEmpty())
    }
}
