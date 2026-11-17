package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCounterTest {

    private fun userMsg(text: String) = Msg(id = "u1", role = Role.USER, text = text)

    private fun assistantMsg(prompt: Int, completion: Int, total: Int = prompt + completion) = Msg(
        id = "a1",
        role = Role.ASSISTANT,
        text = "Hi",
        usage = Usage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
    )

    @Test
    fun `no usage falls back to pure estimate and is marked estimated`() {
        val messages = listOf(userMsg("hello world"))
        val usage = TokenCounter.contextUsage(messages)
        assertEquals(TokenCounter.estimateAll(messages), usage.tokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `empty conversation is zero and estimated`() {
        val usage = TokenCounter.contextUsage(emptyList())
        assertEquals(0, usage.tokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `usage anchor wins over the heuristic`() {
        val messages = listOf(userMsg("Say hi in one word."), assistantMsg(prompt = 10, completion = 1))
        val usage = TokenCounter.contextUsage(messages)
        // 实测 10 + 1，而不是估算出来的 14
        assertEquals(11, usage.tokens)
        assertFalse(usage.estimated)
    }

    @Test
    fun `messages after the anchor are added as estimate`() {
        val trailing = userMsg("next question, which is longer than the previous one")
        val messages = listOf(
            userMsg("Say hi in one word."),
            assistantMsg(prompt = 10, completion = 1),
            trailing
        )
        val usage = TokenCounter.contextUsage(messages)
        assertEquals(11 + TokenCounter.estimateMessage(trailing), usage.tokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `zero totalTokens falls back to prompt plus completion`() {
        val messages = listOf(
            userMsg("hi"),
            assistantMsg(prompt = 20, completion = 5, total = 0)
        )
        assertEquals(25, TokenCounter.contextUsage(messages).tokens)
    }

    @Test
    fun `trim keeps the newest messages and never starts with assistant`() {
        val messages = (1..10).map { Msg(id = "m$it", role = if (it % 2 == 0) Role.ASSISTANT else Role.USER, text = "x".repeat(400)) }
        val kept = TokenCounter.trim(messages, budget = 800, maxMessages = 50)
        assertTrue(kept.isNotEmpty())
        assertEquals(Role.USER, kept.first().role)
        assertEquals(messages.last().id, kept.last().id)
    }
}
