package com.mwalczak.player.ui.guide

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Catalog
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.epg.Programme
import com.mwalczak.player.ui.Actions
import com.mwalczak.player.ui.common.ChannelLogo
import com.mwalczak.player.ui.common.PreviewPane
import com.mwalczak.player.ui.common.PreviewState
import com.mwalczak.player.ui.common.previewEnabled
import androidx.compose.ui.focus.onFocusChanged
import com.mwalczak.player.ui.common.EmptyState
import com.mwalczak.player.ui.common.focusHighlight
import com.mwalczak.player.ui.common.focusMemory
import com.mwalczak.player.ui.common.rememberFocusMemory
import com.mwalczak.player.ui.common.requestFocusSoon
import com.mwalczak.player.ui.home.WithCatalog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.mwalczak.player.data.epg.GuideHit
import com.mwalczak.player.ui.common.CardShape
import com.mwalczak.player.ui.common.progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

private const val FAVORITES = "__favorites__"
private const val ALL = "__all__"

private val ChannelColumn = 150.dp
private val RowHeight = 64.dp
private val PerMinute = 4.dp
private const val WINDOW_SEC = 24 * 60 * 60L
private const val SLOT_SEC = 30 * 60L

/**
 * TV guide grid: channels down the side, time across the top, a line at "now".
 * Choose which channels to show (favorites, a category, all); tap a programme to watch.
 */
@Composable
fun GuideTab(container: AppContainer, actions: Actions) {
    val catalogState by container.catalog.state.collectAsStateWithLifecycle()
    val library by container.library.library.collectAsStateWithLifecycle()
    val epg by container.epg.state.collectAsStateWithLifecycle()

    WithCatalog(container, catalogState) { catalog ->
        val sources = remember(catalog, library.favorites, library.pinnedCategories) {
            buildList {
                if (library.favorites.isNotEmpty()) add(FAVORITES to "Favorites")
                val pinned = library.pinnedCategories.mapNotNull { id -> catalog.categories.firstOrNull { it.id == id } }
                (pinned + catalog.categories.filterNot { it in pinned }).forEach { add(it.id to it.name) }
                add(ALL to "All channels")
            }
        }
        var source by rememberSaveable { mutableStateOf(sources.first().first) }
        if (sources.none { it.first == source }) source = sources.first().first
        val title = sources.first { it.first == source }.second
        val channels = remember(source, catalog, library.favorites) { channelsFor(source, catalog, library.favorites) }

        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }

        Column(Modifier.fillMaxSize()) {
            if (searching) {
                val focus = remember { FocusRequester() }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search shows, e.g. “mecz” or “fakty”") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f).widthIn(max = 640.dp).focusRequester(focus),
                    )
                    IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Default.Close, "Close search") }
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sources, key = { it.first }) { (id, name) ->
                            FilterChip(selected = id == source, onClick = { source = id }, label = { Text(name) })
                        }
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search shows") }
                    TextButton(onClick = { scope.launch { scroll.animateScrollTo(0) } }) { Text("Now") }
                }
            }
            when {
                searching -> GuideSearchResults(container, catalog.channels, epg.channelsWithGuide, epg.version, query, actions)
                channels.isEmpty() -> EmptyState("No channels here.")
                epg.channelsWithGuide == 0 && epg.loading -> EmptyState("Loading the TV guide… ${epg.progress.orEmpty()}")
                epg.channelsWithGuide == 0 -> EmptyState("No guide yet. Download it in Settings → TV guide → Download guide now.")
                else -> if (previewEnabled(container)) {
                    // Browse the guide with a live preview of the focused / tapped channel.
                    val preview = remember(channels) { PreviewState() }
                    val fullscreen: (Int) -> Unit = { index ->
                        preview.stop(container)
                        actions.play(title, channels, index)
                    }
                    Row(Modifier.fillMaxSize()) {
                        GuideGrid(
                            container, channels, epg.version, scroll,
                            onRowFocus = { preview.index = it },
                            modifier = Modifier.weight(1f),
                        ) { index, focused -> if (focused || preview.index == index) fullscreen(index) else preview.index = index }
                        PreviewPane(container, channels, preview, fullscreen, Modifier.weight(0.6f))
                    }
                } else {
                    GuideGrid(container, channels, epg.version, scroll) { index, _ -> actions.play(title, channels, index) }
                }
            }
        }
    }
}

