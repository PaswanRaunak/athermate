package io.ather.pro.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val AtherColorScheme = darkColorScheme(
    primary = AtherAccent,
    onPrimary = Color(0xFF17110D),
    background = AtherBackground,
    onBackground = AtherText,
    surface = AtherCard,
    onSurface = AtherText,
    surfaceVariant = AtherCard,
    onSurfaceVariant = AtherTextMuted,
    outline = AtherDivider
)

@Composable
fun AtherProTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(LocalContext.current)
        else -> AtherColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AtherTypography,
        content = content
    )
}
