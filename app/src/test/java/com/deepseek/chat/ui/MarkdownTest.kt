package com.deepseek.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `table is parsed into header and rows`() {
        val md = """
            | 条件 | PCWrite | 说明 |
            |---|---|---|
            | Normal | 1 | 正常 |
            | Load-use | 0 | 停顿 |
        """.trimIndent()

        val blocks = parseMarkdown(md)

        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("条件", "PCWrite", "说明"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Normal", "1", "正常"), table.rows[0])
        assertEquals(listOf("Load-use", "0", "停顿"), table.rows[1])
    }

    @Test
    fun `table rows are padded or trimmed to the header column count`() {
        val md = """
            | a | b |
            |---|---|
            | 1 |
            | 2 | 3 | 4 |
        """.trimIndent()

        val table = parseMarkdown(md)[0] as MdBlock.Table

        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf("1", ""), table.rows[0])
        assertEquals(listOf("2", "3"), table.rows[1])
    }

    @Test
    fun `pipe lines without a separator stay a paragraph`() {
        val blocks = parseMarkdown("a | b\nc | d")

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `table inside a paragraph is split out`() {
        val md = "前面一句话\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n后面一句话"
        val blocks = parseMarkdown(md)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Table)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun `multi line quote is merged into one block`() {
        val blocks = parseMarkdown("> 第一行\n> 第二行\n>> 嵌套")

        assertEquals(1, blocks.size)
        assertEquals("第一行\n第二行\n嵌套", (blocks[0] as MdBlock.Quote).raw)
    }

    @Test
    fun `nested lists keep their level`() {
        val md = "- a\n  - b\n    - c\n1. x\n  1. y"
        val items = parseMarkdown(md).filterIsInstance<MdBlock.ListItem>()

        assertEquals(5, items.size)
        assertEquals(listOf(0, 1, 2, 0, 1), items.map { it.level })
        assertEquals(listOf("•", "•", "•", "1.", "1."), items.map { it.marker })
    }

    @Test
    fun `heading requires a space after the hashes`() {
        val blocks = parseMarkdown("#1 issue\n# 标题\n### 三级")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals(1, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
    }

    @Test
    fun `code block keeps content and an unclosed fence still renders as code`() {
        val closed = parseMarkdown("```kotlin\nval a = 1\n```")
        assertEquals(1, closed.size)
        assertEquals("kotlin", (closed[0] as MdBlock.Code).language)
        assertEquals("val a = 1", (closed[0] as MdBlock.Code).code)

        // 流式过程中常出现“只有开头没有结尾”的代码块
        val unclosed = parseMarkdown("```\nline1\nline2")
        assertEquals(1, unclosed.size)
        assertEquals("line1\nline2", (unclosed[0] as MdBlock.Code).code)
    }

    @Test
    fun `rule and paragraphs are split correctly`() {
        val blocks = parseMarkdown("第一段\n---\n第二段")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals(MdBlock.Rule, blocks[1])
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }
}
