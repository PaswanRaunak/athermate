package io.ather.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Decided brand palette — no wallpaper-derived colors. Dark is the primary identity:
 * deep charcoal with a green undertone and an electric-green accent that matches the
 * charging glow used across cards and notifications. Light keeps the same family on
 * warm white.
 */

private val GreenDark = Color(0xFF4ADE80)
private val GreenLight = Color(0xFF1E9E5A)

private val AtherDarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF06130B),
    primaryContainer = Color(0xFF1B3325),
    onPrimaryContainer = Color(0xFFB8F1CC),
    secondary = Color(0xFF9DB8A6),
    onSecondary = Color(0xFF102016),
    secondaryContainer = Color(0xFF1E2B22),
    onSecondaryContainer = Color(0xFFD2E6D8),
    tertiary = Color(0xFFFFB74A),
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF3A2B0E),
    onTertiaryContainer = Color(0xFFFFDCAF),
    background = Color(0xFF0A0C0B),
    onBackground = Color(0xFFE2E6E2),
    surface = Color(0xFF0A0C0B),
    onSurface = Color(0xFFE2E6E2),
    surfaceVariant = Color(0xFF171D19),
    onSurfaceVariant = Color(0xFF9BA69E),
    surfaceContainerLowest = Color(0xFF070907),
    surfaceContainerLow = Color(0xFF101311),
    surfaceContainer = Color(0xFF151815),
    surfaceContainerHigh = Color(0xFF1E2220),
    surfaceContainerHighest = Color(0xFF282D2A),
    outline = Color(0xFF39443D),
    outlineVariant = Color(0xFF232B26),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE2E6E2),
    inverseOnSurface = Color(0xFF1A1D1B),
    inversePrimary = GreenLight,
    scrim = Color.Black
)

private val AtherLightColors = lightColorScheme(
    primary = GreenLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F1CC),
    onPrimaryContainer = Color(0xFF072715),
    secondary = Color(0xFF4E6355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D8),
    onSecondaryContainer = Color(0xFF0D2015),
    tertiary = Color(0xFF9C6B1F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2F1A00),
    background = Color(0xFFF6F7F4),
    onBackground = Color(0xFF171B18),
    surface = Color(0xFFF6F7F4),
    onSurface = Color(0xFF171B18),
    surfaceVariant = Color(0xFFDFE5DD),
    onSurfaceVariant = Color(0xFF5C6660),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3EE),
    surfaceContainer = Color(0xFFEAEEE9),
    surfaceContainerHigh = Color(0xFFE4E8E3),
    surfaceContainerHighest = Color(0xFFDEE2DD),
    outline = Color(0xFF7C867F),
    outlineVariant = Color(0xFFD5DCD3),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2B302C),
    inverseOnSurface = Color(0xFFEEF1EC),
    inversePrimary = GreenDark,
    scrim = Color.Black
)

private val AtherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Brand palette for non-compose surfaces (home-screen widget). */
fun materialColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) AtherDarkColors else AtherLightColors

@Composable
fun AtherProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AtherDarkColors else AtherLightColors,
        typography = AtherTypography,
        shapes = AtherShapes,
        content = content
    )
}
