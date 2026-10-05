package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentOrangeStart,
    onPrimary = DarkTextMain,
    secondary = DarkItemSurface,
    onSecondary = DarkTextMain,
    tertiary = DarkMarkBg,
    onTertiary = DarkMarkText,
    background = DarkBgMain,
    onBackground = DarkTextMain,
    surface = DarkSurfaceGlass,
    onSurface = DarkTextMain,
    surfaceVariant = DarkItemSurface,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorderSubtle,
    outlineVariant = DarkTextMuted,
    surfaceContainer = DarkSidebarBg,
    surfaceContainerHigh = DarkItemSurfaceHover
)

private val LightColorScheme = lightColorScheme(
    primary = AccentOrangeStart,
    onPrimary = LightMarkText,
    secondary = LightItemSurface,
    onSecondary = LightTextMain,
    tertiary = LightMarkBg,
    onTertiary = LightMarkText,
    background = LightBgMain,
    onBackground = LightTextMain,
    surface = LightSurfaceGlass,
    onSurface = LightTextMain,
    surfaceVariant = LightItemSurface,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorderSubtle,
    outlineVariant = LightTextMuted,
    surfaceContainer = LightSidebarBg,
    surfaceContainerHigh = LightItemSurfaceHover
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
