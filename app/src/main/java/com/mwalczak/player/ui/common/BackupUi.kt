package com.mwalczak.player.ui.common

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Backup
import com.mwalczak.player.data.BackupLogin
import com.mwalczak.player.data.Backups
import com.mwalczak.player.data.currentSyncPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Export: optional login checkbox, then Android's share sheet (WhatsApp, e-mail, Drive, …). */
@Composable
fun ExportDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeLogin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export favorites & settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Favorites, lists, pinned categories, playback and guide settings go into one file you can send or keep.")
                Row(
                    Modifier.fillMaxWidth().clickable { includeLogin = !includeLogin },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeLogin, onCheckedChange = { includeLogin = it })
                    Text("Include login (server, username and password)")
                }
                if (includeLogin) {
                    Text(
                        "Anyone with this file can use your IPTV account. Only send it to people you trust.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { shareExport(context, container, includeLogin) }
                        .onSuccess { onDismiss() }
                        .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Share…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private suspend fun shareExport(context: Context, container: AppContainer, includeLogin: Boolean) {
    val creds = if (includeLogin) container.settings.currentCredentials() else null
    val backup = Backup(
        exportedAtMillis = System.currentTimeMillis(),
        data = currentSyncPayload(container.settings, container.library),
        login = creds?.let { BackupLogin(it.server, it.username, it.password) },
    )
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "exports").apply { mkdirs() }
            .resolve("bunny-player-settings.json")
            .apply { writeText(Backups.encode(backup)) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Bunny Player settings")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share settings"))
}

/** Returns a function that opens the system file picker and hands back a parsed backup. */
@Composable
fun rememberImportPicker(onPicked: (Backup) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Couldn't read the file")
                    Backups.decode(text)
                }
            }
                .onSuccess(onPicked)
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't read the file", Toast.LENGTH_LONG).show() }
        }
    }
    return {
        runCatching { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
            .onFailure { Toast.makeText(context, "No file picker on this device", Toast.LENGTH_LONG).show() }
    }
}

/** What's inside a backup, in one line. */
fun Backup.summary(): String {
    val lib = data.library
    val parts = listOf(
        "${lib.favorites.size} favorites",
        "${lib.lists.size} lists",
        "${lib.pinnedCategories.size} pinned categories",
        "settings",
    ) + listOfNotNull(login?.let { "login (${it.username})" })
    return parts.joinToString(", ")
}
