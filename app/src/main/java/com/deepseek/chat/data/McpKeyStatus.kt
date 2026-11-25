package com.deepseek.chat.data

/**
 * 密钥状态的展示文案（纯函数，便于单测）。
 * key id = sha256(公钥 SPKI) 的前 16 位 hex，本身就是短指纹。
 */
object McpKeyStatus {

    fun label(keyId: String?): String =
        if (keyId.isNullOrBlank()) "未生成" else "已生成 · key id ${shortId(keyId)}"

    /** 状态行里的短 id，例如 73b3e492… */
    fun shortId(keyId: String): String {
        val trimmed = keyId.trim()
        return if (trimmed.length <= 8) trimmed else trimmed.take(8) + "…"
    }

    /** 弹层里展示的完整指纹，每 4 位一组，便于核对 */
    fun fingerprint(keyId: String?): String {
        val trimmed = keyId.orEmpty().trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.chunked(4).joinToString(" ")
    }
}
