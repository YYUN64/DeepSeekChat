package com.deepseek.chat.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** MCP 工具定义（inputSchema 用 JSON 字符串保存，便于直接塞进 OpenAI tools） */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String
)

/**
 * 极简 MCP 客户端：只实现 streamable HTTP 传输下的
 * initialize / tools/list / tools/call。
 *
 * 参考 MCP 2025-06-18 规范：POST JSON-RPC，响应可能是 application/json
 * 或 text/event-stream；服务端可能用 `Mcp-Session-Id` 头维持会话。
 */
class McpClient(
    private val host: String,
    private val port: Int,
    private val https: Boolean = false,
    /** 已信任的证书指纹（TOFU）；空表示还没信任过 */
    private val pinnedFingerprint: String = "",
    /** 有 Keystore 密钥时用签名鉴权；没有密钥则请求不带鉴权（服务端未配置鉴权时才可用） */
    private val context: android.content.Context? = null
) {

    private val endpoint: String = McpEndpoint.url(host, port, https)
    private var sessionId: String? = null
    private var nextId = 1
    private var tlsVerified = false

    /** HTTPS 时先做一次 TLS 探测：没 pin 抛 UntrustedCertException，pin 不一致抛 CertMismatchException */
    private fun verifyTls() {
        if (!https || tlsVerified) return
        val actual = McpTls.probeFingerprint(McpEndpoint.hostOnly(host), port)
        if (pinnedFingerprint.isBlank()) throw UntrustedCertException(actual)
        if (!McpTls.matches(pinnedFingerprint, actual)) {
            throw CertMismatchException(actual, pinnedFingerprint)
        }
        tlsVerified = true
    }

    fun initialize() {
        call(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "deepseek-lite").put("version", "1.1.0"))
        ) ?: throw ApiException("MCP 初始化没有返回结果")
        runCatching { notify("notifications/initialized", JSONObject()) }
    }

    fun listTools(): List<McpTool> {
        val result = call("tools/list", JSONObject()) ?: return emptyList()
        val tools = result.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<McpTool>(tools.length())
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) continue
            val schema = item.optJSONObject("inputSchema")
                ?: JSONObject().put("type", "object").put("properties", JSONObject())
            out.add(McpTool(name, item.optString("description"), schema.toString()))
        }
        return out
    }

    fun callTool(name: String, argumentsJson: String): String {
        val arguments = runCatching {
            JSONObject(argumentsJson.ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        val result = call(
            "tools/call",
            JSONObject().put("name", name).put("arguments", arguments)
        ) ?: return "（工具没有返回内容）"
        val text = extractText(result)
        return if (result.optBoolean("isError", false)) "工具执行失败：$text" else text
    }

    private fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return result.toString()
        val builder = StringBuilder()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(
                if (item.optString("type") == "text") item.optString("text") else item.toString()
            )
        }
        return builder.toString().ifBlank { result.toString() }
    }

    private fun call(method: String, params: JSONObject): JSONObject? {
        val id = nextId++
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        val response = post(payload, expectResponse = true) ?: return null
        response.optJSONObject("error")?.let { error ->
            throw ApiException("MCP $method 失败：" + error.optString("message"))
        }
        return response.optJSONObject("result")
    }

    private fun notify(method: String, params: JSONObject) {
        post(
            JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params),
            expectResponse = false
        )
    }

    private fun post(payload: JSONObject, expectResponse: Boolean): JSONObject? {
        verifyTls()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        if (connection is javax.net.ssl.HttpsURLConnection) {
            // 证书已经通过指纹 pin 校验（自签也信任），这里只做传输
            connection.sslSocketFactory = McpTls.pinnedContext().socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        val conn = connection.apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            signRequest(conn, body)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { stream -> stream.write(body) }
            conn.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

            val code = conn.responseCode
            if (code == 401) {
                val detail = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                val reason = runCatching {
                    JSONObject(detail.orEmpty()).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw ApiException(
                    reason ?: "MCP 鉴权失败：服务器不接受当前请求签名，请检查「密钥鉴权」里的公钥是否已加到服务器",
                    401
                )
            }
            if (code !in 200..299) {
                val detail = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                throw ApiException(
                    "MCP HTTP $code" + (detail?.take(200)?.let { " · $it" } ?: ""), code
                )
            }
            if (!expectResponse) {
                runCatching { conn.inputStream?.close() }
                return null
            }
            val contentType = conn.getHeaderField("Content-Type") ?: ""
            val id = payload.optInt("id")
            return if (contentType.contains("text/event-stream")) {
                readSse(conn, id)
            } else {
                readJson(conn, id)
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 有 Keystore 密钥时为请求加签名头；没有密钥则不带鉴权（服务端未配置鉴权时才可用） */
    private fun signRequest(conn: HttpURLConnection, body: ByteArray) {
        if (context == null || !McpSigner.hasKey()) return
        val keyId = McpSigner.keyId() ?: return
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = McpSigner.randomNonce()
        val digest = McpSigner.sha256Hex(body)
        val message = "POST\n/mcp\n$digest\n$timestamp\n$nonce".toByteArray(Charsets.UTF_8)
        val signature = McpSigner.sign(message) ?: return
        conn.setRequestProperty("X-MCP-Key-Id", keyId)
        conn.setRequestProperty("X-MCP-Timestamp", timestamp)
        conn.setRequestProperty("X-MCP-Nonce", nonce)
        conn.setRequestProperty("X-MCP-Signature", signature)
    }

    private fun readJson(conn: HttpURLConnection, expectId: Int): JSONObject? {
        val text = conn.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optInt("id", -1) == expectId) return item
        }
        return null
    }

    private fun readSse(conn: HttpURLConnection, expectId: Int): JSONObject? {
        val input = conn.inputStream ?: return null
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val message = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (message.optInt("id", -1) == expectId &&
                    (message.has("result") || message.has("error"))
                ) {
                    return message
                }
            }
        }
        return null
    }

    companion object {
        /** MCP 工具 → OpenAI function tools 数组 */
        fun toOpenAiTools(tools: List<McpTool>): JSONArray {
            val array = JSONArray()
            for (tool in tools) {
                val parameters = runCatching { JSONObject(tool.inputSchema) }
                    .getOrDefault(JSONObject().put("type", "object").put("properties", JSONObject()))
                array.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", parameters)
                        )
                )
            }
            return array
        }
    }
}
