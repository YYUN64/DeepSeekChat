package com.deepseek.chat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX → Bitmap（JLaTeXMath Android 移植）。
 * 按 (latex, 字号, 颜色) 做 LruCache，深浅色主题会各缓存一份。
 */
object MathRenderer {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Volatile
    private var initialized = false

    fun render(context: Context, latex: String, textSizePx: Float, color: Int): Bitmap? {
        val source = latex.trim()
        if (source.isEmpty()) return null
        val key = "$source|${textSizePx.toInt()}|$color"
        cache.get(key)?.let { return it }
        return runCatching {
            if (!initialized) {
                JLatexMathAndroid.init(context.applicationContext)
                initialized = true
            }
            val drawable = JLatexMathDrawable.builder(source)
                .textSize(textSizePx)
                .color(color)
                .fitCanvas(true)
                .build()
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width <= 0 || height <= 0) return null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            cache.put(key, bitmap)
            bitmap
        }.getOrNull()
    }
}
