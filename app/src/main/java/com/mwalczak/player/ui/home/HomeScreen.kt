package com.mwalczak.player.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Catalog
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.Actions
import com.mwalczak.player.ui.common.ChannelRow
import com.mwalczak.player.ui.common.EmptyState
import com.mwalczak.player.ui.lists.FavoritesTab
import com.mwalczak.player.ui.lists.ListsTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ALL_CHANNELS = "__all__"
const val RECENT = "__recent__"

private enum class Tab(val label: String, val icon: ImageVector) {
    Favorites("Favorites", Icons.Default.Star),
    Live("Live TV", Icons.Default.LiveTv),
    Search("Search", Icons.Default.Search),
    Lists("My lists", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, actions: Actions) {
    // Open on Favorites when there are some, otherwise on the full Live TV list.
    var tab by rememberSaveable {
        mutableStateOf(if (container.library.library.value.favorites.isNotEmpty()) Tab.Favorites else Tab.Live)
    }
    val catalogState by container.catalog.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(tab.label) })
                if (catalogState is CatalogState.Loading || (catalogState as? CatalogState.Ready)?.refreshing == true) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(it.icon, contentDescription = null) },
                        label = { Text(it.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Live -> LiveTab(container, actions, catalogState)
                Tab.Search -> SearchTab(container, actions, catalogState)
                Tab.Favorites -> FavoritesTab(container, actions)
                Tab.Lists -> ListsTab(container, actions)
                Tab.Settings -> SettingsTab(container, actions)
            }
        }
    }
}

/** Shows loading / error states and only renders [content] once the catalog is available. */
@Composable
fun WithCatalog(container: AppContainer, state: CatalogState, content: @Composable (Catalog) -> Unit) {
    val scope = container.appScope
    when (state) {
        is CatalogState.Ready -> content(state.catalog)
        CatalogState.Idle, CatalogState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Loading channels…")
            }
        }
        is CatalogState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    scope.launch { container.settings.currentCredentials()?.let { container.catalog.load(it, force = true) } }
                }) { Text("Retry") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTab(container: AppContainer, actions: Actions, state: CatalogState) {
    val library by container.library.library.collectAsStateWithLifecycle()
    WithCatalog(container, state) { catalog ->
        PullToRefreshBox(
            isRefreshing = (state as? CatalogState.Ready)?.refreshing == true,
            onRefresh = {
                container.appScope.launch {
                    container.settings.currentCredentials()?.let { container.catalog.load(it, force = true) }
                }
            },
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (library.recent.isNotEmpty()) {
                    item {
                        CategoryItem("Recently watched", library.recent.size, Icons.Default.History) { actions.openCategory(RECENT) }
                    }
                }
                item {
                    CategoryItem("All channels", catalog.channels.size, Icons.Default.Tv) { actions.openCategory(ALL_CHANNELS) }
                    HorizontalDivider()
                }
                val pinned = library.pinnedCategories.mapNotNull { id -> catalog.categories.firstOrNull { it.id == id } }
                if (pinned.isNotEmpty()) {
                    item { SectionHeader("Pinned") }
                    items(pinned, key = { "pinned-${it.id}" }) { category ->
                        CategoryItem(
                            category.name,
                            catalog.channelsIn(category.id).size,
                            null,
                            pinned = true,
                            onPin = { container.library.togglePinnedCategory(category.id) },
                        ) { actions.openCategory(category.id) }
                    }
                    item {
                        HorizontalDivider()
                        SectionHeader("All categories")
                    }
                }
                items(catalog.categories, key = { it.id }) { category ->
                    CategoryItem(
                        category.name,
                        catalog.channelsIn(category.id).size,
                        null,
                        pinned = category.id in library.pinnedCategories,
                        onPin = { container.library.togglePinnedCategory(category.id) },
                    ) { actions.openCategory(category.id) }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(
    name: String,
    count: Int,
    icon: ImageVector?,
    pinned: Boolean = false,
    onPin: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(name) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onPin != null) {
                    IconButton(onClick = onPin) {
                        Icon(
                            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (pinned) "Unpin $name" else "Pin $name to the top",
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SearchTab(container: AppContainer, actions: Actions, state: CatalogState) {
    WithCatalog(container, state) { catalog ->
        SearchContent(catalog) { results ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.streamId }) { channel ->
                    ChannelRow(
                        container,
                        channel,
                        subtitle = catalog.categoryName(channel),
                        onClick = { actions.play("Search results", results, results.indexOf(channel)) },
                    )
                }
            }
        }
    }
}

/** A search field over the whole catalog; [results] renders the matches. */
@Composable
fun SearchContent(catalog: Catalog, results: @Composable (List<Channel>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Channel>>(emptyList()) }

    LaunchedEffect(query, catalog) {
        delay(200)
        matches = withContext(Dispatchers.Default) { catalog.search(query) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search ${catalog.channels.size} channels") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        when {
            query.isBlank() -> EmptyState("Type a channel name, e.g. “sport” or “bbc one”.")
            matches.isEmpty() -> EmptyState("No channels match “$query”.")
            else -> results(matches)
        }
    }
}

fun Catalog.categoryName(channel: Channel): String? =
    categories.firstOrNull { it.id == channel.categoryId }?.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(container: AppContainer, actions: Actions, categoryId: String) {
    val state by container.catalog.state.collectAsStateWithLifecycle()
    val library by container.library.library.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val catalog = (state as? CatalogState.Ready)?.catalog

    val title = when (categoryId) {
        ALL_CHANNELS -> "All channels"
        RECENT -> "Recently watched"
        else -> catalog?.categories?.firstOrNull { it.id == categoryId }?.name ?: "Channels"
    }
    val channels = remember(catalog, categoryId, library.recent) {
        when (categoryId) {
            ALL_CHANNELS -> catalog?.channels.orEmpty()
            RECENT -> library.recent
            else -> catalog?.channelsIn(categoryId).orEmpty()
        }
    }
    val shown = remember(channels, filter) {
        if (filter.isBlank()) channels
        else {
            val key = com.mwalczak.player.data.ChannelMatcher.searchKey(filter)
            channels.filter { com.mwalczak.player.data.ChannelMatcher.searchKey(it.name).contains(key) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter ${channels.size} channels") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                EmptyState(if (catalog == null && categoryId != RECENT) "Loading channels…" else "No channels here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.streamId }) { channel ->
                        ChannelRow(
                            container,
                            channel,
                            subtitle = if (channel.num > 0) "#${channel.num}" else null,
                            onClick = { actions.play(title, shown, shown.indexOf(channel)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
