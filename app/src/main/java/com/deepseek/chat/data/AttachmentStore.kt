package com.deepseek.chat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 从 SAF（OpenDocument）URI 里读出文本文件内容。
 *
 * 只读原始字节，不落盘：附件是消息文本的一部分。
 */
class AttachmentStore(context: Context) {

    private val appContext: Context = context.applicationContext

    fun read(uri: Uri): FileReadResult {
        val name = displayName(uri)
        val knownSize = sizeOf(uri)
        return try {
            val bytes = readBytes(uri, knownSize)
            TextFiles.buildAttachment(
                name = name,
                bytes = bytes,
                originalBytes = knownSize?.takeIf { it > 0 } ?: bytes.size.toLong()
            )
        } catch (t: Throwable) {
            FileReadResult.Rejected("文件读取失败：${t.message ?: "未知错误"}")
        }
    }

    private fun readBytes(uri: Uri, knownSize: Long?): ByteArray {
        val limit = if (knownSize != null && knownSize > TextFiles.MAX_FILE_BYTES) {
            // 已知超大文件：只读到够判定「截断」并留一点余量，不把整个大文件塞进内存
            TextFiles.MAX_FILE_BYTES + 4 * 1024
        } else {
            MAX_READ_BYTES
        }
        val stream = appContext.contentResolver.openInputStream(uri) ?: error("无法打开文件")
        return stream.use { it.readAtMost(limit) }
    }

    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (out.size() < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "未命名文件"
    }

    private fun sizeOf(uri: Uri): Long? {
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                    }
                }
        }
        return null
    }

    private companion object {
        /** 大小未知时的兜底读取上限（超过就当超大文件处理） */
        const val MAX_READ_BYTES = 8 * 1024 * 1024
    }
}
