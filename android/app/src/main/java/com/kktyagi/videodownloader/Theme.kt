package com.kktyagi.videodownloader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the desktop build, so the two feel like one product.
val BackgroundDark = Color(0xFF0F1016)
val Surface2 = Color(0xFF171923)
val Surface3 = Color(0xFF1E2130)
val TextPrimary = Color(0xFFE7E9F3)
val TextMuted = Color(0xFF8F96B3)
val Accent = Color(0xFF6D5CFF)
val Ok = Color(0xFF3DDC97)
val Err = Color(0xFFFF6B6B)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = Surface2,
    onSurface = TextPrimary,
    surfaceVariant = Surface3,
    onSurfaceVariant = TextMuted,
    error = Err,
)

@Composable
fun VideoDownloaderTheme(
    // The app is dark-only by design; the parameter keeps previews honest.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
