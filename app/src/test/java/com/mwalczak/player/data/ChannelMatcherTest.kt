package com.mwalczak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMatcherTest {

    @Test
    fun `quality tags, country prefixes and styled characters are ignored`() {
        val names = listOf("US: ESPN HD", "US| ESPN FHD", "ESPN ᴴᴰ (Backup)", "[US] ESPN 1080p", "ESPN")
        names.forEach { assertEquals(it, "espn", ChannelMatcher.baseName(it)) }
    }

    @Test
    fun `different channels stay different`() {
        val a = ChannelMatcher.baseName("UK: Sky Sports Main Event HD")
        val b = ChannelMatcher.baseName("UK: Sky Sports Premier League HD")
        assert(a != b)
        assertEquals("sky sports main event", a)
    }

    @Test
    fun `names that reduce to nothing fall back to the raw name`() {
        assertEquals("hd", ChannelMatcher.baseName("HD"))
    }

    @Test
    fun `alternates exclude the channel itself and prefer the same category`() {
        val espn = Channel(1, "US: ESPN HD", categoryId = "sports")
        val catalog = listOf(
            espn,
            Channel(2, "ESPN FHD", categoryId = "backup"),
            Channel(3, "US: ESPN SD", categoryId = "sports"),
            Channel(4, "ESPN 2 HD", categoryId = "sports"),
        )
        assertEquals(listOf(3, 2), ChannelMatcher.alternatesFor(espn, catalog).map { it.streamId })
    }

    @Test
    fun `search key ignores case and accents`() {
        assertEquals("tele cinco", ChannelMatcher.searchKey("Télé Cinco"))
    }

    @Test
    fun `similar names are offered as other feeds, without the exact ones or unrelated channels`() {
        val f1 = Channel(1, "UK: Sky Sports F1 FHD")
        val catalog = listOf(
            f1,
            Channel(2, "Sky Sports F1 HD"),          // same channel: an alternate, not "similar"
            Channel(3, "UK: Sky Sports F1 Extra"),   // similar name
            Channel(4, "UK: Sky Sports Main Event"), // different channel
            Channel(5, "US: F1 TV"),
        )
        assertEquals(listOf(2), ChannelMatcher.alternatesFor(f1, catalog).map { it.streamId })
        assertEquals(listOf(3), ChannelMatcher.similarTo(f1, catalog).map { it.streamId })
    }
}
