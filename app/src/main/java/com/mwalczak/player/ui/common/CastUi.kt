package com.mwalczak.player.ui.common

import com.mwalczak.player.cast.RemoteStatus
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.mwalczak.player.ui.home.categoryName
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.ChannelMatcher
import com.mwalczak.player.cast.isPlaying
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AltRoute
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.cast.RemoteDevice
import kotlinx.coroutines.launch

/**
 * Cast icon: highlighted while channels go to another device. Tapping it lists the
 * Bunny Players found on the network. [onPicked] runs after a TV is chosen (e.g. to hand over
 * the channel being watched).
 */
@Composable
fun CastButton(
    container: AppContainer,
    tint: Color = LocalContentColor.current,
    onPicked: (RemoteDevice) -> Unit = {},
) {
    val target by container.remote.target.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            if (target != null) Icons.Default.CastConnected else Icons.Default.Cast,
            contentDescription = if (target != null) "Casting to ${target?.name}" else "Cast to TV",
            tint = if (target != null) MaterialTheme.colorScheme.primary else tint,
        )
    }
    if (open) {
        CastDialog(container, onDismiss = { open = false }) { device ->
            open = false
            container.remote.select(device)
            if (device != null) onPicked(device)
        }
    }
}

@Composable
private fun CastDialog(container: AppContainer, onDismiss: () -> Unit, onSelect: (RemoteDevice?) -> Unit) {
    val devices by container.remote.devices.collectAsStateWithLifecycle()
    val target by container.remote.target.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("This device") },
                    leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                    trailingContent = { RadioButton(selected = target == null, onClick = null) },
                    modifier = Modifier.clickable { onSelect(null) },
                )
                devices.forEach { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(device.host) },
                        leadingContent = { Icon(Icons.Default.Tv, null) },
                        trailingContent = { RadioButton(selected = target == device, onClick = null) },
                        modifier = Modifier.clickable { onSelect(device) },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
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

/** Shown while casting: what the TV is playing, with channel / stop controls. */
@Composable
fun RemoteBar(container: AppContainer, modifier: Modifier = Modifier) {
    val target by container.remote.target.collectAsStateWithLifecycle()
    val device = target ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by container.remote.targetStatus.collectAsStateWithLifecycle()

    fun send(type: String) {
        scope.launch {
            container.remote.command(device, type)
                .onSuccess { container.remote.refreshSoon() }
                .onFailure { Toast.makeText(context, "${device.name}: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    var choosingFeed by remember { mutableStateOf(false) }
    val s = status
    if (choosingFeed && s != null) {
        FeedsDialog(container, s, onDismiss = { choosingFeed = false }) { channel ->
            choosingFeed = false
            scope.launch {
                container.remote.feed(device, channel)
                    .onSuccess { container.remote.refreshSoon() }
                    .onFailure { Toast.makeText(context, "${device.name}: ${it.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // Two rows, so every control fits on a phone held upright: what's on, then the buttons.
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CardShape, modifier = modifier) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CastConnected, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            s == null -> "Connecting…"
                            s.playing != null -> "${s.playing}  ·  ${s.index + 1}/${s.count} ${s.title.orEmpty()}"
                            else -> "Pick a channel to play it on the TV"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { container.remote.select(null) }) { Icon(Icons.Default.Close, "Stop casting") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val playing = s?.playing != null
                IconButton(onClick = { send("previous") }, enabled = playing) { Icon(Icons.Default.SkipPrevious, "Previous channel on TV") }
                IconButton(onClick = { send("next") }, enabled = playing) { Icon(Icons.Default.SkipNext, "Next channel on TV") }
                IconButton(onClick = { send("reload") }, enabled = playing) { Icon(Icons.Default.Refresh, "Reload the channel on TV") }
                IconButton(onClick = { send("last") }, enabled = playing) { Icon(Icons.Default.SwapHoriz, "Last channel on TV") }
                IconButton(onClick = { choosingFeed = true }, enabled = playing) { Icon(Icons.Default.AltRoute, "Other feeds of the channel on TV") }
                IconButton(onClick = { send("stop") }, enabled = playing) { Icon(Icons.Default.Stop, "Stop on TV") }
            }
        }
    }
}

/** Other streams of the channel on the TV (same channel first, then similar names), from this device's catalog. */
@Composable
private fun FeedsDialog(container: AppContainer, status: RemoteStatus, onDismiss: () -> Unit, onPick: (Channel) -> Unit) {
    val catalog = container.catalog.catalog
    val found by produceState<Pair<Channel?, List<Channel>>?>(null, status.streamId, status.playing, catalog) {
        val all = catalog?.channels.orEmpty()
        value = withContext(Dispatchers.Default) {
            val current = all.firstOrNull { status.isPlaying(it) }
            current to current?.let { ChannelMatcher.alternatesFor(it, all) + ChannelMatcher.similarTo(it, all) }.orEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Other feeds of ${status.playing.orEmpty()}") },
        text = {
            val result = found
            when {
                result == null -> CircularProgressIndicator()
                result.second.isEmpty() -> Text("No other feed of this channel in the channel list.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(result.second, key = { it.streamId }) { channel ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(channel) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ChannelLogo(channel.icon, Modifier.size(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                catalog?.categoryName(channel)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
