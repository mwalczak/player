package com.mwalczak.player.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Remembers which item of a list had the TV remote's focus, so coming back to the screen (e.g.
 * from full screen) puts it back there instead of on the first button of the screen.
 */
@Stable
class FocusMemory internal constructor(saved: String?, preferred: String?) {
    /** The item focused last; saved with the screen. */
    internal var focused: String? = saved

    /** Where focus goes back to, best first; only set when the screen comes back. */
    private var candidates by mutableStateOf(if (saved == null) emptyList() else listOfNotNull(preferred, saved).distinct())

    internal val requester = FocusRequester()

    /** The item to focus, the first candidate for which [contains] is true; null when there is nothing to restore. */
    fun target(contains: (String) -> Boolean): String? = candidates.firstOrNull(contains)

    internal fun done() {
        candidates = emptyList()
    }

    internal val restoring: Boolean get() = candidates.isNotEmpty()
}

/**
 * A [FocusMemory] that survives leaving the screen. [preferred] is asked when coming back and
 * wins over the item focused last if it is in the list (e.g. the channel that ◀/▶ ended on).
 */
@Composable
fun rememberFocusMemory(preferred: () -> String? = { null }): FocusMemory =
    rememberSaveable(saver = Saver(save = { it.focused }, restore = { FocusMemory(it, preferred()) })) {
        FocusMemory(null, null)
    }

/** Marks an item [key] of the list: notes when it has focus, and lets focus come back to it ([target]). */
fun Modifier.focusMemory(memory: FocusMemory, key: String, target: String?): Modifier =
    then(if (key == target) Modifier.focusRequester(memory.requester) else Modifier)
        .onFocusChanged { if (it.hasFocus) memory.focused = key }

/**
 * Puts focus back on the remembered item, at [index] in the list ([ready] once the list is
 * loaded; -1 if the item is gone), scrolling to it if needed.
 */
@Composable
fun RestoreFocus(memory: FocusMemory, index: Int, ready: Boolean, state: LazyGridState) =
    RestoreFocus(memory, index, ready, { i -> state.layoutInfo.visibleItemsInfo.any { it.index == i } }, state::scrollToItem)

@Composable
fun RestoreFocus(memory: FocusMemory, index: Int, ready: Boolean, state: LazyListState) =
    RestoreFocus(memory, index, ready, { i -> state.layoutInfo.visibleItemsInfo.any { it.index == i } }, state::scrollToItem)

@Composable
private fun RestoreFocus(
    memory: FocusMemory,
    index: Int,
    ready: Boolean,
    isVisible: (Int) -> Boolean,
    scrollTo: suspend (Int) -> Unit,
) {
    if (!memory.restoring) return
    LaunchedEffect(memory, index, ready) {
        if (!ready) return@LaunchedEffect
        if (index >= 0) {
            if (!isVisible(index)) scrollTo(index)
            memory.requester.requestFocusSoon()
        }
        memory.done()
    }
}

/**
 * Focuses what this requester is attached to, waiting a few frames for it to appear (a list
 * item that was just scrolled to, a guide row still loading). Returns whether it got there.
 */
suspend fun FocusRequester.requestFocusSoon(frames: Int = 30): Boolean {
    repeat(frames) {
        if (runCatching { requestFocus() }.isSuccess) return true
        withFrameNanos { }
    }
    return false
}
