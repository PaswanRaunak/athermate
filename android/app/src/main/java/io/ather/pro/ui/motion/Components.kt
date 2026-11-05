package io.ather.pro.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Shared motion components — the Apple fluid-interface vocabulary applied
 * app-wide: springs without overshoot for section reveals, press feedback on
 * pointer-down for every action button, and tabular figures for data.
 */

/** Animated section wrapper: fade + vertical expand, critically damped. */
@Composable
fun SmoothSection(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.standard()) +
            expandVertically(animationSpec = Motion.standard()),
        exit = fadeOut(Motion.standard()) +
            shrinkVertically(animationSpec = Motion.standard())
    ) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** Button with press-down scale feedback (Apple: respond on press). */
@Composable
fun FilledActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val (source, scale) = rememberPressScale()
    Button(onClick = onClick, modifier = modifier.then(scale),
        enabled = enabled, interactionSource = source) { content() }
}

@Composable
fun TonalActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val (source, scale) = rememberPressScale()
    FilledTonalButton(onClick = onClick, modifier = modifier.then(scale),
        enabled = enabled, interactionSource = source) { content() }
}

@Composable
fun OutlinedActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val (source, scale) = rememberPressScale()
    OutlinedButton(onClick = onClick, modifier = modifier.then(scale),
        enabled = enabled, interactionSource = source) { content() }
}

@Composable
fun TextActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val (source, scale) = rememberPressScale()
    TextButton(onClick = onClick, modifier = modifier.then(scale),
        enabled = enabled, interactionSource = source) { content() }
}

/** Text style merged with tabular figures for stable numeric readouts. */
fun TextStyle.tabular(): TextStyle = this.merge(TabularNumbers)
