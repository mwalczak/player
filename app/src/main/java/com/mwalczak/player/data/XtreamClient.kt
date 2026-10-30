package com.mwalczak.player.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class XtreamClient(private val http: OkHttpClient) {

    suspend fun authenticate(creds: Credentials): AccountInfo =
        XtreamParser.parseAccount(get(ServerUrls.playerApi(creds)))

    suspend fun liveCategoriesRaw(creds: Credentials): String =
        get(ServerUrls.playerApi(creds, "get_live_categories"))

    suspend fun liveStreamsRaw(creds: Credentials, categoryId: String? = null): String =
        get(ServerUrls.playerApi(creds, "get_live_streams", categoryId?.let { mapOf("category_id" to it) }.orEmpty()))

    suspend fun shortEpg(creds: Credentials, streamId: Int, limit: Int = 2): List<EpgEntry> {
        val body = get(ServerUrls.playerApi(creds, "get_short_epg", mapOf("stream_id" to "$streamId", "limit" to "$limit")))
        return XtreamParser.parseShortEpg(body) { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        401, 403 -> "Access denied by the server (HTTP ${response.code}). Check your username and password."
                        404 -> "Server URL not found (HTTP 404). Check the server address."
                        else -> "Server error: HTTP ${response.code}"
                    },
                )
            }
            response.body?.string().orEmpty()
        }
    }
}
