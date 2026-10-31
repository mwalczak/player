package com.mwalczak.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class UserList(
    val id: String,
    val name: String,
    val channels: List<Channel> = emptyList(),
)

@Serializable
data class Library(
    val favorites: List<Channel> = emptyList(),
    val lists: List<UserList> = emptyList(),
    val recent: List<Channel> = emptyList(),
    /** Category ids shown at the top of Live TV, in pin order. */
    val pinnedCategories: List<String> = emptyList(),
    /**
     * Name of each pinned category (by id). The provider renumbers its categories when one is
     * added or removed, so names are what identifies them; ids are updated to match (see [fitPinned]).
     */
    val pinnedNames: Map<String, String> = emptyMap(),
)

/** The pinned categories as they are in [categories]: found by name (ids change), by id for old pins without one. */
fun Library.pinnedIn(categories: List<Category>): List<Category> {
    val byName = categories.groupBy { it.name }
    val byId = categories.associateBy { it.id }
    return pinnedCategories.mapNotNull { id ->
        val name = pinnedNames[id]
        if (name != null) byName[name]?.let { same -> same.firstOrNull { it.id == id } ?: same.first() } else byId[id]
    }.distinct()
}

/** Pins pointed at the current ids (and old pins given their names), dropping pins whose category is gone. */
fun Library.fitPinned(categories: List<Category>): Library {
    val pinned = pinnedIn(categories)
    return copy(pinnedCategories = pinned.map { it.id }, pinnedNames = pinned.associate { it.id to it.name })
}

/** Favorites, user lists and recently watched channels, persisted as a small JSON file. */
class LibraryStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _library = MutableStateFlow(read())
    val library: StateFlow<Library> = _library.asStateFlow()

    fun isFavorite(streamId: Int): Boolean = _library.value.favorites.any { it.streamId == streamId }

    fun toggleFavorite(channel: Channel) = update { lib ->
        if (lib.favorites.any { it.streamId == channel.streamId }) lib.copy(favorites = lib.favorites.filterNot { it.streamId == channel.streamId })
        else lib.copy(favorites = lib.favorites + channel)
    }

    fun moveFavorite(from: Int, to: Int) = update { it.copy(favorites = it.favorites.move(from, to)) }

    /** Puts favorites in the order of [order], e.g. A–Z or back to how they were. */
    fun reorderFavorites(order: List<Channel>) = update { it.copy(favorites = it.favorites.inOrderOf(order)) }

    fun createList(name: String): String {
        val id = UUID.randomUUID().toString()
        update { it.copy(lists = it.lists + UserList(id, name.trim())) }
        return id
    }

    fun renameList(id: String, name: String) = updateList(id) { it.copy(name = name.trim()) }

    fun deleteList(id: String) = update { lib -> lib.copy(lists = lib.lists.filterNot { it.id == id }) }

    fun toggleInList(listId: String, channel: Channel) = updateList(listId) { list ->
        if (list.channels.any { it.streamId == channel.streamId }) list.copy(channels = list.channels.filterNot { it.streamId == channel.streamId })
        else list.copy(channels = list.channels + channel)
    }

    fun moveInList(listId: String, from: Int, to: Int) = updateList(listId) { it.copy(channels = it.channels.move(from, to)) }

    fun reorderList(listId: String, order: List<Channel>) = updateList(listId) { it.copy(channels = it.channels.inOrderOf(order)) }

    fun togglePinnedCategory(category: Category) = update { lib ->
        val pinnedAs = lib.pinnedCategories.filter { id -> lib.pinnedNames[id]?.let { it == category.name } ?: (id == category.id) }
        if (pinnedAs.isNotEmpty()) {
            lib.copy(pinnedCategories = lib.pinnedCategories - pinnedAs.toSet(), pinnedNames = lib.pinnedNames - pinnedAs.toSet())
        } else {
            lib.copy(
                pinnedCategories = lib.pinnedCategories.filterNot { it == category.id } + category.id,
                pinnedNames = lib.pinnedNames + (category.id to category.name),
            )
        }
    }

    /**
     * Keeps favorites, lists and pins pointing at the same things after the provider renumbered
     * its catalog: pins follow their category's name, channels whose id now has another name
     * follow theirs. Does nothing when everything already matches.
     */
    fun fitTo(catalog: Catalog, channelsToo: Boolean) = update { lib ->
        val fitted = lib.fitPinned(catalog.categories)
        if (channelsToo) Libraries.remap(fitted, catalog.channels) else fitted
    }

    /** Replaces everything, e.g. with favorites and lists copied from the phone. */
    fun replaceAll(library: Library) = update { library }

    fun addRecent(channel: Channel) = update { lib ->
        lib.copy(recent = (listOf(channel) + lib.recent.filterNot { it.streamId == channel.streamId }).take(30))
    }

    private fun updateList(id: String, transform: (UserList) -> UserList) = update { lib ->
        lib.copy(lists = lib.lists.map { if (it.id == id) transform(it) else it })
    }

    private fun update(transform: (Library) -> Library) {
        val before = _library.getAndUpdate(transform)
        if (_library.value == before) return
        // Always persist the latest snapshot, so a slow earlier write can never overwrite a newer one.
        scope.launch(Dispatchers.IO) { mutex.withLock { write(_library.value) } }
    }

    private fun read(): Library =
        runCatching { if (file.exists()) json.decodeFromString<Library>(file.readText()) else Library() }
            .getOrDefault(Library())

    private fun write(library: Library) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Library.serializer(), library))
        tmp.renameTo(file)
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
