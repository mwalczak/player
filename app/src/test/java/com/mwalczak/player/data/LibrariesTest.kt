package com.mwalczak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrariesTest {

    @Test
    fun `merge adds new favorites, joins lists by name and keeps mine first`() {
        val mine = Library(
            favorites = listOf(Channel(1, "TVN")),
            lists = listOf(UserList("m", "Sport", listOf(Channel(2, "Polsat Sport")))),
            recent = listOf(Channel(9, "Recent")),
            pinnedCategories = listOf("5"),
        )
        val theirs = Library(
            favorites = listOf(Channel(3, "TVP1"), Channel(1, "TVN")),
            lists = listOf(UserList("t1", "sport", listOf(Channel(2, "Polsat Sport"), Channel(4, "Eleven"))), UserList("t2", "News", listOf(Channel(5, "TVN24")))),
            pinnedCategories = listOf("5", "7"),
        )
        val merged = Libraries.merge(mine, theirs)
        assertEquals(listOf(1, 3), merged.favorites.map { it.streamId })
        assertEquals(listOf("Sport", "News"), merged.lists.map { it.name })
        assertEquals(listOf(2, 4), merged.lists[0].channels.map { it.streamId })
        assertEquals(listOf("5", "7"), merged.pinnedCategories)
        assertEquals(listOf(9), merged.recent.map { it.streamId })
    }

    @Test
    fun `channels are re-matched to this account by name when ids differ`() {
        val catalog = listOf(Channel(100, "PL: TVN HD"), Channel(200, "PL: TVP 1"))
        val imported = Library(favorites = listOf(Channel(1, "PL: TVN HD"), Channel(200, "PL: TVP 1"), Channel(7, "Unknown")))
        val remapped = Libraries.remap(imported, catalog)
        assertEquals(listOf(100, 200, 7), remapped.favorites.map { it.streamId })
    }
}
