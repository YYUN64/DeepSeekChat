package com.deepseek.chat.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 每个会话存成一个 JSON 文件，简单、抗损坏，便于导出。
 */
class ChatStore(root: File) {

    private val dir: File = File(root, "conversations").apply {
        if (!exists()) mkdirs()
    }

    fun list(): List<ConversationMeta> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        val metas = ArrayList<ConversationMeta>(files.size)
        for (file in files) {
            val conv = runCatching { read(file) }.getOrNull() ?: continue
            metas.add(
                ConversationMeta(
                    id = conv.id,
                    title = conv.title,
                    updatedAt = conv.updatedAt,
                    messageCount = conv.messages.size
                )
            )
        }
        return metas.filter { it.messageCount > 0 }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Conversation? =
        runCatching { read(fileFor(id)) }.getOrNull()
            ?: runCatching { read(File(dir, "$id.json.tmp")) }.getOrNull()

    fun save(conversation: Conversation) {
        val target = fileFor(conversation.id)
        val tmp = File(dir, "${conversation.id}.json.tmp")
        try {
            tmp.writeText(toJson(conversation).toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            return
        }
        if (!tmp.renameTo(target)) {
            // 个别文件系统上 rename 不能覆盖已有文件：删掉旧文件再试一次，
            // 仍然失败就把 tmp 留着，load() 会兜底读取它
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { file -> runCatching { file.delete() } }
    }

    private fun fileFor(id: String): File = File(dir, "$id.json")

    private fun read(file: File): Conversation {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val messages = ArrayList<Msg>()
        val array = root.optJSONArray("messages")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                messages.add(msgFromJson(item))
            }
        }
        val now = System.currentTimeMillis()
        return Conversation(
            id = root.optString("id").ifBlank { file.nameWithoutExtension },
            title = root.optString("title").ifBlank { Conversation.DEFAULT_TITLE },
            createdAt = root.optLong("createdAt", now),
            updatedAt = root.optLong("updatedAt", now),
            model = root.optString("model").ifBlank { Models.FLASH },
            thinking = root.optBoolean("thinking", true),
            effort = Reasoning.effortFor(root.optString("effort")),
            messages = messages
        )
    }

    private fun toJson(conversation: Conversation): JSONObject {
        val messages = JSONArray()
        for (msg in conversation.messages) messages.put(msgToJson(msg))
        return JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("createdAt", conversation.createdAt)
            .put("updatedAt", conversation.updatedAt)
            .put("model", conversation.model)
            .put("thinking", conversation.thinking)
            .put("effort", conversation.effort)
            .put("messages", messages)
    }

    private fun msgToJson(msg: Msg): JSONObject {
        val images = JSONArray()
        for (image in msg.images) {
            images.put(
                JSONObject()
                    .put("fileName", image.fileName)
                    .put("width", image.width)
                    .put("height", image.height)
            )
        }
        // 文本附件：只存元数据（文件名/大小/是否截断）+ 拼好的文件块文本，绝不存二进制
        val attachments = JSONArray()
        for (item in msg.attachments) {
            attachments.put(
                JSONObject()
                    .put("name", item.name)
                    .put("sizeBytes", item.sizeBytes)
                    .put("truncated", item.truncated)
            )
        }
        val json = JSONObject()
            .put("id", msg.id)
            .put("role", msg.role.wire)
            .put("text", msg.text)
            .put("reasoning", msg.reasoning)
            .put("createdAt", msg.createdAt)
            .put("images", images)
            .put("attachments", attachments)
        if (!msg.fileBlocks.isNullOrBlank()) json.put("fileBlocks", msg.fileBlocks)
        if (msg.error != null) json.put("error", msg.error)
        if (msg.model != null) json.put("model", msg.model)
        if (msg.serverToolCalls != null) json.put("serverToolCalls", msg.serverToolCalls)
        if (msg.annotations != null) json.put("annotations", msg.annotations)
        if (msg.toolCallsJson != null) json.put("toolCallsJson", msg.toolCallsJson)
        if (msg.toolCallId != null) json.put("toolCallId", msg.toolCallId)
        if (msg.toolName != null) json.put("toolName", msg.toolName)
        if (msg.toolArgs != null) json.put("toolArgs", msg.toolArgs)
        if (msg.toolDurationMs != null) json.put("toolDurationMs", msg.toolDurationMs)
        if (msg.toolExitCode != null) json.put("toolExitCode", msg.toolExitCode)
        if (msg.toolTimedOut) json.put("toolTimedOut", true)
        if (msg.toolTimeoutSeconds != null) json.put("toolTimeoutSeconds", msg.toolTimeoutSeconds)
        if (msg.toolCancelled) json.put("toolCancelled", true)
        if (msg.intermediate) json.put("intermediate", true)
        val usage = msg.usage
        if (usage != null) {
            json.put(
                "usage",
                JSONObject()
                    .put("prompt", usage.promptTokens)
                    .put("completion", usage.completionTokens)
                    .put("total", usage.totalTokens)
                    .put("reasoning", usage.reasoningTokens)
            )
        }
        return json
    }

