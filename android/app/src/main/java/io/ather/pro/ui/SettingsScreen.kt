package io.ather.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import io.ather.pro.BuildConfig
import io.ather.pro.domain.update.AppUpdateState
import io.ather.pro.ui.update.AppUpdateCard
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.domain.model.ScooterArtwork
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.monitoring.MonitoringState
import io.ather.pro.ui.theme.GlassCard
import kotlinx.coroutines.delay

/** Fully masked preview — no token characters or its true length are shown; copy-only. */
private fun maskToken(token: String): String =
    if (token.isBlank()) "—" else "•••• •••• •••• ••••"

@Composable
internal fun MonitoringCard(state: MonitoringState, limitEnabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status = when {
        state.running -> "Charging monitor active" to colors.primary
        state.alwaysEnabled || limitEnabled -> "Quiet checks while idle" to colors.tertiary
        else -> "Off" to colors.onSurfaceVariant
    }
    io.ather.pro.ui.theme.GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Charging in background", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(status.second))
                        Text(status.first, style = MaterialTheme.typography.labelMedium, color = status.second)
                    }
                }
                Switch(checked = state.alwaysEnabled, onCheckedChange = onChange)
            }
            Text(if (state.alwaysEnabled) "Silent notification while charging. No ongoing notification when idle."
                else if (limitEnabled) "Your charge limit enables charging checks in the background."
                else "Enable automatic charge checks and widget updates after leaving the app.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Enable monitoring notifications") }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Android battery & background settings") }
            Text("Idle checks run about every 15 minutes and Android may delay them. Open the app when plugging in for immediate monitoring. Force-stop prevents checks until the app is opened again.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsScreen(
    session: AuthSession,
    dashboard: ScooterDashboardState,
    monitoring: MonitoringState,
    limitEnabled: Boolean,
    updateState: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
    onMonitoringChange: (Boolean) -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onArtworkColourChange: (String?) -> Unit,
    onTariffChange: (Double) -> Unit,
    onLogout: () -> Unit
) {
    var modelMenu by remember { mutableStateOf(false) }
    var colourMenu by remember { mutableStateOf(false) }
    var rate by rememberSaveable(dashboard.settings.tariffRatePerKWh) { mutableStateOf(dashboard.settings.tariffRatePerKWh.toString()) }
    var signOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val parsedRate = rate.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val colors = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { AppUpdateCard(updateState, onCheckUpdate, onOpenUpdate) }
        item { SectionLabel("Background") }
        item { MonitoringCard(monitoring, limitEnabled, onMonitoringChange) }
        item { SectionLabel("Estimates") }
        item {
            io.ather.pro.ui.theme.GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Charge estimates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Battery size used for energy and cost estimates.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }) { Text(dashboard.settings.selectedModel.displayName) }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            ScooterModel.entries.forEach { model ->
                                DropdownMenuItem(text = { Text(model.displayName) }, onClick = { onModelChange(model); modelMenu = false })
                            }
                        }
                    }
                    Text("SCOOTER COLOUR", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                    Box {
                        OutlinedButton(onClick = { colourMenu = true }) {
                            Text(dashboard.settings.artworkColour ?: "From my scooter")
                        }
                        DropdownMenu(expanded = colourMenu, onDismissRequest = { colourMenu = false }) {
                            DropdownMenuItem(text = { Text("From my scooter") }, onClick = { onArtworkColourChange(null); colourMenu = false })
                            ScooterArtwork.colours(dashboard.settings.selectedModel).forEach { colour ->
                                DropdownMenuItem(text = { Text(colour.label) }, onClick = { onArtworkColourChange(colour.label); colourMenu = false })
                            }
                        }
                    }
                    Text("From my scooter uses the colour reported for this scooter.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedTextField(value = rate, onValueChange = { rate = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Electricity rate · ₹ / kWh") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = parsedRate == null,
                        supportingText = { if (parsedRate == null) Text("Enter a rate from 0 to 100") })
                    Button(onClick = { parsedRate?.let(onTariffChange) },
                        enabled = parsedRate != null && parsedRate != dashboard.settings.tariffRatePerKWh) { Text("Save rate") }
                }
            }
        }
        item { SectionLabel("Support") }
        item { ProjectSupportCard() }
        item { SectionLabel("Account") }
        item {
            io.ather.pro.ui.theme.GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(session.displayName?.takeIf(String::isNotBlank) ?: "Your AtherMate account",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    // JWT sessions expire server-side; show how long this one has left.
                    session.expiresAtEpochSec?.let { expSec ->
                        val remainingSec = expSec - System.currentTimeMillis() / 1000L
                        val (stateLabel, stateColor) = when {
                            remainingSec <= 0 -> "expired" to colors.error
                            remainingSec < 24L * 3600L -> "expires today" to colors.error
                            remainingSec < 7L * 86_400L -> "expires in ${remainingSec / 86_400L} d" to colors.tertiary
                            else -> "valid" to colors.primary
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(stateColor))
                            val expiryText = java.text.SimpleDateFormat("EEE, d MMM · h:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date(expSec * 1000L))
                            Text("Session $stateLabel · valid until $expiryText",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    // Copyable token: lets the user restore access via the login
                    // screen's "Session token" field without spending an OTP.
                    var tokenCopied by remember { mutableStateOf(false) }
                    LaunchedEffect(tokenCopied) {
                        if (tokenCopied) { delay(2000); tokenCopied = false }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SESSION TOKEN", style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                        Text(maskToken(session.token),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = colors.onSurface)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                clipboard.setPrimaryClip(ClipData.newPlainText("AtherMate session token", session.token))
                                tokenCopied = true
                            }) { Text(if (tokenCopied) "Copied ✓" else "Copy token") }
                            Text("bearer — keep private", style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
                        }
                        Text("Paste into “Session token” under Advanced options on the sign-in screen to restore access without an OTP. Copy again before it expires — signing out clears it from this phone.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Text("Your sign-in is stored on this phone and kept when updating AtherMate. When the session expires, sign in again with an OTP — there is no silent refresh.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedButton(onClick = { signOut = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error)) {
                        Text("Sign out")
                    }
                    Text("Independent companion app. Designed for smart EV scooters.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("AtherMate ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant)
                }
            }
        }
    }
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text("Sign out of AtherMate?") },
        text = { Text("Monitoring will stop. You’ll need an OTP to sign in again.") },
        confirmButton = { TextButton(onClick = { signOut = false; onLogout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } })
}
