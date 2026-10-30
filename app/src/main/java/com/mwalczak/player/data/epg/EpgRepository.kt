package com.mwalczak.player.data.epg

import android.content.Context
import android.net.ConnectivityManager
import android.util.Xml
import com.mwalczak.player.data.Catalog
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.EpgSettings
import com.mwalczak.player.data.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** The Polish guide that used to be the single default source; kept to migrate old settings. */
const val DEFAULT_EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_PL1.xml.gz"

data class EpgState(
    /** How many channels have programmes in the guide. */
    val channelsWithGuide: Int = 0,
    val updatedAtMillis: Long = 0,
    /** Guides that were used for the current data, e.g. ["Poland", "United States"]. */
    val sources: List<String> = emptyList(),
    val loading: Boolean = false,
    /** What a running refresh is doing, e.g. "United States (3/18)". */
    val progress: String? = null,
    val error: String? = null,
    /** Bumped whenever new guide data is installed, so screens re-read it. */
    val version: Int = 0,
)

/**
 * Programme guide for the live channels.
 *
 * Several XMLTV files (one per country, picked from the catalog or chosen in Settings) are
 * downloaded at most once a day, parsed one after another in a streaming way, and matched to our
 * channels; a channel takes its programmes from the first guide that matches it. Channels no guide
 * covers but the provider has an EPG id for are filled from the provider's short EPG. Everything is
 * stored in [EpgDatabase]; only "what's on now" per channel is kept in memory ([onAir]).
 */
