package io.ather.pro.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neo-Glass surface system.
 *
 * A glass panel is a translucent fill with a faint vertical sheen, a hairline
 * border, and a brighter 1px highlight along the top edge (light catching the
 * material). Glass is deliberately SUBTLE: fills stay near-transparent so text
 * contrast is unchanged, and no blur is applied (Compose backdrop blur is not
 * cheap enough for scrolling surfaces).
 *
 * Dark (AMOLED): white-alpha layers over the pure-black canvas.
 * Light: white-alpha layers over the soft off-white canvas.
 */
object GlassDefaults {
    val CardShape: Shape = RoundedCornerShape(24.dp)

    // Fill sheen: top slightly brighter than bottom.
    // AMOLED needs stronger alphas than light themes - white 5% on true black is invisible.
    const val DARK_FILL_TOP = 0.11f
    const val DARK_FILL_BOTTOM = 0.055f
    const val DARK_BORDER = 0.16f
    const val DARK_TOP_HIGHLIGHT = 0.32f

    const val LIGHT_FILL_TOP = 0.90f
    const val LIGHT_FILL_BOTTOM = 0.72f
    const val LIGHT_BORDER = 0.10f
    const val LIGHT_TOP_HIGHLIGHT = 0.95f
}

/**
 * Draws the glass material inside [shape]: sheen fill, hairline border inset by
 * half its width, and a top-edge highlight line. Purely decorative.
 */
fun Modifier.glassSurface(
    shape: Shape,
    fillTop: Color,
    fillBottom: Color,
    borderColor: Color,
    topHighlight: Color,
    borderWidth: Dp = 1.dp
): Modifier = drawBehind {
    val fill = Brush.verticalGradient(listOf(fillTop, fillBottom))
    drawRect(brush = fill)
    // Brighter top edge - light catching the material.
    drawLine(
        color = topHighlight,
        start = Offset(size.width * 0.08f, borderWidth.toPx() / 2f + 1f),
        end = Offset(size.width * 0.92f, borderWidth.toPx() / 2f + 1f),
        strokeWidth = borderWidth.toPx()
    )
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, color = borderColor, style = Stroke(borderWidth.toPx()))
}

/**
 * Theme-correct glass material: white-alpha layers over the AMOLED canvas in
 * dark, near-solid white panels over the light canvas.
 */
@Composable
fun Modifier.themeGlassSurface(shape: Shape): Modifier {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        glassSurface(
            shape = shape,
            fillTop = Color.White.copy(alpha = GlassDefaults.DARK_FILL_TOP),
            fillBottom = Color.White.copy(alpha = GlassDefaults.DARK_FILL_BOTTOM),
            borderColor = Color.White.copy(alpha = GlassDefaults.DARK_BORDER),
            topHighlight = Color.White.copy(alpha = GlassDefaults.DARK_TOP_HIGHLIGHT)
        )
    } else {
        glassSurface(
            shape = shape,
            fillTop = Color.White.copy(alpha = GlassDefaults.LIGHT_FILL_TOP),
            fillBottom = Color.White.copy(alpha = GlassDefaults.LIGHT_FILL_BOTTOM),
            borderColor = Color.Black.copy(alpha = GlassDefaults.LIGHT_BORDER * 0.35f),
            topHighlight = Color.White.copy(alpha = GlassDefaults.LIGHT_TOP_HIGHLIGHT)
        )
    }
}

/**
 * The standard Neo-Glass container used across screens. Content keeps its own
 * internal layout; the glass material wraps it.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassDefaults.CardShape,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(modifier.clip(shape).themeGlassSurface(shape), content = content)
}
