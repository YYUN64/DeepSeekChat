package com.deepseek.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 图片落盘 + 缩放 + base64 编码。
 */
class ImageStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dir: File = File(appContext.filesDir, "images").apply {
        if (!exists()) mkdirs()
    }

    fun file(ref: ImageRef): File = File(dir, ref.fileName)

    /** 从相册 URI 导入图片：缩放、按 EXIF 旋转、压缩为 JPEG。 */
    fun import(uri: Uri): ImageRef? {
        val original = decodeScaled(uri) ?: return null
        val rotated = rotateByExif(uri, original)
        val width = rotated.width
        val height = rotated.height
        val name = "${UUID.randomUUID()}.jpg"
        val target = File(dir, name)
        val ok = runCatching {
            target.outputStream().use { stream ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            }
            target.length() > 0
        }.getOrDefault(false)
        if (rotated !== original) original.recycle()
        rotated.recycle()
        if (!ok) {
            target.delete()
            return null
        }
        return ImageRef(name, width, height)
    }

    fun bitmap(ref: ImageRef, maxEdge: Int = 1024): Bitmap? {
        val target = file(ref)
        if (!target.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            var sample = 1
            if (maxEdge > 0 && bounds.outWidth > 0 && bounds.outHeight > 0) {
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) {
                    sample *= 2
                }
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(target.absolutePath, options)
        }.getOrNull()
    }

    /** 转成 OpenAI 兼容的 data URL。 */
    fun dataUrl(ref: ImageRef): String? {
        val target = file(ref)
        if (!target.exists() || target.length() <= 0L) return null
        val bytes = runCatching { target.readBytes() }.getOrNull() ?: return null
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    fun delete(refs: Collection<ImageRef>) {
        for (ref in refs) {
            runCatching { file(ref).delete() }
        }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { file -> runCatching { file.delete() } }
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val largest = max(decoded.width, decoded.height)
        if (largest <= MAX_EDGE) return decoded
        val scale = MAX_EDGE.toFloat() / largest
        val targetWidth = max(1, (decoded.width * scale).toInt())
        val targetHeight = max(1, (decoded.height * scale).toInt())
        val scaled = runCatching {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        }.getOrNull() ?: return decoded
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun rotateByExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    companion object {
        /** 长边缩放上限 */
        const val MAX_EDGE = 1600
        /** 一条消息最多几张图（多选时也以此为上限） */
        const val MAX_IMAGES = 6
    }
}
