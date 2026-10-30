package com.mwalczak.player.data

import com.mwalczak.player.cast.SyncPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTest {

    private fun payload(library: Library) =
        SyncPayload(library, "TS", tryAlternates = true, nextInList = true, stallTimeoutSec = 15, epgEnabled = true, epgUrl = "u")

    @Test
    fun `backup round-trips with and without login`() {
        val library = Library(favorites = listOf(Channel(1, "TVN")), lists = listOf(UserList("a", "Sport", listOf(Channel(2, "Polsat Sport")))))
        val withLogin = Backup(exportedAtMillis = 5, data = payload(library), login = BackupLogin("https://mybunny.tv", "user", "pass"))
        assertEquals(withLogin, Backups.decode(Backups.encode(withLogin)))
        val without = withLogin.copy(login = null)
        assertNull(Backups.decode(Backups.encode(without)).login)
    }

    @Test
    fun `files that are not ours are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Backups.decode("""{"hello":"world"}""") }
        assertThrows(IllegalArgumentException::class.java) { Backups.decode("not json") }
        val other = Backups.encode(Backup(data = payload(Library()))).replace("bunny-player", "something-else")
        assertThrows(IllegalArgumentException::class.java) { Backups.decode(other) }
    }

    @Test
    fun `the file has what was set up but no watch history`() {
        val library = Library(favorites = listOf(Channel(1, "TVN")), recent = listOf(Channel(9, "Watched")))
        val text = Backups.encode(Backup(data = payload(library)))
        assertFalse(text.contains("recent"))
        assertFalse(text.contains("Watched"))
        val back = Backups.decode(text)
        assertEquals(listOf(1), back.data.library.favorites.map { it.streamId })
        assertEquals(emptyList<Channel>(), back.data.library.recent)
    }

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
        val merged = Backups.merge(mine, theirs)
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
        val remapped = Backups.remap(imported, catalog)
        assertEquals(listOf(100, 200, 7), remapped.favorites.map { it.streamId })
    }
}
