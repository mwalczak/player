package com.mwalczak.player.data.epg

import android.content.Context
import android.util.Xml
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

const val DEFAULT_EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_PL1.xml.gz"

data class EpgState(
    /** Programmes per stream id, sorted by start time. */
    val programmes: Map<Int, List<Programme>> = emptyMap(),
    val updatedAtMillis: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

@Serializable
private data class EpgCache(
    val updatedAtMillis: Long,
    val source: String,
    val channelsHash: Int = 0,
    val programmes: Map<Int, List<Programme>>,
)

/**
 * Programme guide for the live channels.
 *
 * Main source: an external XMLTV file (by default the epgshare01 Polish guide), downloaded at
 * most once a day and parsed in a streaming way, keeping only the channels we have and the next
 * ~day. Channels it doesn't cover but the provider has an EPG id for are filled from the
 * provider's short EPG. The result is cached as compact JSON so the guide shows instantly.
 */
class EpgRepository(
    private val context: Context,
    http: OkHttpClient,
    private val xtream: XtreamClient,
) {
    private val http = http.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state.asStateFlow()

    private val mutex = Mutex()
    /** What the current data was built from, to know when a re-parse is needed. */
    private var builtFrom: Pair<String, Int>? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val sourceFile get() = File(context.filesDir, "epg_source")
    private val sourceMeta get() = File(context.filesDir, "epg_source.url")
    private val cacheFile get() = File(context.filesDir, "epg_cache.json")

    fun programmesFor(streamId: Int): List<Programme> = _state.value.programmes[streamId].orEmpty()

    /**
     * Makes sure the guide is fresh for [channels]. Downloads the XMLTV file when it is older than
     * a day (or [force]d) and re-parses when the channel list or source changed.
     */
    suspend fun refresh(creds: Credentials, channels: List<Channel>, url: String, enabled: Boolean, force: Boolean = false) = mutex.withLock {
        if (_state.value.programmes.isEmpty()) loadCache()
        val now = System.currentTimeMillis()
        val cached = _state.value
        val key = sourceKey(url, enabled) to channels.map { it.streamId }.sorted().hashCode()
        if (!force && cached.updatedAtMillis > 0 && now - cached.updatedAtMillis < REFRESH_MS && builtFrom == key) return@withLock

        _state.update { it.copy(loading = true, error = null) }
        try {
            val fromSec = now / 1000 - KEEP_PAST_SEC
            val toSec = now / 1000 + KEEP_AHEAD_SEC
            val external = if (enabled && url.isNotBlank()) {
                downloadIfStale(url, force)
                withContext(Dispatchers.Default) { parseSource(channels, fromSec, toSec) }
            } else {
                emptyMap()
            }
            val provider = providerFill(creds, channels.filter { it.streamId !in external && !it.epgChannelId.isNullOrBlank() })
            val merged = external + provider
            _state.value = EpgState(merged, now)
            builtFrom = key
            withContext(Dispatchers.IO) {
                cacheFile.writeText(json.encodeToString(EpgCache.serializer(), EpgCache(now, key.first, key.second, merged)))
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Could not load the guide") }
        }
    }

    private suspend fun downloadIfStale(url: String, force: Boolean) = withContext(Dispatchers.IO) {
        val file = sourceFile
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS &&
            sourceMeta.takeIf { it.exists() }?.readText() == url
        if (fresh && !force) return@withContext
        val tmp = File(file.parentFile, "${file.name}.tmp")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Guide download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Guide download failed: empty response")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(file)) throw IOException("Could not save the guide")
        sourceMeta.writeText(url)
    }

    private fun parseSource(channels: List<Channel>, fromSec: Long, toSec: Long): Map<Int, List<Programme>> {
        if (!sourceFile.exists()) return emptyMap()
        return openMaybeGzip(sourceFile).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            Xmltv.parse(parser, channels, fromSec, toSec)
        }
    }

    /** The provider's own short EPG for channels the external guide doesn't cover. */
    private suspend fun providerFill(creds: Credentials, channels: List<Channel>): Map<Int, List<Programme>> = coroutineScope {
        val limit = Semaphore(4)
        channels.take(MAX_PROVIDER_CHANNELS).map { ch ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    val entries = runCatching { xtream.shortEpg(creds, ch.streamId, limit = 30) }.getOrDefault(emptyList())
                    ch.streamId to entries.map { Programme(it.startEpochSec, it.endEpochSec, it.title, it.description) }
                }
            }
        }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
    }

    private suspend fun loadCache() = withContext(Dispatchers.IO) {
        val cache = runCatching { json.decodeFromString(EpgCache.serializer(), cacheFile.readText()) }.getOrNull() ?: return@withContext
        _state.value = EpgState(cache.programmes, cache.updatedAtMillis)
        builtFrom = cache.source to cache.channelsHash
    }

    private fun sourceKey(url: String, enabled: Boolean) = if (enabled) url else ""

    private fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream(), 64 * 1024)
        input.mark(2)
        val magic = input.read() or (input.read() shl 8)
        input.reset()
        return if (magic == GZIP_MAGIC) GZIPInputStream(input, 64 * 1024) else input
    }

    private companion object {
        const val REFRESH_MS = 20 * 60 * 60 * 1000L
        const val KEEP_PAST_SEC = 2 * 60 * 60L
        const val KEEP_AHEAD_SEC = 26 * 60 * 60L
        const val MAX_PROVIDER_CHANNELS = 150
        const val GZIP_MAGIC = 0x8b1f
    }
}
