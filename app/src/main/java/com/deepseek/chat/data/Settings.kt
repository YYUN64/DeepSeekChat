package com.deepseek.chat.data

import android.content.Context

data class Settings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = Models.FLASH,
    val thinking: Boolean = true,
    val effort: String = Reasoning.HIGH,
    val contextTokens: Int = 131_072,
    val maxOutputTokens: Int = 8_192,
    val systemPrompt: String = "",
    val theme: String = ThemeMode.SYSTEM,
    /** 声明服务端 Web 工具（deepseek_web_search / deepseek_read_url），默认开启 */
    val webTools: Boolean = true,
    /** 是否启用远程 MCP 服务器（默认关；没服务器时不会发起任何连接） */
    val mcpEnabled: Boolean = false,
    val mcpHost: String = "",
    val mcpPort: Int = DEFAULT_MCP_PORT
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MCP_PORT = 8765

        /** 端口解析：只接受 1..65535 的纯数字，空串/非法返回 null（界面据此保留原始输入） */
        fun parsePort(text: String): Int? =
            text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }
}

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("deepseek_settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        baseUrl = prefs.getString(KEY_BASE_URL, Settings.DEFAULT_BASE_URL)
            ?.takeIf { it.isNotBlank() } ?: Settings.DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, Models.FLASH)?.takeIf { it.isNotBlank() } ?: Models.FLASH,
        thinking = prefs.getBoolean(KEY_THINKING, true),
        effort = Reasoning.effortFor(prefs.getString(KEY_EFFORT, Reasoning.HIGH).orEmpty()),
        contextTokens = prefs.getInt(KEY_CONTEXT, 131_072),
        maxOutputTokens = prefs.getInt(KEY_OUTPUT, 8_192),
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty(),
        theme = ThemeMode.normalize(prefs.getString(KEY_THEME, ThemeMode.SYSTEM).orEmpty()),
        webTools = prefs.getBoolean(KEY_WEB_TOOLS, true),
        mcpEnabled = prefs.getBoolean(KEY_MCP_ENABLED, false),
        mcpHost = prefs.getString(KEY_MCP_HOST, "").orEmpty(),
        mcpPort = prefs.getInt(KEY_MCP_PORT, Settings.DEFAULT_MCP_PORT)
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putBoolean(KEY_THINKING, settings.thinking)
            .putString(KEY_EFFORT, settings.effort)
            .putInt(KEY_CONTEXT, settings.contextTokens)
            .putInt(KEY_OUTPUT, settings.maxOutputTokens)
            .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            .putString(KEY_THEME, settings.theme)
            .putBoolean(KEY_WEB_TOOLS, settings.webTools)
            .putBoolean(KEY_MCP_ENABLED, settings.mcpEnabled)
            .putString(KEY_MCP_HOST, settings.mcpHost)
            .putInt(KEY_MCP_PORT, settings.mcpPort)
            .apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_THINKING = "thinking"
        const val KEY_EFFORT = "effort"
        const val KEY_CONTEXT = "context_tokens"
        const val KEY_OUTPUT = "max_output_tokens"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_THEME = "theme"
        const val KEY_WEB_TOOLS = "web_tools"
        const val KEY_MCP_ENABLED = "mcp_enabled"
        const val KEY_MCP_HOST = "mcp_host"
        const val KEY_MCP_PORT = "mcp_port"
    }
}
