package io.ather.pro.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ather.pro.BuildConfig

/**
 * Contribution card. The UPI identity is personal data, so it is injected at
 * build time from local.properties (athermate.upi / athermate.upiName) and the
 * card disappears when a build has none configured.
 */
@Composable
internal fun ProjectSupportCard() {
    val context = LocalContext.current
    val upiId = BuildConfig.SUPPORT_UPI_ID
    val upiName = BuildConfig.SUPPORT_UPI_NAME
    if (upiId.isBlank()) return
    var message by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Support the project", style = MaterialTheme.typography.titleMedium)
            Text("If you find the app useful, consider supporting the project.",
                style = MaterialTheme.typography.bodyMedium)
            SelectionContainer {
                Text("UPI: $upiId", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Choose an amount and confirm in your payment app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val uri = Uri.Builder().scheme("upi").authority("pay")
                    .appendQueryParameter("pa", upiId)
                    .apply { if (upiName.isNotBlank()) appendQueryParameter("pn", upiName) }
                    .appendQueryParameter("tn", "Support AtherMate")
                    .appendQueryParameter("cu", "INR")
                    .build()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                message = null
                if (intent.resolveActivity(context.packageManager) == null) {
                    message = "No UPI payment app found. Copy the UPI ID to use it in your payment app."
                } else {
                    try {
                        context.startActivity(Intent.createChooser(intent, "Support with UPI"))
                    } catch (_: ActivityNotFoundException) {
                        message = "Couldn’t open a payment app. You can copy the UPI ID instead."
                    } catch (_: SecurityException) {
                        message = "Couldn’t open a payment app. You can copy the UPI ID instead."
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Support via UPI") }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("AtherMate support UPI", upiId))
                message = "UPI ID copied"
            }) { Text("Copy UPI ID") }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
