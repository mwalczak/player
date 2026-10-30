package com.mwalczak.player.ui.lists

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.mwalczak.player.ui.common.ChannelRow
import com.mwalczak.player.ui.common.EmptyState
import com.mwalczak.player.ui.home.SearchContent
import com.mwalczak.player.ui.home.WithCatalog
import com.mwalczak.player.ui.home.categoryName

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
        onMove = container.library::moveFavorite,
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
    onMove: (Int, Int) -> Unit,
    onRemove: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            )
            Row {
                TextButton(onClick = { onPlay(0) }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
                    Text(" Play all")
                }
                TextButton(onClick = onToggleReorder) {
                    Icon(if (reordering) Icons.Default.Check else Icons.Default.SwapVert, null)
                    Text(if (reordering) " Done" else " Reorder")
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (reordering) {
                itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                    ListItem(
                        headlineContent = { Text(channel.name, maxLines = 1) },
                        leadingContent = { ChannelLogo(channel.icon, Modifier.size(40.dp)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                                    Icon(Icons.Default.ArrowUpward, "Move up")
                                }
                                IconButton(onClick = { onMove(index, index + 1) }, enabled = index < channels.lastIndex) {
                                    Icon(Icons.Default.ArrowDownward, "Move down")
                                }
                                IconButton(onClick = { onRemove(channel) }) { Icon(Icons.Default.Delete, "Remove") }
                            }
                        },
                    )
                }
            } else {
                itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                    ChannelRow(container, channel, onClick = { onPlay(index) }, subtitle = "${index + 1}.")
                }
            }
        }
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
            LazyColumn(Modifier.fillMaxSize()) {
                items(library.lists, key = { it.id }) { list ->
                    ListItem(
                        headlineContent = { Text(list.name) },
                        supportingContent = { Text("${list.channels.size} channels") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                        trailingContent = {
                            IconButton(
                                onClick = { actions.play(list.name, list.channels, 0) },
                                enabled = list.channels.isNotEmpty(),
                            ) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Play ${list.name}") }
                        },
                        modifier = Modifier.clickable { actions.openList(list.id) },
                    )
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list?.name ?: "List") },
                navigationIcon = {
                    IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete list") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { actions.openPicker(listId) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add channels") },
            )
        },
    ) { padding ->
        if (list == null) {
            EmptyState("This list no longer exists.", Modifier.padding(padding))
        } else {
            OrderedChannelList(
                container = container,
                channels = list.channels,
                reordering = reordering,
                emptyText = "This list is empty. Use “Add channels” to search and add some.",
                onToggleReorder = { reordering = !reordering },
                onPlay = { index -> actions.play(list.name, list.channels, index) },
                onMove = { from, to -> container.library.moveInList(listId, from, to) },
                onRemove = { container.library.toggleInList(listId, it) },
                modifier = Modifier.padding(padding),
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
                    actions.back()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add to ${list?.name.orEmpty()} (${inList.size})") },
                navigationIcon = {
                    IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(onClick = { actions.back() }) { Text("Done") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            WithCatalog(container, catalogState) { catalog ->
                SearchContent(catalog) { results ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.streamId }) { channel ->
                            val checked = channel.streamId in inList
                            ListItem(
                                headlineContent = { Text(channel.name, maxLines = 1) },
                                supportingContent = catalog.categoryName(channel)?.let { { Text(it, maxLines = 1) } },
                                leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                                modifier = Modifier.clickable { container.library.toggleInList(listId, channel) },
                            )
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
