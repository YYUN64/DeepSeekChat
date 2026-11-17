package com.deepseek.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF4D6BFE)
private val BrandLight = Color(0xFF8FA3FF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E9FF),
    onPrimaryContainer = Color(0xFF12236B),
    secondary = Color(0xFF5A6478),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEFF5),
    onSecondaryContainer = Color(0xFF2A2F3B),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFF1F3F8),
    onSurfaceVariant = Color(0xFF5A6070),
    outline = Color(0xFFD8DCE5),
    outlineVariant = Color(0xFFE7EAF0),
    error = Color(0xFFD0302F),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E6),
    onErrorContainer = Color(0xFF7A1210)
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF0B1233),
    primaryContainer = Color(0xFF243173),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFA8B1C6),
    onSecondary = Color(0xFF15181F),
    secondaryContainer = Color(0xFF232833),
    onSecondaryContainer = Color(0xFFDCE0EA),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE7E9F0),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE7E9F0),
    surfaceVariant = Color(0xFF21252F),
    onSurfaceVariant = Color(0xFFA3ABBB),
    outline = Color(0xFF3A404F),
    outlineVariant = Color(0xFF282D38),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF3A1414),
    onErrorContainer = Color(0xFFFFD9D7)
)

@Composable
fun DeepSeekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
