package com.mwalczak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTest {

    @Test
    fun `per-category channels are merged without duplicates, keeping order`() {
        val base = listOf(Channel(1, "A", categoryId = "1"), Channel(2, "B", categoryId = "1"))
        val extra = listOf(Channel(2, "B", categoryId = "1"), Channel(3, "C", categoryId = "2"), Channel(1, "A", categoryId = "1"))
        assertEquals(listOf(1, 2, 3), mergeChannels(base, extra).map { it.streamId })
    }

    @Test
    fun `empty categories reveal a capped channel list`() {
        val catalog = Catalog(
            categories = listOf(Category("1", "Poland"), Category("2", "UK"), Category("3", "Sport")),
            allRows = listOf(Channel(1, "TVN", categoryId = "1")),
            loadedAtMillis = 0,
        )
        assertEquals(listOf("2", "3"), catalog.emptyCategories.map { it.id })
    }

    @Test
    fun `a stream listed in two categories appears once overall but in both categories`() {
        val catalog = Catalog(
            categories = listOf(Category("1", "Most Watched"), Category("128", "News")),
            allRows = listOf(Channel(7, "US: FOX News", categoryId = "1"), Channel(7, "US: FOX News", categoryId = "128"), Channel(8, "US: CNN", categoryId = "128")),
            loadedAtMillis = 0,
        )
        assertEquals(listOf(7, 8), catalog.channels.map { it.streamId })
        assertEquals(listOf(7), catalog.search("fox").map { it.streamId })
        assertEquals(listOf(7), catalog.channelsIn("1").map { it.streamId })
        assertEquals(listOf(7, 8), catalog.channelsIn("128").map { it.streamId })
    }
}
