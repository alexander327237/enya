package com.enya.ollama.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF7C4DFF)
private val PurpleDark = Color(0xFF5E35D1)

private val LightColors = lightColorScheme(
    primary = Purple,
    secondary = PurpleDark
)

private val DarkColors = darkColorScheme(
    primary = Purple,
    secondary = PurpleDark
)

@Composable
fun EnyaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
