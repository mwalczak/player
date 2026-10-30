package com.mwalczak.player.data.sync

import com.mwalczak.player.cast.SyncPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val SYNC_URL = "https://bunny.walczaki.com"

/** A saved setup on the sync server. */
@Serializable
data class SyncProfile(val code: String, val revision: Int, val updatedAt: Long = 0, val data: SyncPayload)

sealed interface Fetched {
    data class Found(val profile: SyncProfile) : Fetched
    /** Same revision as the one asked about. */
    data object Unchanged : Fetched
    data object NotFound : Fetched
}

sealed interface Saved {
    data class Ok(val code: String, val revision: Int) : Saved
    /** Someone else saved first; this is what's there now. */
    data class Conflict(val current: SyncProfile) : Saved
}

/** The HTTP side of the sync server (see server/sync_server.py). */
class SyncClient(private val http: OkHttpClient, private val baseUrl: String = SYNC_URL) {

    @Serializable
    private data class SaveRequest(val baseRevision: Int?, val data: SyncPayload)

    @Serializable
    private data class SaveResponse(val code: String, val revision: Int)

    /** This person's own setup ([key] from [SyncKey]). */
    suspend fun mine(key: String, since: Int? = null): Fetched =
        fetch(url("/v1/me", since), key)

    /** Someone's setup by its share code. */
    suspend fun shared(code: String, since: Int? = null): Fetched =
        fetch(url("/v1/profiles/${code.filter { it.isLetterOrDigit() }.uppercase()}", since), null)

    /** Saves this person's setup on top of [baseRevision] (null when there is none yet). */
    suspend fun save(key: String, baseRevision: Int?, data: SyncPayload): Saved = withContext(Dispatchers.IO) {
        val body = syncJson.encodeToString(SaveRequest.serializer(), SaveRequest(baseRevision, data))
        val request = Request.Builder()
            .url(url("/v1/me", null))
            .header("Authorization", "Bearer $key")
            .put(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when (response.code) {
                200 -> syncJson.decodeFromString(SaveResponse.serializer(), text).let { Saved.Ok(it.code, it.revision) }
                409 -> Saved.Conflict(syncJson.decodeFromString(SyncProfile.serializer(), text))
                else -> throw IOException("Sync server: HTTP ${response.code}")
            }
        }
    }

    private suspend fun fetch(url: String, key: String?): Fetched = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { key?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> Fetched.Found(syncJson.decodeFromString(SyncProfile.serializer(), response.body!!.string()))
                304 -> Fetched.Unchanged
                404 -> Fetched.NotFound
                429 -> throw IOException("Too many wrong codes – try again in an hour")
                else -> throw IOException("Sync server: HTTP ${response.code}")
            }
        }
    }

    private fun url(path: String, since: Int?) = baseUrl + path + (since?.let { "?since=$it" } ?: "")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

internal val syncJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
