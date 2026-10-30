package com.mwalczak.player.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.CrashReports
import android.content.Intent
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.mwalczak.player.data.Catalog
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.ChannelMatcher
import com.mwalczak.player.ui.Actions
import com.mwalczak.player.ui.common.CardShape
import com.mwalczak.player.ui.common.CastButton
import com.mwalczak.player.ui.common.RemoteBar
import com.mwalczak.player.ui.common.ChannelGrid
import com.mwalczak.player.ui.common.EmptyState
import com.mwalczak.player.ui.common.focusHighlight
import com.mwalczak.player.ui.lists.FavoritesTab
import com.mwalczak.player.ui.guide.GuideTab
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
    Guide("TV guide", Icons.Default.CalendarViewWeek),
    Search("Search", Icons.Default.Search),
    Lists("My lists", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Default.Settings),
}

/** Insets for content next to the navigation rail (the rail itself handles the start edge). */
private val ContentInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)

@Composable
fun HomeScreen(container: AppContainer, actions: Actions) {
    // Open on Favorites when there are some, otherwise on the full Live TV list.
    var tab by rememberSaveable {
        mutableStateOf(if (container.library.library.value.favorites.isNotEmpty()) Tab.Favorites else Tab.Live)
    }
    val catalogState by container.catalog.state.collectAsStateWithLifecycle()

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            if (catalogState is CatalogState.Loading || (catalogState as? CatalogState.Ready)?.refreshing == true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            UpdateBanner(container)
            CrashReportBanner()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.Live -> LiveTab(container, actions, catalogState)
                    Tab.Guide -> GuideTab(container, actions)
                    Tab.Search -> SearchTab(container, actions, catalogState)
                    Tab.Favorites -> FavoritesTab(container, actions)
                    Tab.Lists -> ListsTab(container, actions)
                    Tab.Settings -> SettingsTab(container, actions)
                }
            }
            RemoteBar(container, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (maxWidth < 600.dp) {
            // Portrait phone: title bar on top, navigation at the bottom, one column of channels.
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tab.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    CastButton(container)
                    // Settings lives up here on a phone so the bottom bar keeps five items.
                    IconButton(onClick = { tab = Tab.Settings }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = if (tab == Tab.Settings) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                }
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                )
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.filter { it != Tab.Settings }.forEach {
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(it.icon, contentDescription = null) },
                            label = { Text(it.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        } else {
            // Landscape phone, tablet, TV: navigation in a rail on the left, all height for channels.
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    header = { CastButton(container) },
                ) {
                    Spacer(Modifier.weight(1f))
                    Tab.entries.forEach {
                        NavigationRailItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(it.icon, contentDescription = null) },
                            label = { Text(it.label, maxLines = 1) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                content(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(ContentInsets))
            }
        }
    }
}

/** After a crash: offer to share what happened, since there's no other way to see it on a device. */
@Composable
private fun CrashReportBanner() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReports.read(context)) }
    val text = report ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = CardShape,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Bunny Player ran into a problem last time.", modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Bunny Player crash report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share crash report"))
            }) { Text("Share report") }
            TextButton(onClick = {
                CrashReports.clear(context)
                report = null
            }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun UpdateBanner(container: AppContainer) {
    val state by container.updater.state.collectAsStateWithLifecycle()
    if (!state.available && state.message == null) return
    val activity = LocalActivity.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CardShape,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.message ?: "A new version is available (build ${state.latestBuild}, you have ${state.currentBuild})")
                if (state.busy) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            if (state.available && !state.busy) {
                Button(onClick = { activity?.let(container.updater::install) }) { Text("Update") }
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
            val pinned = library.pinnedCategories.mapNotNull { id -> catalog.categories.firstOrNull { it.id == id } }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pinned.isNotEmpty()) {
                    header { SectionHeader("Pinned") }
                    items(pinned, key = { "pinned-${it.id}" }) { category ->
                        CategoryCard(
                            category.name,
                            catalog.channelsIn(category.id).size,
                            pinned = true,
                            onPin = { container.library.togglePinnedCategory(category.id) },
                        ) { actions.openCategory(category.id) }
                    }
                }
                header { SectionHeader("Categories") }
                if (library.recent.isNotEmpty()) {
                    item(key = RECENT) {
                        CategoryCard("Recently watched", library.recent.size, icon = Icons.Default.History) {
                            actions.openCategory(RECENT)
                        }
                    }
                }
                item(key = ALL_CHANNELS) {
                    CategoryCard("All channels", catalog.channels.size, icon = Icons.Default.Tv) {
                        actions.openCategory(ALL_CHANNELS)
                    }
                }
                items(catalog.categories, key = { it.id }) { category ->
                    CategoryCard(
                        category.name,
                        catalog.channelsIn(category.id).size,
                        pinned = category.id in library.pinnedCategories,
                        onPin = { container.library.togglePinnedCategory(category.id) },
                    ) { actions.openCategory(category.id) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun CategoryCard(
    name: String,
    count: Int,
    icon: ImageVector? = null,
    pinned: Boolean = false,
    onPin: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .focusHighlight(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            name,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (pinned) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (onPin != null) {
            IconButton(onClick = onPin) {
                Icon(
                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "Unpin $name" else "Pin $name to the top",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SearchTab(container: AppContainer, actions: Actions, state: CatalogState) {
    WithCatalog(container, state) { catalog ->
        SearchContent(catalog) { results ->
            ChannelGrid(
                container,
                results,
                onPlay = { index -> actions.play("Search results", results, index) },
                subtitle = { _, channel -> catalog.categoryName(channel) },
            )
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
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
        )
        when {
            query.isBlank() -> EmptyState("Type a channel name, e.g. “sport” or “polsat”.")
            matches.isEmpty() -> EmptyState("No channels match “$query”.")
            else -> results(matches)
        }
    }
}

fun Catalog.categoryName(channel: Channel): String? =
    categories.firstOrNull { it.id == channel.categoryId }?.name

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
    // Filtering tens of thousands of names happens off the main thread, with names folded once.
    val keys by produceState<List<String>?>(null, channels) {
        value = withContext(Dispatchers.Default) { channels.map { ChannelMatcher.searchKey(it.name) } }
    }
    val shown by produceState(channels, channels, filter, keys) {
        val folded = keys
        value = if (filter.isBlank() || folded == null) {
            channels
        } else {
            delay(150)
            withContext(Dispatchers.Default) {
                val key = ChannelMatcher.searchKey(filter).trim()
                channels.filterIndexed { i, _ -> folded[i].contains(key) }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // One compact header row (back, title, filter) to leave the height for channels in landscape.
        ScreenHeader(title, onBack = { actions.back() }) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter ${channels.size} channels") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        }
        if (shown.isEmpty()) {
            EmptyState(if (catalog == null && categoryId != RECENT) "Loading channels…" else "No channels here.")
        } else {
            ChannelGrid(
                container,
                shown,
                onPlay = { index -> actions.play(title, shown, index) },
                subtitle = { _, channel -> if (channel.num > 0) "#${channel.num}" else null },
            )
        }
    }
}

/**
 * Back button + title, and optional [actions] (e.g. a filter field): on the same row when there
 * is room, on a second row on a portrait phone.
 */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = !wide || actions == null),
                )
                if (wide && actions != null) {
                    Spacer(Modifier.weight(0.01f))
                    actions()
                }
            }
            if (!wide && actions != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) { actions() }
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String, startPadding: androidx.compose.ui.unit.Dp = 4.dp) {
    Row(Modifier.fillMaxWidth().padding(start = startPadding, top = 12.dp, bottom = 4.dp).height(24.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
