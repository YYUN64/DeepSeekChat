package com.deepseek.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onRemoveImage: (ImageRef) -> Unit,
    onRegenerate: () -> Unit,
    onDismissError: () -> Unit,
    onClearNotice: () -> Unit,
    onSetModel: (String) -> Unit,
    onSetReasoning: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var preview by remember { mutableStateOf<ImageRef?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    val conversation = state.conversation
    val messages = conversation?.messages.orEmpty()
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

    val itemCount = messages.size + if (state.streaming) 1 else 0
    // 只有用户自己滚动到底部之后才继续跟随；往上看历史时不再把他拽回去
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                followBottom = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(messages.size) { followBottom = true }
    LaunchedEffect(itemCount, state.streamText.length / 40) {
        if (itemCount > 0 && followBottom) {
            // 大偏移量让长消息也滚到真正底部（内部会按内容边界裁剪）
            listState.scrollToItem(itemCount - 1, 100_000)
        }
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice
        if (notice != null) {
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
                    ErrorBanner(message = error, onDismiss = onDismissError)
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
                    imageLoader = imageLoader,
                    onRemoveImage = onRemoveImage,
                    onPickImage = onPickImage,
                    onSend = onSend,
                    onStop = onStop,
                    streaming = state.streaming
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (messages.isEmpty() && !state.streaming) {
                item(key = "empty") {
                    EmptyState(hasKey = state.settings.hasKey)
                }
            }
            items(messages, key = { it.id }) { msg ->
                val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id
                MessageItem(
                    msg = msg,
                    streaming = false,
                    imageLoader = imageLoader,
                    onRegenerate = if (msg.id == lastAssistantId) onRegenerate else null,
                    onImageClick = { preview = it }
                )
            }
            if (state.streaming) {
                item(key = "streaming") {
                    MessageItem(
                        msg = Msg(
                            id = "streaming",
                            role = Role.ASSISTANT,
                            text = state.streamText,
                            reasoning = state.streamReasoning,
                            model = model
                        ),
                        streaming = true,
                        imageLoader = imageLoader,
                        onRegenerate = null,
                        onImageClick = {}
                    )
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
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
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
