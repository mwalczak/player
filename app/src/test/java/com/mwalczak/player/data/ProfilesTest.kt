package com.mwalczak.player.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfilesTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val main = Credentials("http://mybunny.tv", "jan", "secret")
    private val second = Credentials("http://other.tv:8080", "kids", "x")

    @Test
    fun `the first profile is what the app had, with the old file names`() {
        val store = ProfileStore(context)
        assertEquals(FIRST_PROFILE, store.current.id)
        assertEquals("library.json", store.current.scoped("library.json"))
        store.updateCurrentLogin(main)
        assertEquals("jan", store.current.name)
        assertEquals(main, store.current.credentials)
    }

    @Test
    fun `other profiles get their own files`() {
        assertEquals("library-ab12.json", scopedName("ab12", "library.json"))
        assertEquals("epg-ab12.db", scopedName("ab12", "epg.db"))
        assertEquals("sync-ab12", scopedName("ab12", "sync"))
    }

    @Test
    fun `profiles are added, renamed, switched and removed, and kept`() {
        val store = ProfileStore(context)
        store.updateCurrentLogin(main)
        val kids = store.add("Kids", second)
        store.rename(kids.id, "Dzieci")
        store.setActive(kids.id)
        assertEquals("Dzieci", ProfileStore(context).current.name)

        store.remove(kids.id) // in use: stays
        assertEquals(2, store.profiles.value.profiles.size)
        store.setActive(FIRST_PROFILE)
        store.remove(kids.id)
        assertEquals(listOf(FIRST_PROFILE), ProfileStore(context).profiles.value.profiles.map { it.id })
    }

    @Test
    fun `each profile keeps its own favorites`(): Unit = runBlocking {
        val library = LibraryStore(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        library.toggleFavorite(Channel(1, "TVN"))
        library.switchTo("kids")
        assertTrue(library.library.value.favorites.isEmpty())
        library.toggleFavorite(Channel(9, "MiniMini+"))
        library.switchTo(FIRST_PROFILE)
        assertEquals(listOf(1), library.library.value.favorites.map { it.streamId })
        library.switchTo("kids")
        assertEquals(listOf(9), library.library.value.favorites.map { it.streamId })
        assertTrue(File(context.filesDir, "library-kids.json").exists())
    }

    @Test
    fun `a login already in a profile is found, however the server was typed`() {
        val store = ProfileStore(context)
        store.updateCurrentLogin(main)
        assertEquals(FIRST_PROFILE, store.withLogin(Credentials("https://MyBunny.tv:80/", "jan", "new password"))?.id)
        assertEquals(null, store.withLogin(Credentials("http://mybunny.tv", "ola", "x")))
    }
}
