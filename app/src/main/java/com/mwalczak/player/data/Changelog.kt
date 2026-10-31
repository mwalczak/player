package com.mwalczak.player.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One release's "what's new" (assets/changelog.json; a higher [id] is newer). */
@Serializable
data class ChangelogEntry(val id: Int, val date: String, val title: String, val items: List<String>)

object Changelog {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every entry, newest first. */
    fun load(context: Context): List<ChangelogEntry> =
        runCatching {
            context.assets.open("changelog.json").bufferedReader().use { json.decodeFromString(ListSerializer(ChangelogEntry.serializer()), it.readText()) }
        }.getOrDefault(emptyList()).sortedByDescending { it.id }

    /**
     * What to show after an update: entries newer than [lastSeen]. Nothing on a fresh install;
     * the newest one when updating from a version that didn't keep track yet.
     */
    fun unseen(entries: List<ChangelogEntry>, lastSeen: Int?, freshInstall: Boolean): List<ChangelogEntry> = when {
        lastSeen != null -> entries.filter { it.id > lastSeen }
        freshInstall -> emptyList()
        else -> entries.take(1)
    }
}
