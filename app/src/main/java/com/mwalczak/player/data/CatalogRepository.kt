package com.mwalczak.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class Catalog(
    val categories: List<Category>,
    /** Every row the server sent; the same stream can appear in several categories. */
    val allRows: List<Channel>,
    val loadedAtMillis: Long,
) {
    /** Each channel once (lists keyed by stream id would crash on duplicates). */
    val channels: List<Channel> = allRows.distinctBy { it.streamId }

    /** Every category keeps all of its channels, including ones also listed elsewhere. */
    val byCategory: Map<String, List<Channel>> =
        allRows.groupBy { it.categoryId.orEmpty() }.mapValues { (_, list) -> list.distinctBy { it.streamId } }
    private val searchIndex: List<Pair<String, Channel>> = channels.map { ChannelMatcher.searchKey(it.name) to it }

    fun channelsIn(categoryId: String): List<Channel> = byCategory[categoryId].orEmpty()

    val emptyCategories: List<Category> get() = categories.filter { byCategory[it.id].isNullOrEmpty() }

    fun search(query: String, limit: Int = 500): List<Channel> {
        val terms = ChannelMatcher.searchKey(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return searchIndex.asSequence()
            .filter { (key, _) -> terms.all { key.contains(it) } }
            .map { it.second }
            .take(limit)
            .toList()
    }
}

sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Ready(val catalog: Catalog, val refreshing: Boolean = false) : CatalogState
    data class Error(val message: String) : CatalogState
}

/**
 * Holds the live channel catalog in memory, backed by a raw JSON cache on disk so the app
 * opens instantly; the network copy is refreshed when the cache is older than [maxCacheAgeMillis].
 */
class CatalogRepository(
    private val context: Context,
    private val client: XtreamClient,
) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val maxCacheAgeMillis = 12 * 60 * 60 * 1000L

    val catalog: Catalog? get() = (state.value as? CatalogState.Ready)?.catalog

    suspend fun load(creds: Credentials, force: Boolean = false) = mutex.withLock {
        val current = catalog
        if (!force && current != null) return@withLock

        val cats = cacheFile(creds, "categories")
        val streams = cacheFile(creds, "channels")
        val cached = if (current == null) readCache(cats, streams) else null
        if (cached != null) _state.value = CatalogState.Ready(cached)

        val fresh = cached != null && System.currentTimeMillis() - cached.loadedAtMillis < maxCacheAgeMillis
        if (fresh && !force) return@withLock

        val shown = catalog
        _state.value = if (shown != null) CatalogState.Ready(shown, refreshing = true) else CatalogState.Loading
        try {
            val (catsRaw, streamsRaw) = coroutineScope {
                val c = async { client.liveCategoriesRaw(creds) }
                val s = async { client.liveStreamsRaw(creds) }
                c.await() to s.await()
            }
            var loaded = withContext(Dispatchers.Default) {
                Catalog(XtreamParser.parseCategories(catsRaw), XtreamParser.parseLiveStreams(streamsRaw), System.currentTimeMillis())
            }
            // Some panels cap the "all channels" answer; categories that come back empty give it away.
            // Then ask category by category (which such panels answer in full) and merge.
            if (loaded.emptyCategories.isNotEmpty()) {
                loaded = withPerCategoryChannels(creds, loaded)
            }
            if (loaded.channels.isEmpty() && shown != null) {
                // Treat an empty answer as a hiccup rather than wiping a working catalog.
                _state.value = CatalogState.Ready(shown)
                return@withLock
            }
            withContext(Dispatchers.IO) {
                cats.writeText(catsRaw)
                streams.writeText(cacheJson.encodeToString(channelList, loaded.allRows))
            }
            _state.value = CatalogState.Ready(loaded)
        } catch (e: Exception) {
            _state.value = if (shown != null) CatalogState.Ready(shown) else CatalogState.Error(e.message ?: "Could not load channels")
        }
    }

    fun clear(creds: Credentials?) {
        _state.value = CatalogState.Idle
        if (creds != null) {
            cacheFile(creds, "categories").delete()
            cacheFile(creds, "channels").delete()
        }
    }

    private suspend fun withPerCategoryChannels(creds: Credentials, catalog: Catalog): Catalog {
        val limit = Semaphore(8)
        val perCategory = coroutineScope {
            catalog.categories.map { category ->
                async {
                    limit.withPermit {
                        runCatching { XtreamParser.parseLiveStreams(client.liveStreamsRaw(creds, category.id)) }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        val merged = withContext(Dispatchers.Default) { mergeChannels(catalog.allRows, perCategory.flatten()) }
        return Catalog(catalog.categories, merged, catalog.loadedAtMillis)
    }

    private suspend fun readCache(cats: File, streams: File): Catalog? = withContext(Dispatchers.IO) {
        if (!cats.exists() || !streams.exists()) return@withContext null
        runCatching {
            val channels = cacheJson.decodeFromString(channelList, streams.readText())
            if (channels.isEmpty()) null
            else Catalog(XtreamParser.parseCategories(cats.readText()), channels, streams.lastModified())
        }.getOrNull()
    }

    private fun cacheFile(creds: Credentials, name: String) = File(context.cacheDir, "catalog_${creds.accountKey}_$name.json")

    private val cacheJson = Json { ignoreUnknownKeys = true }
    private val channelList = ListSerializer(Channel.serializer())
}

/** [base] plus any rows from [extra] it doesn't have yet (same stream in the same category), keeping the order. */
internal fun mergeChannels(base: List<Channel>, extra: List<Channel>): List<Channel> {
    val seen = base.mapTo(HashSet()) { it.streamId to it.categoryId }
    return base + extra.filter { seen.add(it.streamId to it.categoryId) }
}
