package com.deepseek.chat.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.deepseek.chat.data.ContextLimits
import com.deepseek.chat.data.McpKeyStatus
import com.deepseek.chat.data.McpSigner
import com.deepseek.chat.data.Models
import com.deepseek.chat.data.Reasoning
import com.deepseek.chat.data.Settings
import com.deepseek.chat.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    notice: String?,
    onChange: (Settings) -> Unit,
    onTestMcp: () -> Unit,
    onClearNotice: () -> Unit,
    onBack: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    // MCP 密钥：公钥只在生成的那一刻放进内存，关闭弹层就丢弃（不持久化）
    var keyId by remember { mutableStateOf(McpSigner.keyId()) }
    var showKeyPage by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var oneTimePublicKey by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    fun generateKeyNow() {
        if (McpSigner.generateKey()) {
            keyId = McpSigner.keyId()
            oneTimePublicKey = McpSigner.publicKeySpkiBase64()
        }
    }
    var showContextPicker by remember { mutableStateOf(false) }
    var showOutputPicker by remember { mutableStateOf(false) }
    val level = Reasoning.levelOf(settings.thinking, settings.effort)
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // 设置页自己的 Snackbar 宿主：MCP 测试结果当场显示，不再等回到对话页才弹
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            onClearNotice()
        }
    }
    // 端口输入框和 settings.mcpPort 解耦：本地文本允许暂时为空/非法，
    // 只有解析成 1..65535 才写回 settings（否则退格永远删不掉最后一位数字）。
    // portFallback 记录“这次编辑开始前的合法端口”，清空后回退到它，
    // 而不是回退到输入过程中写进去的中间值（比如 9000 一路删成 9）。
    var portText by remember { mutableStateOf(settings.mcpPort.toString()) }
    var portFallback by remember {
        mutableStateOf(settings.mcpPort.takeIf { it in 1..65535 } ?: Settings.DEFAULT_MCP_PORT)
    }

    fun commitPort() {
        val parsed = Settings.parsePort(portText)
        if (parsed != null) {
            if (parsed != settings.mcpPort) onChange(settings.copy(mcpPort = parsed))
            portFallback = parsed
            return
        }
        // 失焦 / 点测试连接时回退到编辑前的合法值（默认 8765），避免“显示空但实际用旧值”
        val restored = portFallback.takeIf { it in 1..65535 } ?: Settings.DEFAULT_MCP_PORT
        portText = restored.toString()
        if (settings.mcpPort != restored) onChange(settings.copy(mcpPort = restored))
        Toast.makeText(
            context,
            "端口需在 1–65535，已恢复为 $restored",
            Toast.LENGTH_SHORT
        ).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            SettingSection("联网") {
                SwitchRow(
                    label = "联网搜索",
                    description = "由 DeepSeek 服务端执行搜索与网页读取，按次计费",
                    checked = settings.webTools,
                    onCheckedChange = { onChange(settings.copy(webTools = it)) }
                )
            }

            SettingSection("MCP 服务器") {
                SwitchRow(
                    label = "启用 MCP",
                    description = "把远程 MCP 服务器的工具交给模型调用（需自己部署，streamable HTTP）",
                    checked = settings.mcpEnabled,
                    onCheckedChange = { onChange(settings.copy(mcpEnabled = it)) }
                )
                if (settings.mcpEnabled) {
                    SectionDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = settings.mcpHost,
                            onValueChange = { onChange(settings.copy(mcpHost = it.trim())) },
                            label = { Text("服务器 IPv4") },
                            placeholder = { Text("192.168.1.10") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(5)
                                portText = digits
                                Settings.parsePort(digits)?.let { onChange(settings.copy(mcpPort = it)) }
                            },
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                commitPort()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier
                                .width(96.dp)
                                .padding(vertical = 8.dp)
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        // 记下这次编辑开始前的合法端口
                                        portFallback = Settings.parsePort(portText)
                                            ?: settings.mcpPort.takeIf { it in 1..65535 }
                                            ?: Settings.DEFAULT_MCP_PORT
                                    } else {
                                        commitPort()
                                    }
                                }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        // 点「测试连接」前先把端口输入框里没提交的内容校验一遍
                        TextButton(onClick = {
                            commitPort()
                            onTestMcp()
                        }) { Text("测试连接") }
                    }
                    Text(
                        text = "地址形如 http://<IPv4>:<端口>/mcp。开启后本轮的「联网」服务端工具会自动让位（DeepSeek 不允许两者同时声明）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // 密钥鉴权：私钥在 Android Keystore（不可导出），公钥只在生成时一次性展示
                    Text(
                        text = "MCP 密钥",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showKeyPage = true }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = McpKeyStatus.label(keyId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (keyId == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = "私钥在 Android Keystore（不可导出）；公钥只在生成时显示一次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "工具调用轮数",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (rounds in listOf(2, 4, 8, Settings.MAX_MCP_ROUNDS)) {
                            SelectableChip(
                                label = if (rounds == Settings.MAX_MCP_ROUNDS) "不限" else rounds.toString(),
                                selected = settings.mcpMaxRounds == rounds,
                                onClick = { onChange(settings.copy(mcpMaxRounds = rounds)) }
                            )
                        }
                    }
                    if (settings.mcpMaxRounds == Settings.MAX_MCP_ROUNDS) {
                        Text(
                            text = "「不限」内部上限 ${Settings.MAX_MCP_ROUNDS} 轮，避免死循环烧钱",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
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
                        com.deepseek.chat.BuildConfig.VERSION_NAME,
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

    // ---- MCP 密钥：二级弹层 + 二次确认 + 一次性公钥展示 ----
    if (showKeyPage) {
        AlertDialog(
            onDismissRequest = { showKeyPage = false },
            title = { Text("MCP 密钥") },
            text = {
                Column {
                    Text(
                        text = if (keyId == null) {
                            "还没有生成密钥。生成后公钥只显示这一次，关闭后无法再次查看。"
                        } else {
                            "已生成\nkey id：${McpKeyStatus.fingerprint(keyId)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "私钥保存在 Android Keystore，不可导出；服务端只存公钥。" +
                            "公钥加到 ~/.config/pocket-mcp/authorized_keys（用户级）或 " +
                            "/etc/mcp/authorized_keys（系统级，sudo）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                if (keyId == null) {
                    TextButton(onClick = {
                        showKeyPage = false
                        generateKeyNow()
                    }) { Text("生成密钥") }
                } else {
                    TextButton(onClick = { showRegenerateConfirm = true }) {
                        Text("重新生成", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyPage = false }) { Text("关闭") }
            }
        )
    }

    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("重新生成密钥？") },
            text = {
                Text(
                    "旧公钥会立刻失效。你需要把新公钥加到服务器的 authorized_keys 并删掉旧行，" +
                        "否则 App 会连不上。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirm = false
                    showKeyPage = false
                    generateKeyNow()
                }) {
                    Text("确认重新生成", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) { Text("取消") }
            }
        )
    }

    val generatedKey = oneTimePublicKey
    if (generatedKey != null) {
        AlertDialog(
            onDismissRequest = { oneTimePublicKey = null },
            title = { Text("公钥（只显示这一次）") },
            text = {
                Column {
                    Text(
                        text = "复制后加到服务器的 authorized_keys（用户级 ~/.config/pocket-mcp/authorized_keys，" +
                            "系统级 /etc/mcp/authorized_keys），删掉旧行，再点「测试连接」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer {
                        Text(
                            text = generatedKey,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    Text(
                        text = "关闭后无法再次查看；需要再次使用时必须重新生成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(generatedKey))
                    }) { Text("复制公钥") }
                }
            },
            confirmButton = {
                TextButton(onClick = { oneTimePublicKey = null }) { Text("我已复制，关闭") }
            }
        )
    }
}
