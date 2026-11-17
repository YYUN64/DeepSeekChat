package com.deepseek.chat.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val statusCode: Int = 0) : Exception(message)

data class StreamResult(
    val usage: Usage?,
    val finishReason: String?,
    /** 引用来源（JSON 数组字符串） */
    val annotations: String? = null,
    /** 服务端 Web 工具调用块（JSON 数组字符串，需要原样回传） */
    val serverToolCalls: String? = null
)

/**
 * DeepSeek / OpenAI 兼容的流式对话客户端。
 * 支持 thinking、reasoning_effort、图片（content 数组）。
 */
class DeepSeekClient(private val images: ImageStore) {

    @Volatile
    private var connection: HttpURLConnection? = null

    /** 取消正在进行的请求（会主动断开连接，让阻塞的读立即返回）。 */
    fun cancel() {
        val current = connection
        connection = null
        runCatching { current?.disconnect() }
    }

    suspend fun streamChat(
        settings: Settings,
        model: String,
        thinking: Boolean,
        effort: String,
        history: List<Msg>,
        systemPrompt: String,
        onReasoning: (String) -> Unit,
        onContent: (String) -> Unit
    ): StreamResult = withContext(Dispatchers.IO) {
        if (!settings.hasKey) throw ApiException("还没有填写 API Key", 401)
        if (settings.baseUrl.trim().startsWith("http://", ignoreCase = true)) {
            throw ApiException(
                "Base URL 必须是 https 地址。系统默认禁止明文 HTTP，如需连接本地网关请自行在 AndroidManifest 里开启 usesCleartextTraffic。",
                0
            )
        }

        val payload = buildPayload(settings, model, thinking, effort, history, systemPrompt)
        // 图片转 base64 可能耗时，构造完再确认一次是否已被取消
        currentCoroutineContext().ensureActive()
        val conn = (URL(endpoint(settings.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 300_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey.trim()}")
        }
        connection = conn
        // 取消动作可能发生在赋值之前（那时 cancel 只看到 null），这里补一次断开
        if (!currentCoroutineContext().isActive) {
            runCatching { conn.disconnect() }
            connection = null
            throw CancellationException("请求已取消")
        }

        try {
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { stream -> stream.write(body) }

            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(readError(conn, code), code)
            }
            val input = conn.inputStream ?: throw ApiException("服务器返回了空响应", code)

            var usage: Usage? = null
            var finishReason: String? = null
            val annotations = JSONArray()
            val serverToolCalls = JSONArray()
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break

                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    chunk.optJSONObject("usage")?.let { usage = parseUsage(it) }
                    // Web 工具的引用与调用块可能挂在顶层、delta 或 message 上，三处都收
                    collectInto(annotations, chunk.optJSONArray("annotations"))
                    collectInto(serverToolCalls, chunk.optJSONArray("server_tool_calls"))

                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.optJSONObject(0) ?: continue
                    if (!choice.isNull("finish_reason")) {
                        val finish = choice.optString("finish_reason")
                        if (finish.isNotBlank()) finishReason = finish
                    }
                    choice.optJSONObject("message")?.let { message ->
                        collectInto(annotations, message.optJSONArray("annotations"))
                        collectInto(serverToolCalls, message.optJSONArray("server_tool_calls"))
                    }

                    val delta = choice.optJSONObject("delta") ?: continue
                    collectInto(annotations, delta.optJSONArray("annotations"))
                    collectInto(serverToolCalls, delta.optJSONArray("server_tool_calls"))
                    // 注意：思考阶段的 content 是 JSON null，optString 会返回字符串 "null"，
                    // 必须先用 isNull 判断，否则正文里会混进一堆 "null"
                    if (!delta.isNull("reasoning_content")) {
                        val reasoning = delta.optString("reasoning_content")
                        if (reasoning.isNotEmpty()) onReasoning(reasoning)
                    }
                    if (!delta.isNull("content")) {
                        val content = delta.optString("content")
                        if (content.isNotEmpty()) onContent(content)
                    }
                }
            }
            StreamResult(
                usage = usage,
                finishReason = finishReason,
                annotations = annotations.takeIf { it.length() > 0 }?.toString(),
                serverToolCalls = serverToolCalls.takeIf { it.length() > 0 }?.toString()
            )
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("请求已取消")
            throw ApiException(friendlyIoMessage(e.message), 0)
        } finally {
            runCatching { conn.disconnect() }
            if (connection === conn) connection = null
        }
    }

    private fun buildPayload(
        settings: Settings,
        model: String,
        thinking: Boolean,
        effort: String,
        history: List<Msg>,
        systemPrompt: String
    ): JSONObject {
        val messages = JSONArray()
        // 单次请求的图片原始字节预算（local，避免并发请求互相扣减）
        val imageBudget = longArrayOf(MAX_IMAGE_BYTES)
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt.trim()))
        }
        val supportsVision = Models.supportsVision(model)
        val withTools = settings.webTools
        for (msg in history) {
            // 只有思考内容、没有正文的 assistant 消息会让接口报 400，直接跳过
            if (msg.role == Role.ASSISTANT && msg.text.isBlank()) continue
            messages.put(messageToJson(msg, supportsVision, withTools, imageBudget))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("max_tokens", settings.maxOutputTokens)
        if (withTools) {
            // 服务端执行的 Web 工具：搜索 / 打开网页都在 DeepSeek 侧完成，客户端不执行工具
            payload.put(
                "tools",
                JSONArray()
                    .put(JSONObject().put("type", "deepseek_web_search").put("name", "deepseek_web_search"))
                    .put(JSONObject().put("type", "deepseek_read_url").put("name", "deepseek_read_url"))
            )
        }
        if (thinking) {
            payload.put("thinking", JSONObject().put("type", "enabled"))
            payload.put("reasoning_effort", Reasoning.effortFor(effort))
        } else {
            payload.put("thinking", JSONObject().put("type", "disabled"))
        }
        return payload
    }

    private fun messageToJson(
        msg: Msg,
        supportsVision: Boolean,
        withTools: Boolean,
        imageBudget: LongArray
    ): JSONObject {
        val json = JSONObject().put("role", msg.role.wire)
        if (msg.role == Role.ASSISTANT && withTools) {
            // 带 tools 时，历史里的思考内容必须原样回传，否则 400
            if (msg.reasoning.isNotBlank()) json.put("reasoning_content", msg.reasoning)
            // 服务端工具调用块（含 kind/signature）也要原样带回，否则读过的网页会丢
            msg.serverToolCalls?.let { raw ->
                runCatching { json.put("server_tool_calls", JSONArray(raw)) }
            }
        }
        if (msg.role == Role.USER && msg.images.isNotEmpty() && supportsVision) {
            val parts = JSONArray()
            if (msg.text.isNotBlank()) {
                parts.put(JSONObject().put("type", "text").put("text", msg.text))
            }
            for (image in msg.images) {
                val size = runCatching { images.file(image).length() }.getOrDefault(0L)
                if (size <= 0L || size > imageBudget[0]) continue
                val url = images.dataUrl(image) ?: continue
                imageBudget[0] -= size
                parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", url).put("detail", "high"))
                )
            }
            if (parts.length() == 0) {
                json.put("content", msg.text)
            } else {
                json.put("content", parts)
            }
        } else if (msg.role == Role.USER && msg.images.isNotEmpty()) {
            // 模型不支持看图：保留文字，图片用占位符说明（空 content 会被接口拒绝）
            json.put(
                "content",
                if (msg.text.isBlank()) "[图片已省略]" else msg.text + "\n\n[图片已省略]"
            )
        } else {
            json.put("content", msg.text)
        }
        return json
    }

    /** 把 chunk 里出现的数组元素追加到收集器（引用、服务端工具调用） */
    private fun collectInto(target: JSONArray, source: JSONArray?) {
        if (source == null) return
        for (index in 0 until source.length()) {
            val item = source.opt(index)
            if (item != null) target.put(item)
        }
    }

    private fun parseUsage(json: JSONObject): Usage = Usage(        promptTokens = json.optInt("prompt_tokens", 0),
        completionTokens = json.optInt("completion_tokens", 0),
        totalTokens = json.optInt("total_tokens", 0),
        reasoningTokens = json.optJSONObject("completion_tokens_details")
            ?.optInt("reasoning_tokens", 0) ?: 0
    )

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().ifEmpty { Settings.DEFAULT_BASE_URL }.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun readError(conn: HttpURLConnection, code: Int): String {
        val raw = runCatching {
            (conn.errorStream ?: conn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        val detail = raw?.let { body ->
            runCatching {
                val error = JSONObject(body).optJSONObject("error")
                if (error == null || error.isNull("message")) null else error.optString("message")
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: body.take(400)
        }
        val hint = when (code) {
            400 -> "请求格式有误"
            401 -> "API Key 无效，请检查设置"
            402 -> "账户余额不足"
            403 -> "没有访问权限"
            404 -> "接口地址不存在，请检查 Base URL"
            422 -> "请求参数不合法"
            429 -> "请求太频繁，请稍后再试"
            in 500..599 -> "服务端暂时不可用，请稍后再试"
            else -> "请求失败"
        }
        return if (detail.isNullOrBlank()) "HTTP $code · $hint" else "HTTP $code · $hint\n$detail"
    }

    private fun friendlyIoMessage(message: String?): String {
        val text = message ?: return "网络连接失败，请检查网络后重试"
        return when {
            text.contains("timeout", ignoreCase = true) -> "请求超时，请检查网络或稍后重试"
            text.contains("Unable to resolve host", ignoreCase = true) -> "无法解析服务器地址，请检查网络"
            text.contains("SSL", ignoreCase = true) -> "HTTPS 连接失败：$text"
            else -> "网络异常：$text"
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    }
}
