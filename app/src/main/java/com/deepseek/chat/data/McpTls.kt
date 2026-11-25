package com.deepseek.chat.data

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 首次连接时服务端证书未被信任：fingerprint 是证书 DER 的 SHA-256（冒号大写） */
class UntrustedCertException(val fingerprint: String) : IOException("首次连接，证书未信任")

/** 已信任的指纹与当前证书不一致 */
class CertMismatchException(val actual: String, val pinned: String) :
    IOException("证书指纹不匹配")

/** TLS 指纹计算与格式化（纯逻辑，便于单测） */
object McpTls {

    /** 与 openssl / 服务器启动日志一致的格式：AA:BB:CC:… */
    fun formatFingerprint(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** 归一化用户/存储里的指纹（去空格、转大写） */
    fun normalize(fingerprint: String): String =
        fingerprint.replace(" ", "").replace(":", "").uppercase()

    fun matches(pinned: String, actual: String): Boolean =
        pinned.isNotBlank() && normalize(pinned) == normalize(actual)

    private fun trustAllContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    /** 打开 TLS 连接并取叶子证书指纹（不发送任何应用数据） */
    fun probeFingerprint(host: String, port: Int, timeoutMs: Int = 10_000): String {
        val socket = trustAllContext().socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = timeoutMs
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.first() as X509Certificate
            return formatFingerprint(certificate.encoded)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 供 HTTPS 请求使用的“信任但已 pin”的 context */
    fun pinnedContext(): SSLContext = trustAllContext()
}
