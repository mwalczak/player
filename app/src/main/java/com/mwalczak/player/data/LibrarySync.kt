package com.mwalczak.player.data

import com.mwalczak.player.data.sync.SyncPayload
import kotlinx.coroutines.flow.first

/** Combining and matching favorites and lists that come from another device or person. */
object Libraries {
    /**
     * Points channels from someone else's setup at this account's catalog: same stream id if it
     * exists, otherwise the channel with the same name (another provider numbers its streams
     * differently). Channels that can't be found are kept as they are.
     */
    fun remap(library: Library, catalog: List<Channel>?): Library {
        if (catalog.isNullOrEmpty()) return library
        val byId = catalog.associateBy { it.streamId }
        val byName = catalog.groupBy { ChannelMatcher.searchKey(it.name.trim()) }.mapValues { it.value.first() }
        fun fix(ch: Channel) = byId[ch.streamId]?.takeIf { sameName(it, ch) }
            ?: byName[ChannelMatcher.searchKey(ch.name.trim())]
            ?: byId[ch.streamId]
            ?: ch
        return library.copy(
            favorites = library.favorites.map(::fix).distinctBy { it.streamId },
            lists = library.lists.map { list -> list.copy(channels = list.channels.map(::fix).distinctBy { it.streamId }) },
        )
    }

    /** Adds [imported] favorites, lists and pinned categories to [mine] without duplicates. */
    fun merge(mine: Library, imported: Library): Library {
        val favoriteIds = mine.favorites.map { it.streamId }.toSet()
        val lists = mine.lists.toMutableList()
        imported.lists.forEach { incoming ->
            val index = lists.indexOfFirst { it.name.equals(incoming.name, ignoreCase = true) }
            if (index >= 0) {
                val existing = lists[index]
                val ids = existing.channels.map { it.streamId }.toSet()
                lists[index] = existing.copy(channels = existing.channels + incoming.channels.filter { it.streamId !in ids })
            } else {
                // Fresh id so it can never collide with one of ours.
                lists += incoming.copy(id = java.util.UUID.randomUUID().toString())
            }
        }
        return mine.copy(
            favorites = mine.favorites + imported.favorites.filter { it.streamId !in favoriteIds },
            lists = lists,
            pinnedCategories = (mine.pinnedCategories + imported.pinnedCategories).distinct(),
            pinnedNames = imported.pinnedNames + mine.pinnedNames,
        )
    }

    private fun sameName(a: Channel, b: Channel) =
        ChannelMatcher.searchKey(a.name.trim()) == ChannelMatcher.searchKey(b.name.trim())
}

/** This device's favorites, lists and settings (without "recently watched"). */
suspend fun currentSyncPayload(settings: SettingsStore, library: LibraryStore): SyncPayload {
    val playback = settings.playback.first()
    val epg = settings.epg.first()
    return SyncPayload(
        library = library.library.value.copy(recent = emptyList()),
        format = playback.format.name,
        tryAlternates = playback.tryAlternates,
        nextInList = playback.nextInList,
        stallTimeoutSec = playback.stallTimeoutSec,
        epgEnabled = epg.enabled,
        epgUrl = epg.url,
        epgSources = epg.sources?.sorted(),
        epgWifiOnly = epg.wifiOnly,
    )
}

/** Replaces favorites, lists, pinned categories and settings with [payload]; keeps "recently watched". */
suspend fun applySyncPayload(settings: SettingsStore, library: LibraryStore, payload: SyncPayload) {
    library.replaceAll(payload.library.copy(recent = library.library.value.recent))
    settings.updatePlayback {
        PlaybackSettings(
            format = runCatching { StreamFormat.valueOf(payload.format) }.getOrDefault(it.format),
            tryAlternates = payload.tryAlternates,
            nextInList = payload.nextInList,
            stallTimeoutSec = payload.stallTimeoutSec,
        )
    }
    settings.updateEpg(EpgSettings(payload.epgEnabled, payload.epgUrl, payload.epgSources?.toSet(), payload.epgWifiOnly))
}
