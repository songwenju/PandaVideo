package dev.pandavideo.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

internal val Accent = Color(0xFFFF6740)

private val LightMintHeader = Color(0xFFE8F8F8)
private val DarkMintHeader = Color(0xFF102A2C)

/** Reflects the resolved scheme so header tints can follow the user setting, not only the system. */
internal val LocalDarkTheme = compositionLocalOf { false }

/** Mint-tinted header gradient color matching the active color scheme. */
internal val MaterialTheme.mintHeader: Color
    @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) DarkMintHeader else LightMintHeader

internal val LightColors = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDE7), onPrimaryContainer = Color(0xFF943218),
    secondary = Color(0xFF159AAB), onSecondary = Color.White,
    background = Color.White, onBackground = Color(0xFF1B1B1B),
    surface = Color.White, onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFF5F5F5), onSurfaceVariant = Color(0xFF737373),
    surfaceContainer = Color(0xFFF5F5F5), surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFF5F5F5), surfaceTint = Color.Transparent,
    outline = Color(0xFFD8D8D8),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7D5A), onPrimary = Color(0xFF3A1206),
    primaryContainer = Color(0xFF5C2415), onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFF55C6D6), onSecondary = Color(0xFF00363F),
    background = Color(0xFF121212), onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1B1B1B), onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB2B2B2),
    surfaceContainer = Color(0xFF202020), surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF2C2C2C), surfaceTint = Color.Transparent,
    outline = Color(0xFF484848),
)
