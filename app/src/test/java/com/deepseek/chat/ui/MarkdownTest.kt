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

    // ---------------------------------------------------------- LaTeX 兜底

    @Test
    fun `bracket pmatrix block becomes an aligned code block`() {
        val md = """
            \[
            A=\begin{pmatrix}
            1 & 2 & 3\\
            4 & 5 & 6\\
            7 & 8 & 10
            \end{pmatrix}
            \]
        """.trimIndent()

        val blocks = parseMarkdown(md)

        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("math", code.language)
        assertEquals("A=\n1  2  3\n4  5  6\n7  8  10", code.code)
    }

    @Test
    fun `dollar block and vmatrix are converted to plain math text`() {
        val dollars = parseMarkdown("""$$\int_0^1 x dx$$""")
        assertEquals(1, dollars.size)
        assertEquals("∫_0^1 x dx", (dollars[0] as MdBlock.Code).code)

        val vmatrix = parseMarkdown(
            """
            \begin{vmatrix}
            a & b\\
            c & d
            \end{vmatrix}
            """.trimIndent()
        )
        assertEquals(1, vmatrix.size)
        assertEquals("a  b\nc  d", (vmatrix[0] as MdBlock.Code).code)
    }

    @Test
    fun `unclosed bracket block still renders as code`() {
        val blocks = parseMarkdown("\\[ x^2")

        assertEquals(1, blocks.size)
        assertEquals("x^2", (blocks[0] as MdBlock.Code).code)
    }

    @Test
    fun `latexToPlain strips commands and keeps readable symbols`() {
        assertEquals("a/b", latexToPlain("\\frac{a}{b}"))
        assertEquals("速度", latexToPlain("\\text{速度}"))
        assertEquals("( x )", latexToPlain("\\left( x \\right)"))
        assertEquals("x^2", latexToPlain("x^{2}"))
        assertEquals("a_1", latexToPlain("a_{1}"))
        assertEquals("1×2 - 3×4", latexToPlain("1\\times2 - 3\\times4"))
    }

    @Test
    fun `normal inline text is not treated as math`() {
        // 货币和普通 $ 不应被转换（由 buildInline 的 group 11 分支处理，这里验证转换函数不误伤）
        assertEquals("5 and 10", latexToPlain("5 and 10"))
    }
}
