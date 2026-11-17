package com.deepseek.chat.data

import kotlin.math.max

/**
 * 粗略 token 估算：中日韩字符按 1 token 计，其它字符按 4 字符 1 token 计。
 * 只用于上下文裁剪，不要求精确。
 */
object TokenCounter {

    private const val IMAGE_TOKENS = 1024

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x2E80..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0xFF00..0xFFEF ||
            code in 0x3000..0x303F
    }

    fun estimateMessage(msg: Msg): Int {
        var tokens = estimate(msg.text) + 4
        for (image in msg.images) {
            tokens += IMAGE_TOKENS
        }
        return tokens
    }

    fun estimateAll(messages: List<Msg>): Int = messages.sumOf { estimateMessage(it) }

    /**
     * 从最新消息往前取，直到超出预算；不会以 assistant 消息开头。
     */
    fun trim(messages: List<Msg>, budget: Int, maxMessages: Int = 200): List<Msg> {
        if (messages.isEmpty()) return messages
        val limit = max(budget, 2048)
        val picked = ArrayDeque<Msg>()
        var used = 0
        for (index in messages.indices.reversed()) {
            val msg = messages[index]
            val cost = estimateMessage(msg)
            val full = picked.size >= maxMessages || (picked.isNotEmpty() && used + cost > limit)
            if (full) break
            picked.addFirst(msg)
            used += cost
        }
        while (picked.isNotEmpty() && picked.first().role == Role.ASSISTANT) {
            picked.removeFirst()
        }
        return picked.toList()
    }

    fun describe(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000f)
        tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", tokens / 1_000f)
        else -> "$tokens"
    }
}
