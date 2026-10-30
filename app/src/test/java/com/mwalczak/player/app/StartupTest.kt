package com.mwalczak.player.app

import androidx.test.core.app.ApplicationProvider
import com.mwalczak.player.PlayerApp
import com.mwalczak.player.data.ChannelMatcher
import com.mwalczak.player.data.epg.EpgDatabase
import com.mwalczak.player.data.epg.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    @Test
    fun `the app and its container start`() {
        val app = ApplicationProvider.getApplicationContext<PlayerApp>()
        assertNotNull(app.container.epg.state.value)
        assertNotNull(app.container.catalog.state.value)
    }

    @Test
    fun `guide database builds, swaps and answers queries`() {
        val db = EpgDatabase(ApplicationProvider.getApplicationContext())
        assertEquals(0, db.channelCount())
        val builder = db.newBuilder()
        builder.add(
            mapOf(
                1 to listOf(Programme(100, 200, "Fakty", "News"), Programme(200, 300, "Mecz: Polska – Włochy")),
                2 to listOf(Programme(150, 250, "Wydarzenia")),
            ),
        )
        db.swapIn(builder, mapOf("updated" to "42"))
        assertEquals(2, db.channelCount())
        assertEquals("42", db.meta("updated"))
        assertEquals(mapOf(1 to "Fakty", 2 to "Wydarzenia"), db.onAir(160).mapValues { it.value.title })
        assertEquals(listOf("Fakty", "Mecz: Polska – Włochy"), db.programmes(1, 0, 1000).map { it.title })
        assertEquals(listOf("Mecz: Polska – Włochy"), db.searchCandidates(ChannelMatcher.searchKey("wlochy"), 160)[1]?.map { it.title })

        // A second build replaces the first one completely.
        val second = db.newBuilder()
        second.add(mapOf(3 to listOf(Programme(100, 400, "Kuchnia"))))
        db.swapIn(second, mapOf("updated" to "43"))
        assertEquals(1, db.channelCount())
        assertEquals("43", db.meta("updated"))
        assertEquals(emptyList<Programme>(), db.programmes(1, 0, 1000))
    }
}
