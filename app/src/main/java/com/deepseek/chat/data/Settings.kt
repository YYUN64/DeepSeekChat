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
    val theme: String = ThemeMode.SYSTEM
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
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
        theme = ThemeMode.normalize(prefs.getString(KEY_THEME, ThemeMode.SYSTEM).orEmpty())
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
    }
}
