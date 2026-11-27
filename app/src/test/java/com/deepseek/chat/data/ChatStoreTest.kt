package com.deepseek.chat.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `attachments and file blocks survive a round trip`() {
        val store = store()
        val blocks = "【文件：notes.md】\n```text\nThe secret code is BLUE-WHALE-42\n```"
        val conversation = Conversation(
            id = "conv-files",
            title = "看看文件",
            messages = listOf(
                Msg(
                    id = "u1",
                    role = Role.USER,
                    text = "看看这个",
                    attachments = listOf(
                        AttachmentRef("notes.md", 51200L),
                        AttachmentRef("big.txt", 512000L, truncated = true)
                    ),
                    fileBlocks = blocks
                )
            )
        )

        store.save(conversation)
        val msg = store.load("conv-files")!!.messages[0]

        assertEquals("看看这个", msg.text)
        assertEquals(blocks, msg.fileBlocks)
        assertEquals(2, msg.attachments.size)
        assertEquals("notes.md", msg.attachments[0].name)
        assertEquals(51200L, msg.attachments[0].sizeBytes)
        assertFalse(msg.attachments[0].truncated)
        assertTrue(msg.attachments[1].truncated)
        // 文件内容只在 fileBlocks 里，不会再被塞回气泡正文
        assertFalse(msg.text.contains("BLUE-WHALE-42"))
        assertTrue(msg.modelText.contains("BLUE-WHALE-42"))
    }

    @Test
    fun `old conversation json without attachments loads unchanged`() {
        // 1.9.0 存下来的会话：没有 attachments / fileBlocks 字段，文件内容直接内联在 text 里
        val dir = File(root, "conversations").apply { mkdirs() }
        val inline = "看看这个\n\n【文件：notes.md】\n```text\nBLUE-WHALE-42\n```"
        val raw = """
            {"id":"conv-old","title":"旧会话","createdAt":1,"updatedAt":2,"model":"deepseek-flash",
             "thinking":true,"effort":"high",
             "messages":[{"id":"u1","role":"user","text":${JSONObject.quote(inline)},
                          "reasoning":"","createdAt":3,"images":[]}]}
        """.trimIndent()
        File(dir, "conv-old.json").writeText(raw, Charsets.UTF_8)

        val msg = store().load("conv-old")!!.messages[0]
        assertEquals(inline, msg.text)
        assertNull(msg.fileBlocks)
        assertTrue(msg.attachments.isEmpty())
        // 老消息发给模型的仍是原文（内容照样能读到）
        assertEquals(inline, msg.modelText)
    }

    @Test
    fun `tool step keeps name args result and failure marker`() {
        val store = store()
        val conversation = Conversation(
            id = "conv-tools",
            messages = listOf(
                Msg(id = "u1", role = Role.USER, text = "算一下 1234*5678"),
                Msg(
                    id = "prelude",
                    role = Role.ASSISTANT,
                    text = "我用 calc 算一下。",
                    reasoning = "需要调用计算工具",
                    intermediate = true
                ),
                Msg(
                    id = "step-ok",
                    role = Role.TOOL_STEP,
                    text = "7006652",
                    toolCallId = "call_1",
                    toolName = "calc",
                    toolArgs = "{\"expression\":\"1234*5678\"}"
                ),
                Msg(
                    id = "step-fail",
                    role = Role.TOOL_STEP,
                    text = "",
                    toolCallId = "call_2",
                    toolName = "read_file",
                    toolArgs = "{\"path\":\"/nope\"}",
                    error = "调用失败：file not found"
                ),
                Msg(id = "final", role = Role.ASSISTANT, text = "结果是 7006652")
            )
        )

        store.save(conversation)
        val loaded = store.load("conv-tools")!!

        assertEquals(5, loaded.messages.size)
        val prelude = loaded.messages[1]
        assertTrue(prelude.intermediate)
        assertEquals("需要调用计算工具", prelude.reasoning)

        val ok = loaded.messages[2]
        assertEquals(Role.TOOL_STEP, ok.role)
        assertEquals("calc", ok.toolName)
        assertEquals("{\"expression\":\"1234*5678\"}", ok.toolArgs)
        assertEquals("7006652", ok.text)
        assertEquals("call_1", ok.toolCallId)
        assertNull(ok.error)

        val failed = loaded.messages[3]
        assertEquals(Role.TOOL_STEP, failed.role)
        assertEquals("read_file", failed.toolName)
        assertEquals("调用失败：file not found", failed.error)
        assertEquals("结果是 7006652", loaded.messages[4].text)
    }
}
