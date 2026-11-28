package com.deepseek.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.chat.data.ImageRef
import com.deepseek.chat.data.MessageQueue
import com.deepseek.chat.data.QueuedMessage
import com.deepseek.chat.data.TextAttachment

@Composable
fun Composer(
    input: String,
    onInput: (String) -> Unit,
    images: List<ImageRef>,
    files: List<TextAttachment>,
    queued: List<QueuedMessage>,
    imageLoader: (ImageRef, Int) -> android.graphics.Bitmap?,
    onRemoveImage: (ImageRef) -> Unit,
    onRemoveFile: (Int) -> Unit,
    onCancelQueued: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    streaming: Boolean
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
    ) {
        // 生成中按发送的消息排队在这里，等这一轮结束后自动按顺序发出
        if (queued.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 132.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (item in queued) {
                    QueuedRow(item = item, onCancel = { onCancelQueued(item.id) })
                }
            }
        }

        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (image in images) {
                    Box {
                        ImageThumb(ref = image, loader = imageLoader, size = 60.dp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { onRemoveImage(image) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "移除图片",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        if (files.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((index, file) in files.withIndex()) {
                    AttachmentChip(
                        name = file.name,
                        sizeBytes = file.bytes,
                        truncated = file.truncated,
                        onRemove = { onRemoveFile(index) }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            // 输入框左侧的「+」：添加图片 / 添加文件
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !streaming,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("添加图片") },
                        leadingIcon = {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onPickImage()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("添加文件") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Description, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onPickFile()
                        }
                    )
                }
            }
            TextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                placeholder = {
                    Text(
                        text = if (streaming) "生成中…" else "给 DeepSeek 发消息…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            if (streaming) {
                // 生成中两个按钮并排：左「停止」只停当前生成；右「发送」= 排队，不打断
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止生成",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank() || images.isNotEmpty() || files.isNotEmpty(),
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(6.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank() || images.isNotEmpty() || files.isNotEmpty(),
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

/** 一条排队项：`排队中：<前 30 字>…` + 右侧 × 取消 */
@Composable
private fun QueuedRow(item: QueuedMessage, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = MessageQueue.label(item),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "取消排队",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
