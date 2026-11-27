package com.deepseek.chat.data

/**
 * 生成中按发送后排进队列的一条消息（「中途插话」= 排队，对齐 DSH）。
 *
 * 只在内存里：进程被杀就丢，不做持久化。
 */
data class QueuedMessage(
    val id: String,
    /** 用户自己打的字 */
    val text: String = "",
    val images: List<ImageRef> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    /** 拼好的文件块，只发给模型 */
    val fileBlocks: String? = null
) {
    val isEmpty: Boolean
        get() = text.isBlank() && images.isEmpty() && attachments.isEmpty()
}

/**
 * 排队的纯逻辑：先进先出、按 id 取消、切会话整体清空。
 * 放在这里是为了能脱离 Android 单测（顺序/取消/清空）。
 */
object MessageQueue {

    /** 追加到队尾（空消息不入队） */
    fun enqueue(items: List<QueuedMessage>, item: QueuedMessage): List<QueuedMessage> =
        if (item.isEmpty) items else items + item

    /** 取消一条（按 id），不影响其它项的顺序 */
    fun cancel(items: List<QueuedMessage>, id: String): List<QueuedMessage> =
        items.filterNot { it.id == id }

    /** 切会话/新建对话时整体清空 */
    fun clear(items: List<QueuedMessage>): List<QueuedMessage> = emptyList()

    /** 取出队首与剩余队列 */
    fun takeFirst(items: List<QueuedMessage>): Pair<QueuedMessage?, List<QueuedMessage>> =
        if (items.isEmpty()) null to items else items.first() to items.drop(1)

    /** 发送前预检失败时放回队首，不丢消息 */
    fun restoreFirst(items: List<QueuedMessage>, item: QueuedMessage): List<QueuedMessage> =
        listOf(item) + items

    /** 「排队中：<前 30 字>…」里冒号后面那段摘要 */
    fun summary(item: QueuedMessage, max: Int = 30): String {
        val flat = item.text.replace(Regex("\\s+"), " ").trim()
        return when {
            flat.isNotEmpty() -> if (flat.length <= max) flat else flat.take(max) + "…"
            item.images.isNotEmpty() -> "（图片 ${item.images.size} 张）"
            item.attachments.isNotEmpty() -> {
                val names = item.attachments.take(2).joinToString("、") { it.name }
                if (item.attachments.size > 2) {
                    "$names 等 ${item.attachments.size} 个文件"
                } else {
                    names
                }
            }
            else -> ""
        }
    }

    /** 输入框上方那一行提示文字 */
    fun label(item: QueuedMessage, max: Int = 30): String = "排队中：${summary(item, max)}"
}
