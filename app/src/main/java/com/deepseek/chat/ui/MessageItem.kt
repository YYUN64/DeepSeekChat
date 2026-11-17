package com.deepseek.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.chat.data.ImageRef
import com.deepseek.chat.data.Models
import com.deepseek.chat.data.Msg
import com.deepseek.chat.data.Role
import org.json.JSONArray

@Composable
fun MessageItem(
    msg: Msg,
    streaming: Boolean,
    imageLoader: (ImageRef, Int) -> android.graphics.Bitmap?,
    onRegenerate: (() -> Unit)?,
    onImageClick: (ImageRef) -> Unit
) {
    if (msg.role == Role.USER) {
        UserMessage(msg, imageLoader, onImageClick)
    } else {
        AssistantMessage(msg, streaming, onRegenerate)
    }
}

@Composable
private fun UserMessage(
    msg: Msg,
    imageLoader: (ImageRef, Int) -> android.graphics.Bitmap?,
    onImageClick: (ImageRef) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (msg.images.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (image in msg.images) {
                    ImageThumb(
                        ref = image,
                        loader = imageLoader,
                        size = 88.dp,
                        onClick = { onImageClick(image) }
                    )
                }
            }
        }
        if (msg.text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(msg: Msg, streaming: Boolean, onRegenerate: (() -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var expanded by remember(msg.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (msg.reasoning.isNotBlank()) {
            ReasoningPanel(
                text = msg.reasoning,
                streaming = streaming,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (msg.text.isNotBlank()) {
            if (streaming) {
                // 流式阶段先按纯文本渲染，避免每来一个字就整篇重排 Markdown
                SelectionContainer {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                MarkdownText(text = msg.text)
            }
        } else if (streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (msg.reasoning.isNotBlank()) "正在整理答案…" else "思考中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        val error = msg.error
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        val sources = remember(msg.annotations) { parseSources(msg.annotations) }
        if (sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "参考来源",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for ((title, url) in sources.take(8)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri(url) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }

        if (!streaming) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (msg.text.isNotBlank()) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(msg.text)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (onRegenerate != null) {
                    TextButton(
                        onClick = onRegenerate,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试", style = MaterialTheme.typography.labelMedium)
                    }
                }
                val usage = msg.usage
                if (usage != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "输入 ${usage.promptTokens} · 输出 ${usage.completionTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningPanel(    text: String,
    streaming: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (streaming) "深度思考中…" else "已深度思考",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 从 annotations 里取出引用来源 (标题, URL)，按 URL 去重。
 * 兼容两种形状：`{"url":..,"title":..}` 与 OpenAI 风格的 `{"url_citation":{...}}`。
 */
private fun parseSources(raw: String?): List<Pair<String, String>> {
    if (raw.isNullOrBlank()) return emptyList()
    val found = LinkedHashMap<String, String>()
    runCatching {
        val array = JSONArray(raw)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val citation = item.optJSONObject("url_citation") ?: item
            val url = citation.optString("url").trim()
            if (url.isEmpty()) continue
            val title = citation.optString("title").trim().ifEmpty { url }
            found.putIfAbsent(url, title)
        }
    }
    return found.map { (url, title) -> title to url }
}
