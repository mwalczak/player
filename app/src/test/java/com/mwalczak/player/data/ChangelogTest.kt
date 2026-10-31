package com.mwalczak.player.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChangelogTest {

    private val entries = listOf(3, 2, 1).map { ChangelogEntry(it, "2026-10-3$it", "Release $it", listOf("change $it")) }

    @Test
    fun `the shipped changelog reads, newest first, with unique ids`() {
        val shipped = Changelog.load(ApplicationProvider.getApplicationContext())
        assertTrue(shipped.size >= 5)
        assertEquals(shipped.map { it.id }.sortedDescending(), shipped.map { it.id })
        assertEquals(shipped.size, shipped.map { it.id }.toSet().size)
        assertTrue(shipped.all { it.items.isNotEmpty() })
    }

    @Test
    fun `after an update only the entries not seen yet`() {
        assertEquals(listOf(3, 2), Changelog.unseen(entries, lastSeen = 1, freshInstall = false).map { it.id })
        assertEquals(emptyList<Int>(), Changelog.unseen(entries, lastSeen = 3, freshInstall = false).map { it.id })
    }

    @Test
    fun `nothing on a fresh install, the newest when updating from before the changelog`() {
        assertEquals(emptyList<Int>(), Changelog.unseen(entries, lastSeen = null, freshInstall = true).map { it.id })
        assertEquals(listOf(3), Changelog.unseen(entries, lastSeen = null, freshInstall = false).map { it.id })
    }
}
