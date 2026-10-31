package com.mwalczak.player.player

import com.mwalczak.player.cast.RemoteStatus
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.autoplayStart
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplayStartTest {

    private val list = listOf(Channel(1, "TVN"), Channel(2, "TVP1"), Channel(3, "Polsat"))

    @Test
    fun `starts from the channel watched last in this list`() {
        val recent = listOf(Channel(9, "Elsewhere"), Channel(3, "Polsat"), Channel(1, "TVN"))
        assertEquals(2, autoplayStart(list, null, recent))
    }

    @Test
    fun `when casting, starts from what the TV shows`() {
        val tv = RemoteStatus("TV", signedIn = true, playing = "TVP1", streamId = 2)
        assertEquals(1, autoplayStart(list, tv, listOf(Channel(3, "Polsat"))))
    }

    @Test
    fun `from the top when nothing from the list was watched`() {
        assertEquals(0, autoplayStart(list, null, listOf(Channel(9, "Elsewhere"))))
    }
}
