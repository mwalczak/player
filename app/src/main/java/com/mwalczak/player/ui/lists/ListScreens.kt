package com.mwalczak.player.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.Actions
import com.mwalczak.player.ui.common.ChannelLogo
import com.mwalczak.player.ui.common.ChannelGrid
import com.mwalczak.player.data.sortedByName
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Autorenew
import com.mwalczak.player.ui.common.EmptyState
import com.mwalczak.player.ui.home.SearchContent
import com.mwalczak.player.ui.home.WithCatalog
import com.mwalczak.player.ui.home.categoryName
import com.mwalczak.player.ui.home.ScreenHeader
import com.mwalczak.player.ui.common.CardShape
import com.mwalczak.player.ui.common.GRID_MIN_CELL
import com.mwalczak.player.ui.common.RestoreFocus
import com.mwalczak.player.ui.common.focusHighlight
import com.mwalczak.player.ui.common.focusMemory
import com.mwalczak.player.ui.common.rememberFocusMemory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun FavoritesTab(container: AppContainer, actions: Actions) {
    val library by container.library.library.collectAsStateWithLifecycle()
    var reordering by remember { mutableStateOf(false) }

    OrderedChannelList(
        container = container,
        channels = library.favorites,
        reordering = reordering,
        emptyText = "No favorites yet. Long-press any channel (or tap its ☆) to add it here.",
        onToggleReorder = { reordering = !reordering },
        onPlay = { index -> actions.play("Favorites", library.favorites, index) },
        onAutoplay = { actions.autoplay("Favorites", library.favorites) },
        onMove = container.library::moveFavorite,
        onReorder = container.library::reorderFavorites,
        onRemove = container.library::toggleFavorite,
    )
}

/**
 * A channel list whose order matters (it is the zapping and failover order), with a reorder mode.
 */
@Composable
private fun OrderedChannelList(
    container: AppContainer,
    channels: List<Channel>,
    reordering: Boolean,
    emptyText: String,
    onToggleReorder: () -> Unit,
    onPlay: (Int) -> Unit,
    /** Plays the list with autoplay on. */
    onAutoplay: () -> Unit,
    onMove: (Int, Int) -> Unit,
    /** Puts the channels in this order (A–Z, or back to how they were). */
    onReorder: (List<Channel>) -> Unit,
    onRemove: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The order before "A–Z", so it can be undone while reordering.
    var beforeSort by remember { mutableStateOf<List<Channel>?>(null) }
    LaunchedEffect(reordering) { if (!reordering) beforeSort = null }
    if (channels.isEmpty()) {
        EmptyState(emptyText, modifier)
        return
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${channels.size} channels",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row {
                val undo = beforeSort?.takeIf { it != channels && channels == channels.sortedByName() }
                when {
                    !reordering -> TextButton(onClick = onAutoplay) {
                        Icon(Icons.Default.Autorenew, null)
                        Text(" Autoplay")
                    }
                    undo != null -> TextButton(onClick = { onReorder(undo); beforeSort = null }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null)
                        Text(" Undo A–Z")
                    }
                    else -> TextButton(onClick = { beforeSort = channels; onReorder(channels.sortedByName()) }) {
                        Icon(Icons.Default.SortByAlpha, null)
                        Text(" A–Z")
                    }
                }
                TextButton(onClick = onToggleReorder) {
                    Icon(if (reordering) Icons.Default.Check else Icons.Default.SwapVert, null)
                    Text(if (reordering) " Done" else " Reorder")
                }
            }
        }
        if (!reordering) {
            ChannelGrid(container, channels, onPlay = onPlay, subtitle = { index, _ -> "${index + 1}." })
            return@Column
        }
        ReorderList(channels, onMove = onMove, onRemove = onRemove)
    }
}

@Composable
fun ListsTab(container: AppContainer, actions: Actions) {
    val library by container.library.library.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (library.lists.isEmpty()) {
            EmptyState("Create lists like “Sports” or “News” and fill them with your channels.")
        } else {
            // Back from a list, the remote lands on the list it was opened from.
            val gridState = rememberLazyGridState()
            val memory = rememberFocusMemory()
            val target = memory.target { key -> library.lists.any { it.id == key } }
            RestoreFocus(memory, library.lists.indexOfFirst { it.id == target }, ready = true, gridState)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(library.lists, key = { it.id }) { list ->
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .focusMemory(memory, list.id, target)
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .focusHighlight(interaction)
                            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                                actions.openList(list.id)
                            }
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${list.channels.size} channels",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { actions.play(list.name, list.channels, 0) },
                            enabled = list.channels.isNotEmpty(),
                        ) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Play ${list.name}") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("New list") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) {
        NameDialog(
            title = "New list",
            initial = "",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                actions.openList(container.library.createList(name))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(container: AppContainer, actions: Actions, listId: String) {
    val library by container.library.library.collectAsStateWithLifecycle()
    val list = library.lists.firstOrNull { it.id == listId }
    var reordering by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    BackHandler { if (reordering) reordering = false else actions.backHome() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(list?.name ?: "List", onBack = { actions.backHome() }) {
            Button(onClick = { actions.openPicker(listId) }) {
                Icon(Icons.Default.Add, null)
                Text(" Add channels")
            }
            IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete list") }
        }
        if (list == null) {
            EmptyState("This list no longer exists.")
        } else {
            OrderedChannelList(
                container = container,
                channels = list.channels,
                reordering = reordering,
                emptyText = "This list is empty. Use “Add channels” to search and add some.",
                onToggleReorder = { reordering = !reordering },
                onPlay = { index -> actions.play(list.name, list.channels, index) },
                onAutoplay = { actions.autoplay(list.name, list.channels) },
                onMove = { from, to -> container.library.moveInList(listId, from, to) },
                onReorder = { container.library.reorderList(listId, it) },
                onRemove = { container.library.toggleInList(listId, it) },
            )
        }
    }

    if (renaming && list != null) {
        NameDialog("Rename list", list.name, "Save", onDismiss = { renaming = false }) {
            container.library.renameList(listId, it)
            renaming = false
        }
    }
    if (deleting && list != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${list.name}”?") },
            text = { Text("The channels themselves are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    actions.backHome()
                    container.library.deleteList(listId)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** Search the whole catalog and tick channels to add them to a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPickerScreen(container: AppContainer, actions: Actions, listId: String) {
    val library by container.library.library.collectAsStateWithLifecycle()
    val catalogState by container.catalog.state.collectAsStateWithLifecycle()
    val list = library.lists.firstOrNull { it.id == listId }
    val inList = list?.channels.orEmpty().map { it.streamId }.toSet()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader("Add to ${list?.name.orEmpty()} (${inList.size})", onBack = { actions.back() }) {
            Button(onClick = { actions.back() }) { Text("Done") }
        }
        WithCatalog(container, catalogState) { catalog ->
            SearchContent(catalog) { results ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.streamId }) { channel ->
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(CardShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .focusHighlight(interaction)
                                .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                                    container.library.toggleInList(listId, channel)
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = channel.streamId in inList, onCheckedChange = null)
                            ChannelLogo(channel.icon, Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                catalog.categoryName(channel)?.let {
                                    Text(
                                        it,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
