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
    val effort: String = Efforts.MEDIUM,
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

    fun description(model: String): String = when (model) {
        FLASH -> "支持看图，速度快，价格低"
        PRO -> "纯文本，推理与写作更强，不支持图片"
        else -> "自定义模型"
    }

    fun supportsVision(model: String): Boolean = model == FLASH
}

object Efforts {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val MAX = "max"

    val presets = listOf(LOW, MEDIUM, HIGH, MAX)

    fun label(effort: String): String = when (effort) {
        LOW -> "低"
        MEDIUM -> "中"
        HIGH -> "高"
        MAX -> "极致"
        else -> effort
    }

    fun normalize(effort: String): String = if (effort in presets) effort else MEDIUM
}

object ContextLimits {
    val contextPresets = listOf(16_384, 32_768, 65_536, 131_072, 262_144, 524_288, 1_048_576)
    val outputPresets = listOf(4_096, 8_192, 16_384, 32_768, 65_536, 131_072, 262_144, 393_216)

    fun label(tokens: Int): String = when {
        tokens >= 1_048_576 -> "1M"
        tokens >= 1024 -> "${tokens / 1024}K"
        else -> "$tokens"
    }
}
