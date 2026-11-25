package com.deepseek.chat.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature

/**
 * MCP 请求签名：RSA-2048 私钥保存在 Android Keystore（不可导出），
 * 服务端只保存公钥（X.509 SPKI base64）。
 *
 * 签名消息：`POST\n/mcp\n<sha256hex(body)>\n<timestamp>\n<nonce>`，
 * 算法 SHA256withRSA（PKCS#1 v1.5）。
 */
object McpSigner {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "mcp-signing"

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /** X.509 SubjectPublicKeyInfo（DER）的 base64，直接贴到服务器 authorized_keys */
    fun publicKeySpkiBase64(): String? = runCatching {
        val certificate = keyStore().getCertificate(ALIAS) ?: return null
        Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }.getOrNull()

    /** key id = sha256(SPKI) 的前 16 位 hex */
    fun keyId(): String? = runCatching {
        val certificate = keyStore().getCertificate(ALIAS) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        digest.toHex().take(16)
    }.getOrNull()

    /** 生成/重新生成密钥（同一 alias 会被覆盖；旧公钥随即失效） */
    fun generateKey(): Boolean = runCatching {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            KEYSTORE
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
        true
    }.getOrDefault(false)

    fun deleteKey(): Boolean = runCatching {
        keyStore().deleteEntry(ALIAS)
        true
    }.getOrDefault(false)

    /** 对 "POST\n/mcp\n..." 的规范消息签名，返回 base64（失败返回 null） */
    fun sign(message: ByteArray): String? = runCatching {
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey ?: return null
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(message)
        Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }.getOrNull()

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun randomNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
