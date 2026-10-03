package io.ather.pro.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val AtherColorScheme = darkColorScheme(
    primary = AtherAccent,
    secondary = AtherAccent,
    primaryContainer = Color(0xFF10382E),
    secondaryContainer = Color(0xFF163A32),
    tertiary = Color(0xFFCCE475),
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
    dynamicColor: Boolean = false,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> AtherColorScheme
        else -> lightColorScheme(primary = Color(0xFF176B52), secondary = Color(0xFF456354), tertiary = Color(0xFF48657F))
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AtherTypography,
        content = content
    )
}
