package com.mwalczak.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
)

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

    fun togglePinnedCategory(id: String) = update { lib ->
        lib.copy(pinnedCategories = if (id in lib.pinnedCategories) lib.pinnedCategories - id else lib.pinnedCategories + id)
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
        _library.update(transform)
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
