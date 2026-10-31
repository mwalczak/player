package com.mwalczak.player.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.pinnedIn
import com.mwalczak.player.ui.Actions
import com.mwalczak.player.ui.common.CardShape
import com.mwalczak.player.ui.common.ChannelLogo
import com.mwalczak.player.ui.common.RestoreFocus
import com.mwalczak.player.ui.common.focusHighlight
import com.mwalczak.player.ui.common.focusMemory
import com.mwalczak.player.ui.common.rememberFocusMemory

/**
 * The first page: Favorites, the user's lists and the pinned categories as tiles. A tile starts
 * watching (from its first channel); ≡ on it opens the list to browse.
 */
@Composable
internal fun StartTab(container: AppContainer, actions: Actions, onBrowseFavorites: () -> Unit, onFindChannels: () -> Unit) {
    val library by container.library.library.collectAsStateWithLifecycle()
    val catalogState by container.catalog.state.collectAsStateWithLifecycle()
    val browsable by container.browsable.collectAsStateWithLifecycle()
    val catalog = (catalogState as? CatalogState.Ready)?.let { browsable ?: it.catalog }
    val pinned = remember(library, catalog) { catalog?.let { library.pinnedIn(it.categories) }.orEmpty() }

    // Back from a list or full screen, the remote lands on the tile it came from.
    val gridState = rememberLazyGridState()
    val memory = rememberFocusMemory()
    val keys = buildList {
        add(FAVORITES_TILE)
        if (library.lists.isNotEmpty()) add("")
        library.lists.forEach { add("list-${it.id}") }
        if (pinned.isNotEmpty()) add("")
        pinned.forEach { add("category-${it.id}") }
    }
    val target = memory.target { it in keys }
    RestoreFocus(memory, target?.let(keys::indexOf) ?: -1, ready = true, gridState)
    fun Modifier.remembered(key: String) = focusMemory(memory, key, target)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 260.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = FAVORITES_TILE) {
            val favorites = library.favorites
            Tile(
                title = "Favorites",
                icon = Icons.Default.Star,
                channels = favorites,
                empty = "Long-press a channel to add it here",
                modifier = Modifier.remembered(FAVORITES_TILE),
                onPlay = { if (favorites.isEmpty()) onFindChannels() else actions.play("Favorites", favorites, 0) },
                onBrowse = onBrowseFavorites,
            )
        }
        if (library.lists.isNotEmpty()) {
            header("My lists")
            items(library.lists, key = { "list-${it.id}" }) { list ->
                Tile(
                    title = list.name,
                    icon = Icons.AutoMirrored.Filled.List,
                    channels = list.channels,
                    empty = "Empty – open it to add channels",
                    modifier = Modifier.remembered("list-${list.id}"),
                    onPlay = { if (list.channels.isEmpty()) actions.openList(list.id) else actions.play(list.name, list.channels, 0) },
                    onBrowse = { actions.openList(list.id) },
                )
            }
        }
        if (pinned.isNotEmpty() && catalog != null) {
            header("Pinned categories")
            items(pinned, key = { "category-${it.id}" }) { category ->
                val channels = remember(catalog, category.id) { catalog.channelsIn(category.id) }
                Tile(
                    title = category.name,
                    icon = Icons.Default.PushPin,
                    channels = channels,
                    empty = "No channels right now",
                    modifier = Modifier.remembered("category-${category.id}"),
                    onPlay = { if (channels.isNotEmpty()) actions.play(category.name, channels, 0) },
                    onBrowse = { actions.openCategory(category.id) },
                )
            }
        }
    }
}

private const val FAVORITES_TILE = "favorites"

private fun LazyGridScope.header(text: String) =
    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(text) }

/** One tile: name, channel count and a few logos; tap to watch, ≡ to browse. */
@Composable
private fun Tile(
    title: String,
    icon: ImageVector,
    channels: List<Channel>,
    empty: String,
    modifier: Modifier,
    onPlay: () -> Unit,
    onBrowse: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .focusHighlight(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onPlay)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (channels.isEmpty()) {
                Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    channels.take(4).forEach { ChannelLogo(it.icon, Modifier.size(36.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "${channels.size} channels · ${channels.first().name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onBrowse) { Icon(Icons.AutoMirrored.Filled.List, "Open $title") }
    }
}
