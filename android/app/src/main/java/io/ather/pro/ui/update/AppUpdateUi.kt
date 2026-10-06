package io.ather.pro.ui.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ather.pro.BuildConfig
import io.ather.pro.domain.update.AppUpdateState
import java.text.DateFormat
import java.util.Date

@Composable
fun AppUpdateBanner(state: AppUpdateState, onOpen: () -> Unit) {
    val release = state.release ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Athr+ ${release.versionName} available",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onOpen) { Text(if (state.downloading) "Downloading…" else "Update") }
        }
    }
}

@Composable
fun AppUpdateCard(state: AppUpdateState, onCheck: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text(state.release?.let { "Athr+ ${it.versionName} available" } ?: "Installed · ${BuildConfig.VERSION_NAME}",
                color = if (state.release != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text("Checks on opening the app and daily in the background. New releases appear here and in notifications.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (state.release == null && state.lastCheckedAt != 0L && state.error == null) {
                Text("No newer release available", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.lastCheckedAt != 0L) Text("Last checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastCheckedAt))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.release != null) Button(onClick = onOpen) { Text(if (state.downloading) "Downloading…" else "View update") }
                TextButton(onClick = onCheck, enabled = !state.checking && !state.downloading) { Text("Check now") }
            }
        }
    }
}

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    canInstall: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstall: () -> Unit,
    onReleaseDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Athr+ ${release.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${BuildConfig.VERSION_NAME} → ${release.versionName}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(release.notes.ifBlank { "A new version of Athr+ is available." }, style = MaterialTheme.typography.bodyMedium)
                Text("Your sign-in and app data stay on this phone.", style = MaterialTheme.typography.bodySmall)
                when {
                    state.downloading -> {
                        LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading · ${(state.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    state.readyToInstall && !canInstall -> Text("Allow Athr+ to install app updates in Android settings, then return and tap Install.",
                        style = MaterialTheme.typography.bodySmall)
                    state.readyToInstall -> Text("Download verified. Android will ask you to confirm installation.", style = MaterialTheme.typography.bodySmall)
                    else -> Text("Download · ${"%.1f".format(release.apkSize / 1_048_576.0)} MB", style = MaterialTheme.typography.labelMedium)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onReleaseDetails) { Text("Release details") }
            }
        },
        confirmButton = {
            Button(onClick = when {
                !state.readyToInstall -> onDownload
                !canInstall -> onAllowInstall
                else -> onInstall
            }, enabled = !state.downloading && !state.checking) {
                Text(when {
                    state.downloading -> "Downloading…"
                    !state.readyToInstall -> "Download update"
                    !canInstall -> "Allow installs"
                    else -> "Install update"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}
