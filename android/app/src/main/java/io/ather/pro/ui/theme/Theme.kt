package io.ather.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Neo-Glass AMOLED palette — no wallpaper-derived colors.
 *
 * Dark is the primary identity: true black (AMOLED) with emerald neon accents.
 * Cards and chrome are built as translucent glass over the black canvas (see
 * ui/theme/Glass.kt), so the palette itself stays near-black and lets the glass
 * layers supply depth. Light keeps the same emerald family on soft green-tinted
 * off-white.
 */

private val EmeraldDark = Color(0xFF34D399)
private val EmeraldLight = Color(0xFF059669)

private val AtherDarkColors = darkColorScheme(
    primary = EmeraldDark,
    onPrimary = Color(0xFF00301C),
    primaryContainer = Color(0xFF0B3322),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFFA2C3AC),
    onSecondary = Color(0xFF0F1F16),
    secondaryContainer = Color(0xFF1D2B23),
    onSecondaryContainer = Color(0xFFD2E6D8),
    tertiary = Color(0xFFFFB74A),
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF3A2B0E),
    onTertiaryContainer = Color(0xFFFFDCAF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6EAE7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6EAE7),
    surfaceVariant = Color(0xFF121713),
    onSurfaceVariant = Color(0xFF9DA8A0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF070A08),
    surfaceContainer = Color(0xFF0C0F0D),
    surfaceContainerHigh = Color(0xFF131714),
    surfaceContainerHighest = Color(0xFF1B201C),
    outline = Color(0xFF35403A),
    outlineVariant = Color(0xFF1F2722),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6EAE7),
    inverseOnSurface = Color(0xFF10140F),
    inversePrimary = EmeraldLight,
    scrim = Color.Black
)

private val AtherLightColors = lightColorScheme(
    primary = EmeraldLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F2DB),
    onPrimaryContainer = Color(0xFF04291A),
    secondary = Color(0xFF4C6455),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8DA),
    onSecondaryContainer = Color(0xFF0C1F15),
    tertiary = Color(0xFF9A6A20),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2F1A00),
    background = Color(0xFFF4F6F3),
    onBackground = Color(0xFF161A17),
    surface = Color(0xFFF4F6F3),
    onSurface = Color(0xFF161A17),
    surfaceVariant = Color(0xFFDFE5DD),
    onSurfaceVariant = Color(0xFF5D675F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3EF),
    surfaceContainer = Color(0xFFEBEFEA),
    surfaceContainerHigh = Color(0xFFE5E9E4),
    surfaceContainerHighest = Color(0xFFDFE3DE),
    outline = Color(0xFF7A857D),
    outlineVariant = Color(0xFFD6DDD4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2C312D),
    inverseOnSurface = Color(0xFFEFF2EE),
    inversePrimary = EmeraldDark,
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
