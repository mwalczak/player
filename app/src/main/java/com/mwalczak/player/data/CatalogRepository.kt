package com.mwalczak.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val channels: List<Channel>,
    val loadedAtMillis: Long,
) {
    val byCategory: Map<String, List<Channel>> = channels.groupBy { it.categoryId.orEmpty() }
    private val searchIndex: List<Pair<String, Channel>> = channels.map { ChannelMatcher.searchKey(it.name) to it }

    fun channelsIn(categoryId: String): List<Channel> = byCategory[categoryId].orEmpty()

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
        val streams = cacheFile(creds, "streams")
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
            val loaded = withContext(Dispatchers.Default) {
                Catalog(XtreamParser.parseCategories(catsRaw), XtreamParser.parseLiveStreams(streamsRaw), System.currentTimeMillis())
            }
            if (loaded.channels.isEmpty() && shown != null) {
                // Treat an empty answer as a hiccup rather than wiping a working catalog.
                _state.value = CatalogState.Ready(shown)
                return@withLock
            }
            withContext(Dispatchers.IO) {
                cats.writeText(catsRaw)
                streams.writeText(streamsRaw)
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
            cacheFile(creds, "streams").delete()
        }
    }

    private suspend fun readCache(cats: File, streams: File): Catalog? = withContext(Dispatchers.IO) {
        if (!cats.exists() || !streams.exists()) return@withContext null
        runCatching {
            val channels = XtreamParser.parseLiveStreams(streams.readText())
            if (channels.isEmpty()) null
            else Catalog(XtreamParser.parseCategories(cats.readText()), channels, streams.lastModified())
        }.getOrNull()
    }

    private fun cacheFile(creds: Credentials, name: String) = File(context.cacheDir, "catalog_${creds.accountKey}_$name.json")
}
