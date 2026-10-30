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
