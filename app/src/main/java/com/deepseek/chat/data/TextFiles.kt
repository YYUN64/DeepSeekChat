package com.deepseek.chat.data

/**
 * 文本文件附件的纯逻辑（不依赖 Android，方便单测）。
 *
 * 约定：文件内容不落盘、不进会话 JSON 的独立字段，发送时就拼进这条 user 消息的文本里，
 * 历史里看到的就是普通消息文本。
 */
data class TextAttachment(
    val name: String,
    /** 已解码（必要时已截断）的文本内容 */
    val text: String,
    /** 原文件字节数（截断前），用于 chip 显示大小与总大小限制 */
    val bytes: Long,
    /** 是否因为超过单文件上限被截断 */
    val truncated: Boolean = false
)

sealed interface FileReadResult {
    data class Ok(val attachment: TextAttachment) : FileReadResult
    data class Rejected(val reason: String) : FileReadResult
}

/** 追加附件前的数量/总大小检查 */
data class LimitCheck(val allowed: Boolean, val reason: String? = null)

/** 拼好的用户消息：正文只放用户打的字，文件内容单独放在 fileBlocks（只发给模型） */
data class ComposedMessage(
    /** 用户自己打的字（trim 后） */
    val text: String,
    /** 拼好的文件块（`【文件：…】` + ```text 围栏），没有附件时为 null */
    val fileBlocks: String? = null,
    val kept: List<TextAttachment> = emptyList(),
    val dropped: List<TextAttachment> = emptyList()
) {
    /** 发给模型的正文：文字 + 文件块（没有文字就只发文件块） */
    val modelText: String
        get() = when {
            fileBlocks.isNullOrBlank() -> text
            text.isBlank() -> fileBlocks
            else -> text + "\n\n" + fileBlocks
        }
}

object TextFiles {

    /** 单个文件上限：256 KB */
    const val MAX_FILE_BYTES = 256 * 1024

    /** 最多同时附 5 个文件 */
    const val MAX_FILES = 5

    /** 附件总大小上限：1 MB */
    const val MAX_TOTAL_BYTES = 1024 * 1024

    /** 拼接后整条消息上限：300 KB */
    const val MAX_MESSAGE_BYTES = 300 * 1024

    const val BINARY_MESSAGE = "这个看起来是二进制文件（PDF/Word/图片等暂不支持），请先转成文本"

