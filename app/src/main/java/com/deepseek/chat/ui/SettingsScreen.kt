package com.deepseek.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.deepseek.chat.data.ContextLimits
import com.deepseek.chat.data.Models
import com.deepseek.chat.data.Reasoning
import com.deepseek.chat.data.Settings
import com.deepseek.chat.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onBack: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var showContextPicker by remember { mutableStateOf(false) }
    var showOutputPicker by remember { mutableStateOf(false) }
    val level = Reasoning.levelOf(settings.thinking, settings.effort)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = onBack) {
                        Text("返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingSection("接入") {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { onChange(settings.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showKey) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                SectionDivider()
                OutlinedTextField(
                    value = settings.baseUrl,
                    onValueChange = { onChange(settings.copy(baseUrl = it)) },
                    label = { Text("Base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                Text(
                    text = "Key 在 platform.deepseek.com 申请，只保存在这台手机上。Base URL 可换成任意 OpenAI 兼容网关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            SettingSection("模型") {
                for (index in Models.presets.indices) {
                    if (index > 0) SectionDivider()
                    val model = Models.presets[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 先留出与分割线的间距，再裁剪，按压高亮就不会和分割线连在一起
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onChange(settings.copy(model = model)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.model == model,
                            onClick = { onChange(settings.copy(model = model)) }
                        )
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(Models.label(model), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = Models.description(model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = "V4 Pro 不支持看图：带图片的消息会自动用 Flash 发送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            SettingSection("推理等级") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (preset in Reasoning.presets) {
                        SelectableChip(
                            label = Reasoning.label(preset),
                            selected = level == preset,
                            onClick = {
                                onChange(
                                    settings.copy(
                                        thinking = Reasoning.thinkingOf(preset),
                                        effort = Reasoning.effortFor(preset)
                                    )
                                )
                            }
                        )
                    }
                }
                Text(
                    text = Reasoning.hint(level),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            SettingSection("外观") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (mode in ThemeMode.presets) {
                        SelectableChip(
                            label = ThemeMode.label(mode),
                            selected = settings.theme == mode,
                            onClick = { onChange(settings.copy(theme = mode)) }
                        )
                    }
                }
            }

            SettingSection("上下文与输出") {
                ChoiceRow(
                    label = "上下文长度",
                    value = ContextLimits.label(settings.contextTokens),
                    onClick = { showContextPicker = true }
                )
                SectionDivider()
                ChoiceRow(
                    label = "最大回复长度",
                    value = ContextLimits.label(settings.maxOutputTokens),
                    onClick = { showOutputPicker = true }
                )
                Text(
                    text = "超出上下文长度时，会自动丢掉最早的对话；推理等级与上下文长度都会影响 token 消耗。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            SettingSection("系统提示词") {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = { onChange(settings.copy(systemPrompt = it)) },
                    placeholder = { Text("例如：你是一个简洁的中文助手。") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            SettingSection("关于") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("版本", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "1.0.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "这是一个调用 DeepSeek 官方 API 的第三方客户端，不是官方 App。数据只存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showContextPicker) {
        OptionDialog(
            title = "上下文长度",
            options = ContextLimits.contextPresets.map { tokens ->
                tokens.toString() to ContextLimits.label(tokens)
            },
            selected = settings.contextTokens.toString(),
            onSelect = { value ->
                value.toIntOrNull()?.let { onChange(settings.copy(contextTokens = it)) }
            },
            onDismiss = { showContextPicker = false }
        )
    }

    if (showOutputPicker) {
        OptionDialog(
            title = "最大回复长度",
            options = ContextLimits.outputPresets.map { tokens ->
                tokens.toString() to ContextLimits.label(tokens)
            },
            selected = settings.maxOutputTokens.toString(),
            onSelect = { value ->
                value.toIntOrNull()?.let { onChange(settings.copy(maxOutputTokens = it)) }
            },
            onDismiss = { showOutputPicker = false }
        )
    }
}
