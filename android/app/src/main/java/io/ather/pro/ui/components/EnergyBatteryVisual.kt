package io.ather.pro.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.ather.pro.ui.visuals.BatteryArtwork

@Composable
internal fun EnergyBatteryVisual(soc: Double?, charging: Boolean, fresh: Boolean,
    limitPercent: Int?, modifier: Modifier = Modifier) {
    val artwork = remember { BatteryArtwork() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = charging && fresh && lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
        ValueAnimator.areAnimatorsEnabled()
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "Charging energy")
        val travel by transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
            label = "Energy flow")
        travel
    } else 0f
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            artwork.draw(canvas.nativeCanvas, size.width, size.height, soc,
                charging && fresh, limitPercent, phase)
        }
    }
}
