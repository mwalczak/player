package com.mwalczak.player.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Autorenew
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import com.mwalczak.player.data.EpgSettings
import com.mwalczak.player.data.epg.EpgSource
import com.mwalczak.player.data.epg.EpgSources
import androidx.compose.foundation.layout.heightIn
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
    var choosingSources by remember { mutableStateOf(false) }
    val readyCatalog = (catalogState as? CatalogState.Ready)?.catalog
    val activeSources = remember(readyCatalog, epgSettings) {
        readyCatalog?.let { container.epg.sourcesFor(it, epgSettings) }.orEmpty()
    }
    if (choosingSources) {
        GuideSourcesDialog(
            selected = epgSettings.sources,
            automatic = readyCatalog?.let { EpgSources.autoSelect(it.channels, it.categories) }.orEmpty(),
            onDismiss = { choosingSources = false },
        ) { chosen ->
            choosingSources = false
            scope.launch { container.settings.updateEpg(epgSettings.copy(sources = chosen)) }
        }
    }
    val gridColumns by container.settings.gridColumns.collectAsStateWithLifecycle(0)
    val autoplaySeconds by container.settings.autoplaySeconds.collectAsStateWithLifecycle(60)
    val previewWhileBrowsing by container.settings.previewWhileBrowsing.collectAsStateWithLifecycle(true)

    if (editingEpgUrl) {
        var url by remember { mutableStateOf(epgSettings.url) }
        AlertDialog(
            onDismissRequest = { editingEpgUrl = false },
            title = { Text("Extra guide") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional: one more XMLTV guide on top of the built-in ones. Leave empty for none.")
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("XMLTV URL (.xml or .xml.gz)") })
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

        SectionHeader(startPadding = 16.dp, text = "Display")
        ListItem(
            headlineContent = { Text("Columns in channel lists: ${if (gridColumns == 0) "automatic" else gridColumns.toString()}") },
            supportingContent = { Text("Fewer columns leave more room for channel names. Tap to change (automatic, 1, 2, 3, 4).") },
            modifier = Modifier.clickable {
                scope.launch { container.settings.setGridColumns(if (gridColumns >= 4) 0 else gridColumns + 1) }
            },
        )

        ListItem(
            headlineContent = { Text("Live preview while browsing") },
            supportingContent = { Text("On TV, tablet and landscape phone: the selected channel plays next to the list; OK or a second tap opens full screen") },
            trailingContent = { Switch(checked = previewWhileBrowsing, onCheckedChange = null) },
            modifier = Modifier.clickable { scope.launch { container.settings.setPreviewWhileBrowsing(!previewWhileBrowsing) } },
        )

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
        ListItem(
            headlineContent = { Text("Skip channels without sound") },
            supportingContent = { Text("“Temporarily unavailable” screens are silent: after 5 s without sound the channel counts as off") },
            trailingContent = { Switch(checked = playback.skipSilent, onCheckedChange = null) },
            modifier = Modifier.clickable { update { it.copy(skipSilent = !it.skipSilent) } },
        )
        val timeouts = listOf(8, 15, 30)
        ListItem(
            headlineContent = { Text("Give up after buffering for ${playback.stallTimeoutSec} s") },
            supportingContent = { Text("Tap to change (${timeouts.joinToString(" / ") { "$it s" }})") },
            modifier = Modifier.clickable {
                update { s -> s.copy(stallTimeoutSec = timeouts[(timeouts.indexOf(s.stallTimeoutSec) + 1) % timeouts.size]) }
            },
        )

        SectionHeader(startPadding = 16.dp, text = "Autoplay")
        val autoplayTimes = listOf(30, 60, 120, 300, 600)
        ListItem(
            headlineContent = { Text("Change channel every ${autoplayLabel(autoplaySeconds)}") },
            supportingContent = {
                Text("Autoplay (in favorites, lists and categories) goes through the channels like this. Tap to change (${autoplayTimes.joinToString(" / ") { autoplayLabel(it) }})")
            },
            leadingContent = { Icon(Icons.Default.Autorenew, null) },
            modifier = Modifier.clickable {
                val next = autoplayTimes[(autoplayTimes.indexOf(autoplaySeconds) + 1) % autoplayTimes.size]
                scope.launch { container.settings.setAutoplaySeconds(next) }
            },
        )

        SectionHeader(startPadding = 16.dp, text = "TV guide (EPG)")
        ListItem(
            headlineContent = { Text("Programme guide") },
            supportingContent = {
                Text(
                    when {
                        !epgSettings.enabled -> "Off – only your provider's own guide in the player"
                        epgState.loading -> "Updating… ${epgState.progress.orEmpty()}"
                        epgState.updatedAtMillis > 0 -> {
                            val total = readyCatalog?.channels?.size ?: 0
                            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epgState.updatedAtMillis))
                            "Guide for ${epgState.channelsWithGuide} of $total channels · updated $time" +
                                (epgState.error?.let { "\n$it" } ?: "")
                        }
                        epgState.error != null -> epgState.error!!
                        else -> "Not downloaded yet – tap Download guide now"
                    },
                )
            },
            trailingContent = { Switch(checked = epgSettings.enabled, onCheckedChange = null) },
            modifier = Modifier.clickable {
                scope.launch { container.settings.updateEpg(epgSettings.copy(enabled = !epgSettings.enabled)) }
            },
        )
        if (epgSettings.enabled) {
            ListItem(
                headlineContent = { Text(if (epgSettings.sources == null) "Guides: automatic" else "Guides: chosen by you") },
                supportingContent = {
                    Text(
                        activeSources.filter { it.id != "custom" }.joinToString(", ") { it.label }.ifEmpty { "None" } +
                            " · ~${activeSources.sumOf { it.approxMb }} MB per day",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.clickable { choosingSources = true },
            )
            ListItem(
                headlineContent = { Text("Download guides only on Wi-Fi") },
                supportingContent = { Text("Saves mobile data; the TV and Wi-Fi devices always update") },
                trailingContent = { Switch(checked = epgSettings.wifiOnly, onCheckedChange = null) },
                modifier = Modifier.clickable {
                    scope.launch { container.settings.updateEpg(epgSettings.copy(wifiOnly = !epgSettings.wifiOnly)) }
                },
            )
            ListItem(
                headlineContent = { Text("Extra guide (XMLTV URL)") },
                supportingContent = { Text(epgSettings.url.ifBlank { "None" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable { editingEpgUrl = true },
            )
            ListItem(
                headlineContent = { Text(if (epgState.updatedAtMillis > 0) "Update guide now" else "Download guide now") },
                supportingContent = { Text("Downloads the guides above (~${activeSources.sumOf { it.approxMb }} MB) and matches them to your channels. Takes a few minutes; you can keep watching.") },
                leadingContent = { Icon(Icons.Default.Refresh, null) },
                modifier = Modifier.clickable(enabled = !epgState.loading) { scope.launch { container.refreshEpg() } },
            )
        }

        SectionHeader(startPadding = 16.dp, text = "Sync & sharing")
        SyncSection(container, creds?.username)

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

@Composable
private fun GuideSourcesDialog(
    selected: Set<String>?,
    automatic: List<EpgSource>,
    onDismiss: () -> Unit,
    onSave: (Set<String>?) -> Unit,
) {
    var auto by remember { mutableStateOf(selected == null) }
    var chosen by remember { mutableStateOf(selected ?: automatic.map { it.id }.toSet()) }
    val shown = if (auto) automatic.map { it.id }.toSet() else chosen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guides") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { auto = !auto }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = auto, onCheckedChange = { auto = it })
                    Text("Automatic – countries found in your channel list")
                }
                Text(
                    "~${EpgSources.all.filter { it.id in shown }.sumOf { it.approxMb }} MB per day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(EpgSources.all.size) { i ->
                        val source = EpgSources.all[i]
                        val checked = source.id in shown
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !auto) {
                                chosen = if (checked) chosen - source.id else chosen + source.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = !auto,
                                onCheckedChange = { chosen = if (it) chosen + source.id else chosen - source.id },
                            )
                            Text("${source.label}  (~${source.approxMb} MB)", modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(if (auto) null else chosen) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "30 s", "1 min", "5 min". */
internal fun autoplayLabel(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"
