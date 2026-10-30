package com.mwalczak.player.data.epg

import com.mwalczak.player.data.Category
import com.mwalczak.player.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgSourcesTest {

    @Test
    fun `guides are picked from channel country codes and category names`() {
        val categories = listOf(Category("1", "Poland"), Category("2", "Canada"), Category("3", "PLEX"), Category("4", "PPV Live Events"))
        val channels =
            (1..12).map { Channel(it, "PL: Channel $it", categoryId = "1") } +
                (13..24).map { Channel(it, "Some channel $it", categoryId = "2") } +
                (25..40).map { Channel(it, "Plex movie $it", categoryId = "3") } +
                (41..45).map { Channel(it, "UK: Too few $it", categoryId = "4") } +
                (46..80).map { Channel(it, "PPV event $it", categoryId = "4") }
        val picked = EpgSources.autoSelect(channels, categories).map { it.id }
        assertEquals(listOf("PL1", "CA2", "PLEX1"), picked)
    }

    @Test
    fun `us local stations are never offered`() {
        assertEquals(emptyList<String>(), EpgSources.all.map { it.id }.filter { it.contains("LOCALS") })
    }

    @Test
    fun `the provider's epg id is matched exactly before names`() {
        val ours = listOf(
            Channel(1, "US FOX 28 (KAYU) Spokane (H)", epgChannelId = "foxkayu.us"),
            Channel(2, "US: FOX 28"),
        )
        val guide = linkedMapOf("FOX.28.us" to listOf("FOX 28"), "foxkayu.us" to listOf("KAYU-TV"))
        assertEquals(mapOf("foxkayu.us" to listOf(1), "FOX.28.us" to listOf(2)), Xmltv.match(guide, ours))
    }
}
