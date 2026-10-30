package com.mwalczak.player.data.sync

import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Library
import com.mwalczak.player.data.UserList
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadTest {

    @Test
    fun `a saved setup keeps favorites order, lists and settings`() {
        val library = Library(
            favorites = listOf(Channel(2, "TVN24"), Channel(1, "TVP1")),
            lists = listOf(UserList("l1", "Sport", listOf(Channel(3, "Polsat Sport")))),
            pinnedCategories = listOf("5"),
        )
        val payload = SyncPayload(library, "HLS", tryAlternates = false, nextInList = true, stallTimeoutSec = 8, epgEnabled = true, epgUrl = "https://g/x.xml.gz")
        val decoded = syncJson.decodeFromString(SyncPayload.serializer(), syncJson.encodeToString(SyncPayload.serializer(), payload))
        assertEquals(payload, decoded)
        assertEquals(listOf(2, 1), decoded.library.favorites.map { it.streamId })
    }
}
