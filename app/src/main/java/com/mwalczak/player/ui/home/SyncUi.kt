package com.mwalczak.player.ui.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.LaunchedEffect
import com.mwalczak.player.data.sync.SyncVersion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.sync.SyncProfile
import com.mwalczak.player.data.sync.formatShareCode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings → "Sync & sharing": saved online for this login, a share code, and using someone else's. */
@Composable
fun SyncSection(container: AppContainer, username: String?) {
    val status by container.sync.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var joining by remember { mutableStateOf(false) }

    var showingHistory by remember { mutableStateOf(false) }

    if (joining) JoinDialog(container, onDismiss = { joining = false })
    if (showingHistory) HistoryDialog(container, username, onDismiss = { showingHistory = false })

    val following = status.following
    val time = if (status.lastSyncedMillis > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.lastSyncedMillis)) else null
    ListItem(
        headlineContent = {
            Text(
                when {
                    following != null -> "Following ${formatShareCode(following)}"
                    status.code != null -> "Saved online"
                    else -> "Not saved online yet"
                },
            )
        },
        supportingContent = {
            Column {
                Text(
                    if (following != null) {
                        "This device shows that setup – favorites, lists, pinned categories and settings – and gets its changes. Changes made here are replaced by the next one."
                    } else {
                        "Favorites, lists, pinned categories and settings come back on any device signed in as ${username ?: "you"}, also after reinstalling."
                    },
                )
                val line = when {
                    status.busy -> "Syncing…"
                    status.error != null -> "Couldn't sync: ${status.error}"
                    time != null -> "Last synced $time – tap to sync now"
                    else -> "Tap to sync now"
                }
                Text(
                    line,
                    color = if (status.error != null && !status.busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = { Icon(if (status.error != null) Icons.Default.CloudOff else Icons.Default.CloudDone, null) },
        trailingContent = { if (status.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
        modifier = Modifier.clickable(enabled = !status.busy) { container.sync.syncNowInBackground() },
    )
    val code = status.code
    if (code != null && following == null) {
        ListItem(
            headlineContent = { Text("Share with a friend: ${formatShareCode(code)}") },
            supportingContent = { Text("They enter this code in Settings → Sync & sharing to follow or copy your favorites and lists. Your login is never shared.") },
            leadingContent = { Icon(Icons.Default.Share, null) },
            modifier = Modifier.clickable {
                val text = "My Bunny Player favorites and lists: code ${formatShareCode(code)}\n" +
                    "In Bunny Player open Settings → Sync & sharing → Use someone's setup."
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share code"),
                )
            },
        )
    }
    ListItem(
        headlineContent = { Text("Use someone's setup") },
        supportingContent = { Text("Enter a friend's code to follow their favorites and lists, or copy them once") },
        leadingContent = { Icon(Icons.Default.GroupAdd, null) },
        modifier = Modifier.clickable { joining = true },
    )
    if (following == null && code != null) {
        ListItem(
            headlineContent = { Text("History") },
            supportingContent = { Text("The last 100 saved versions – when, from which device and address. Pick one to go back to it.") },
            leadingContent = { Icon(Icons.Default.History, null) },
            modifier = Modifier.clickable { showingHistory = true },
        )
    }
    if (following != null) {
        val scope = rememberCoroutineScope()
        ListItem(
            headlineContent = { Text("Stop following") },
            supportingContent = { Text("Keep what's here now as your own and stop getting updates") },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
            modifier = Modifier.clickable {
                scope.launch {
                    container.sync.stopFollowing()
                    Toast.makeText(context, "Stopped following – it's your own setup now", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

/** Enter a code, see what it has, then follow it or copy it once. */
@Composable
private fun JoinDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<SyncProfile?>(null) }

    fun use(follow: Boolean, profile: SyncProfile) {
        busy = true
        scope.launch {
            if (follow) container.sync.follow(profile) else container.sync.copy(profile)
            busy = false
            val failure = container.sync.status.value.error
            if (failure != null) {
                error = failure
            } else {
                Toast.makeText(context, if (follow) "Following ${formatShareCode(profile.code)}" else "Copied to this device", Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use someone's setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val profile = found
                if (profile == null) {
                    Text("Enter the code they shared (Settings → Sync & sharing on their device).")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(12); error = null },
                        label = { Text("Code, e.g. ABCD-EFGH") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                } else {
                    val lib = profile.data.library
                    Text("${formatShareCode(profile.code)}: ${lib.favorites.size} favorites, ${lib.lists.size} lists, ${lib.pinnedCategories.size} pinned categories.")
                    Text("Follow – this device shows their setup and gets their changes.", style = MaterialTheme.typography.bodyMedium)
                    Text("Copy once – take it now and make it your own.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Either way it replaces the favorites, lists and settings on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            }
        },
        confirmButton = {
            val profile = found
            if (profile == null) {
                TextButton(
                    enabled = !busy && code.count { it.isLetterOrDigit() } == 8,
                    onClick = {
                        busy = true
                        scope.launch {
                            container.sync.preview(code)
                                .onSuccess {
                                    if (it.code == container.sync.status.value.code) {
                                        error = "That's your own code – devices signed in as you already share it."
                                    } else {
                                        found = it
                                    }
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { Text("Next") }
            } else {
                Row {
                    TextButton(enabled = !busy, onClick = { use(follow = false, profile) }) { Text("Copy once") }
                    TextButton(enabled = !busy, onClick = { use(follow = true, profile) }) { Text("Follow") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Saved versions, newest first; picking one (after a confirmation) makes it the current setup everywhere. */
@Composable
private fun HistoryDialog(container: AppContainer, username: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<SyncVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<SyncVersion?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LaunchedEffect(Unit) {
        container.sync.history().onSuccess { versions = it }.onFailure { error = it.message ?: "Couldn't load the history" }
    }

    val choice = picked
    if (choice != null) {
        AlertDialog(
            onDismissRequest = { if (!restoring) picked = null },
            title = { Text("Go back to this version?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${format.format(Date(choice.savedAt))} – ${choice.summary()}")
                    Text(
                        "It replaces the favorites, lists, pinned categories and settings on every device signed in as " +
                            "${username ?: "you"}. What you have now stays in the history.",
                    )
                    if (restoring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            confirmButton = {
                TextButton(enabled = !restoring, onClick = {
                    restoring = true
                    scope.launch {
                        container.sync.restore(choice.revision)
                        restoring = false
                        val failure = container.sync.status.value.error
                        if (failure != null) {
                            Toast.makeText(context, "Couldn't restore: $failure", Toast.LENGTH_LONG).show()
                            picked = null
                        } else {
                            Toast.makeText(context, "Restored the version from ${format.format(Date(choice.savedAt))}", Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(enabled = !restoring, onClick = { picked = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History") },
        text = {
            val list = versions
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                list == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                list.isEmpty() -> Text("Nothing saved yet.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(list, key = { _, v -> v.revision }) { index, version ->
                        val where = listOf(version.device, version.ip).filter { it.isNotBlank() }.joinToString(" · ")
                        ListItem(
                            headlineContent = {
                                Text(format.format(Date(version.savedAt)) + if (index == 0) "  (current)" else "")
                            },
                            supportingContent = {
                                Column {
                                    Text(version.summary())
                                    if (where.isNotEmpty()) {
                                        Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            modifier = Modifier.clickable(enabled = index > 0) { picked = version },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun SyncVersion.summary() = "$favorites favorites, $lists lists, $pinned pinned categories"
