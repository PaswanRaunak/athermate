package io.ather.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Legacy screen names resolve to the current Material color roles, including wallpaper changes.
val AtherBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val AtherCard: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val AtherDivider: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val AtherAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
val AtherGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
val AtherText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val AtherTextMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val AtherDarkBg: Color @Composable get() = MaterialTheme.colorScheme.background
val AtherError: Color @Composable get() = MaterialTheme.colorScheme.error
