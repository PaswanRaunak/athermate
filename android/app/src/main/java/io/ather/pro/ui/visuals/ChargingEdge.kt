package io.ather.pro.ui.visuals

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.hypot

/** Accent shared by charging visuals: dashboard pill, edge glow, notification tint. */
val ChargingGlow = Color(0xFF4ADE80)

/**
 * Charging glow that traces a card edge: a breathing halo plus — while the scooter
 * actually draws current — a bright highlight travelling around the border. With
 * [flowing] false (charger plugged in, scooter idle/paused) only the gentle halo
 * runs, so plugged-in reads calmer than charging. Draws fully inside the card
 * bounds (each stroke is inset by half its width), so no outer clip is required.
 *
 * Deliberately avoids canvas rotation and gradient shaders: some GPU drivers
 * (gfxstream among them) render rotated stroked paths as straight chords. The
 * travelling highlight is instead a solid stroke clipped to a wedge whose angle is
 * baked into the path each frame.
 */
@Composable
fun Modifier.chargingEdge(
    active: Boolean,
    shape: Shape,
    color: Color = ChargingGlow,
    flowing: Boolean = true
): Modifier {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = active && lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
        ValueAnimator.areAnimatorsEnabled()
    if (!animate) return Modifier
    val transition = rememberInfiniteTransition(label = "Charging edge")
    val sweep by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "Comet")
    val pulse by transition.animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Halo")
    return drawBehind {
        val halo = 12.dp.toPx()
        val glow = 7.dp.toPx()
        val ring = 1.5.dp.toPx()
        val comet = 4.dp.toPx()
        val drawEdge: (Float, Float, Color) -> Unit = { insetPx, widthPx, edgeColor ->
            val s = Size(size.width - insetPx * 2f, size.height - insetPx * 2f)
            val outline = shape.createOutline(s, layoutDirection, this)
            translate(insetPx, insetPx) {
                drawOutline(outline, edgeColor, style = Stroke(widthPx))
            }
        }
        drawEdge(halo / 2f, halo, color.copy(alpha = 0.10f * pulse))
        drawEdge(glow / 2f, glow, color.copy(alpha = 0.22f * pulse))
        drawEdge(ring / 2f, ring, color.copy(alpha = 0.30f))
        if (!flowing) return@drawBehind
        val inset = glow / 2f
        val s = Size(size.width - inset * 2f, size.height - inset * 2f)
        val cometOutline = shape.createOutline(s, layoutDirection, this)
        val cometCenter = Offset(s.width / 2f, s.height / 2f)
        val reach = hypot(s.width, s.height)
        val headAngle = -90f + sweep * 360f
        fun wedge(halfAngleDegrees: Float): Path = Path().apply {
            val bounds = Rect(cometCenter.x - reach, cometCenter.y - reach,
                cometCenter.x + reach, cometCenter.y + reach)
            moveTo(cometCenter.x, cometCenter.y)
            arcTo(bounds, startAngleDegrees = headAngle - halfAngleDegrees,
                sweepAngleDegrees = halfAngleDegrees * 2f, forceMoveTo = false)
            close()
        }
        translate(inset, inset) {
            clipPath(wedge(20f)) {
                drawOutline(cometOutline, color.copy(alpha = 0.30f), style = Stroke(glow))
            }
            clipPath(wedge(8f)) {
                drawOutline(cometOutline, color, style = Stroke(comet))
            }
        }
    }
}

/**
 * Crisp card outline drawn fully inside the bounds. `Modifier.border()` centres its
 * stroke on the shape path, so half of it is clipped away and the line reads faint;
 * insetting by half the width keeps the whole stroke visible.
 */
fun Modifier.cardOutline(shape: Shape, color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val inset = width.toPx() / 2f
    val s = Size(size.width - inset * 2f, size.height - inset * 2f)
    val outline = shape.createOutline(s, layoutDirection, this)
    translate(inset, inset) { drawOutline(outline, color, style = Stroke(width.toPx())) }
}
