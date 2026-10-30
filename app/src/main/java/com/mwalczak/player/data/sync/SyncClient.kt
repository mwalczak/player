package com.mwalczak.player.data.sync

import android.os.Build
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

/** One saved version of this person's setup: when, from where and which device ([data] only when fetched alone). */
@Serializable
data class SyncVersion(
    val revision: Int,
    val savedAt: Long,
    val ip: String = "",
    val device: String = "",
    val favorites: Int = 0,
    val lists: Int = 0,
    val pinned: Int = 0,
    val data: SyncPayload? = null,
)

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

/**
 * The HTTP side of the sync server (see server/sync_server.py). [device] names this device in
 * the setup's history, next to the address it saved from.
 */
class SyncClient(
    private val http: OkHttpClient,
    private val baseUrl: String = SYNC_URL,
    device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
) {
    // Header values must be plain ASCII.
    private val device = device.filter { it.code in 32..126 }.trim().take(80)

    @Serializable
    private data class SaveRequest(val baseRevision: Int?, val data: SyncPayload)

    @Serializable
    private data class SaveResponse(val code: String, val revision: Int)

    @Serializable
    private data class Versions(val versions: List<SyncVersion>)

    /** This person's own setup ([key] from [SyncKey]). */
    suspend fun mine(key: String, since: Int? = null): Fetched =
        fetch(url("/v1/me", since), key)

    /** Someone's setup by its share code. */
    suspend fun shared(code: String, since: Int? = null): Fetched =
        fetch(url("/v1/profiles/${code.filter { it.isLetterOrDigit() }.uppercase()}", since), null)

    /** This person's saved versions, newest first (without their data). */
    suspend fun versions(key: String): List<SyncVersion> = get(url("/v1/me/versions", null), key) { text ->
        syncJson.decodeFromString(Versions.serializer(), text).versions
    }

    /** One saved version, with its data. */
    suspend fun version(key: String, revision: Int): SyncVersion = get(url("/v1/me/versions/$revision", null), key) { text ->
        syncJson.decodeFromString(SyncVersion.serializer(), text)
    }

    /** Saves this person's setup on top of [baseRevision] (null when there is none yet). */
    suspend fun save(key: String, baseRevision: Int?, data: SyncPayload): Saved = withContext(Dispatchers.IO) {
        val body = syncJson.encodeToString(SaveRequest.serializer(), SaveRequest(baseRevision, data))
        val request = Request.Builder()
            .url(url("/v1/me", null))
            .header("Authorization", "Bearer $key")
            .header("X-Device", device)
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

    private suspend fun <T> get(url: String, key: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> parse(response.body!!.string())
                404 -> throw IOException("Not found on the sync server")
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
