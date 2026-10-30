package com.mwalczak.player.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.common.CardShape
import com.mwalczak.player.ui.common.ChannelLogo
import kotlinx.coroutines.launch

/**
 * Channels to put in order: drag a row by its handle (≡). With a TV remote, OK on the handle picks
 * the row up, ▲/▼ move it and OK (or moving away) puts it down.
 */
@Composable
fun ReorderList(
    channels: List<Channel>,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)

    // The row being dragged: where it was laid out when the drag started, and how far it has gone.
    var dragged by remember { mutableStateOf<Int?>(null) }
    var startOffset by remember { mutableIntStateOf(0) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    // Where the dragged row should be after the last swap: no new swap until the list is laid out like that.
    var expectedIndex by remember { mutableIntStateOf(-1) }
    // The row picked up with a remote.
    var lifted by remember { mutableStateOf<Int?>(null) }

    fun draggedInfo() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragged }

    fun onDrag(dy: Float) {
        dragDelta += dy
        val current = draggedInfo() ?: return
        if (current.index != expectedIndex) return
        val top = startOffset + dragDelta
        val middle = (top + current.size / 2).toInt()
        // Swap with whichever row the dragged one's middle is over.
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key != dragged && middle in it.offset until it.offset + it.size }
            ?.let { target ->
                move(current.index, target.index)
                expectedIndex = target.index
            }
        // Near the top or bottom edge: scroll so the row can go further.
        val info = listState.layoutInfo
        val overscroll = when {
            top + current.size > info.viewportEndOffset -> top + current.size - info.viewportEndOffset
            top < info.viewportStartOffset -> top - info.viewportStartOffset
            else -> 0f
        }
        if (overscroll != 0f) scope.launch { listState.scrollBy(overscroll / 4) }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
            val key = channel.streamId
            val isDragged = dragged == key
            val isLifted = lifted == key
            Row(
                Modifier
                    .then(if (isDragged) Modifier else Modifier.animateItem())
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragged) draggedInfo()?.let { startOffset + dragDelta - it.offset } ?: 0f else 0f
                        val scale = if (isDragged || isLifted) 1.02f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(
                        if (isDragged || isLifted) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surfaceContainer,
                    )
                    .then(if (isLifted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardShape) else Modifier)
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChannelLogo(channel.icon, Modifier.size(40.dp))
                Text(
                    channel.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = { lifted = if (isLifted) null else key },
                    modifier = Modifier
                        .onFocusChanged { if (!it.isFocused && lifted == key) lifted = null }
                        .onPreviewKeyEvent { e ->
                            if (!isLifted || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val to = when (e.key) {
                                Key.DirectionUp -> index - 1
                                Key.DirectionDown -> index + 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (to in channels.indices) {
                                move(index, to)
                                scope.launch {
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    if (visible.size > 2 && (to <= visible.first().index || to >= visible.last().index)) {
                                        listState.animateScrollToItem((to - visible.size / 2).coerceAtLeast(0))
                                    }
                                }
                            }
                            true
                        }
                        .pointerInput(key) {
                            detectDragGestures(
                                onDragStart = {
                                    dragged = key
                                    dragDelta = 0f
                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                                    startOffset = info?.offset ?: 0
                                    expectedIndex = info?.index ?: -1
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                },
                                onDragEnd = { dragged = null },
                                onDragCancel = { dragged = null },
                            )
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = if (isLifted) "Put ${channel.name} down" else "Move ${channel.name}",
                            tint = if (isLifted || isDragged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onRemove(channel) }) { Icon(Icons.Default.Delete, "Remove ${channel.name}") }
            }
        }
    }
}