    /** 只接受这些扩展名的文本文件（统一小写比较） */
    val ALLOWED_EXTENSIONS: Set<String> = linkedSetOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml",
        "ini", "toml", "conf", "cfg", "properties", "env",
        "py", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "c", "h", "hpp", "cpp", "cc",
        "cs", "go", "rs", "rb", "php", "swift", "m", "sh", "bash", "zsh", "ps1", "bat",
        "sql", "html", "htm", "css", "scss", "less", "vue", "svelte", "gradle", "mk",
        "lua", "pl", "r", "dart", "scala", "gitignore", "diff", "patch", "tex"
    )

    /** 取扩展名（小写，不含点）；没有扩展名返回空串，`.env` 这类点开头文件取点后面的部分 */
    fun extensionOf(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        if (dot < 0 || dot == base.length - 1) return ""
        if (dot == 0) return base.substring(1).lowercase()
        return base.substring(dot + 1).lowercase()
    }

    fun isTextFileName(name: String): Boolean = extensionOf(name) in ALLOWED_EXTENSIONS

    fun unsupportedMessage(name: String): String {
        val ext = extensionOf(name)
        val label = if (ext.isEmpty()) name else ".$ext"
        return "不支持的文件类型：$label（只支持文本类：.txt .md .json .csv .log .xml 等；PDF/Word/图片请先转成文本）"
    }

    /** 前 [length] 个字节里出现 NUL → 判定为二进制 */
    fun hasNulByte(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val end = minOf(length, bytes.size)
        for (index in 0 until end) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        else -> {
            val tenths = bytes * 10L / (1024L * 1024L)
            if (tenths % 10L == 0L) "${tenths / 10L} MB" else "${tenths / 10L}.${tenths % 10L} MB"
        }
    }

    /** 截断说明：「…（已截断，原文件 N KB）」 */
    fun truncationNotice(originalBytes: Long): String =
        "…（已截断，原文件 ${(originalBytes + 1023L) / 1024L} KB）"

    /** 按 UTF-8 解码（非法字节替换为 U+FFFD，去掉 BOM） */
    fun decode(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    /** 把字符串截到不超过 [maxBytes] 个 UTF-8 字节，且不切断多字节字符 */
    fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
        var used = 0
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (used + size > maxBytes) break
            builder.appendCodePoint(codePoint)
            used += size
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * 把原始字节变成一个附件：类型校验 → NUL 校验 → 超限截断 + 标注。
     *
     * @param originalBytes 原文件真实大小（流被提前掐断时由调用方传入）
     */
    fun buildAttachment(
        name: String,
        bytes: ByteArray,
        originalBytes: Long = bytes.size.toLong()
    ): FileReadResult {
        if (!isTextFileName(name)) return FileReadResult.Rejected(unsupportedMessage(name))
        if (hasNulByte(bytes)) return FileReadResult.Rejected(BINARY_MESSAGE)

        val total = maxOf(originalBytes, bytes.size.toLong())
        val truncated = total > MAX_FILE_BYTES
        var text = decode(bytes)
        if (truncated) {
            text = truncateToUtf8Bytes(text, MAX_FILE_BYTES)
                .removeSuffix("\uFFFD")
                .trimEnd()
            text = text + "\n\n" + truncationNotice(total)
        }
        return FileReadResult.Ok(
            TextAttachment(name = name, text = text, bytes = total, truncated = truncated)
        )
    }

    /** 是否还能再追加这个附件（数量 + 总大小） */
    fun checkAdd(existing: List<TextAttachment>, incoming: TextAttachment): LimitCheck {
        if (existing.size >= MAX_FILES) {
            return LimitCheck(false, "最多附加 $MAX_FILES 个文件")
        }
        val used = existing.sumOf { it.bytes }
        if (used + incoming.bytes > MAX_TOTAL_BYTES) {
            return LimitCheck(
                false,
                "附件总大小不能超过 ${sizeLabel(MAX_TOTAL_BYTES.toLong())}（已用 ${sizeLabel(used)}）"
            )
        }
        return LimitCheck(true)
    }

    /** 单个文件的拼接块：`【文件：name】` + ```text 围栏 */
    fun renderBlock(attachment: TextAttachment): String =
        "【文件：${attachment.name}】\n```text\n${attachment.text.trimEnd('\n')}\n```"

    /** 把若干个附件拼成文件块（不含用户文字） */
    fun blocksOf(files: List<TextAttachment>): String =
        files.joinToString("\n\n") { renderBlock(it) }

    /** 附件 chip 需要的元数据（持久化到消息里，不进内容） */
    fun attachmentRefs(files: List<TextAttachment>): List<AttachmentRef> =
        files.map { AttachmentRef(name = it.name, sizeBytes = it.bytes, truncated = it.truncated) }

    /** chip 上那一行字：`notes.md · 50 KB（已截断）` */
    fun describe(ref: AttachmentRef): String = buildString {
        append(ref.name)
        append(" · ")
        append(sizeLabel(ref.sizeBytes))
        if (ref.truncated) append("（已截断）")
    }

    private fun modelText(text: String, blocks: String): String = when {
        blocks.isBlank() -> text
        text.isBlank() -> blocks
        else -> text + "\n\n" + blocks
    }

    /**
     * 把用户输入 + 附件拼成最终要发出去的消息：text 是用户文字，fileBlocks 是文件块。
     * 超过 [MAX_MESSAGE_BYTES] 时从后往前丢文件，丢掉的部分放在 [ComposedMessage.dropped]。
     */
    fun compose(input: String, files: List<TextAttachment>): ComposedMessage {
        val trimmed = input.trim()
        if (files.isEmpty()) return ComposedMessage(text = trimmed)

        var kept = files
        var blocks = blocksOf(kept)
        while (utf8Length(modelText(trimmed, blocks)) > MAX_MESSAGE_BYTES && kept.size > 1) {
            kept = kept.dropLast(1)
            blocks = blocksOf(kept)
        }
        if (utf8Length(modelText(trimmed, blocks)) > MAX_MESSAGE_BYTES && kept.size == 1) {
            // 单个文件 + 用户输入仍然超限：保用户输入，丢掉这个文件
            kept = emptyList()
            blocks = ""
        }
        val dropped = files.drop(kept.size).toList()
        return ComposedMessage(
            text = trimmed,
            fileBlocks = blocks.ifBlank { null },
            kept = kept.toList(),
            dropped = dropped
        )
    }
}