private fun channelsFor(source: String, catalog: Catalog, favorites: List<Channel>): List<Channel> = when (source) {
    FAVORITES -> favorites
    ALL -> catalog.channels
    else -> catalog.channelsIn(source)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideGrid(
    container: AppContainer,
    channels: List<Channel>,
    version: Int,
    scroll: ScrollState,
    onRowFocus: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** index, and whether a TV remote is on that row (OK) rather than a touch tap. */
    onClick: (Int, Boolean) -> Unit,
) {
    var focusedRow by remember { mutableStateOf(-1) }
    val onPlay: (Int) -> Unit = { index -> onClick(index, focusedRow == index) }
    val now by container.clock.collectAsStateWithLifecycle()
    // The window starts at the half hour before now and stays put while the screen is open
    // (and when coming back from full screen, so the scroll position still matches).
    val windowStart by rememberSaveable { mutableLongStateOf((System.currentTimeMillis() / 1000 - SLOT_SEC) / SLOT_SEC * SLOT_SEC) }
    val windowEnd = windowStart + WINDOW_SEC
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val density = LocalDensity.current

    // Remote navigation keeps a time cursor: ▲/▼ go to whatever is on at that time in the row
    // above/below (not to whichever cell happens to be nearest on screen), ◀/▶ move it along.
    var cursorSec by rememberSaveable { mutableLongStateOf(maxOf(System.currentTimeMillis() / 1000, windowStart)) }
    var movingVertically by remember { mutableStateOf(false) }
    val requesters = remember { HashMap<Int, FocusRequester>() }
    fun requester(i: Int) = requesters.getOrPut(i) { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Back from full screen, the remote lands on the same time in the row that was playing.
    val memory = rememberFocusMemory { container.lastPlayedStreamId?.toString() }
    val restoreTo = memory.target { key -> channels.any { it.streamId.toString() == key } }
    if (restoreTo != null) {
        LaunchedEffect(restoreTo) {
            val index = channels.indexOfFirst { it.streamId.toString() == restoreTo }
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            movingVertically = true
            requester(index).requestFocusSoon()
            movingVertically = false
            memory.done()
        }
    }
    fun visibleStartSec(): Long = windowStart + (with(density) { scroll.value.toDp() } / PerMinute * 60).toLong()
    fun moveTo(target: Int) {
        if (target !in channels.indices) return
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) listState.scrollToItem(target)
            movingVertically = true
            requester(target).requestFocusSoon()
            withFrameNanos { }
            movingVertically = false
        }
    }

    // Only scroll as much as needed: a long programme that is already partly on screen stays put
    // instead of the whole timeline jumping to its far end.
    CompositionLocalProvider(LocalBringIntoViewSpec provides GuideScrollSpec) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Time ruler
            Row(Modifier.fillMaxWidth().height(28.dp)) {
                Spacer(Modifier.width(ChannelColumn))
                Row(Modifier.horizontalScroll(scroll)) {
                    var t = windowStart
                    while (t < windowEnd) {
                        Box(Modifier.width(widthOf(SLOT_SEC)).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            Text(
                                timeFormat.format(Date(t * 1000)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        t += SLOT_SEC
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(RowHeight)
                            .padding(vertical = 2.dp)
                            .focusMemory(memory, channel.streamId.toString(), target = null)
                            .onFocusChanged { f ->
                                if (f.hasFocus) {
                                    focusedRow = index
                                    onRowFocus?.invoke(index)
                                } else if (focusedRow == index) {
                                    focusedRow = -1
                                }
                            },
                    ) {
                        ChannelCell(channel) { onPlay(index) }
                        Row(
                            Modifier
                                .horizontalScroll(scroll)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionDown -> { moveTo(index + 1); true }
                                        Key.DirectionUp -> if (index == 0) false else { moveTo(index - 1); true }
                                        else -> false
                                    }
                                },
                        ) {
                            // Each row reads its own channel from the guide database as it scrolls into view.
                            // Nothing to focus until it's loaded, so ▲/▼ wait for the real cell at the cursor.
                            val programmes by produceState<List<Programme>?>(null, channel.streamId, version) {
                                value = container.epg.programmesFor(channel.streamId, windowStart, windowEnd)
                            }
                            val loaded = programmes
                            if (loaded == null) {
                                Spacer(Modifier.width(widthOf(windowEnd - windowStart)))
                                return@Row
                            }
                            ProgrammeCells(
                                loaded, windowStart, windowEnd, now, timeFormat,
                                cursorSec = cursorSec,
                                cursorRequester = requester(index),
                                onCellFocused = { cellStart -> if (!movingVertically) cursorSec = maxOf(cellStart, visibleStartSec()) },
                            ) {
                                onPlay(index)
                            }
                        }
                    }
                }
            }
        }
        // "Now" line across all rows.
        val nowOffsetPx = with(density) { (ChannelColumn + PerMinute * ((now - windowStart) / 60f)).roundToPx() } - scroll.value
        if (nowOffsetPx >= with(density) { ChannelColumn.roundToPx() }) {
            Box(
                Modifier
                    .offset { IntOffset(nowOffsetPx, 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
    }
}

/** Minimal scrolling; never jumps for an item that is larger than the view and already partly visible. */
@OptIn(ExperimentalFoundationApi::class)
private val GuideScrollSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0 && end <= containerSize -> 0f
            size > containerSize -> if (end > 0 && offset < containerSize) 0f else offset
            offset < 0 -> offset
            else -> end - containerSize
        }
    }
}

