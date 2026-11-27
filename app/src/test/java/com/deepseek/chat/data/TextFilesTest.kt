package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilesTest {

    // ------------------------------------------------------------ 工具

    private fun attachment(
        name: String,
        text: String,
        bytes: Long = text.toByteArray(Charsets.UTF_8).size.toLong(),
        truncated: Boolean = false
    ) = TextAttachment(name = name, text = text, bytes = bytes, truncated = truncated)

    private fun ok(result: FileReadResult): TextAttachment {
        assertTrue("期望 Ok，实际 $result", result is FileReadResult.Ok)
        return (result as FileReadResult.Ok).attachment
    }

    private fun rejected(result: FileReadResult): String {
        assertTrue("期望 Rejected，实际 $result", result is FileReadResult.Rejected)
        return (result as FileReadResult.Rejected).reason
    }

    // ------------------------------------------------------------ 文件类型判定

    @Test
    fun `text extensions are accepted`() {
        val names = listOf(
            "notes.md", "a.txt", "data.json", "table.csv", "run.log", "conf.xml",
            "app.yaml", "app.yml", "app.ini", "pyproject.toml", "main.py", "index.js",
            "Main.kt", "Main.java", "a.c", "a.h", "a.cpp", "main.go", "lib.rs",
            "deploy.sh", "q.sql", "page.html", "style.css"
        )
        for (name in names) {
            assertTrue("$name 应该是文本文件", TextFiles.isTextFileName(name))
        }
    }

    @Test
    fun `extension is case insensitive and follows the file name`() {
        assertEquals("md", TextFiles.extensionOf("NOTES.MD"))
        assertEquals("kt", TextFiles.extensionOf("some/dir/Main.Kt"))
        assertEquals("txt", TextFiles.extensionOf("C:\\tmp\\a.b.txt"))
        assertEquals("", TextFiles.extensionOf("noext"))
        assertEquals("env", TextFiles.extensionOf(".env"))
        assertTrue(TextFiles.isTextFileName("README.MD"))
    }

    @Test
    fun `image is rejected with a friendly hint`() {
        val reason = rejected(TextFiles.buildAttachment("photo.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertTrue(reason.contains("不支持的文件类型"))
        assertTrue(reason.contains(".png"))
    }

    @Test
    fun `pdf and docx are rejected`() {
        assertTrue(TextFiles.buildAttachment("a.pdf", ByteArray(8)) is FileReadResult.Rejected)
        assertTrue(TextFiles.buildAttachment("a.docx", ByteArray(8)) is FileReadResult.Rejected)
    }

    @Test
    fun `nul byte means binary`() {
        val bytes = "PK\u0000\u0003binary".toByteArray(Charsets.ISO_8859_1)
        val reason = rejected(TextFiles.buildAttachment("fake.txt", bytes))
        assertEquals(TextFiles.BINARY_MESSAGE, reason)
        assertTrue(reason.contains("请先转成文本"))
    }

    @Test
    fun `plain text without nul is accepted`() {
        val result = TextFiles.buildAttachment("notes.md", "# 标题\n正文".toByteArray(Charsets.UTF_8))
        val attachment = ok(result)
        assertEquals("notes.md", attachment.name)
        assertEquals("# 标题\n正文", attachment.text)
        assertFalse(attachment.truncated)
    }

    // ------------------------------------------------------------ 大小截断

    @Test
    fun `file under limit is not truncated`() {
        val text = "x".repeat(1000)
        val attachment = ok(TextFiles.buildAttachment("small.txt", text.toByteArray()))
        assertFalse(attachment.truncated)
        assertEquals(text, attachment.text)
        assertEquals(1000L, attachment.bytes)
    }

    @Test
    fun `500kb file is truncated with notice`() {
        val content = "a".repeat(500 * 1024)
        val attachment = ok(TextFiles.buildAttachment("big.txt", content.toByteArray(Charsets.UTF_8)))
        assertTrue(attachment.truncated)
        assertEquals(500L * 1024, attachment.bytes)
        assertTrue(attachment.text.endsWith(TextFiles.truncationNotice(500L * 1024)))
        assertTrue(attachment.text.contains("已截断，原文件 500 KB"))

        val body = attachment.text.substringBefore("\n\n…（已截断")
        assertTrue(
            "正文应被截到 256 KB 以内，实际 ${TextFiles.utf8Length(body)}",
            TextFiles.utf8Length(body) <= TextFiles.MAX_FILE_BYTES
        )
        assertTrue(TextFiles.utf8Length(body) >= TextFiles.MAX_FILE_BYTES - 4)
    }

    @Test
    fun `utf8 truncation does not split characters`() {
        val content = "中文".repeat(60_000) // 360000 字节 > 256 KB
        val attachment = ok(TextFiles.buildAttachment("cn.txt", content.toByteArray(Charsets.UTF_8)))
        assertTrue(attachment.truncated)
        assertFalse("截断处不应出现替换字符", attachment.text.contains('\uFFFD'))
        val body = attachment.text.substringBefore("\n\n…（已截断")
        assertTrue(TextFiles.utf8Length(body) <= TextFiles.MAX_FILE_BYTES)
        // 截断点按整字符收尾（3 字节一个汉字）
        assertEquals(0, TextFiles.utf8Length(body) % 3)
    }

    @Test
    fun `known huge file is marked truncated even with small buffer`() {
        val attachment = ok(
            TextFiles.buildAttachment("huge.log", "short".toByteArray(), originalBytes = 10L * 1024 * 1024)
        )
        assertTrue(attachment.truncated)
        assertEquals(10L * 1024 * 1024, attachment.bytes)
        assertTrue(attachment.text.contains("原文件 10240 KB"))
    }

    @Test
    fun `size label`() {
        assertEquals("512 B", TextFiles.sizeLabel(512))
        assertEquals("1 KB", TextFiles.sizeLabel(1536))
        assertEquals("50 KB", TextFiles.sizeLabel(50L * 1024))
        assertEquals("1 MB", TextFiles.sizeLabel(1024L * 1024))
        assertEquals("1.5 MB", TextFiles.sizeLabel(1536L * 1024))
    }

    // ------------------------------------------------------------ 多文件拼接

    @Test
    fun `single file block format`() {
        val composed = TextFiles.compose("", listOf(attachment("notes.md", "# 标题\n内容")))
        assertEquals("【文件：notes.md】\n```text\n# 标题\n内容\n```", composed.fileBlocks)
        assertEquals("", composed.text)
        assertEquals(composed.fileBlocks, composed.modelText)
        assertEquals(1, composed.kept.size)
        assertTrue(composed.dropped.isEmpty())
    }

    @Test
    fun `multiple files are joined in order after the input`() {
        val files = listOf(
            attachment("a.md", "AAA"),
            attachment("b.py", "print(1)")
        )
        val composed = TextFiles.compose("看看这两个文件", files)
        assertEquals("看看这两个文件", composed.text)
        assertEquals(
            "【文件：a.md】\n```text\nAAA\n```\n\n" +
                "【文件：b.py】\n```text\nprint(1)\n```",
            composed.modelText.removePrefix("看看这两个文件\n\n")
        )
        assertEquals(2, composed.kept.size)
        assertTrue(composed.modelText.indexOf("【文件：a.md】") < composed.modelText.indexOf("【文件：b.py】"))
    }

    @Test
    fun `file only message has no leading blank line`() {
        val composed = TextFiles.compose("   ", listOf(attachment("a.txt", "hi")))
        assertTrue(composed.modelText.startsWith("【文件：a.txt】"))
        assertEquals("", composed.text)
    }

    @Test
    fun `too long message keeps only the first files`() {
        val files = (1..4).map { attachment("f$it.txt", "x".repeat(100 * 1024)) }
        val composed = TextFiles.compose("", files)
        assertEquals(2, composed.kept.size)
        assertEquals(2, composed.dropped.size)
        assertTrue(TextFiles.utf8Length(composed.modelText) <= TextFiles.MAX_MESSAGE_BYTES)
        assertEquals("f1.txt", composed.kept.first().name)
        assertEquals("f4.txt", composed.dropped.last().name)
    }

    @Test
    fun `no files means input only and no file blocks`() {
        val composed = TextFiles.compose(" 你好 ", emptyList())
        assertEquals("你好", composed.text)
        assertNull(composed.fileBlocks)
        assertEquals("你好", composed.modelText)
        assertTrue(composed.kept.isEmpty())
        assertTrue(composed.dropped.isEmpty())
    }

    @Test
    fun `attachment refs carry name size and truncated flag`() {
        val big = ok(TextFiles.buildAttachment("big.txt", "a".repeat(300 * 1024).toByteArray()))
        val small = ok(TextFiles.buildAttachment("notes.md", "hi".toByteArray()))
        val refs = TextFiles.attachmentRefs(listOf(big, small))

        assertEquals(listOf("big.txt", "notes.md"), refs.map { it.name })
        assertEquals(300L * 1024, refs[0].sizeBytes)
        assertTrue(refs[0].truncated)
        assertEquals(2L, refs[1].sizeBytes)
        assertFalse(refs[1].truncated)
        assertEquals("big.txt · 300 KB（已截断）", TextFiles.describe(refs[0]))
        assertEquals("notes.md · 2 B", TextFiles.describe(refs[1]))
    }

    // ------------------------------------------------------------ 数量/大小上限

    @Test
    fun `five files allowed sixth rejected`() {
        val existing = (1..4).map { attachment("f$it.txt", "hi") }
        assertTrue(TextFiles.checkAdd(existing, attachment("f5.txt", "hi")).allowed)
        assertTrue(TextFiles.checkAdd(existing + attachment("f5.txt", "hi"), attachment("f6.txt", "hi")).allowed.not())
        val reason = TextFiles.checkAdd(existing + attachment("f5.txt", "hi"), attachment("f6.txt", "hi")).reason
        assertTrue(reason!!.contains("最多附加 5 个文件"))
    }

    @Test
    fun `total size over one megabyte rejected`() {
        val existing = listOf(attachment("a.txt", "x", bytes = 600L * 1024))
        val check = TextFiles.checkAdd(existing, attachment("b.txt", "y", bytes = 600L * 1024))
        assertFalse(check.allowed)
        assertTrue(check.reason!!.contains("1 MB"))
    }

    @Test
    fun `total size under limit allowed`() {
        val existing = listOf(attachment("a.txt", "x", bytes = 500L * 1024))
        assertTrue(TextFiles.checkAdd(existing, attachment("b.txt", "y", bytes = 500L * 1024)).allowed)
    }
}
