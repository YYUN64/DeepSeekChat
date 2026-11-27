package com.deepseek.chat.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.chat.ChatEngine
import com.deepseek.chat.ChatService
import com.deepseek.chat.data.ApiException
import com.deepseek.chat.data.CertMismatchException
import com.deepseek.chat.data.AttachmentRef
import com.deepseek.chat.data.AttachmentStore
import com.deepseek.chat.data.ComposedMessage
import com.deepseek.chat.data.ConversationMarkdown
import com.deepseek.chat.data.FileReadResult
import com.deepseek.chat.data.MessageQueue
import com.deepseek.chat.data.QueuedMessage
import com.deepseek.chat.data.TextAttachment
import com.deepseek.chat.data.TextFiles
import com.deepseek.chat.data.McpEndpoint
import com.deepseek.chat.data.McpErrors
import com.deepseek.chat.data.McpTool
import com.deepseek.chat.data.McpToolFilter
import com.deepseek.chat.data.McpTls
import com.deepseek.chat.data.UntrustedCertException
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
import kotlinx.coroutines.delay
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
    /** 待发送的文本附件（内容只在内存，发送时拼进消息文本） */
    val pendingFiles: List<TextAttachment> = emptyList(),
    /** 生成中按发送后排队、等当前生成结束依次自动发送（只在内存，进程被杀即丢） */
    val queued: List<QueuedMessage> = emptyList(),
    val streaming: Boolean = false,
    val streamText: String = "",
    val streamReasoning: String = "",
    val error: String? = null,
    val notice: String? = null,
    val showSettings: Boolean = false,
    val lastUsage: Usage? = null,
    /** 正在执行的工具名（MCP），null 表示没有 */
    val toolStatus: String? = null,
    /** 首次连接时待用户确认的证书指纹（TOFU）；null 表示没有待确认 */
    val pendingCertFingerprint: String? = null,
    /** 待确认来自聊天里的一次发送（信任后自动重发），而不是设置页的「测试连接」 */
    val pendingCertRetryChat: Boolean = false,
    /** 上次「测试连接」拿到的工具列表（设置页展示 + 开关） */
    val mcpTools: List<McpTool> = emptyList(),
    /** 服务端标识：name version（来自 initialize 的 serverInfo） */
    val mcpServerLabel: String? = null,
    /** 「测试连接」的常驻状态（成功/失败都留到下次成功或手动关闭） */
    val mcpStatusText: String? = null,
    val mcpStatusError: Boolean = false,
    /** 本轮因 MCP 不可用被拦下时，显示「仅本次用普通对话」按钮 */
    val mcpBypassAvailable: Boolean = false
) {
    val messages: List<Msg> get() = conversation?.messages.orEmpty()
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val imageStore = ImageStore(application)
    private val attachmentStore = AttachmentStore(application)
    private val chatStore = ChatStore(File(application.filesDir, "chat"))
    private val client = DeepSeekClient(imageStore)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 生成/工具调用跑在应用级 scope（见 ChatEngine），Activity 销毁不会取消 */
    private val engineScope = ChatEngine.scope

    /** 本轮正在启动（MCP 预检阶段）：避免前台服务被提前收掉 */
    @Volatile
    private var roundStarting = false

    private var job: Job? = null

    /** 本轮正在用的 MCP 客户端（点「停止」时要靠它发取消通知） */
    @Volatile
    private var activeMcp: McpClient? = null

    /** send() 预连接好的 MCP（initialize + tools/list），交给 launchCompletion 复用 */
    private var preparedTools: Pair<McpClient, List<McpTool>>? = null

    /** 「仅本次用普通对话」：只影响下一轮，不持久化 */
    private var bypassMcpOnce = false

    /** 本轮（正在进行）是否跳过 MCP */
    private var bypassThisRound = false

    /** 请求轮次：取消/切换后用来丢弃过期回合的回写 */
    private var round = 0

    /** 本轮流式输出的检查点消息 id：进程被杀前留底用，正常结束会被完整消息覆盖 */
    private var checkpointId = ""

    /** 流式文本推给 UI 的节流时间戳（~12Hz），避免每个 delta 都整页重组 */
    private var lastStreamPush = 0L

    /** 输入会话令牌：发消息之后才导入完成的图片不应再挂到输入框上 */
    private var inputSession = 0

    /** 队列归属的会话 id：切会话必须清空，避免排队消息串到别的会话 */
    private var queueConversationId = ""

    /** 一条待发送的用户消息（直接发送与排队后自动发送共用同一条流程） */
    private data class Outgoing(
        val id: String = UUID.randomUUID().toString(),
        val text: String = "",
        val images: List<ImageRef> = emptyList(),
        val attachments: List<AttachmentRef> = emptyList(),
        /** 文件块：只发给模型，不进气泡、不进 text */
        val fileBlocks: String? = null,
        /** 会话标题提示（用户原始输入） */
        val titleHint: String = "",
        /** 因整条消息太长被丢掉的文件数 */
        val droppedFiles: Int = 0,
        /** 是否来自排队队列 */
        val fromQueue: Boolean = false
    ) {
        val hasContent: Boolean
            get() = text.isNotBlank() || images.isNotEmpty() || attachments.isNotEmpty()

        fun toQueued(): QueuedMessage = QueuedMessage(
            id = id,
            text = text,
            images = images,
            attachments = attachments,
            fileBlocks = fileBlocks
        )
    }

    private fun QueuedMessage.toOutgoing(): Outgoing = Outgoing(
        id = id,
        text = text,
        images = images,
        attachments = attachments,
        fileBlocks = fileBlocks,
        titleHint = text,
        fromQueue = true
    )

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

    /** 单张（兼容旧入口） */
    fun addImage(uri: Uri) = addImages(listOf(uri))

    /**
     * 多选照片：一次导入多张，超过 [ImageStore.MAX_IMAGES] 的部分丢弃并提示。
     */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val current = _state.value
        val room = (ImageStore.MAX_IMAGES - current.pendingImages.size).coerceAtLeast(0)
        if (room <= 0) {
            toast("一条消息最多 ${ImageStore.MAX_IMAGES} 张图片")
            return
        }
        if (uris.size > room) {
            toast("最多还能添加 $room 张，其余 ${uris.size - room} 张已丢弃")
        }
        val accepted = uris.take(room)
        val session = inputSession
        viewModelScope.launch {
            var failed = 0
            for (uri in accepted) {
                val ref = withContext(Dispatchers.IO) { imageStore.import(uri) }
                if (ref == null) {
                    failed++
                    continue
                }
                if (session != inputSession) {
                    imageStore.delete(listOf(ref))
                    continue
                }
                var rejected = false
                _state.update { state ->
                    if (state.pendingImages.size >= ImageStore.MAX_IMAGES) {
                        rejected = true
                        state
                    } else {
                        state.copy(pendingImages = state.pendingImages + ref, error = null)
                    }
                }
                if (rejected) {
                    imageStore.delete(listOf(ref))
                    break
                }
            }
            if (failed > 0) {
                _state.update { it.copy(error = "有 $failed 张图片读取失败，已跳过") }
            }
        }
    }

    fun removeImage(ref: ImageRef) {
        _state.update {
            it.copy(pendingImages = it.pendingImages.filterNot { image -> image.fileName == ref.fileName })
        }
        imageStore.delete(listOf(ref))
    }

    // ---------------------------------------------------------------- 文本文件

    /**
     * 读取一个文本文件作为附件：类型/NUL/大小校验都在 [TextFiles] 里做，
     * 这里只负责数量、总大小和会话令牌。
     */
    fun addFile(uri: Uri) {
        val session = inputSession
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { attachmentStore.read(uri) }
            when (result) {
                is FileReadResult.Rejected -> toast(result.reason)
                is FileReadResult.Ok -> {
                    val attachment = result.attachment
                    if (session != inputSession) return@launch
                    val state = _state.value
                    val check = TextFiles.checkAdd(state.pendingFiles, attachment)
                    if (!check.allowed) {
                        toast(check.reason ?: "无法添加这个文件")
                        return@launch
                    }
                    _state.update {
                        it.copy(pendingFiles = it.pendingFiles + attachment, error = null)
                    }
                    if (attachment.truncated) {
                        toast("${attachment.name} 超过 ${TextFiles.sizeLabel(TextFiles.MAX_FILE_BYTES.toLong())}，已截断")
                    }
                }
            }
        }
    }

    fun removeFile(index: Int) {
        _state.update { state ->
            if (index !in state.pendingFiles.indices) {
                state
            } else {
                state.copy(pendingFiles = state.pendingFiles.filterIndexed { i, _ -> i != index })
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 会话

    fun newConversation() {
        val queuedCount = _state.value.queued.size
        flushPartial()
        stopStreaming()
        inputSession++
        queueConversationId = ""
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
                pendingFiles = emptyList(),
                queued = emptyList(),
                streamText = "",
                streamReasoning = "",
                error = null,
                notice = if (queuedCount > 0) {
                    "已新建对话，排队中的 $queuedCount 条已清空"
                } else {
                    null
                }
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
        val queuedCount = _state.value.queued.size
        flushPartial()
        stopStreaming()
        inputSession++
        queueConversationId = ""
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
                pendingFiles = emptyList(),
                queued = emptyList(),
                streamText = "",
                streamReasoning = "",
                error = null,
                notice = if (queuedCount > 0) {
                    "已切换会话，排队中的 $queuedCount 条已清空"
                } else {
                    null
                },
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

    /** 把整段会话复制成 Markdown（历史抽屉的「复制内容」） */
    fun copyConversation(id: String) {
        viewModelScope.launch {
            val conversation = withContext(Dispatchers.IO) { chatStore.load(id) }
            if (conversation == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "会话不存在", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val markdown = ConversationMarkdown.render(conversation.messages)
            val count = conversation.messages.size
            withContext(Dispatchers.Main) {
                val clipboard = getApplication<android.app.Application>()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("deepseek-conversation", markdown)
                )
                Toast.makeText(getApplication(), "已复制 $count 条消息", Toast.LENGTH_SHORT).show()
            }
        }
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
                pendingFiles = emptyList(),
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
        val hasContent = snapshot.input.isNotBlank() ||
            snapshot.pendingImages.isNotEmpty() ||
            snapshot.pendingFiles.isNotEmpty()
        if (!hasContent) return

        if (!snapshot.settings.hasKey) {
            _state.update { it.copy(showSettings = true, error = "请先在设置里填好 API Key") }
            return
        }

        // 正在生成：不打断这一轮，把这条排进队列（本轮结束后自动按顺序发出）
        if (snapshot.streaming) {
            enqueueCurrent(snapshot)
            return
        }

        submit(outgoingFrom(snapshot))
    }

    /** 把输入框里的这条（连图片/文件一起）放进排队队列，输入框先清空 */
    private fun enqueueCurrent(snapshot: ChatUiState) {
        val composed = TextFiles.compose(snapshot.input, snapshot.pendingFiles)
        val item = QueuedMessage(
            id = UUID.randomUUID().toString(),
            text = composed.text,
            images = snapshot.pendingImages,
            attachments = TextFiles.attachmentRefs(composed.kept),
            fileBlocks = composed.fileBlocks
        )
        if (item.isEmpty) return
        queueConversationId = snapshot.conversation?.id.orEmpty()
        _state.update {
            it.copy(
                queued = MessageQueue.enqueue(it.queued, item),
                input = "",
                pendingImages = emptyList(),
                pendingFiles = emptyList(),
                error = null
            )
        }
        inputSession++
        if (composed.dropped.isNotEmpty()) {
            _state.update { it.copy(notice = droppedFilesNotice(composed)) }
        }
    }

    /** 取消一条排队消息（不动正在进行的生成） */
    fun cancelQueued(id: String) {
        _state.update { it.copy(queued = MessageQueue.cancel(it.queued, id)) }
    }

    /** 直接发送时用当前输入框内容拼一条 */
    private fun outgoingFrom(snapshot: ChatUiState): Outgoing {
        val composed = TextFiles.compose(snapshot.input, snapshot.pendingFiles)
        return Outgoing(
            text = composed.text,
            images = snapshot.pendingImages,
            attachments = TextFiles.attachmentRefs(composed.kept),
            fileBlocks = composed.fileBlocks,
            titleHint = snapshot.input.trim(),
            droppedFiles = composed.dropped.size
        )
    }

    /** 「太长只保留前几个文件」的提示 */
    private fun droppedFilesNotice(composed: ComposedMessage): String =
        "消息太长（超过 ${TextFiles.sizeLabel(TextFiles.MAX_MESSAGE_BYTES.toLong())}），" +
            "只保留了前 ${composed.kept.size} 个文件，其余 ${composed.dropped.size} 个未发送"

    /**
     * 发送前检查 + 发送（直接发送、排队后自动发送共用）。
     *
     * MCP 开着就先真连一次：连不上时直接发送保留输入框、排队消息放回队首，都不丢消息。
     */
    private fun submit(outgoing: Outgoing) {
        if (!outgoing.hasContent) return
        val settings = _state.value.settings
        val mcpGate = settings.mcpEnabled && settings.mcpHost.isNotBlank() && !bypassMcpOnce
        roundStarting = true
        // 通知从「开始连接 MCP」就亮起来，整轮不断
        ChatService.start(
            getApplication(),
            if (mcpGate) ChatService.PHASE_CONNECTING_MCP else ChatService.PHASE_GENERATING
        )
        if (mcpGate) {
            engineScope.launch {
                _state.update { it.copy(notice = "正在连接 MCP…") }
                val prepared = withContext(Dispatchers.IO) {
                    runCatching { prepareMcp(settings, TEST_CONNECT_TIMEOUT_MS) }
                }
                val failure = prepared.exceptionOrNull()
                when {
                    failure is UntrustedCertException -> {
                        finishWithoutRound()
                        requeueIfQueued(outgoing)
                        _state.update {
                            it.copy(notice = null, pendingCertFingerprint = failure.fingerprint, pendingCertRetryChat = true)
                        }
                    }
                    failure is CertMismatchException -> {
                        finishWithoutRound()
                        requeueIfQueued(outgoing)
                        _state.update {
                            it.copy(
                                notice = null,
                                error = "证书指纹不匹配（可能是中间人，或服务端换了证书）；如确认更换请在设置里重置信任，本轮未发送",
                                mcpBypassAvailable = true
                            )
                        }
                    }
                    failure != null -> {
                        finishWithoutRound()
                        requeueIfQueued(outgoing)
                        _state.update {
                            it.copy(
                                notice = null,
                                error = "MCP 服务器连接失败：${describeMcpError(failure)}。本轮未发送",
                                mcpBypassAvailable = true
                            )
                        }
                    }
                    else -> {
                        val (client, tools) = prepared.getOrThrow()
                        val enabled = McpToolFilter.enabledTools(tools, settings.disabledMcpTools)
                        if (enabled.isEmpty()) {
                            finishWithoutRound()
                            requeueIfQueued(outgoing)
                            _state.update {
                                it.copy(
                                    notice = null,
                                    error = "MCP 没有可用工具（可能全部被关闭或服务器没提供工具），本轮未发送",
                                    mcpBypassAvailable = true
                                )
                            }
                        } else {
                            preparedTools = Pair(client, tools)
                            _state.update { it.copy(notice = null, mcpBypassAvailable = false) }
                            proceedSend(outgoing)
                        }
                    }
                }
            }
            return
        }
        proceedSend(outgoing)
    }

    /** 这一轮没真正开始（发送前检查失败）：复位启动标记，并在真的空闲时收掉通知 */
    private fun finishWithoutRound() {
        roundStarting = false
        stopServiceIfIdle()
    }

    /** 生成/排队都空了才收前台服务；还有下一轮在启动时不收 */
    private fun stopServiceIfIdle() {
        if (roundStarting) return
        val state = _state.value
        if (state.streaming || state.queued.isNotEmpty()) return
        ChatService.stop(getApplication())
    }

    /** 排队消息因为发送前检查失败没能发出：放回队首，别丢 */
    private fun requeueIfQueued(outgoing: Outgoing) {
        if (!outgoing.fromQueue) return
        _state.update {
            it.copy(queued = MessageQueue.restoreFirst(it.queued, outgoing.toQueued()))
        }
    }

    /** 当前生成结束后调用：自动发出排队里的第一条（顺序发送，直到队列空） */
    private fun dispatchNextQueued() {
        val state = _state.value
        if (state.streaming) return
        if (state.queued.isEmpty()) return
        if (queueConversationId.isNotBlank() && queueConversationId != state.conversation?.id) {
            // 会话已经换了（理论上切会话时已清空，这里兜底，绝不让消息串会话）
            _state.update {
                it.copy(queued = emptyList(), notice = "已切换会话，排队中的 ${state.queued.size} 条已清空")
            }
            queueConversationId = ""
            return
        }
        val (item, rest) = MessageQueue.takeFirst(state.queued)
        if (item == null) return
        _state.update { it.copy(queued = rest) }
        submit(item.toOutgoing())
    }

    /** 仅本轮不带 MCP 工具发送（不写设置、下一轮仍走「MCP 必须在」） */
    fun sendWithoutMcp() {
        bypassMcpOnce = true
        _state.update { it.copy(error = null, mcpBypassAvailable = false) }
        val snapshot = _state.value
        val hasContent = snapshot.input.isNotBlank() ||
            snapshot.pendingImages.isNotEmpty() ||
            snapshot.pendingFiles.isNotEmpty()
        if (hasContent) {
            send()
        } else if (snapshot.queued.isNotEmpty()) {
            // 被 MCP 拦下的其实是排队消息（输入框是空的）：放回队首的那条重新发
            dispatchNextQueued()
        }
        bypassMcpOnce = false
    }

    /** 手动关闭「测试连接」的常驻状态 */
    fun clearMcpStatus() {
        _state.update { it.copy(mcpStatusText = null, mcpStatusError = false) }
    }

    /** 连接 MCP：initialize + tools/list（用完即断，不缓存会话） */
    private suspend fun prepareMcp(settings: Settings, timeoutMs: Int): Pair<McpClient, List<McpTool>> =
        withContext(Dispatchers.IO) {
            val client = McpClient(
                settings.mcpHost,
                settings.mcpPort,
                settings.mcpHttps,
                settings.mcpCertFingerprint,
                getApplication(),
                timeoutMs
            )
            client.initialize()
            client to client.listTools()
        }

    private fun describeMcpError(error: Throwable): String = McpErrors.friendly(error)

    /** 真正把这条用户消息加入会话并发起请求（直接发送、排队自动发送、重试共用） */
    private fun proceedSend(outgoing: Outgoing) {
        if (!outgoing.hasContent) return
        bypassThisRound = bypassMcpOnce
        bypassMcpOnce = false

        val base = _state.value.conversation ?: ensureConversation()
        val userMsg = Msg(
            id = UUID.randomUUID().toString(),
            role = Role.USER,
            text = outgoing.text,
            images = outgoing.images,
            attachments = outgoing.attachments,
            fileBlocks = outgoing.fileBlocks
        )
        var updated = base.copy(
            messages = base.messages + userMsg,
            updatedAt = System.currentTimeMillis()
        )
        if (updated.title.isBlank() || updated.title == Conversation.DEFAULT_TITLE) {
            updated = updated.copy(
                title = titleFor(
                    outgoing.titleHint.ifBlank { outgoing.text },
                    outgoing.images,
                    outgoing.attachments
                )
            )
        }
        _state.update {
            it.copy(
                conversation = updated,
                input = "",
                pendingImages = emptyList(),
                pendingFiles = emptyList(),
                streamText = "",
                streamReasoning = "",
                error = null,
                notice = null,
                streaming = true,
                lastUsage = null
            )
        }
        if (outgoing.droppedFiles > 0) {
            _state.update {
                it.copy(
                    notice = "消息太长（超过 ${TextFiles.sizeLabel(TextFiles.MAX_MESSAGE_BYTES.toLong())}），" +
                        "只保留了前 ${outgoing.attachments.size} 个文件，其余 ${outgoing.droppedFiles} 个未发送"
                )
            }
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
        bypassThisRound = false
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
        val mcp = activeMcp
        val callId = mcp?.activeCallId
        if (mcp != null && callId != null) {
            // 正在跑工具：先发取消，给它最多 3 秒把「已取消 + 部分输出」带回来，再取消协程
            viewModelScope.launch {
                withContext(Dispatchers.IO) { mcp.cancelActiveCall() }
                delay(3000)
                stopStreaming()
            }
            return
        }
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

        // 前台服务：整轮（连接 MCP → 生成 → 工具 → 整理）都保持进程优先级
        roundStarting = false
        ChatService.start(getApplication(), ChatService.PHASE_GENERATING)

        // 跑在应用级 scope：Activity 被回收 / 最近任务被划掉都不打断这一轮
        job = engineScope.launch {
            var failure: String? = null
            var usage: Usage? = null
            var citations: String? = null
            var toolCalls: String? = null
            var cancelled = false

            // send() 已经预连接好就复用；否则（regenerate 等）现场连一次
            var mcp: McpClient? = null
            var functionTools: String? = null
            if (settings.mcpEnabled && settings.mcpHost.isNotBlank() && !bypassThisRound) {
                try {
                    val prepared = preparedTools ?: withContext(Dispatchers.IO) {
                        prepareMcp(settings, 45_000)
                    }
                    preparedTools = null
                    mcp = prepared.first
                    activeMcp = prepared.first
                    val tools = prepared.second
                    val enabled = McpToolFilter.enabledTools(tools, settings.disabledMcpTools)
                    if (tools.isEmpty()) {
                        mcp = null
                        _state.update { it.copy(notice = "MCP 服务器没有提供工具，本轮用普通对话") }
                    } else if (enabled.isEmpty()) {
                        mcp = null
                        _state.update {
                            it.copy(notice = "所有 MCP 工具都被关掉了，本轮用普通对话（可在设置里打开）")
                        }
                    } else {
                        functionTools = McpClient.toOpenAiTools(enabled).toString()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UntrustedCertException) {
                    mcp = null
                    _state.update {
                        it.copy(
                            pendingCertFingerprint = e.fingerprint,
                            notice = "首次连接：请在设置里确认服务器证书指纹"
                        )
                    }
                } catch (e: CertMismatchException) {
                    mcp = null
                    _state.update {
                        it.copy(
                            notice = "MCP 证书指纹不匹配（可能是中间人，或服务端换了证书）。" +
                                "确认是服务端更换后，可在设置里「重置信任」重新确认"
                        )
                    }
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
                    // 通知文案跟着阶段走：第一轮是「生成」，工具跑完再回来的那轮是「整理答案」
                    ChatService.update(
                        getApplication(),
                        if (rounds == 0) ChatService.PHASE_GENERATING else ChatService.PHASE_FINISHING
                    )
                    val result = try {
                        client.streamChat(
                            // V4 Pro 不支持服务端联网工具，带上会被 API 400；本轮自动关掉
                            settings = if (!Models.supportsWebTools(effectiveModel) && settings.webTools) {
                                settings.copy(webTools = false)
                            } else {
                                settings
                            },
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

                    // 1) 工具调用前这一轮的正文/思考 + tool_calls 合并成**一条** assistant 消息：
                    //    既用于 UI（正文气泡），也持久化并原样发给模型（tool_calls 必须成对出现）。
                    val segmentText = content.substring(contentStart)
                    val segmentThought = reasoning.substring(reasoningStart)
                    val toolCallAssistant = Msg(
                        id = UUID.randomUUID().toString(),
                        role = Role.ASSISTANT,
                        text = segmentText,
                        reasoning = segmentThought,
                        toolCallsJson = calls,
                        model = effectiveModel,
                        intermediate = true
                    )
                    appendVisible(conversation.id, myRound, toolCallAssistant)
                    sawToolRound = true
                    // 下一轮从空白开始展示，避免和刚固化的内容重复
                    _state.update { it.copy(streamText = "", streamReasoning = "") }
                    lastStreamPush = 0L

                    // 2) 带 tool_calls 的 assistant 轮次接进给模型的上下文（本轮）
                    requestMessages += toolCallAssistant
                    val array = JSONArray(calls)
                    var mcpDisconnected = false
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
                        ChatService.update(getApplication(), ChatService.phaseTool(name))
                        var failed = false
                        var failureReason: Throwable? = null
                        var callResult: McpClient.ToolCallResult? = null
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { activeMcp.callToolWithMeta(name, arguments) }
                        }
                        callResult = outcome.getOrNull()
                        if (callResult?.failed == true) failed = true
                        val output: String = outcome.fold(
                            onSuccess = { it.text },
                            onFailure = { error ->
                                failed = true
                                failureReason = error
                                "工具调用失败：${error.message}"
                            }
                        )
                        // 45 秒工具超时：卡片标成失败并把原因写进本轮结果，循环继续（不会整体挂住）
                        val timedOut = failureReason is java.net.SocketTimeoutException ||
                            (failureReason?.message ?: "").contains("timed out", ignoreCase = true) ||
                            (failureReason?.message ?: "").contains("timeout", ignoreCase = true)
                        val meta = callResult?.meta
                        // 连接中断（IOException 但不是超时）：卡片标「连接中断」并终止本轮
                        val interrupted = failureReason is java.io.IOException && !timedOut
                        if (interrupted) mcpDisconnected = true
                        // 取消不是失败：卡片显示「已取消 · 1.2s」，保留已捕获输出
                        if (meta?.cancelled == true) failed = false
                        updateVisible(conversation.id, myRound, stepId) {
                            it.copy(
                                text = if (failed) "" else summarize(output, 400),
                                error = when {
                                    !failed -> null
                                    timedOut -> "执行超时：$name"
                                    interrupted -> "连接中断：$name"
                                    else -> "调用失败：$name：${summarize(output, 180)}"
                                },
                                toolDurationMs = meta?.durationMs,
                                toolExitCode = meta?.code,
                                toolTimedOut = meta?.timedOut == true,
                                toolTimeoutSeconds = meta?.timeoutSeconds,
                                toolCancelled = meta?.cancelled == true
                            )
                        }
                        val toolResult = if (timedOut) {
                            "工具执行超时，已终止：$name"
                        } else if (interrupted) {
                            "工具执行失败（MCP 连接中断）：$name：${summarize(output, 200)}"
                        } else {
                            output
                        }
                        // 3) tool 结果也必须持久化：assistant(tool_calls) 与 tool 成对，下一轮模型才看得到
                        val toolMessage = Msg(
                            id = UUID.randomUUID().toString(),
                            role = Role.TOOL,
                            toolCallId = call.optString("id"),
                            text = toolResult.take(MAX_TOOL_OUTPUT),
                            intermediate = true
                        )
                        appendVisible(conversation.id, myRound, toolMessage)
                        requestMessages += toolMessage
                    }
                    _state.update { it.copy(toolStatus = null) }
                    if (mcpDisconnected) {
                        failure = "MCP 连接已断开，工具调用中止"
                        break
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: java.net.SocketTimeoutException) {
                // 看门狗：60 秒没有任何数据（DeepSeekClient readTimeout）
                failure = "响应超时（60 秒没有收到数据），请重试"
            } catch (e: Exception) {
                failure = e.message ?: "请求失败"
            } finally {
                _state.update { it.copy(toolStatus = null) }
                // 停止/异常时正在跑的工具卡片不能永远停在「执行中」
                markStaleToolSteps(conversation.id, myRound)
                // 并且要补齐缺失的 tool 结果，保证 tool_calls/tool 成对（否则下一轮请求 400）
                closeDanglingToolCalls(conversation.id, myRound, "执行已中断（用户停止）")
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
        // 一轮结束（成功/失败/超时/停止）：复位界面、自动发队首；
        // 只有真的没有下一轮时（排队被强制刷新等）才收掉前台服务
        job?.invokeOnCompletion {
            activeMcp = null
            if (myRound == round) {
                _state.update { it.copy(streaming = false, toolStatus = null) }
                // 当前生成结束（成功/失败/停止/超时）→ 自动发出排队里的第一条
                dispatchNextQueued()
            }
            stopServiceIfIdle()
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
    private fun markStaleToolSteps(conversationId: String, myRound: Int) {        if (myRound != round) return
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

    /**
     * 停止/异常时，assistant(tool_calls) 可能缺少对应的 tool 结果。
     * 补齐「执行已中断（用户停止）」，保证发给模型时 tool_calls/tool 成对（缺了会 400）。
     */
    private fun closeDanglingToolCalls(conversationId: String, myRound: Int, reason: String) {
        if (myRound != round) return
        val current = _state.value.conversation ?: return
        if (current.id != conversationId) return
        val answered = current.messages
            .filter { it.role == Role.TOOL }
            .mapNotNull { it.toolCallId }
            .toMutableSet()
        val additions = ArrayList<Msg>()
        for (message in current.messages) {
            if (message.role != Role.ASSISTANT || message.toolCallsJson.isNullOrBlank()) continue
            val calls = runCatching { JSONArray(message.toolCallsJson) }.getOrNull() ?: continue
            for (index in 0 until calls.length()) {
                val id = calls.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isBlank() || id in answered) continue
                answered.add(id)
                additions.add(
                    Msg(
                        id = UUID.randomUUID().toString(),
                        role = Role.TOOL,
                        toolCallId = id,
                        text = reason,
                        intermediate = true
                    )
                )
            }
        }
        if (additions.isEmpty()) return
        val updated = current.copy(
            messages = current.messages + additions,
            updatedAt = System.currentTimeMillis()
        )
        _state.update { it.copy(conversation = updated) }
        runCatching { chatStore.save(updated) }
    }

    override fun onCleared() {
        // 只留一份检查点当兜底：生成协程跑在 ChatEngine 的应用级 scope 上，
        // 这里**不**取消它（否则划掉最近任务就会把正在执行的 MCP 工具调用丢掉）。
        saveStreamingCheckpoint()
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
            _state.update { it.copy(notice = "正在连接 MCP…", pendingCertFingerprint = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = McpClient(
                        settings.mcpHost,
                        settings.mcpPort,
                        settings.mcpHttps,
                        settings.mcpCertFingerprint,
                        getApplication()
                    )
                    client.initialize()
                    client to client.listTools()
                }
            }
            val failure = result.exceptionOrNull()
            when {
                failure is UntrustedCertException -> {
                    _state.update {
                        it.copy(
                            notice = null,
                            pendingCertFingerprint = failure.fingerprint
                        )
                    }
                }
                failure is CertMismatchException -> {
                    _state.update {
                        it.copy(
                            notice = "证书指纹不匹配（可能是中间人，或服务端换了证书）。" +
                                "确认是服务端更换后，可在「MCP 密钥」里重置信任"
                        )
                    }
                }
                else -> result.onSuccess { (client, tools) ->
                    val label = listOf(client.serverName, client.serverVersion)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { null }
                    _state.update {
                        it.copy(
                            mcpTools = tools,
                            mcpServerLabel = label,
                            mcpStatusError = false,
                            // 常驻状态：成功后一直显示，直到下次失败或手动关闭
                            mcpStatusText = "已连接 · ${tools.size} 个工具",
                            notice = null
                        )
                    }
                }.onFailure { e ->
                    _state.update {
                        it.copy(
                            mcpStatusError = true,
                            mcpStatusText = "连接失败：${McpErrors.friendly(e)}",
                            notice = null
                        )
                    }
                }
            }
        }
    }

    /** 设置页工具开关：关闭的工具不发给模型（下一轮请求立即生效） */
    fun setToolEnabled(name: String, enabled: Boolean) {
        val current = _state.value.settings
        val disabled = if (enabled) {
            current.disabledMcpTools - name
        } else {
            current.disabledMcpTools + name
        }
        val updated = current.copy(disabledMcpTools = disabled)
        settingsStore.save(updated)
        _state.update {
            it.copy(
                settings = updated,
                notice = if (enabled) "已启用工具 $name（下一轮生效）" else "已关闭工具 $name（下一轮生效）"
            )
        }
    }

    /** 用户确认首次连接的证书指纹：存下 pin 并立刻重试（聊天里是重发刚才那条） */
    fun trustPendingCert() {
        val current = _state.value
        val fingerprint = current.pendingCertFingerprint ?: return
        val retryChat = current.pendingCertRetryChat
        val updated = current.settings.copy(mcpCertFingerprint = fingerprint)
        settingsStore.save(updated)
        _state.update {
            it.copy(
                settings = updated,
                pendingCertFingerprint = null,
                pendingCertRetryChat = false
            )
        }
        if (retryChat) {
            val snapshot = _state.value
            val hasContent = snapshot.input.isNotBlank() ||
                snapshot.pendingImages.isNotEmpty() ||
                snapshot.pendingFiles.isNotEmpty()
            // 被证书拦下的可能是排队消息（输入框是空的）：信任后重发队首
            if (hasContent) send() else dispatchNextQueued()
        } else {
            testMcp()
        }
    }

    /** 清除已信任的证书指纹（下次连接重新走首次确认） */
    fun resetCertTrust() {
        val updated = _state.value.settings.copy(mcpCertFingerprint = "")
        settingsStore.save(updated)
        _state.update { it.copy(settings = updated, pendingCertFingerprint = null) }
    }

    /** 取消首次连接确认（输入框内容保持原样） */
    fun dismissPendingCert() {
        _state.update { it.copy(pendingCertFingerprint = null, pendingCertRetryChat = false) }
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

    private fun titleFor(input: String, images: List<ImageRef>, attachments: List<AttachmentRef>): String {
        val single = input.replace("\n", " ").trim()
        return when {
            single.isNotEmpty() -> if (single.length <= 24) single else single.take(24) + "…"
            attachments.isNotEmpty() -> attachments.first().name.take(24)
            images.isNotEmpty() -> "图片对话"
            else -> Conversation.DEFAULT_TITLE
        }
    }

    private companion object {
        const val MAX_TOOL_OUTPUT = 8000

        /** 流式文本推给 UI 的最小间隔 */
        const val STREAM_PUSH_INTERVAL_MS = 120L
        /** 「测试连接」和「发送前握手」的超时（工具执行仍用 45 秒） */
        const val TEST_CONNECT_TIMEOUT_MS = 3_000
    }
}
