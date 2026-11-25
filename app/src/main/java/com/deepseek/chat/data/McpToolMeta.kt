package com.deepseek.chat.data

import org.json.JSONObject

/** 服务端 tools/call 返回的 _meta（执行状态/耗时/退出码） */
data class ToolCallMeta(
    val dispatched: Boolean,
    val code: Int?,
    val durationMs: Long?,
    val timedOut: Boolean,
    val timeoutSeconds: Int?,
    val cancelled: Boolean = false
) {
    companion object {
        fun parse(json: JSONObject?): ToolCallMeta? {
            if (json == null) return null
            val duration = if (json.has("durationMs")) json.optLong("durationMs", -1) else -1
            val code = if (json.has("code") && !json.isNull("code")) json.optInt("code") else null
            return ToolCallMeta(
                dispatched = json.optBoolean("dispatched", false),
                code = code,
                durationMs = if (duration >= 0) duration else null,
                timedOut = json.optBoolean("timeout", false),
                timeoutSeconds = if (json.has("timeoutSeconds") && !json.isNull("timeoutSeconds")) {
                    json.optInt("timeoutSeconds")
                } else {
                    null
                },
                cancelled = json.optBoolean("cancelled", false)
            )
        }
    }
}

/** 工具卡片的右侧状态文案：完成 · 123ms / 失败 · 退出码 2 · 45ms / 超时（5s） */
object ToolStatus {

    fun formatDuration(ms: Long?): String? {
        if (ms == null) return null
        return if (ms < 1000) "${ms}ms" else String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    }

    fun label(meta: ToolCallMeta?, failed: Boolean): String {
        if (meta == null) return if (failed) "失败" else "完成"
        if (meta.cancelled) {
            val duration = formatDuration(meta.durationMs)
            return if (duration != null) "已取消 · $duration" else "已取消"
        }
        if (meta.timedOut) {
            val seconds = meta.timeoutSeconds?.let { "${it}s" } ?: formatDuration(meta.durationMs)
            return if (seconds != null) "超时（$seconds）" else "超时"
        }
        val parts = mutableListOf<String>()
        parts.add(if (failed) "失败" else "完成")
        if (failed && meta.code != null) parts.add("退出码 ${meta.code}")
        formatDuration(meta.durationMs)?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }
}