    private fun msgFromJson(json: JSONObject): Msg {
        val images = ArrayList<ImageRef>()
        val imageArray = json.optJSONArray("images")
        if (imageArray != null) {
            for (i in 0 until imageArray.length()) {
                val item = imageArray.optJSONObject(i) ?: continue
                val name = item.optString("fileName")
                if (name.isNotBlank()) {
                    images.add(
                        ImageRef(
                            fileName = name,
                            width = item.optInt("width", 0),
                            height = item.optInt("height", 0)
                        )
                    )
                }
            }
        }
        // 文本附件元数据（老会话没有这个字段 → 空列表，text 里的内联内容保持原样）
        val attachments = ArrayList<AttachmentRef>()
        val attachmentArray = json.optJSONArray("attachments")
        if (attachmentArray != null) {
            for (i in 0 until attachmentArray.length()) {
                val item = attachmentArray.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isNotBlank()) {
                    attachments.add(
                        AttachmentRef(
                            name = name,
                            sizeBytes = item.optLong("sizeBytes", 0L),
                            truncated = item.optBoolean("truncated", false)
                        )
                    )
                }
            }
        }
        var usage: Usage? = null
        val usageJson = json.optJSONObject("usage")
        if (usageJson != null) {
            usage = Usage(
                promptTokens = usageJson.optInt("prompt", 0),
                completionTokens = usageJson.optInt("completion", 0),
                totalTokens = usageJson.optInt("total", 0),
                reasoningTokens = usageJson.optInt("reasoning", 0)
            )
        }
        val msg = Msg(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            role = Role.fromWire(json.optString("role")),
            text = json.optString("text"),
            reasoning = json.optString("reasoning"),
            images = images,
            attachments = attachments,
            fileBlocks = json.optString("fileBlocks").takeIf { it.isNotBlank() },
            createdAt = json.optLong("createdAt", 0L),
            usage = usage,
            error = json.optString("error").takeIf { it.isNotBlank() },
            model = json.optString("model").takeIf { it.isNotBlank() },
            serverToolCalls = json.optString("serverToolCalls").takeIf { it.isNotBlank() },
            annotations = json.optString("annotations").takeIf { it.isNotBlank() },
            toolCallsJson = json.optString("toolCallsJson").takeIf { it.isNotBlank() },
            toolCallId = json.optString("toolCallId").takeIf { it.isNotBlank() },
            toolName = json.optString("toolName").takeIf { it.isNotBlank() },
            toolArgs = json.optString("toolArgs").takeIf { it.isNotBlank() },
            toolDurationMs = if (json.has("toolDurationMs")) json.optLong("toolDurationMs") else null,
            toolExitCode = if (json.has("toolExitCode")) json.optInt("toolExitCode") else null,
            toolTimedOut = json.optBoolean("toolTimedOut", false),
            toolCancelled = json.optBoolean("toolCancelled", false),
            toolTimeoutSeconds = if (json.has("toolTimeoutSeconds")) {
                json.optInt("toolTimeoutSeconds")
            } else {
                null
            },
            intermediate = json.optBoolean("intermediate", false)
        )
        // 上次进程在工具执行中途被杀：卡片不能永远停在「执行中…」
        return if (msg.role == Role.TOOL_STEP && msg.text.isBlank() && msg.error == null) {
            msg.copy(error = "执行已中断")
        } else {
            msg
        }
    }
}
