package com.mwalczak.player.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.lists.ReorderList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReorderListTest {

    @get:Rule
    val compose = createComposeRule()

    private var order = emptyList<String>()

    private fun show(vararg names: String) {
        compose.setContent {
            var channels by remember { mutableStateOf(names.mapIndexed { i, n -> Channel(i + 1, n) }) }
            order = channels.map { it.name }
            ReorderList(
                channels,
                onMove = { from, to -> channels = channels.toMutableList().apply { add(to, removeAt(from)) } },
                onRemove = { c -> channels = channels - c },
            )
        }
    }

    @Test
    fun `dragging the handle moves a channel`() {
        show("A", "B", "C", "D")
        val handle = compose.onNodeWithContentDescription("Move A")
        handle.performTouchInput { down(center) }
        // Rows are ~56dp apart; drag two and a half rows down in small steps, a frame apart like a finger.
        repeat(25) {
            handle.performTouchInput { moveBy(Offset(0f, 2.5f * 56 * density / 25)) }
            compose.mainClock.advanceTimeByFrame()
        }
        handle.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("B", "C", "A", "D"), order)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with a remote, OK picks a channel up and down arrows move it`() {
        show("A", "B", "C")
        val handle = compose.onNodeWithContentDescription("Move A")
        handle.requestFocus()
        handle.performClick()
        compose.onNodeWithContentDescription("Put A down").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Put A down").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(listOf("B", "C", "A"), order)
    }
}
