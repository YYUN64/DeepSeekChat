package com.deepseek.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.deepseek.chat.R
import com.deepseek.chat.data.ContextLimits
import com.deepseek.chat.data.Conversation
import com.deepseek.chat.data.Reasoning
import com.deepseek.chat.data.ImageRef
import com.deepseek.chat.data.Models
import com.deepseek.chat.data.Msg
import com.deepseek.chat.data.Role
import com.deepseek.chat.data.TokenCounter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    imageLoader: (ImageRef, Int) -> android.graphics.Bitmap?,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveImage: (ImageRef) -> Unit,
    onRemoveFile: (Int) -> Unit,
    onRegenerate: () -> Unit,
    onDismissError: () -> Unit,
    onBypassMcp: () -> Unit,
    onClearNotice: () -> Unit,
    onSetModel: (String) -> Unit,
    onSetReasoning: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var preview by remember { mutableStateOf<ImageRef?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    val conversation = state.conversation
    // tool 消息只用于发给模型（tool_calls 配对），界面上由 TOOL_STEP 卡片展示；
    // 纯工具调用、没有正文/思考的 assistant 也不显示
    val messages = conversation?.messages.orEmpty().filter { msg ->
        msg.role != Role.TOOL &&
            !(msg.role == Role.ASSISTANT && msg.text.isBlank() && msg.reasoning.isBlank() &&
                !msg.toolCallsJson.isNullOrBlank())
    }
    val model = conversation?.model ?: state.settings.model
    val level = Reasoning.levelOf(
        conversation?.thinking ?: state.settings.thinking,
        conversation?.effort ?: state.settings.effort
    )

    // 上下文占用：有服务端 usage 时以其为锚点，只有新增消息才用估算
    val contextUsage = remember(messages) { TokenCounter.contextUsage(messages) }
    val streamTokens = remember(state.streamText, state.streamReasoning) {
        TokenCounter.estimate(state.streamText) + TokenCounter.estimate(state.streamReasoning)
    }
    val usedTokens = contextUsage.tokens + streamTokens
    val usedEstimated = usedTokens > 0 && (contextUsage.estimated || streamTokens > 0)
    val subtitle = buildString {
        append(Models.label(model))
        append(" · ")
        append(if (level == Reasoning.OFF) "不推理" else "推理·${Reasoning.label(level)}")
        append(" · 上下文 ")
        if (usedEstimated) append("≈")
        append(TokenCounter.describe(usedTokens))
        append(" / ")
        append(ContextLimits.label(state.settings.contextTokens))
    }

    // 列表用 reverseLayout 贴底（越靠前的 item 越靠视觉底部）。
    //
    // 跟随规则（1.8.3 修「往上翻还被拽回底部」）：
    //  - 手指一碰（isScrollInProgress 变 true）立刻停止跟随，不等松手；
    //  - 松手后只有真正贴底（index 0 + offset 0）才恢复跟随；
    //  - 跟随只用瞬时 scrollToItem，绝不 animate（避免和手势抢）；
    //  - isScrollInProgress 期间不许自动滚动；
    //  - 不跟随时把流式气泡的内容**冻结**在用户上翻那一刻，否则气泡继续长高，
    //    reverseLayout 会把上面的内容整体推走（这才是「停不下来」的真正原因）。
    var followBottom by remember { mutableStateOf(true) }
    // 用户一拖动就停跟随（NestedScrollConnection 比 isScrollInProgress 更早、更可靠）
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y != 0f) {
                    followBottom = false
                }
                return Offset.Zero
            }
        }
    }
    var frozenStream by remember { mutableStateOf<String?>(null) }
    var frozenReasoning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            val atBottom = ChatScroll.atBottom(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
            followBottom = ChatScroll.nextFollowing(scrolling = scrolling, atBottom = atBottom)
        }
    }
    // 上翻时冻结流式文本（松手后也保持冻结，直到回到底部/恢复跟随）
    LaunchedEffect(followBottom, state.streaming) {
        if (!followBottom && state.streaming) {
            frozenStream = state.streamText
            frozenReasoning = state.streamReasoning
        } else if (followBottom) {
            frozenStream = null
            frozenReasoning = null
        }
    }
    // 打开/切换会话：直接贴底
    LaunchedEffect(conversation?.id) {
        followBottom = true
        frozenStream = null
        frozenReasoning = null
        if (state.messages.isNotEmpty() || state.streaming) listState.scrollToItem(0)
    }
    // 新消息 / 工具卡片插入或隐藏 / 流式重新开始：贴底且用户没在滑动时才瞬时跟到底
    LaunchedEffect(messages.size, state.streaming, state.toolStatus) {
        if (followBottom && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    val scope = rememberCoroutineScope()
    val streamTextForUi = frozenStream ?: state.streamText
    val streamReasoningForUi = frozenReasoning ?: state.streamReasoning
    LaunchedEffect(state.notice) {
        val notice = state.notice
        // 设置页自己有 Snackbar 宿主，这里只在对话页消费，避免两边抢同一个 notice
        if (notice != null && !state.showSettings) {
            snackbarHostState.showSnackbar(notice)
            onClearNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        // 三条左对齐、长度递减的横线，与官方 App 的历史入口一致
                        Icon(
                            painter = painterResource(R.drawable.ic_history_lines),
                            contentDescription = "会话列表"
                        )
                    }
                },
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showOptions = true }
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = conversation?.title ?: Conversation.DEFAULT_TITLE,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建对话")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Column {
                val error = state.error
                if (error != null) {
                    ErrorBanner(message = error, onDismiss = onDismissError, onBypass = if (state.mcpBypassAvailable) onBypassMcp else null)
                }
                val toolStatus = state.toolStatus
                if (toolStatus != null) {
                    Text(
                        text = toolStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                    )
                }
                Composer(
                    input = state.input,
                    onInput = onInput,
                    images = state.pendingImages,
                    files = state.pendingFiles,
                    imageLoader = imageLoader,
                    onRemoveImage = onRemoveImage,
                    onRemoveFile = onRemoveFile,
                    onPickImage = onPickImage,
                    onPickFile = onPickFile,
                    onSend = onSend,
                    onStop = onStop,
                    streaming = state.streaming
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        if (messages.isEmpty() && !state.streaming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                EmptyState(hasKey = state.settings.hasKey)
            }
        } else {
            LazyColumn(
                state = listState,
                // 贴底布局：第 0 项在最下面，内容长高时自动向上顶，视口天然跟随最新文字
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .nestedScroll(scrollConnection)
                    // 手指一碰（甚至只是按住）就停止跟随：isScrollInProgress 在快速滑动时
                    // 可能来不及被 snapshotFlow 采样，pointerInput 保证 Start 立刻生效。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            followBottom = false
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        }
                    },
                // reverseLayout 下 top/bottom 是反的：start 是视觉底部
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 越靠前的 item 越贴底：正在流式的消息永远在第一项，
                // 历史消息倒序排在它上面（视觉上仍是旧→新）
                // 工具正在执行时先不显示「思考中…」的空条：这一步由工具卡片自己展示
                if (state.streaming && state.toolStatus == null) {
                    item(key = "streaming") {
                        MessageItem(
                            msg = Msg(
                                id = "streaming",
                                role = Role.ASSISTANT,
                                text = streamTextForUi,
                                reasoning = streamReasoningForUi,
                                model = model
                            ),
                            streaming = true,
                            imageLoader = imageLoader,
                            onRegenerate = null,
                            onImageClick = {}
                        )
                    }
                }
                items(messages.asReversed(), key = { it.id }) { msg ->
                    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id
                    MessageItem(
                        msg = msg,
                        streaming = false,
                        imageLoader = imageLoader,
                        onRegenerate = if (msg.id == lastAssistantId) onRegenerate else null,
                        onImageClick = { preview = it }
                    )
                }
            }
        }
            // 上翻阅读时的「回到底部」：覆盖在列表区域右下角（不依赖 bottomBar 重组）。
            // 注意：state 是普通参数不是 State，derivedStateOf 捕获它不会随新 state 重算，
            // 所以这里用普通表达式——任何一次重组都会重新求值，最可靠。
            val showBackToBottom = !followBottom && state.streaming
            if (showBackToBottom) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                        .clickable {
                            followBottom = true
                            frozenStream = null
                            frozenReasoning = null
                            scope.launch { listState.scrollToItem(0) }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "回到底部",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }

    val previewRef = preview
    if (previewRef != null) {
        ImagePreviewDialog(ref = previewRef, loader = imageLoader, onDismiss = { preview = null })
    }
    if (showOptions) {
        ConversationOptionsDialog(
            model = model,
            level = level,
            onSetModel = onSetModel,
            onSetReasoning = onSetReasoning,
            onDismiss = { showOptions = false }
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onBypass: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (onBypass != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBypass,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("仅本次用普通对话", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    text = "只影响这一次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
        }
    }
}

@Composable
private fun EmptyState(hasKey: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 150.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "今天有什么可以帮到你？",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!hasKey) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "先在左下角菜单 → 设置里填入 API Key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun ConversationOptionsDialog(
    model: String,
    level: String,
    onSetModel: (String) -> Unit,
    onSetReasoning: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本轮对话设置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "模型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (item in Models.presets) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSetModel(item) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = model == item, onClick = { onSetModel(item) })
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(Models.label(item), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = Models.description(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "推理等级",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (preset in Reasoning.presets) {
                        SelectableChip(
                            label = Reasoning.label(preset),
                            selected = level == preset,
                            onClick = { onSetReasoning(preset) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = Reasoning.hint(level),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
