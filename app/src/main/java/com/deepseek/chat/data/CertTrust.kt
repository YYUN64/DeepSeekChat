package com.deepseek.chat.data

/** TOFU 判定结果 */
enum class CertDecision {
    /** 指纹匹配，可以直接连接 */
    OK,

    /** 还没有信任过，需要用户确认 */
    NEEDS_CONFIRM,

    /** 已信任但指纹变了，必须拒绝（走「重置信任」） */
    MISMATCH
}

/** 首次连接信任判定的纯逻辑（便于单测） */
object CertTrust {

    fun evaluate(pinned: String, actual: String): CertDecision = when {
        pinned.isBlank() -> CertDecision.NEEDS_CONFIRM
        McpTls.matches(pinned, actual) -> CertDecision.OK
        else -> CertDecision.MISMATCH
    }
}
