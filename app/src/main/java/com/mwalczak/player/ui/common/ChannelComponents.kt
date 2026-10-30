package com.mwalczak.player.ui.common

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.epg.Programme
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator

@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val placeholder = @Composable {
            Icon(Icons.Default.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (url.isNullOrBlank()) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

/**
 * A channel row with favorite star and an overflow menu. [trailing] lets list screens add
 * their own controls (e.g. reorder buttons).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    container: AppContainer,
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    /** Told when a TV remote moves onto / off this row (drives the browsing preview). */
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val library by container.library.library.collectAsStateWithLifecycle()
    val isFavorite = library.favorites.any { it.streamId == channel.streamId }
    var menuOpen by remember { mutableStateOf(false) }
    var addToList by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .focusHighlight(interaction)
            .then(if (onFocusChange != null) Modifier.onFocusChanged { onFocusChange(it.hasFocus) } else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                // Long-press (or long-press OK on a remote) is the quickest way to (un)favorite.
                onLongClick = {
                    container.library.toggleFavorite(channel)
                    val message = if (isFavorite) "Removed from favorites" else "Added to favorites"
                    Toast.makeText(context, "${channel.name}: $message", Toast.LENGTH_SHORT).show()
                },
            )
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChannelLogo(channel.icon, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val onAir = nowOnAir(container, channel)
            val line = listOfNotNull(subtitle, onAir?.title).joinToString("  ·  ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onAir != null) {
                val now by container.clock.collectAsStateWithLifecycle()
                LinearProgressIndicator(
                    progress = { onAir.progress(now) },
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp).fillMaxWidth().height(2.dp),
                    drawStopIndicator = {},
                )
            }
        }
        trailing()
        IconButton(onClick = { container.library.toggleFavorite(channel) }) {
            Icon(
                if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add to list…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = { menuOpen = false; addToList = true },
                )
            }
        }
    }

    if (addToList) AddToListDialog(container, channel, onDismiss = { addToList = false })
}

val CardShape = RoundedCornerShape(12.dp)

/** The programme currently on [channel], from the guide. */
@Composable
fun nowOnAir(container: AppContainer, channel: Channel): Programme? {
    val onAir by container.epg.onAir.collectAsStateWithLifecycle()
    val now by container.clock.collectAsStateWithLifecycle()
    return onAir[channel.streamId]?.takeIf { it.start <= now && now < it.stop }
}

fun Programme.progress(nowSec: Long): Float =
    ((nowSec - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Bold outline + tint when focused, so D-pad navigation on a TV is easy to follow. */
@Composable
fun Modifier.focusHighlight(interaction: InteractionSource, shape: Shape = CardShape): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    if (!focused) return this
    val color = MaterialTheme.colorScheme.primary
    return this
        .border(3.dp, color, shape)
        .background(color.copy(alpha = 0.18f), shape)
}

/** Channels laid out in as many columns as fit: 2 on a phone in landscape, 3-4 on a tablet, 2-3 on a TV. */
@Composable
fun ChannelGrid(
    container: AppContainer,
    channels: List<Channel>,
    onPlay: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (index: Int, channel: Channel) -> String? = { _, _ -> null },
) {
    val fixed by container.settings.gridColumns.collectAsStateWithLifecycle(0)
    // Back from full screen, the remote lands on the channel that was playing (or the one it left).
    val gridState = rememberLazyGridState()
    val memory = rememberFocusMemory { container.lastPlayedStreamId?.toString() }
    val target = memory.target { key -> channels.any { it.streamId.toString() == key } }
    val targetIndex = target?.let { t -> channels.indexOfFirst { it.streamId.toString() == t } } ?: -1
    RestoreFocus(memory, targetIndex, ready = channels.isNotEmpty(), gridState)

    val grid: @Composable (Modifier, (Int, Boolean) -> Unit, ((Int, Boolean) -> Unit)?) -> Unit = { gridModifier, click, focus ->
        LazyVerticalGrid(
            columns = if (fixed > 0) GridCells.Fixed(fixed) else GridCells.Adaptive(minSize = GRID_MIN_CELL),
            modifier = gridModifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                var focused by remember { mutableStateOf(false) }
                ChannelRow(
                    container,
                    channel,
                    onClick = { click(index, focused) },
                    modifier = Modifier.focusMemory(memory, channel.streamId.toString(), target),
                    subtitle = subtitle(index, channel),
                    onFocusChange = focus?.let { f -> { has -> focused = has; f(index, has) } },
                )
            }
        }
    }

    if (!previewEnabled(container)) {
        grid(modifier, { index, _ -> onPlay(index) }, null)
        return
    }
    // Wide screens: browse with a live preview; OK on a focused row (or a second tap) goes full screen.
    val preview = remember { PreviewState() }
    val fullscreen: (Int) -> Unit = { index ->
        preview.stop(container)
        onPlay(index)
    }
    Row(modifier.fillMaxSize()) {
        grid(
            Modifier.weight(1f),
            { index, focused -> if (focused || preview.index == index) fullscreen(index) else preview.index = index },
            { index, has -> if (has) preview.index = index },
        )
        PreviewPane(container, channels, preview, fullscreen, Modifier.weight(0.75f))
    }
}

val GRID_MIN_CELL = 300.dp

@Composable
fun AddToListDialog(container: AppContainer, channel: Channel, onDismiss: () -> Unit) {
    val library by container.library.library.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(library.lists.isEmpty()) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add “${channel.name}” to") },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(library.lists, key = { it.id }) { list ->
                        val checked = list.channels.any { it.streamId == channel.streamId }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { container.library.toggleInList(list.id, channel) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { container.library.toggleInList(list.id, channel) })
                            Text(list.name, Modifier.weight(1f))
                        }
                    }
                }
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New list name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, null)
                        Text("New list")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (creating && newName.isNotBlank()) {
                    val id = container.library.createList(newName)
                    container.library.toggleInList(id, channel)
                }
                onDismiss()
            }) { Text(if (creating && newName.isNotBlank()) "Create & add" else "Done") }
        },
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
