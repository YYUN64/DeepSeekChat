package com.deepseek.chat.data

/** 本地保存的一张图片（实际文件位于 filesDir/images 下） */
data class ImageRef(
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0
)

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(value: String): Role = when (value) {
            "system" -> SYSTEM
            "assistant" -> ASSISTANT
            else -> USER
        }
    }
}

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val reasoningTokens: Int = 0
)

data class Msg(
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    val images: List<ImageRef> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val usage: Usage? = null,
    val error: String? = null,
    val model: String? = null
)

data class Conversation(
    val id: String,
    val title: String = DEFAULT_TITLE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val model: String = Models.FLASH,
    val thinking: Boolean = true,
    val effort: String = Reasoning.HIGH,
    val messages: List<Msg> = emptyList()
) {
    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}

data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

object Models {
    const val FLASH = "deepseek-flash"
    const val PRO = "deepseek-v4-pro"

    val presets = listOf(FLASH, PRO)

    fun label(model: String): String = when (model) {
        FLASH -> "DeepSeek Flash"
        PRO -> "DeepSeek V4 Pro"
        else -> model
    }

    /** 顶栏/胶囊里用的短名 */
    fun shortLabel(model: String): String = when (model) {
        FLASH -> "Flash"
        PRO -> "V4 Pro"
        else -> model
    }

    fun description(model: String): String = when (model) {
        FLASH -> "支持看图，速度快，价格低"
        PRO -> "纯文本，推理与写作更强，不支持图片"
        else -> "自定义模型"
    }

    fun supportsVision(model: String): Boolean = model == FLASH
}

/**
 * 推理等级，取值与 DeepSeek Harness 一致：off / low / high / max。
 * 存储上仍复用 thinking + effort 两个字段（老会话无需迁移），这里负责互相换算。
 */
object Reasoning {
    const val OFF = "off"
    const val LOW = "low"
    const val HIGH = "high"
    const val MAX = "max"

    val presets = listOf(OFF, LOW, HIGH, MAX)

    fun label(level: String): String = when (normalize(level)) {
        OFF -> "关闭"
        LOW -> "低"
        HIGH -> "高"
        MAX -> "极致"
        else -> level
    }

    fun hint(level: String): String = when (normalize(level)) {
        OFF -> "不推理，直接回答，最快"
        LOW -> "少量推理，适合简单问题"
        HIGH -> "默认档，适合大多数问题"
        MAX -> "推理最充分，最慢，适合难题"
        else -> ""
    }

    /** 兼容历史值：none/minimal/medium/xhigh/ultra 等统一收敛到四档 */
    fun normalize(level: String): String = when (level.trim().lowercase()) {
        OFF, "none", "disabled", "false" -> OFF
        LOW, "minimal" -> LOW
        HIGH, "medium", "xhigh", "true" -> HIGH
        MAX, "ultra" -> MAX
        else -> HIGH
    }

    /** 由旧的 thinking + effort 推导出等级 */
    fun levelOf(thinking: Boolean, effort: String): String =
        if (!thinking) OFF else normalize(effort)

    fun thinkingOf(level: String): Boolean = normalize(level) != OFF

    /** 发给接口的 reasoning_effort，绝不返回 off（关闭时走 thinking.disabled） */
    fun effortFor(level: String): String {
        val n = normalize(level)
        return if (n == OFF) HIGH else n
    }
}

/** 外观：跟随系统 / 浅色 / 深色 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    val presets = listOf(SYSTEM, LIGHT, DARK)

    fun label(mode: String): String = when (mode) {
        LIGHT -> "浅色"
        DARK -> "深色"
        else -> "跟随系统"
    }

    fun normalize(mode: String): String = if (mode in presets) mode else SYSTEM
}

object ContextLimits {    val contextPresets = listOf(16_384, 32_768, 65_536, 131_072, 262_144, 524_288, 1_048_576)
    val outputPresets = listOf(4_096, 8_192, 16_384, 32_768, 65_536, 131_072, 262_144, 393_216)

    fun label(tokens: Int): String = when {
        tokens >= 1_048_576 -> "1M"
        tokens >= 1024 -> "${tokens / 1024}K"
        else -> "$tokens"
    }
}
