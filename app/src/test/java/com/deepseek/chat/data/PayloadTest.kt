package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {

    private fun toolCallAssistant() = Msg(
        id = "a1",
        role = Role.ASSISTANT,
        text = "我来查一下",
        toolCallsJson = "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{}\"}}]",
        intermediate = true
    )

    private fun toolMessage(text: String = "退出码 0") = Msg(
        id = "t1",
        role = Role.TOOL,
        toolCallId = "call_1",
        text = text
    )

    private fun stepCard() = Msg(
        id = "s1",
        role = Role.TOOL_STEP,
        toolCallId = "call_1",
        toolName = "run_command",
        toolArgs = "{}"
    )

    @Test
    fun `payload skips tool step cards but keeps the tool exchange`() {
        val history = listOf(
            Msg(id = "u1", role = Role.USER, text = "跑个命令"),
            stepCard(),
            toolCallAssistant(),
            toolMessage(),
            Msg(id = "a2", role = Role.ASSISTANT, text = "完成")
        )
        val roles = DeepSeekClient.payloadMessages(history).map { msg ->
            if (msg.role == Role.ASSISTANT && msg.toolCallsJson != null) {
                "assistant(tool_calls)"
            } else {
                msg.role.wire
            }
        }
        assertEquals(
            listOf("user", "assistant(tool_calls)", "tool", "assistant"),
            roles
        )
    }

    @Test
    fun `payload keeps assistant tool calls even when text is blank`() {
        val history = listOf(
            Msg(id = "u1", role = Role.USER, text = "go"),
            Msg(
                id = "a1",
                role = Role.ASSISTANT,
                text = "",
                toolCallsJson = "[{\"id\":\"call_1\"}]",
                intermediate = true
            ),
            toolMessage("执行已中断（用户停止）")
        )
        assertEquals(3, DeepSeekClient.payloadMessages(history).size)
    }

    // ------------------------------------------------------------ 附件 payload

    private val block = "【文件：notes.md】\n```text\nThe secret code is BLUE-WHALE-42\n```"

    @Test
    fun `user content is the typed text followed by the file blocks`() {
        val msg = Msg(
            id = "u1",
            role = Role.USER,
            text = "看看这个",
            attachments = listOf(AttachmentRef("notes.md", 51200)),
            fileBlocks = block
        )
        assertEquals("看看这个\n\n$block", DeepSeekClient.payloadContent(msg))
        // 气泡正文里没有文件内容
        assertEquals("看看这个", msg.text)
    }

    @Test
    fun `file only message sends just the file blocks`() {
        val msg = Msg(
            id = "u1",
            role = Role.USER,
            attachments = listOf(AttachmentRef("notes.md", 51200)),
            fileBlocks = block
        )
        assertEquals(block, DeepSeekClient.payloadContent(msg))
    }

    @Test
    fun `message without attachments is sent as before`() {
        val msg = Msg(id = "u1", role = Role.USER, text = "普通消息")
        assertNull(msg.fileBlocks)
        assertEquals("普通消息", DeepSeekClient.payloadContent(msg))
    }

    @Test
    fun `old inline file message is still sent verbatim`() {
        // 1.9.0 及以前：文件内容直接写在 text 里，没有 fileBlocks
        val inline = "看看这个\n\n$block"
        val msg = Msg(id = "u-old", role = Role.USER, text = inline)
        assertNull(msg.fileBlocks)
        assertTrue(msg.attachments.isEmpty())
        assertEquals(inline, DeepSeekClient.payloadContent(msg))
    }

    @Test
    fun `assistant content is not touched by file blocks`() {
        val msg = Msg(id = "a1", role = Role.ASSISTANT, text = "回答", fileBlocks = block)
        assertEquals("回答", DeepSeekClient.payloadContent(msg))
    }

    @Test
    fun `token counter counts the file blocks`() {
        val blocks = "x".repeat(4000)
        val plain = Msg(id = "u1", role = Role.USER, text = "你好")
        val withBlocks = plain.copy(id = "u2", text = "你好", fileBlocks = blocks)
        val delta = TokenCounter.estimateMessage(withBlocks) - TokenCounter.estimateMessage(plain)
        assertTrue("fileBlocks 应该计入 token，实际差 $delta", delta >= 900)
    }

    @Test
    fun `trim keeps the user message that carries file blocks`() {
        val history = listOf(
            Msg(id = "u1", role = Role.USER, text = "问", fileBlocks = "y".repeat(2000)),
            Msg(id = "a1", role = Role.ASSISTANT, text = "答")
        )
        val trimmed = TokenCounter.trim(history, budget = 2048)
        assertEquals(Role.USER, trimmed.first().role)
        assertTrue(trimmed.first().fileBlocks!!.isNotEmpty())
    }

    @Test
    fun `trim never starts with a dangling tool exchange`() {        val history = listOf(
            Msg(id = "u1", role = Role.USER, text = "第一个问题 " + "x".repeat(400)),
            toolCallAssistant(),
            toolMessage("y".repeat(400)),
            Msg(id = "a2", role = Role.ASSISTANT, text = "回答"),
            Msg(id = "u2", role = Role.USER, text = "第二个问题"),
            Msg(id = "a3", role = Role.ASSISTANT, text = "第二个回答")
        )
        // 预算只够最后两三条
        val trimmed = TokenCounter.trim(history, budget = 2048)
        assertEquals(Role.USER, trimmed.first().role)
        val roles = trimmed.map { it.role.wire }
        // 不允许出现 tool 在没有前面 assistant(tool_calls) 的情况下作为开头
        assertTrue(roles.first() == "user")
        // 成对：assistant(tool_calls) 出现时，后面必须紧跟 tool
        trimmed.forEachIndexed { index, msg ->
            if (msg.role == Role.ASSISTANT && msg.toolCallsJson != null) {
                val next = trimmed.getOrNull(index + 1)
                assertTrue("tool_calls 必须紧跟 tool 结果", next?.role == Role.TOOL)
            }
        }
    }
}
