package com.mwalczak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSortTest {

    private fun names(vararg names: String) = names.mapIndexed { i, n -> Channel(i + 1, n) }

    @Test
    fun `sorts by name ignoring case and accents, numbers as numbers`() {
        val sorted = names("UK: Sky Sports 10", "uk: sky sports 2", "AU: FOX SPORTS", "US: Fox Sports 1", "Ćwiczenia", "Canal+").sortedByName()
        assertEquals(
            listOf("AU: FOX SPORTS", "Canal+", "Ćwiczenia", "uk: sky sports 2", "UK: Sky Sports 10", "US: Fox Sports 1"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `reordering keeps channels that aren't in the new order`() {
        val channels = names("A", "B", "C")
        assertEquals(listOf(3, 1, 2), channels.inOrderOf(listOf(Channel(3, "C"), Channel(1, "A"))).map { it.streamId })
    }
}
