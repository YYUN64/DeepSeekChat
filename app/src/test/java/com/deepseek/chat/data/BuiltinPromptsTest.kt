package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinPromptsTest {

    @Test
    fun `empty user prompt gets the built-in math rule`() {
        assertEquals(BuiltinPrompts.MATH_RULE, BuiltinPrompts.compose(""))
        assertTrue(BuiltinPrompts.compose("   ").contains("数学输出规则"))
    }

    @Test
    fun `user prompt is kept and the rule is appended`() {
        val composed = BuiltinPrompts.compose("你是一个简洁的中文助手。")

        assertTrue(composed.startsWith("你是一个简洁的中文助手。"))
        assertTrue(composed.contains("数学输出规则"))
        assertTrue(composed.contains("数学公式请用 LaTeX"))
    }

    @Test
    fun `user prompt explicitly asking for latex is left untouched`() {
        val english = "Always use LaTeX for math."
        assertEquals(english, BuiltinPrompts.compose(english))

        val chinese = "用公式推导，允许 LaTeX"
        assertEquals(chinese, BuiltinPrompts.compose(chinese))
    }
}