class EpgRepository(
    private val context: Context,
    http: OkHttpClient,
    private val xtream: XtreamClient,
    scope: CoroutineScope,
) {
    private val http = http.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
    private val db = EpgDatabase(context)
    private val dir = File(context.filesDir, "epg").apply { mkdirs() }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state.asStateFlow()

    private val _onAir = MutableStateFlow<Map<Int, Programme>>(emptyMap())
    /** The programme on air on each channel that has a guide; refreshed every minute. */
    val onAir: StateFlow<Map<Int, Programme>> = _onAir.asStateFlow()

    init {
        // Files from the single-guide version.
        listOf("epg_source", "epg_source.url", "epg_cache.json").forEach { File(context.filesDir, it).delete() }
        scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    channelsWithGuide = db.channelCount(),
                    updatedAtMillis = db.meta(META_UPDATED)?.toLongOrNull() ?: 0,
                    sources = db.meta(META_SOURCES)?.split('|')?.filter(String::isNotBlank).orEmpty(),
                )
            }
            while (true) {
                refreshOnAir()
                delay(60_000)
            }
        }
    }

    /** One channel's programmes in a time window (default: from 2 h ago to a day ahead). */
    suspend fun programmesFor(
        streamId: Int,
        fromSec: Long = System.currentTimeMillis() / 1000 - KEEP_PAST_SEC,
        toSec: Long = System.currentTimeMillis() / 1000 + KEEP_AHEAD_SEC,
    ): List<Programme> = withContext(Dispatchers.IO) { db.programmes(streamId, fromSec, toSec) }

    /** Shows whose title contains every word of [query], across [channels], what's on now first. */
    suspend fun search(query: String, channels: List<Channel>, nowSec: Long): List<GuideHit> = withContext(Dispatchers.IO) {
        val terms = com.mwalczak.player.data.ChannelMatcher.searchKey(query).split(' ').filter { it.isNotBlank() }
        val longest = terms.maxByOrNull { it.length } ?: return@withContext emptyList()
        GuideSearch.search(db.searchCandidates(longest, nowSec), channels, query, nowSec)
    }

    /** Sources that would be used for [catalog] with [settings]. */
    fun sourcesFor(catalog: Catalog, settings: EpgSettings): List<EpgSource> {
        if (!settings.enabled) return emptyList()
        val builtIn = settings.sources?.mapNotNull(EpgSources::byId) ?: EpgSources.autoSelect(catalog.channels, catalog.categories)
        val extra = settings.url.takeIf { it.isNotBlank() }?.let { EpgSource("custom", "Extra guide", 0) }
        return builtIn + listOfNotNull(extra)
    }

    /**
     * Makes sure the guide is fresh: rebuilds it when it is older than a day, when the sources or
     * the channel list changed, or when [force]d. [priority] channels (favorites, lists) are
     * filled from the provider first when no guide covers them.
     */
    suspend fun refresh(
        creds: Credentials,
        catalog: Catalog,
        settings: EpgSettings,
        priority: List<Channel>,
        force: Boolean = false,
    ) = mutex.withLock {
        val sources = sourcesFor(catalog, settings)
        val key = (sources.map { if (it.id == "custom") settings.url else it.id } + catalog.channels.size.toString()).joinToString("|")
        val now = System.currentTimeMillis()
        val updated = withContext(Dispatchers.IO) { db.meta(META_UPDATED)?.toLongOrNull() ?: 0 }
        val builtFor = withContext(Dispatchers.IO) { db.meta(META_KEY) }
        if (!force && now - updated < REFRESH_MS && builtFor == key) return@withLock

        val canDownload = !settings.wifiOnly || !isMetered()
        _state.update { it.copy(loading = true, error = null, progress = null) }
        val builder = withContext(Dispatchers.IO) { db.newBuilder() }
        try {
            val fromSec = now / 1000 - KEEP_PAST_SEC
            val toSec = now / 1000 + KEEP_AHEAD_SEC
            val covered = HashSet<Int>()
            val used = mutableListOf<String>()
            var skipped = 0
            sources.forEachIndexed { i, source ->
                _state.update { it.copy(progress = "${source.label} (${i + 1}/${sources.size})") }
                val url = if (source.id == "custom") settings.url else source.url
                val file = runCatching { fetch(source.id, url, canDownload, force) }.getOrNull()
                if (file == null) {
                    skipped++
                    return@forEachIndexed
                }
                val remaining = catalog.channels.filter { it.streamId !in covered }
                val found = withContext(Dispatchers.Default) {
                    runCatching { parse(file, remaining, fromSec, toSec) }.getOrDefault(emptyMap())
                }
                if (found.isNotEmpty()) {
                    withContext(Dispatchers.IO) { builder.add(found) }
                    covered += found.keys
                    used += source.label
                }
            }

            _state.update { it.copy(progress = "Provider guide") }
            val wanted = (priority + catalog.channels).distinctBy { it.streamId }
                .filter { it.streamId !in covered && !it.epgChannelId.isNullOrBlank() }
            val provider = providerFill(creds, wanted)
            if (provider.isNotEmpty()) {
                withContext(Dispatchers.IO) { builder.add(provider) }
                covered += provider.keys
            }

            if (covered.isEmpty() && _state.value.channelsWithGuide > 0) {
                // Nothing new could be loaded (e.g. waiting for Wi-Fi): keep the guide we have.
                withContext(Dispatchers.IO) { builder.discard() }
                _state.update {
                    it.copy(loading = false, progress = null, error = if (!canDownload) "Waiting for Wi-Fi to update the guide" else "Couldn't update the guide")
                }
                return@withLock
            }
            withContext(Dispatchers.IO) {
                db.swapIn(
                    builder,
                    mapOf(META_UPDATED to now.toString(), META_KEY to key, META_SOURCES to used.joinToString("|")),
                )
            }
            refreshOnAir()
            _state.update {
                it.copy(
                    channelsWithGuide = covered.size,
                    updatedAtMillis = now,
                    sources = used,
                    loading = false,
                    progress = null,
                    error = when {
                        skipped > 0 && !canDownload -> "Some guides wait for Wi-Fi"
                        skipped > 0 -> "$skipped guide${if (skipped == 1) "" else "s"} couldn't be downloaded"
                        else -> null
                    },
                    version = it.version + 1,
                )
            }
        } catch (e: Throwable) {
            // Including OutOfMemoryError: a failed guide update must never take the app down.
            if (e is kotlinx.coroutines.CancellationException) throw e
            runCatching { withContext(Dispatchers.IO) { builder.discard() } }
            com.mwalczak.player.CrashReports.record(context, "Guide update failed (app kept running)", e)
            _state.update {
                it.copy(
                    loading = false,
                    progress = null,
                    error = if (e is OutOfMemoryError) "Not enough memory for all guides – pick fewer in Guides" else e.message ?: "Could not load the guide",
                )
            }
        }
    }

    private suspend fun refreshOnAir() {
        _onAir.value = withContext(Dispatchers.IO) { db.onAir(System.currentTimeMillis() / 1000) }
    }

    /** The guide file for [id]: re-downloaded when older than a day (if allowed), else the copy we have. */
    private suspend fun fetch(id: String, url: String, canDownload: Boolean, force: Boolean): File? = withContext(Dispatchers.IO) {
        val file = File(dir, "$id.xml.gz")
        val meta = File(dir, "$id.url")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS &&
            meta.takeIf { it.exists() }?.readText() == url
        if ((fresh && !force) || !canDownload) return@withContext file.takeIf { it.exists() }
        val tmp = File(dir, "$id.tmp")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(file)) throw IOException("Could not save the guide")
        meta.writeText(url)
        file
    }

    private fun parse(file: File, channels: List<Channel>, fromSec: Long, toSec: Long): Map<Int, List<Programme>> =
        openMaybeGzip(file).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            Xmltv.parse(parser, channels, fromSec, toSec)
        }

    /** The provider's own short EPG for channels no guide covers, favorites first. */
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

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

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
        const val META_UPDATED = "updated"
        const val META_KEY = "key"
        const val META_SOURCES = "sources"
    }
}
