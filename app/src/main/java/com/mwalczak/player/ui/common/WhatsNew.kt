package com.mwalczak.player.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Changelog
import com.mwalczak.player.data.ChangelogEntry
import kotlinx.coroutines.flow.first

/** The changelog [entries] (newest first), scrollable. */
@Composable
fun WhatsNewDialog(entries: List<ChangelogEntry>, title: String = "What's new", onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(entry.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        entry.items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** After an update: what's new since the last time (once). */
@Composable
fun WhatsNewAfterUpdate(container: AppContainer) {
    val context = LocalContext.current
    var unseen by remember { mutableStateOf<List<ChangelogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val entries = Changelog.load(context)
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val freshInstall = info != null && info.firstInstallTime == info.lastUpdateTime
        unseen = Changelog.unseen(entries, container.settings.changelogSeen.first(), freshInstall)
        // Remember the newest right away: shown once, even if the app is closed with it open.
        entries.firstOrNull()?.let { container.settings.setChangelogSeen(it.id) }
    }
    if (unseen.isNotEmpty()) {
        WhatsNewDialog(unseen, onDismiss = { unseen = emptyList() })
    }
}
