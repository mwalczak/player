package com.mwalczak.player.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.unit.dp
import com.mwalczak.player.BuildConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.AccountInfo
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.cast.RemoteDevice
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.mwalczak.player.data.EpgSettings
import com.mwalczak.player.data.epg.DEFAULT_EPG_URL
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import com.mwalczak.player.data.PlaybackSettings
import com.mwalczak.player.data.StreamFormat
import com.mwalczak.player.ui.Actions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsTab(container: AppContainer, actions: Actions) {
    val playback by container.settings.playback.collectAsStateWithLifecycle(PlaybackSettings())
    val creds by container.settings.credentials.collectAsStateWithLifecycle(null)
    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    val scope = container.appScope

    LaunchedEffect(creds) {
        val c = creds ?: return@LaunchedEffect
        runCatching { container.xtream.authenticate(c) }
            .onSuccess { account = it; accountError = null }
            .onFailure { accountError = it.message }
    }

    fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        scope.launch { container.settings.updatePlayback(transform) }
    }

    val update by container.updater.state.collectAsStateWithLifecycle()
    val catalogState by container.catalog.state.collectAsStateWithLifecycle()
    val epgState by container.epg.state.collectAsStateWithLifecycle()
    val epgSettings by container.settings.epg.collectAsStateWithLifecycle(EpgSettings())
    var editingEpgUrl by remember { mutableStateOf(false) }
    var copyingToTv by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (copyingToTv) {
        CopyToTvDialog(container, onDismiss = { copyingToTv = false }) { device ->
            copyingToTv = false
            scope.launch {
                container.remote.syncTo(device)
                    .onSuccess { Toast.makeText(context, "Copied to ${device.name}", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(context, "${device.name}: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    if (editingEpgUrl) {
        var url by remember { mutableStateOf(epgSettings.url) }
        AlertDialog(
            onDismissRequest = { editingEpgUrl = false },
            title = { Text("Guide source") },
            text = {
                Column {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("XMLTV URL (.xml or .xml.gz)") })
                    TextButton(onClick = { url = DEFAULT_EPG_URL }) { Text("Use default (Poland)") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingEpgUrl = false
                    scope.launch { container.settings.updateEpg(epgSettings.copy(url = url)) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingEpgUrl = false }) { Text("Cancel") } },
        )
    }
    val activity = LocalActivity.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).widthIn(max = 760.dp)) {
        SectionHeader(startPadding = 16.dp, text = "Account")
        ListItem(
            headlineContent = { Text(creds?.username ?: "—") },
            supportingContent = { Text(creds?.server ?: "") },
        )
        account?.let { info ->
            val expires = info.expiresAtEpochSec?.let { DateFormat.getDateInstance().format(Date(it * 1000)) } ?: "Never"
            ListItem(
                headlineContent = { Text("Status: ${info.status}${if (info.isTrial) " (trial)" else ""}") },
                supportingContent = {
                    Text("Expires: $expires · Connections: ${info.activeConnections ?: "?"} / ${info.maxConnections ?: "?"}")
                },
            )
        }
        accountError?.let {
            ListItem(headlineContent = { Text(it, color = MaterialTheme.colorScheme.error) })
        }

        SectionHeader(startPadding = 16.dp, text = "Stream format")
        StreamFormat.entries.forEach { format ->
            ListItem(
                headlineContent = { Text(format.label) },
                supportingContent = {
                    Text(if (format == StreamFormat.TS) "Lowest latency, works with most providers" else "Try this if .ts streams stutter")
                },
                leadingContent = { RadioButton(selected = playback.format == format, onClick = null) },
                modifier = Modifier.clickable { update { it.copy(format = format) } },
            )
        }

        SectionHeader(startPadding = 16.dp, text = "When a channel is off")
        ListItem(
            headlineContent = { Text("Try other feeds of the same channel") },
            supportingContent = { Text("e.g. “ESPN HD” is down → play “ESPN FHD” or a backup feed") },
            trailingContent = { Switch(checked = playback.tryAlternates, onCheckedChange = null) },
            modifier = Modifier.clickable { update { it.copy(tryAlternates = !it.tryAlternates) } },
        )
        ListItem(
            headlineContent = { Text("Then switch to the next channel in the list") },
            supportingContent = { Text("Order your favorites / lists so the backups come next") },
            trailingContent = { Switch(checked = playback.nextInList, onCheckedChange = null) },
            modifier = Modifier.clickable { update { it.copy(nextInList = !it.nextInList) } },
        )
        val timeouts = listOf(8, 15, 30)
        ListItem(
            headlineContent = { Text("Give up after buffering for ${playback.stallTimeoutSec} s") },
            supportingContent = { Text("Tap to change (${timeouts.joinToString(" / ") { "$it s" }})") },
            modifier = Modifier.clickable {
                update { s -> s.copy(stallTimeoutSec = timeouts[(timeouts.indexOf(s.stallTimeoutSec) + 1) % timeouts.size]) }
            },
        )

        SectionHeader(startPadding = 16.dp, text = "TV guide (EPG)")
        ListItem(
            headlineContent = { Text("Use external guide") },
            supportingContent = {
                Text(
                    when {
                        epgState.loading -> "Updating the guide…"
                        epgState.error != null -> "Error: ${epgState.error}"
                        epgState.updatedAtMillis > 0 -> {
                            val total = (catalogState as? CatalogState.Ready)?.catalog?.channels?.size ?: 0
                            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epgState.updatedAtMillis))
                            "Guide for ${epgState.programmes.size} of $total channels · updated $time"
                        }
                        else -> "Fills in programmes your provider doesn't send"
                    },
                )
            },
            trailingContent = { Switch(checked = epgSettings.enabled, onCheckedChange = null) },
            modifier = Modifier.clickable {
                scope.launch { container.settings.updateEpg(epgSettings.copy(enabled = !epgSettings.enabled)) }
            },
        )
        ListItem(
            headlineContent = { Text("Guide source (XMLTV)") },
            supportingContent = { Text(epgSettings.url, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.clickable { editingEpgUrl = true },
        )
        ListItem(
            headlineContent = { Text("Refresh guide now") },
            leadingContent = { Icon(Icons.Default.Refresh, null) },
            modifier = Modifier.clickable { scope.launch { container.refreshEpg() } },
        )

        SectionHeader(startPadding = 16.dp, text = "Other devices")
        ListItem(
            headlineContent = { Text("Copy favorites & settings to TV") },
            supportingContent = { Text("Favorites, lists, pinned categories, settings and login – open Bunny Player on the TV first") },
            leadingContent = { Icon(Icons.Default.Tv, null) },
            modifier = Modifier.clickable { copyingToTv = true },
        )

        SectionHeader(startPadding = 16.dp, text = "App")
        ListItem(
            headlineContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
            supportingContent = {
                Text(
                    when {
                        update.currentBuild <= 1 -> "Local build – updates only for builds from GitHub"
                        update.message != null -> update.message!!
                        update.busy -> "Downloading update… ${(update.progress * 100).toInt()}%"
                        update.checkError != null -> "Couldn't check for updates: ${update.checkError}"
                        update.available -> "Build ${update.latestBuild} is available – tap to update"
                        update.latestBuild > 0 -> "Up to date"
                        else -> "Tap to check for updates"
                    },
                )
            },
            leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
            modifier = Modifier.clickable {
                if (update.available) activity?.let(container.updater::install) else container.updater.check(force = true)
            },
        )

        SectionHeader(startPadding = 16.dp, text = "Data")
        ListItem(
            headlineContent = { Text("Reload channel list") },
            supportingContent = {
                (catalogState as? CatalogState.Ready)?.catalog?.let { c ->
                    val empty = c.emptyCategories.size
                    Text("${c.channels.size} live channels in ${c.categories.size} categories" + if (empty > 0) " ($empty empty)" else "")
                }
            },
            leadingContent = { Icon(Icons.Default.Refresh, null) },
            modifier = Modifier.clickable {
                scope.launch { creds?.let { container.catalog.load(it, force = true) } }
            },
        )
        ListItem(
            headlineContent = { Text("Sign out") },
            supportingContent = { Text("Favorites and lists stay on this device") },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
            modifier = Modifier.clickable {
                scope.launch {
                    container.catalog.clear(creds)
                    container.settings.clearCredentials()
                    actions.loggedOut()
                }
            },
        )
    }
}

/** Pick a TV running Bunny Player, then confirm before overwriting its favorites and settings. */
@Composable
private fun CopyToTvDialog(container: AppContainer, onDismiss: () -> Unit, onCopy: (RemoteDevice) -> Unit) {
    val devices by container.remote.devices.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<RemoteDevice?>(null) }
    val target = confirm
    if (target != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Copy to ${target.name}?") },
            text = { Text("Favorites, lists, pinned categories and settings on ${target.name} will be replaced with the ones from this device.") },
            confirmButton = { TextButton(onClick = { onCopy(target) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy to which TV?") },
        text = {
            Column {
                devices.forEach { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(device.host) },
                        leadingContent = { Icon(Icons.Default.Tv, null) },
                        modifier = Modifier.clickable { confirm = device },
                    )
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Looking for TVs… Open Bunny Player on the TV, on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
