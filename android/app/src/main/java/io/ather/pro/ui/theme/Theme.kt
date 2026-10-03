package io.ather.pro.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** System wallpaper palette on Android 12+, standard Material palettes on older phones. */
fun materialColorScheme(context: Context, darkTheme: Boolean, dynamicColor: Boolean = true): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    darkTheme -> darkColorScheme()
    else -> lightColorScheme()
}

@Composable
fun AtherProTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = materialColorScheme(LocalContext.current, darkTheme, dynamicColor),
        typography = AtherTypography,
        content = content
    )
}
