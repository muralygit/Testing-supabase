package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PaperColorScheme = lightColorScheme(
    primary = BrandAccent,
    onPrimary = CardPaper,
    primaryContainer = BrandAccentDark,
    onPrimaryContainer = CardPaper,
    secondary = InkSecondary,
    onSecondary = CardPaper,
    background = PaperBg,
    onBackground = InkPrimary,
    surface = CardPaper,
    onSurface = InkPrimary,
    surfaceVariant = PaperBg,
    onSurfaceVariant = InkPrimary,
    error = DangerAccent,
    onError = CardPaper,
    outline = BorderDivider
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PaperColorScheme,
        typography = Typography,
        content = content
    )
}
