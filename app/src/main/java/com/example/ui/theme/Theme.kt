package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SlateBlue,
    onPrimary = Color(0xFF381E72),
    secondary = SlateGrey,
    onSecondary = SlateBg,
    background = SlateBg,
    onBackground = SlateWhite,
    surface = SlateSurface,
    onSurface = SlateWhite,
    error = SlateRed,
    onError = SlateWhite
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
