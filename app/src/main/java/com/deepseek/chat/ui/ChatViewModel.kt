package com.deepseek.chat.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.chat.data.ChatStore
import com.deepseek.chat.data.Conversation
import com.deepseek.chat.data.ConversationMeta
import com.deepseek.chat.data.DeepSeekClient
import com.deepseek.chat.data.ImageRef
import com.deepseek.chat.data.ImageStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val lastUsage: Usage? = null
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

    fun openConversation(id: String) {
        if (_state.value.conversation?.id == id && !_state.value.streaming) return
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
        while (kept.isNotEmpty() && kept.last().role == Role.ASSISTANT) {
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

        job = viewModelScope.launch {
            var failure: String? = null
            var usage: Usage? = null
            var cancelled = false
            try {
                val result = client.streamChat(
                    settings = settings,
                    model = effectiveModel,
                    thinking = conversation.thinking,
                    effort = conversation.effort,
                    history = history,
                    systemPrompt = settings.systemPrompt,
                    onReasoning = { delta ->
                        reasoning.append(delta)
                        _state.update { it.copy(streamReasoning = reasoning.toString()) }
                    },
                    onContent = { delta ->
                        content.append(delta)
                        _state.update { it.copy(streamText = content.toString()) }
                    }
                )
                usage = result.usage
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                failure = e.message ?: "请求失败"
            }

            // 已经被新一轮请求取代：直接丢弃，避免把过期内容写进当前会话。
            // 注意 stop() 不递增 round：用户点“停止”时，已生成的内容仍会作为一条消息保留。
            if (myRound != round) return@launch

            val answer = content.toString()
            val thought = reasoning.toString()
            if (answer.isBlank() && thought.isBlank()) {
                _state.update {
                    it.copy(
                        streaming = false,
                        streamText = "",
                        streamReasoning = "",
                        error = failure ?: if (cancelled) "已停止" else "没有收到内容，请重试"
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
                model = effectiveModel
            )
            val latest = _state.value.conversation
            if (latest == null || latest.id != conversation.id) {
                // 用户已经切到别的会话：把结果写回文件，不动当前界面状态
                val stored = chatStore.load(conversation.id)
                if (stored != null) {
                    withContext(Dispatchers.IO) {
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
            withContext(Dispatchers.IO) { chatStore.save(withAnswer) }
            refreshConversations()
        }
    }

    override fun onCleared() {
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
    }
}
