package com.deepseek.chat.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.chat.data.ApiException
import com.deepseek.chat.data.BuiltinPrompts
import com.deepseek.chat.data.ChatStore
import com.deepseek.chat.data.Conversation
import com.deepseek.chat.data.ConversationMeta
import com.deepseek.chat.data.DeepSeekClient
import com.deepseek.chat.data.ImageRef
import com.deepseek.chat.data.ImageStore
import com.deepseek.chat.data.McpClient
import com.deepseek.chat.data.Msg
import com.deepseek.chat.data.Models
import com.deepseek.chat.data.Reasoning
import com.deepseek.chat.data.Role
import com.deepseek.chat.data.Settings
import com.deepseek.chat.data.SettingsStore
import com.deepseek.chat.data.TokenCounter
import com.deepseek.chat.data.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class ChatUiState(
    val settings: Settings = Settings(),
    val conversations: List<ConversationMeta> = emptyList(),
    val conversation: Conversation? = null,
    val input: String = "",
    val pendingImages: List<ImageRef> = emptyList(),
    val streaming: Boolean = false,
    val streamText: String = "",
    val streamReasoning: String = "",
    val error: String? = null,
    val notice: String? = null,
    val showSettings: Boolean = false,
    val lastUsage: Usage? = null,
    /** 正在执行的工具名（MCP），null 表示没有 */
    val toolStatus: String? = null
) {
    val messages: List<Msg> get() = conversation?.messages.orEmpty()
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val imageStore = ImageStore(application)
    private val chatStore = ChatStore(File(application.filesDir, "chat"))
    private val client = DeepSeekClient(imageStore)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 请求轮次：取消/切换后用来丢弃过期回合的回写 */
    private var round = 0

    /** 本轮流式输出的检查点消息 id：进程被杀前留底用，正常结束会被完整消息覆盖 */
    private var checkpointId = ""

    /** 流式文本推给 UI 的节流时间戳（~12Hz），避免每个 delta 都整页重组 */
    private var lastStreamPush = 0L

    /** 输入会话令牌：发消息之后才导入完成的图片不应再挂到输入框上 */
    private var inputSession = 0

    private val bitmapCache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        val settings = settingsStore.load()
        // 冷启动一律从新会话开始（历史仍在抽屉里）。进程存活期间的返回不受影响。
        val fresh = Conversation(
            id = UUID.randomUUID().toString(),
            model = settings.model,
            thinking = settings.thinking,
            effort = settings.effort
        )
        _state.update { it.copy(settings = settings, conversation = fresh) }
        // 历史列表等打开抽屉时再读（见 loadHistory），启动阶段不碰磁盘
    }

    /** 打开历史抽屉时调用：懒加载会话列表 */
    fun loadHistory() {
        refreshConversations()
    }

    // ---------------------------------------------------------------- 图片

    fun bitmapOf(ref: ImageRef, maxEdge: Int): Bitmap? {
        val key = ref.fileName + "@" + maxEdge
        bitmapCache.get(key)?.let { return it }
        val bitmap = imageStore.bitmap(ref, maxEdge) ?: return null
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    fun addImage(uri: Uri) {
        val current = _state.value
        if (current.pendingImages.size >= MAX_IMAGES) {
            _state.update { it.copy(error = "一条消息最多 $MAX_IMAGES 张图片") }
            return
        }
        val session = inputSession
        viewModelScope.launch {
            val ref = withContext(Dispatchers.IO) { imageStore.import(uri) }
            when {
                ref == null -> _state.update { it.copy(error = "这张图片读取失败了，换一张试试") }
                session != inputSession -> ref?.let { imageStore.delete(listOf(it)) }
                else -> _state.update { state ->
                    if (state.pendingImages.size >= MAX_IMAGES) {
                        state.copy(error = "一条消息最多 $MAX_IMAGES 张图片")
                    } else {
                        state.copy(pendingImages = state.pendingImages + ref, error = null)
                    }
                }
            }
        }
    }

    fun removeImage(ref: ImageRef) {
        _state.update {
            it.copy(pendingImages = it.pendingImages.filterNot { image -> image.fileName == ref.fileName })
        }
        imageStore.delete(listOf(ref))
    }

    // ---------------------------------------------------------------- 会话

    fun newConversation() {
        flushPartial()
        stopStreaming()
        inputSession++
        val settings = _state.value.settings
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            model = settings.model,
            thinking = settings.thinking,
            effort = settings.effort
        )
        _state.update {
            it.copy(
                conversation = conversation,
                input = "",
                pendingImages = emptyList(),
                streamText = "",
                streamReasoning = "",
                error = null,
                notice = null
            )
        }
    }

    /**
     * 切换到别的会话前，把正在流式输出的内容落盘。
     * 否则 stopStreaming() 之后立刻从磁盘 load，读到的还是旧内容，思考过程就丢了。
     */
    private fun flushPartial() {
        val state = _state.value
        val conversation = state.conversation ?: return
        if (!state.streaming) return
        val text = state.streamText
        val thought = state.streamReasoning
        if (text.isBlank() && thought.isBlank()) return
        val partial = Msg(
            id = UUID.randomUUID().toString(),
            role = Role.ASSISTANT,
            text = text,
            reasoning = thought,
            error = "已停止"
        )
        val updated = conversation.copy(
            messages = conversation.messages + partial,
            updatedAt = System.currentTimeMillis()
        )
        runCatching { chatStore.save(updated) }
        // 让还在跑的那次请求作废，避免它结束后再补一条重复消息
        round++
        _state.update { it.copy(conversation = updated, streaming = false, streamText = "", streamReasoning = "") }
    }

    /**
     * 把正在流式输出的内容落盘，但不打断这次生成。
     *
     * 用于 Activity 进入后台 / 进程可能被系统回收时留底：否则思考过程只存在于内存，
     * 进程一死回来就只剩用户提问。正常生成结束后会用完整消息覆盖同一个 id。
     */
    fun saveStreamingCheckpoint() {
        val snapshot = _state.value
        val conversation = snapshot.conversation ?: return
        if (!snapshot.streaming) return
        if (snapshot.streamText.isBlank() && snapshot.streamReasoning.isBlank()) return
        val id = checkpointId.ifBlank { return }
        val partial = Msg(
            id = id,
            role = Role.ASSISTANT,
            text = snapshot.streamText,
            reasoning = snapshot.streamReasoning,
            error = "已停止"
        )
        // 同一轮里重复进入后台只留一条检查点
        val messages = conversation.messages.filterNot { it.id == id } + partial
        runCatching {
            chatStore.save(
                conversation.copy(
                    messages = messages,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun openConversation(id: String) {
        if (_state.value.conversation?.id == id && !_state.value.streaming) return
        flushPartial()
        stopStreaming()
        inputSession++
        val conversation = chatStore.load(id)
        if (conversation == null) {
            _state.update { it.copy(error = "会话读取失败") }
            return
        }
        _state.update {
            it.copy(
                conversation = conversation,
                input = "",
                pendingImages = emptyList(),
                streamText = "",
                streamReasoning = "",
                error = null,
                notice = null,
                lastUsage = null
            )
        }
    }

    fun deleteConversation(id: String) {
        // 先按会话内容回收它独占的图片文件
        chatStore.load(id)?.messages?.flatMap { it.images }?.let { imageStore.delete(it) }
        chatStore.delete(id)
        val isCurrent = _state.value.conversation?.id == id
        if (isCurrent) {
            stopStreaming()
            _state.update { it.copy(conversation = null, streamText = "", streamReasoning = "") }
            newConversation()
        }
        refreshConversations()
    }

    fun renameConversation(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val current = _state.value.conversation
        if (current != null && current.id == id) {
            val updated = current.copy(title = trimmed)
            _state.update { it.copy(conversation = updated) }
            // 还没发过言的草稿不落盘，免得历史里堆空会话
            if (updated.messages.isNotEmpty()) chatStore.save(updated)
        } else {
            val stored = chatStore.load(id) ?: return
            chatStore.save(stored.copy(title = trimmed))
        }
        refreshConversations()
    }

    fun deleteAllConversations() {
        stopStreaming()
        chatStore.deleteAll()
        imageStore.deleteAll()
        _state.update {
            it.copy(
                conversations = emptyList(),
                conversation = null,
                input = "",
                pendingImages = emptyList(),
                streamText = "",
                streamReasoning = ""
            )
        }
        newConversation()
        _state.update { it.copy(notice = "已清空全部会话") }
    }

    // ---------------------------------------------------------------- 本轮参数

    fun setModel(model: String) {
        applyConversation(ensureConversation().copy(model = model))
    }

    fun setReasoning(level: String) {
        applyConversation(
            ensureConversation().copy(
                thinking = Reasoning.thinkingOf(level),
                effort = Reasoning.effortFor(level)
            )
        )
    }

    // ---------------------------------------------------------------- 发送

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.streaming) return
        val text = snapshot.input.trim()
        val images = snapshot.pendingImages
        if (text.isEmpty() && images.isEmpty()) return

        if (!snapshot.settings.hasKey) {
            _state.update { it.copy(showSettings = true, error = "请先在设置里填好 API Key") }
            return
        }

        val base = snapshot.conversation ?: ensureConversation()
        val userMsg = Msg(
            id = UUID.randomUUID().toString(),
            role = Role.USER,
            text = text,
            images = images
        )
        var updated = base.copy(
            messages = base.messages + userMsg,
            updatedAt = System.currentTimeMillis()
        )
        if (updated.title.isBlank() || updated.title == Conversation.DEFAULT_TITLE) {
            updated = updated.copy(title = titleFor(text, images))
        }
        _state.update {
            it.copy(
                conversation = updated,
                input = "",
                pendingImages = emptyList(),
                streamText = "",
                streamReasoning = "",
                error = null,
                notice = null,
                streaming = true,
                lastUsage = null
            )
        }
        inputSession++
        viewModelScope.launch {
            withContext(Dispatchers.IO) { chatStore.save(updated) }
            refreshConversations()
        }
        launchCompletion(updated)
    }

    fun regenerate() {
        val current = _state.value.conversation ?: return
        if (_state.value.streaming) return
        val kept = current.messages.toMutableList()
        // 工具调用会在 assistant 中间插入「分段正文」和「工具步骤」，重试时要一起回退到用户消息
        while (kept.isNotEmpty() && kept.last().role != Role.USER) {
            kept.removeAt(kept.size - 1)
        }
        if (kept.isEmpty() || kept.last().role != Role.USER) return
        val updated = current.copy(messages = kept, updatedAt = System.currentTimeMillis())
        _state.update {
            it.copy(
                conversation = updated,
                streamText = "",
                streamReasoning = "",
                error = null,
                streaming = true,
                lastUsage = null
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { chatStore.save(updated) }
            refreshConversations()
        }
        launchCompletion(updated)
    }

    fun stop() {
        stopStreaming()
    }

    private fun launchCompletion(conversation: Conversation) {
        val settings = _state.value.settings
        val requestedModel = conversation.model.ifBlank { settings.model }
        // 只看这条新消息有没有图：历史里的图由 client 按模型能力自动剔除
        val newHasImages = conversation.messages.lastOrNull()?.images?.isNotEmpty() == true
        val effectiveModel = if (newHasImages && !Models.supportsVision(requestedModel)) {
            Models.FLASH
        } else {
            requestedModel
        }
        if (effectiveModel != requestedModel) {
            _state.update {
                it.copy(
                    notice = "${Models.label(requestedModel)} 不支持图片，这次改用 ${Models.label(effectiveModel)} 发送"
                )
            }
        }
        val budget = budgetOf(settings)
        val history = TokenCounter.trim(conversation.messages, budget)

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val myRound = ++round
        checkpointId = UUID.randomUUID().toString()
        lastStreamPush = 0L

        job = viewModelScope.launch {
            var failure: String? = null
            var usage: Usage? = null
            var citations: String? = null
            var toolCalls: String? = null
            var cancelled = false

            // 只有显式开启且填了地址才去连 MCP，避免没服务器时白等
            var mcp: McpClient? = null
            var functionTools: String? = null
            if (settings.mcpEnabled && settings.mcpHost.isNotBlank()) {
                try {
                    val prepared = withContext(Dispatchers.IO) {
                        val client = McpClient(settings.mcpHost, settings.mcpPort, settings.mcpToken)
                        client.initialize()
                        client to client.listTools()
                    }
                    mcp = prepared.first
                    val tools = prepared.second
                    if (tools.isEmpty()) {
                        mcp = null
                        _state.update { it.copy(notice = "MCP 服务器没有提供工具，本轮用普通对话") }
                    } else {
                        functionTools = McpClient.toOpenAiTools(tools).toString()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mcp = null
                    _state.update { it.copy(notice = "MCP 连接失败，本轮用普通对话：${e.message}") }
                }
            }

            val requestMessages = history.toMutableList()
            var rounds = 0
            // 工具轮数上限来自设置（64 = 「不限」，内部仍封顶 64）
            val maxToolRounds = settings.effectiveMcpRounds()
            var webFallback = false
            // 已显示的内容只追加不清空：模型先说话、再调用工具时，前面的文字必须留在屏幕上。
            // 但每次工具调用都会把「本轮」增量固化成一条消息，下一轮从空白重新开始，
            // 这样工具卡片能插在「思考」和「最终答案」中间，而不是被后来的文字盖住。
            var contentStart = 0
            var reasoningStart = 0
            // 是否已经因为工具调用固化过内容：最后一轮没有正文时不该再报「没有收到内容」
            var sawToolRound = false
            try {
                while (true) {
                    contentStart = content.length
                    reasoningStart = reasoning.length
                    val result = try {
                        client.streamChat(
                            settings = settings,
                            model = effectiveModel,
                            thinking = conversation.thinking,
                            effort = conversation.effort,
                            history = requestMessages,
                            systemPrompt = BuiltinPrompts.compose(settings.systemPrompt),
                            functionTools = functionTools,
                            onReasoning = { delta ->
                                reasoning.append(delta)
                                publishStream(reasoning = reasoning.substring(reasoningStart))
                            },
                            onContent = { delta ->
                                content.append(delta)
                                publishStream(content = content.substring(contentStart))
                            }
                        )
                    } catch (e: ApiException) {
                        // 工具声明被拒绝时退回普通请求，保证还能聊
                        if (e.statusCode == 400 && !webFallback &&
                            (functionTools != null || settings.webTools)
                        ) {
                            webFallback = true
                            functionTools = null
                            mcp = null
                            _state.update { it.copy(notice = "工具请求被拒绝，已退回普通对话") }
                            continue
                        }
                        throw e
                    }
                    usage = result.usage
                    citations = result.annotations
                    toolCalls = result.serverToolCalls

                    val calls = result.toolCallsJson
                    val activeMcp = mcp
                    if (calls.isNullOrBlank() || activeMcp == null) break
                    if (rounds >= maxToolRounds) {
                        failure = "工具调用超过 $maxToolRounds 轮，已停止"
                        break
                    }
                    rounds++

                    // 1) 工具调用前这一轮的正文/思考固化成可见消息：
                    //    思考面板随之从「深度思考中…」变成「已深度思考」，不再和工具卡片抢位置
                    val segmentText = content.substring(contentStart)
                    val segmentThought = reasoning.substring(reasoningStart)
                    if (segmentText.isNotBlank() || segmentThought.isNotBlank()) {
                        appendVisible(
                            conversation.id,
                            myRound,
                            Msg(
                                id = UUID.randomUUID().toString(),
                                role = Role.ASSISTANT,
                                text = segmentText,
                                reasoning = segmentThought,
                                model = effectiveModel,
                                intermediate = true
                            )
                        )
                        sawToolRound = true
                    }
                    // 下一轮从空白开始展示，避免和刚固化的内容重复
                    _state.update { it.copy(streamText = "", streamReasoning = "") }
                    lastStreamPush = 0L

                    // 2) 带 tool_calls 的 assistant 轮次接进给模型的上下文
                    requestMessages += Msg(
                        id = UUID.randomUUID().toString(),
                        role = Role.ASSISTANT,
                        text = segmentText,
                        reasoning = segmentThought,
                        toolCallsJson = calls,
                        model = effectiveModel
                    )
                    val array = JSONArray(calls)
                    for (index in 0 until array.length()) {
                        val call = array.optJSONObject(index) ?: continue
                        val function = call.optJSONObject("function")
                        val name = function?.optString("name").orEmpty()
                        val arguments = function?.optString("arguments").orEmpty().ifBlank { "{}" }
                        // 3) 先落一张「执行中」的工具卡片（可见、可持久化），跑完再补结果
                        val stepId = UUID.randomUUID().toString()
                        appendVisible(
                            conversation.id,
                            myRound,
                            Msg(
                                id = stepId,
                                role = Role.TOOL_STEP,
                                toolCallId = call.optString("id"),
                                toolName = name.ifBlank { "tool" },
                                toolArgs = summarize(arguments, 200)
                            )
                        )
                        sawToolRound = true
                        // 底栏状态只是补充，整个工具执行期间都留着，不再设置后立刻清空
                        _state.update { it.copy(toolStatus = "正在调用工具：$name") }
                        var failed = false
                        val output = withContext(Dispatchers.IO) {
                            runCatching { activeMcp.callTool(name, arguments) }
                                .onFailure { failed = true }
                                .getOrElse { "工具调用失败：${it.message}" }
                        }
                        updateVisible(conversation.id, myRound, stepId) {
                            it.copy(
                                text = if (failed) "" else summarize(output, 400),
                                error = if (failed) "调用失败：${summarize(output, 200)}" else null
                            )
                        }
                        requestMessages += Msg(
                            id = UUID.randomUUID().toString(),
                            role = Role.TOOL,
                            toolCallId = call.optString("id"),
                            text = output.take(MAX_TOOL_OUTPUT)
                        )
                    }
                    _state.update { it.copy(toolStatus = null) }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                failure = e.message ?: "请求失败"
            } finally {
                _state.update { it.copy(toolStatus = null) }
                // 停止/异常时正在跑的工具卡片不能永远停在「执行中」
                markStaleToolSteps(conversation.id, myRound)
            }

            // 已经被新一轮请求取代：直接丢弃，避免把过期内容写进当前会话。
            // 注意 stop() 不递增 round：用户点“停止”时，已生成的内容仍会作为一条消息保留。
            if (myRound != round) return@launch

            // 最后一轮（工具调用之后）的增量才是最终答案
            val answer = content.substring(contentStart)
            val thought = reasoning.substring(reasoningStart)
            if (answer.isBlank() && thought.isBlank()) {
                _state.update {
                    it.copy(
                        streaming = false,
                        streamText = "",
                        streamReasoning = "",
                        error = when {
                            failure != null -> failure
                            // 工具步骤已经展示过内容，这里只是本轮没有正文，不报错
                            sawToolRound -> null
                            cancelled -> "已停止"
                            else -> "没有收到内容，请重试"
                        }
                    )
                }
                return@launch
            }

            val assistant = Msg(
                id = UUID.randomUUID().toString(),
                role = Role.ASSISTANT,
                text = answer,
                reasoning = thought,
                usage = usage,
                error = failure,
                model = effectiveModel,
                serverToolCalls = toolCalls,
                annotations = citations
            )
            val latest = _state.value.conversation
            if (latest == null || latest.id != conversation.id) {
                // 用户已经切到别的会话：把结果写回文件，不动当前界面状态
                val stored = chatStore.load(conversation.id)
                if (stored != null) {
                    // 用户点过“停止”时本协程已被取消：落盘必须放在 NonCancellable 里，
                    // 否则 withContext 会直接抛 CancellationException，界面上有、磁盘上没有
                    withContext(NonCancellable + Dispatchers.IO) {
                        chatStore.save(
                            stored.copy(
                                messages = stored.messages + assistant,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    refreshConversations()
                    // 写盘是挂起点，期间可能已经开了新一轮，别把它的流式状态清掉
                    if (myRound != round) return@launch
                }
                _state.update { it.copy(streaming = false, streamText = "", streamReasoning = "") }
                return@launch
            }
            val withAnswer = latest.copy(
                messages = latest.messages + assistant,
                updatedAt = System.currentTimeMillis()
            )
            // 先提交 UI（不挂起），再落盘，避免挂起期间状态被别处改掉
            _state.update {
                it.copy(
                    conversation = withAnswer,
                    streaming = false,
                    streamText = "",
                    streamReasoning = "",
                    lastUsage = usage,
                    error = null
                )
            }
            withContext(NonCancellable + Dispatchers.IO) { chatStore.save(withAnswer) }
            refreshConversations()
        }
    }

    /**
     * 流式文本节流后再推给 UI（~12Hz）。
     * 服务端 delta 每秒可能几十个，每个都 setState 会让整页（含 token 估算、顶栏小字）
     * 跟着重组，跟随滚动的节奏也会变得忽快忽慢。
     */
    private fun publishStream(reasoning: String? = null, content: String? = null) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStreamPush < STREAM_PUSH_INTERVAL_MS) return
        lastStreamPush = now
        _state.update {
            it.copy(
                streamReasoning = reasoning ?: it.streamReasoning,
                streamText = content ?: it.streamText
            )
        }
    }

    /**
     * 把一条可见消息（工具步骤 / 工具调用前的 assistant 分段）追加到当前会话并立即落盘。
     * 只在本轮流式会话仍有效时执行，避免切会话后把内容写到别的会话里。
     */
    private fun appendVisible(conversationId: String, myRound: Int, msg: Msg) {
        if (myRound != round) return
        val current = _state.value.conversation ?: return
        if (current.id != conversationId) return
        val updated = current.copy(
            messages = current.messages + msg,
            updatedAt = System.currentTimeMillis()
        )
        _state.update { it.copy(conversation = updated) }
        runCatching { chatStore.save(updated) }
    }

    /** 更新一条已追加的可见消息（例如工具执行结果）并落盘 */
    private fun updateVisible(conversationId: String, myRound: Int, id: String, transform: (Msg) -> Msg) {
        if (myRound != round) return
        val current = _state.value.conversation ?: return
        if (current.id != conversationId) return
        val updated = current.copy(
            messages = current.messages.map { if (it.id == id) transform(it) else it },
            updatedAt = System.currentTimeMillis()
        )
        _state.update { it.copy(conversation = updated) }
        runCatching { chatStore.save(updated) }
    }

    /** 把工具参数/结果压成一行摘要，便于卡片展示 */
    private fun summarize(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }

    /** 本轮结束时仍没有结果的工具步骤（被停止/异常）标记为中断，避免卡片一直显示「执行中…」 */
    private fun markStaleToolSteps(conversationId: String, myRound: Int) {
        if (myRound != round) return
        val current = _state.value.conversation ?: return
        if (current.id != conversationId) return
        val hasRunning = current.messages.any {
            it.role == Role.TOOL_STEP && it.text.isBlank() && it.error == null
        }
        if (!hasRunning) return
        val updated = current.copy(
            messages = current.messages.map {
                if (it.role == Role.TOOL_STEP && it.text.isBlank() && it.error == null) {
                    it.copy(error = "执行已中断")
                } else {
                    it
                }
            },
            updatedAt = System.currentTimeMillis()
        )
        _state.update { it.copy(conversation = updated) }
        runCatching { chatStore.save(updated) }
    }

    override fun onCleared() {
        // Activity 直接销毁（返回键退出等）时兜底留一份思考过程
        saveStreamingCheckpoint()
        client.cancel()
        job?.cancel()
        job = null
        super.onCleared()
    }

    private fun stopStreaming() {
        client.cancel()
        job?.cancel()
        job = null
        _state.update { it.copy(streaming = false, streamText = "", streamReasoning = "") }
    }

    // ---------------------------------------------------------------- 设置

    fun setShowSettings(show: Boolean) {
        _state.update { it.copy(showSettings = show, error = null) }
    }

    /** 设置页「测试连接」：握手并列出工具名 */
    fun testMcp() {
        val settings = _state.value.settings
        if (!settings.mcpEnabled || settings.mcpHost.isBlank()) {
            _state.update { it.copy(notice = "先打开 MCP 开关并填好服务器地址") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(notice = "正在连接 MCP…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = McpClient(settings.mcpHost, settings.mcpPort, settings.mcpToken)
                    client.initialize()
                    client.listTools()
                }
            }
            _state.update {
                it.copy(
                    notice = result.fold(
                        onSuccess = { tools ->
                            if (tools.isEmpty()) "连上了，但服务器没有提供工具"
                            else "MCP 连接成功，工具：" + tools.joinToString("、") { tool -> tool.name }
                        },
                        onFailure = { e -> "MCP 连接失败：${e.message}" }
                    )
                )
            }
        }
    }

    fun updateSettings(settings: Settings) {
        settingsStore.save(settings)
        _state.update { current ->
            val conversation = current.conversation
            val synced = if (conversation != null && conversation.messages.isEmpty()) {
                conversation.copy(
                    model = settings.model,
                    thinking = settings.thinking,
                    effort = settings.effort
                )
            } else {
                conversation
            }
            current.copy(settings = settings, conversation = synced)
        }
    }

    // ---------------------------------------------------------------- 杂项

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun ensureConversation(): Conversation {
        val existing = _state.value.conversation
        if (existing != null) return existing
        val settings = _state.value.settings
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            model = settings.model,
            thinking = settings.thinking,
            effort = settings.effort
        )
        _state.update { it.copy(conversation = conversation) }
        return conversation
    }

    private fun applyConversation(conversation: Conversation) {
        _state.update { it.copy(conversation = conversation) }
        if (conversation.messages.isNotEmpty()) {
            chatStore.save(conversation)
            refreshConversations()
        }
    }

    private var refreshTicket = 0

    private fun refreshConversations() {
        val ticket = ++refreshTicket
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { chatStore.list() }
            if (ticket == refreshTicket) {
                _state.update { it.copy(conversations = metas) }
            }
        }
    }

    private fun budgetOf(settings: Settings): Int {
        val reserved = settings.maxOutputTokens.coerceAtLeast(1024)
        return (settings.contextTokens - reserved).coerceAtLeast(2048)
    }

    private fun titleFor(text: String, images: List<ImageRef>): String {
        val single = text.replace("\n", " ").trim()
        return when {
            single.isNotEmpty() -> if (single.length <= 24) single else single.take(24) + "…"
            images.isNotEmpty() -> "图片对话"
            else -> Conversation.DEFAULT_TITLE
        }
    }

    private companion object {
        const val MAX_IMAGES = 6
        const val MAX_TOOL_OUTPUT = 8000

        /** 流式文本推给 UI 的最小间隔 */
        const val STREAM_PUSH_INTERVAL_MS = 80L
    }
}
