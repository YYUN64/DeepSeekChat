package com.deepseek.chat.data

import org.junit.Assert.assertEquals
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

    @Test
    fun `trim never starts with a dangling tool exchange`() {
        val history = listOf(
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
