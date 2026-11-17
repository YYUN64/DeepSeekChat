package com.deepseek.chat.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话落盘的往返测试，重点盯 reasoning（思考过程）不能丢：
 * 退出会话后思考过程消失就是从这条路径开始的。
 */
class ChatStoreTest {

    private val root: File = Files.createTempDirectory("chatstore-test").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = ChatStore(root)

    @Test
    fun `reasoning survives a save load round trip`() {
        val store = store()
        val thought = "先分析题意：把 sqrt(2) 写成 p/q，再推出矛盾。\n第二段：推广到 n 次根。"
        val conversation = Conversation(
            id = "conv-reasoning",
            title = "证明 sqrt 2 是无理数",
            model = Models.FLASH,
            thinking = true,
            effort = Reasoning.HIGH,
            messages = listOf(
                Msg(id = "u1", role = Role.USER, text = "帮我证明一下"),
                Msg(
                    id = "a1",
                    role = Role.ASSISTANT,
                    text = "答案正文",
                    reasoning = thought,
                    usage = Usage(promptTokens = 10, completionTokens = 20, totalTokens = 30, reasoningTokens = 7),
                    model = Models.FLASH
                )
            )
        )

        store.save(conversation)
        val loaded = store.load("conv-reasoning")

        assertNotNull(loaded)
        assertEquals(2, loaded!!.messages.size)
        val assistant = loaded.messages[1]
        assertEquals(thought, assistant.reasoning)
        assertEquals("答案正文", assistant.text)
        assertEquals(Usage(10, 20, 30, 7), assistant.usage)
        assertEquals(Models.FLASH, assistant.model)
    }

    @Test
    fun `stopped partial keeps both text and reasoning plus the stopped marker`() {
        val store = store()
        val conversation = Conversation(
            id = "conv-partial",
            messages = listOf(
                Msg(id = "u1", role = Role.USER, text = "问题"),
                Msg(
                    id = "a-partial",
                    role = Role.ASSISTANT,
                    text = "写到一半的答案",
                    reasoning = "写到一半的思考过程",
                    error = "已停止"
                )
            )
        )

        store.save(conversation)
        val loaded = store.load("conv-partial")!!

        val partial = loaded.messages[1]
        assertEquals("写到一半的思考过程", partial.reasoning)
        assertEquals("写到一半的答案", partial.text)
        assertEquals("已停止", partial.error)
    }

    @Test
    fun `blank reasoning round trips as empty string and messages keep their order`() {
        val store = store()
        val conversation = Conversation(
            id = "conv-plain",
            messages = listOf(
                Msg(id = "u1", role = Role.USER, text = "一"),
                Msg(id = "a1", role = Role.ASSISTANT, text = "二"),
                Msg(id = "u2", role = Role.USER, text = "三")
            )
        )

        store.save(conversation)
        val loaded = store.load("conv-plain")!!

        assertEquals(listOf("u1", "a1", "u2"), loaded.messages.map { it.id })
        assertTrue(loaded.messages[1].reasoning.isEmpty())
        assertNull(loaded.messages[1].error)
    }
}
