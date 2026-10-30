package com.mwalczak.player.data.epg

import com.mwalczak.player.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideSearchTest {

    private val tvn = Channel(1, "PL: TVN")
    private val polsat = Channel(2, "PL: Polsat")
    private val unknown = Channel(3, "Not in catalog")

    private val guide = mapOf(
        1 to listOf(Programme(0, 100, "Fakty"), Programme(100, 200, "Mecz: Polska – Włochy"), Programme(300, 400, "Kuchenne rewolucje")),
        2 to listOf(Programme(150, 250, "Wydarzenia"), Programme(250, 350, "Siatkówka: Polska – Brazylia")),
        3 to listOf(Programme(100, 200, "Polska na żywo")),
    )

    @Test
    fun `finds titles by words in any order, ignoring accents, on air first`() {
        val hits = GuideSearch.search(guide, listOf(tvn, polsat), "polska", nowSec = 160)
        assertEquals(listOf("Mecz: Polska – Włochy", "Siatkówka: Polska – Brazylia"), hits.map { it.programme.title })
        assertEquals(listOf(1, 2), hits.map { it.channel.streamId })
        assertEquals(listOf("Mecz: Polska – Włochy"), GuideSearch.search(guide, listOf(tvn, polsat), "wlochy MECZ", 160).map { it.programme.title })
    }

    @Test
    fun `ended programmes and channels not in the catalog are skipped`() {
        assertEquals(emptyList<String>(), GuideSearch.search(guide, listOf(tvn), "fakty", nowSec = 160).map { it.programme.title })
        assertEquals(emptyList<GuideHit>(), GuideSearch.search(guide, listOf(tvn, polsat), "na zywo", nowSec = 0))
        assertEquals(emptyList<GuideHit>(), GuideSearch.search(guide, listOf(tvn), "  ", nowSec = 0))
    }
}