@Composable
private fun ChannelCell(channel: Channel, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .width(ChannelColumn)
            .fillMaxHeight()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusHighlight(interaction, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChannelLogo(channel.icon, Modifier.size(36.dp))
        Text(channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgrammeCells(
    programmes: List<Programme>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    timeFormat: DateFormat,
    cursorSec: Long,
    cursorRequester: FocusRequester,
    onCellFocused: (startSec: Long) -> Unit,
    onClick: () -> Unit,
) {
    val visible = programmes.filter { it.stop > windowStart && it.start < windowEnd }
    if (visible.isEmpty()) {
        GuideCell(
            widthOf(windowEnd - windowStart), "No guide data", null, onAir = false, dim = true,
            modifier = Modifier.focusRequester(cursorRequester),
            onFocused = {},
            onClick = onClick,
        )
        return
    }
    // The cell ▲/▼ land on: the one on at the cursor time, else the next one, else the last.
    val target = visible.firstOrNull { it.start <= cursorSec && cursorSec < it.stop }
        ?: visible.firstOrNull { it.start > cursorSec }
        ?: visible.last()
    var cursor = windowStart
    visible.forEach { p ->
        val start = maxOf(p.start, cursor)
        val stop = minOf(p.stop, windowEnd)
        if (start > cursor) Spacer(Modifier.width(widthOf(start - cursor)))
        if (stop > start) {
            val cellStart = start
            GuideCell(
                widthOf(stop - start),
                p.title,
                "${timeFormat.format(Date(p.start * 1000))} – ${timeFormat.format(Date(p.stop * 1000))}",
                onAir = p.start <= now && now < p.stop,
                dim = p.stop <= now,
                modifier = if (p === target) Modifier.focusRequester(cursorRequester) else Modifier,
                onFocused = { onCellFocused(cellStart) },
                onClick = onClick,
            )
            cursor = stop
        }
    }
    if (cursor < windowEnd) Spacer(Modifier.width(widthOf(windowEnd - cursor)))
}

@Composable
private fun GuideCell(
    width: Dp,
    title: String,
    time: String?,
    onAir: Boolean,
    dim: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(shape)
            .background(if (onAir) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .focusHighlight(interaction, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (onAir) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (time != null) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun widthOf(seconds: Long): Dp = PerMinute * (seconds / 60f)

@Composable
private fun GuideSearchResults(
    container: AppContainer,
    channels: List<Channel>,
    channelsWithGuide: Int,
    version: Int,
    query: String,
    actions: Actions,
) {
    var hits by remember { mutableStateOf<List<GuideHit>>(emptyList()) }
    LaunchedEffect(query, version) {
        delay(250)
        hits = container.epg.search(query, channels, System.currentTimeMillis() / 1000)
    }
    val now by container.clock.collectAsStateWithLifecycle()
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    when {
        query.isBlank() -> EmptyState("Type the name of a show to find when and where it's on.")
        channelsWithGuide == 0 -> EmptyState("The TV guide isn't loaded yet.")
        hits.isEmpty() -> EmptyState("Nothing called “$query” in the next 24 hours.")
        else -> {
            // Playing a result zaps through the channels of all results.
            val queue = hits.map { it.channel }.distinctBy { it.streamId }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits, key = { "${it.channel.streamId}-${it.programme.start}" }) { hit ->
                    val onAir = hit.programme.start <= now && now < hit.programme.stop
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(if (onAir) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                            .focusHighlight(interaction)
                            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                                actions.play("Search: $query", queue, queue.indexOfFirst { it.streamId == hit.channel.streamId })
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            whenLabel(hit.programme, now, time),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (onAir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(110.dp),
                        )
                        ChannelLogo(hit.channel.icon, Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hit.programme.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                hit.channel.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (onAir) {
                                LinearProgressIndicator(
                                    progress = { hit.programme.progress(now) },
                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp),
                                    drawStopIndicator = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Now · until 21:00", "20:15", "Tomorrow 08:00" or a date for later days. */
private fun whenLabel(p: Programme, nowSec: Long, time: DateFormat): String {
    if (p.start <= nowSec) return "Now · until ${time.format(Date(p.stop * 1000))}"
    val days = dayNumber(p.start) - dayNumber(nowSec)
    val at = time.format(Date(p.start * 1000))
    return when (days) {
        0L -> at
        1L -> "Tomorrow $at"
        else -> "${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(p.start * 1000))} $at"
    }
}

private fun dayNumber(epochSec: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = epochSec * 1000 }
    return cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
}
