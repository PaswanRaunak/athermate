package io.ather.pro.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Motion vocabulary for the app — the Apple "fluid interfaces" set translated
 * to Compose: critically damped springs by default, bounce reserved for
 * momentum, press feedback on pointer-down, and a status pulse that doubles as
 * the "searching" cue. Compose applies the system animator duration scale
 * (Accessibility → Remove animations) automatically, so reduced-motion users
 * get near-instant transitions.
 */
object Motion {

    /** Critically damped default — no overshoot, graceful settle (damping 1.0). */
    fun <T> standard(): androidx.compose.animation.core.SpringSpec<T> =
        androidx.compose.animation.core.spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        )

    /** Slightly bouncy — only for momentum-driven transitions (damping ~0.8). */
    fun <T> bouncy(): androidx.compose.animation.core.SpringSpec<T> =
        androidx.compose.animation.core.spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow
        )
}

/**
 * Press feedback that begins on pointer-DOWN, not release (Apple: "respond on
 * press"): a quick 0.97 scale driven by the interaction source.
 */
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.standard(),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Convenience: an interaction source plus its press-scale modifier, remembered. */
@Composable
fun rememberPressScale(): Pair<MutableInteractionSource, Modifier> {
    val source = remember { MutableInteractionSource() }
    return source to Modifier.pressScale(source)
}

/** A status dot: solid when settled, gently pulsing while working (scanning/pairing). */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "statusDotAlpha"
        )
        pulse
    } else 1f
    Surface(
        color = color.copy(alpha = alpha),
        shape = CircleShape,
        modifier = modifier.size(8.dp)
    ) {}
}

/** Tabular figures for numeric readouts (RSSI, distances, timers) so digits don't jitter. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")
