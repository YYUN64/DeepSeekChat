package com.deepseek.chat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX → Bitmap（JLaTeXMath Android 移植）。
 * 按 (latex, 字号, 颜色) 做 LruCache，深浅色主题会各缓存一份。
 * 渲染失败会打 Log.w("MathRenderer", ...) 并返回 null，由调用方回退成文本/代码块。
 */
object MathRenderer {

    private const val TAG = "MathRenderer"

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Volatile
    private var initialized = false

    /** 最近一次渲染失败的原因（debug 预览页显示，便于不看 adb 也能定位） */
    @Volatile
    var lastError: String? = null
        private set

    fun render(context: Context, latex: String, textSizePx: Float, color: Int): Bitmap? {
        val source = latex.trim()
        if (source.isEmpty()) return null
        val key = "$source|${textSizePx.toInt()}|$color"
        cache.get(key)?.let { return it }
        return runCatching {
            if (!initialized) {
                // 正常情况下 JLaTeXMathInitProvider 已经在进程启动时调用过，
                // 这里再兜一层（init 只是保存 ApplicationContext，可重复调用）。
                JLatexMathAndroid.init(context.applicationContext)
                initialized = true
            }
            val drawable = JLatexMathDrawable.builder(source)
                .textSize(textSizePx)
                .color(color)
                .build()
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "公式尺寸非法: ${width}x$height, latex=$source")
                return null
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            cache.put(key, bitmap)
            bitmap
        }.onFailure { error ->
            lastError = error.toString()
            Log.w(TAG, "公式渲染失败，回退为文本: $source", error)
        }.getOrNull()
    }
}
