package com.deepseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「中途插话 = 排队」的纯逻辑：顺序、取消、切会话清空、摘要。
 */
class MessageQueueTest {

    private fun item(id: String, text: String = "问题 $id") = QueuedMessage(id = id, text = text)

    @Test
    fun `messages keep the order they were queued in`() {
        var queue = emptyList<QueuedMessage>()
        queue = MessageQueue.enqueue(queue, item("a"))
        queue = MessageQueue.enqueue(queue, item("b"))
        queue = MessageQueue.enqueue(queue, item("c"))

        assertEquals(listOf("a", "b", "c"), queue.map { it.id })

        val (first, rest) = MessageQueue.takeFirst(queue)
        assertEquals("a", first?.id)
        assertEquals(listOf("b", "c"), rest.map { it.id })

        val (second, rest2) = MessageQueue.takeFirst(rest)
        assertEquals("b", second?.id)
        assertEquals(listOf("c"), rest2.map { it.id })
    }

    @Test
    fun `cancelling one item keeps the others in order`() {
        val queue = listOf(item("a"), item("b"), item("c"))
        val afterB = MessageQueue.cancel(queue, "b")
        assertEquals(listOf("a", "c"), afterB.map { it.id })

        val afterAll = MessageQueue.cancel(afterB, "a").let { MessageQueue.cancel(it, "c") }
        assertTrue(afterAll.isEmpty())
    }

    @Test
    fun `cancelling an unknown id changes nothing`() {
        val queue = listOf(item("a"), item("b"))
        assertEquals(listOf("a", "b"), MessageQueue.cancel(queue, "zzz").map { it.id })
    }

    @Test
    fun `switching conversation clears the whole queue`() {
        val queue = listOf(item("a"), item("b"), item("c"))
        assertTrue(MessageQueue.clear(queue).isEmpty())
        assertTrue(MessageQueue.clear(emptyList()).isEmpty())
    }

    @Test
    fun `empty messages are not queued`() {
        assertTrue(MessageQueue.enqueue(emptyList(), QueuedMessage(id = "x")).isEmpty())
        val withFile = QueuedMessage(
            id = "f",
            attachments = listOf(AttachmentRef("notes.md", 1024))
        )
        assertEquals(1, MessageQueue.enqueue(emptyList(), withFile).size)
    }

    @Test
    fun `failed send puts the message back at the head`() {
        val queue = listOf(item("b"), item("c"))
        val restored = MessageQueue.restoreFirst(queue, item("a"))
        assertEquals(listOf("a", "b", "c"), restored.map { it.id })
    }

    @Test
    fun `label shows the first 30 characters`() {
        val long = "这是一个很长的问题".repeat(10)
        val label = MessageQueue.label(item("a", long))
        assertTrue(label.startsWith("排队中："))
        assertEquals("排队中：" + long.take(30) + "…", label)
    }

    @Test
    fun `label falls back to images or file names when text is blank`() {
        val imageOnly = QueuedMessage(
            id = "i",
            images = listOf(ImageRef("a.jpg"), ImageRef("b.jpg"))
        )
        assertEquals("排队中：（图片 2 张）", MessageQueue.label(imageOnly))

        val fileOnly = QueuedMessage(
            id = "f",
            attachments = listOf(AttachmentRef("notes.md", 1024), AttachmentRef("a.py", 10))
        )
        assertEquals("排队中：notes.md、a.py", MessageQueue.label(fileOnly))

        val manyFiles = QueuedMessage(
            id = "m",
            attachments = listOf(
                AttachmentRef("a.md", 1),
                AttachmentRef("b.md", 1),
                AttachmentRef("c.md", 1)
            )
        )
        assertEquals("排队中：a.md、b.md 等 3 个文件", MessageQueue.label(manyFiles))
    }

    @Test
    fun `newlines in the text are flattened in the label`() {
        assertEquals("排队中：第一行 第二行", MessageQueue.label(item("a", "第一行\n第二行")))
    }

    @Test
    fun `queue entry keeps file blocks for the model but no content in text`() {
        val entry = QueuedMessage(
            id = "q",
            text = "看看这个",
            attachments = listOf(AttachmentRef("notes.md", 51200L)),
            fileBlocks = "【文件：notes.md】\n```text\n内容\n```"
        )
        assertEquals("看看这个", entry.text)
        assertTrue(entry.fileBlocks!!.contains("【文件：notes.md】"))
    }

    @Test
    fun `takeFirst on an empty queue yields null`() {
        val (first, rest) = MessageQueue.takeFirst(emptyList())
        assertNull(first)
        assertTrue(rest.isEmpty())
    }
}
