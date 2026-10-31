package com.mwalczak.player.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mwalczak.player.data.CatalogRepository
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.LibraryStore
import com.mwalczak.player.data.SettingsStore
import com.mwalczak.player.data.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Same API as server/sync_server.py, answered in memory by an OkHttp interceptor. */
private class FakeSyncServer : Interceptor {
    class Profile(val code: String, var revision: Int, var data: JsonObject)

    val byOwner = HashMap<String, Profile>()
    val versions = HashMap<String, MutableList<Pair<Int, JsonObject>>>()
    var lastDevice: String? = null
    private var nextCode = 0
    val url = "http://sync.test"

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val since = request.url.queryParameter("since")?.toIntOrNull()
        val owner = request.header("Authorization")?.removePrefix("Bearer ")
        return when {
            path == "/v1/me" && request.method == "GET" -> answer(request, byOwner[owner], since)
            path == "/v1/me" && request.method == "PUT" -> {
                val buffer = Buffer().also { request.body!!.writeTo(it) }
                val body = syncJson.parseToJsonElement(buffer.readUtf8()).jsonObject
                val base = body["baseRevision"]?.jsonPrimitive?.intOrNull
                val data = body.getValue("data").jsonObject
                val current = byOwner[owner!!]
                when {
                    current == null -> byOwner[owner] = Profile("CODE%04d".format(nextCode++), 1, data)
                    current.revision != base -> return send(request, 409, json(current))
                    else -> { current.revision++; current.data = data }
                }
                val saved = byOwner.getValue(owner)
                versions.getOrPut(owner) { mutableListOf() }.add(saved.revision to data)
                lastDevice = request.header("X-Device")
                send(request, 200, buildJsonObject { put("code", saved.code); put("revision", saved.revision) }.toString())
            }
            path == "/v1/me/versions" -> send(request, 200, buildJsonObject {
                put("versions", JsonArray(versions[owner].orEmpty().reversed().map { (revision, _) ->
                    buildJsonObject { put("revision", revision); put("savedAt", revision * 1000L) }
                }))
            }.toString())
            path.startsWith("/v1/me/versions/") -> versions[owner].orEmpty().firstOrNull { it.first == path.substringAfterLast('/').toInt() }
                ?.let { (revision, data) -> send(request, 200, buildJsonObject { put("revision", revision); put("savedAt", 0); put("data", data) }.toString()) }
                ?: send(request, 404, "{}")
            path.startsWith("/v1/profiles/") -> answer(request, byOwner.values.firstOrNull { it.code == path.substringAfterLast('/') }, since)
            else -> send(request, 404, "{}")
        }
    }

    private fun answer(request: Request, profile: Profile?, since: Int?) = when {
        profile == null -> send(request, 404, "{}")
        since != null && since >= profile.revision -> send(request, 304, "")
        else -> send(request, 200, json(profile))
    }

    private fun json(p: Profile) = buildJsonObject {
        put("code", p.code); put("revision", p.revision); put("data", p.data)
    }.toString()

    private fun send(request: Request, status: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

// A plain Application, so the real app (and its sync to the real server) doesn't start in tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncManagerTest {

    private val fake = FakeSyncServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val http = OkHttpClient.Builder().addInterceptor(fake).build()
    private val settings = SettingsStore(context)
    private val alice = Credentials("http://mybunny.tv", "alice", "a-secret")
    private val bob = Credentials("http://mybunny.tv", "bob", "b-secret")

    private inner class Device {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val library = LibraryStore(context, scope)
        val sync = SyncManager(context, SyncClient(http, fake.url), settings, library, CatalogRepository(context, XtreamClient(http)), scope)

        fun favorites() = library.library.value.favorites.map { it.streamId }

        /** Leaves nothing behind, like uninstalling the app. */
        fun uninstall(): Unit = runBlocking {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
            File(context.filesDir, "library.json").delete()
            context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().clear().commit()
            Unit
        }
    }

    private fun saved(creds: Credentials): SyncPayload =
        syncJson.decodeFromJsonElement(SyncPayload.serializer(), fake.byOwner.getValue(SyncKey.forLogin(creds)).data)

    @Test
    fun `devices with one login share a setup, a reinstall gets it back, friends follow it`(): Unit = runBlocking {
        // Alice sets things up on her phone; it's saved online.
        settings.saveCredentials(alice)
        val phone = Device()
        phone.library.toggleFavorite(Channel(1, "TVN"))
        phone.library.togglePinnedCategory(com.mwalczak.player.data.Category("5", "Sport"))
        val list = phone.library.createList("Sport")
        phone.library.toggleInList(list, Channel(7, "Eleven"))
        phone.sync.syncNow()
        assertNull(phone.sync.status.value.error)
        val code = checkNotNull(phone.sync.status.value.code) { "no share code" }
        assertEquals(listOf(1), saved(alice).library.favorites.map { it.streamId })

        // She reinstalls: signing in brings everything back, and the empty fresh install isn't uploaded.
        phone.uninstall()
        val reinstalled = Device()
        assertTrue(reinstalled.favorites().isEmpty())
        reinstalled.sync.syncNow()
        assertEquals(listOf(1), reinstalled.favorites())
        assertEquals(listOf("5"), reinstalled.library.library.value.pinnedCategories)
        assertEquals(listOf("Sport"), reinstalled.library.library.value.lists.map { it.name })
        assertEquals(1, fake.byOwner.getValue(SyncKey.forLogin(alice)).revision)

        // A change on one device is saved for the others.
        reinstalled.library.toggleFavorite(Channel(2, "TVP1"))
        reinstalled.sync.syncNow()
        assertEquals(listOf(1, 2), saved(alice).library.favorites.map { it.streamId })
        reinstalled.uninstall()

        // Bob follows Alice's code on his own device and login…
        settings.saveCredentials(bob)
        val friend = Device()
        friend.sync.syncNow()
        val profile = friend.sync.preview(formatShareCode(code)).getOrThrow()
        friend.sync.follow(profile)
        assertEquals(listOf(1, 2), friend.favorites())
        assertEquals(code, friend.sync.status.value.following)

        // …and gets her changes.
        val aliceProfile = fake.byOwner.getValue(SyncKey.forLogin(alice))
        val changed = saved(alice).let { it.copy(library = it.library.copy(favorites = it.library.favorites + Channel(3, "Polsat"))) }
        aliceProfile.data = syncJson.encodeToJsonElement(SyncPayload.serializer(), changed).jsonObject
        aliceProfile.revision++
        friend.sync.syncNow()
        assertEquals(listOf(1, 2, 3), friend.favorites())
        // Following never writes to Alice's setup.
        assertEquals(listOf(1, 2, 3), saved(alice).library.favorites.map { it.streamId })

        // When he stops following, what he has becomes his own saved setup.
        friend.sync.stopFollowing()
        assertNull(friend.sync.status.value.following)
        assertEquals(listOf(1, 2, 3), saved(bob).library.favorites.map { it.streamId })
        assertEquals(3, aliceProfile.revision)
        friend.uninstall()
    }

    @Test
    fun `an older version can be put back on every device`(): Unit = runBlocking {
        settings.saveCredentials(alice)
        val phone = Device()
        phone.library.toggleFavorite(Channel(1, "TVN"))
        phone.sync.syncNow()
        phone.library.toggleFavorite(Channel(2, "TVP1"))
        phone.library.toggleFavorite(Channel(1, "TVN"))
        phone.sync.syncNow()
        assertEquals(listOf(2), saved(alice).library.favorites.map { it.streamId })
        assertTrue(fake.lastDevice!!.isNotBlank())

        val history = phone.sync.history().getOrThrow()
        assertEquals(listOf(2, 1), history.map { it.revision })
        phone.sync.restore(1)
        assertNull(phone.sync.status.value.error)
        assertEquals(listOf(1), phone.favorites())
        // Saved as the newest version, so the other devices get it too; the replaced one stays.
        assertEquals(listOf(1), saved(alice).library.favorites.map { it.streamId })
        assertEquals(listOf(3, 2, 1), phone.sync.history().getOrThrow().map { it.revision })
        phone.uninstall()
    }
}
