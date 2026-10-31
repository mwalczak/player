package com.mwalczak.player.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.mwalczak.player.data.epg.DEFAULT_EPG_URL

data class EpgSettings(
    val enabled: Boolean = true,
    /** Extra XMLTV guide on top of the built-in ones; empty for none. */
    val url: String = "",
    /** Chosen built-in guides ([com.mwalczak.player.data.epg.EpgSources] ids); null = pick automatically. */
    val sources: Set<String>? = null,
    /** Only download guide files on an unmetered connection (Wi-Fi / Ethernet). */
    val wifiOnly: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val server = stringPreferencesKey("server")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password")
        val format = stringPreferencesKey("stream_format")
        val tryAlternates = booleanPreferencesKey("try_alternates")
        val nextInList = booleanPreferencesKey("next_in_list")
        val stallTimeout = intPreferencesKey("stall_timeout_sec")
        val epgUrl = stringPreferencesKey("epg_url")
        val epgEnabled = booleanPreferencesKey("epg_enabled")
        val epgSources = stringPreferencesKey("epg_sources")
        val epgWifiOnly = booleanPreferencesKey("epg_wifi_only")
        val gridColumns = intPreferencesKey("grid_columns")
        val preview = booleanPreferencesKey("preview_while_browsing")
        val guideSource = stringPreferencesKey("guide_source")
        val autoplaySeconds = intPreferencesKey("autoplay_seconds")
    }

    /** Live preview next to channel lists on wide screens (TV, tablet, landscape phone). */
    val previewWhileBrowsing: Flow<Boolean> = context.dataStore.data.map { it[Keys.preview] ?: true }

    suspend fun setPreviewWhileBrowsing(enabled: Boolean) {
        context.dataStore.edit { it[Keys.preview] = enabled }
    }

    /** How long autoplay stays on each channel. */
    val autoplaySeconds: Flow<Int> = context.dataStore.data.map { it[Keys.autoplaySeconds] ?: 60 }

    suspend fun setAutoplaySeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.autoplaySeconds] = seconds.coerceIn(10, 3600) }
    }

    /** The category last picked in the TV guide on this device (Favorites, a category id, all). */
    val guideSource: Flow<String?> = context.dataStore.data.map { it[Keys.guideSource] }

    suspend fun setGuideSource(id: String) {
        context.dataStore.edit { it[Keys.guideSource] = id }
    }

    /** Columns in channel lists on this device; 0 = as many as fit. Not copied to other devices. */
    val gridColumns: Flow<Int> = context.dataStore.data.map { it[Keys.gridColumns] ?: 0 }

    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { it[Keys.gridColumns] = columns.coerceIn(0, 6) }
    }

    /** External XMLTV guide; on by default with the Polish epgshare01 guide. */
    val epg: Flow<EpgSettings> = context.dataStore.data.map { p ->
        EpgSettings(
            enabled = p[Keys.epgEnabled] ?: true,
            // The old single-guide default (Polish file) is now one of the automatic sources.
            url = p[Keys.epgUrl]?.takeIf { it != DEFAULT_EPG_URL }.orEmpty(),
            sources = p[Keys.epgSources]?.takeIf { it != AUTO }?.split(',')?.filter { it.isNotBlank() }?.toSet(),
            wifiOnly = p[Keys.epgWifiOnly] ?: true,
        )
    }

    suspend fun updateEpg(settings: EpgSettings) {
        context.dataStore.edit {
            it[Keys.epgEnabled] = settings.enabled
            it[Keys.epgUrl] = settings.url.trim()
            it[Keys.epgSources] = settings.sources?.sorted()?.joinToString(",") ?: AUTO
            it[Keys.epgWifiOnly] = settings.wifiOnly
        }
    }

    private companion object {
        const val AUTO = "auto"
    }

    val credentials: Flow<Credentials?> = context.dataStore.data.map { it.credentials() }

    val playback: Flow<PlaybackSettings> = context.dataStore.data.map { p ->
        val defaults = PlaybackSettings()
        PlaybackSettings(
            format = p[Keys.format]?.let { runCatching { StreamFormat.valueOf(it) }.getOrNull() } ?: defaults.format,
            tryAlternates = p[Keys.tryAlternates] ?: defaults.tryAlternates,
            nextInList = p[Keys.nextInList] ?: defaults.nextInList,
            stallTimeoutSec = p[Keys.stallTimeout] ?: defaults.stallTimeoutSec,
        )
    }

    suspend fun currentCredentials(): Credentials? = credentials.first()

    suspend fun saveCredentials(creds: Credentials) {
        context.dataStore.edit {
            it[Keys.server] = creds.server
            it[Keys.username] = creds.username
            it[Keys.password] = creds.password
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(Keys.username)
            it.remove(Keys.password)
            // The server is kept so signing back in only needs the username and password.
        }
    }

    suspend fun lastServer(): String? = context.dataStore.data.first()[Keys.server]

    suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) {
        val updated = transform(playback.first())
        context.dataStore.edit {
            it[Keys.format] = updated.format.name
            it[Keys.tryAlternates] = updated.tryAlternates
            it[Keys.nextInList] = updated.nextInList
            it[Keys.stallTimeout] = updated.stallTimeoutSec
        }
    }

    private fun Preferences.credentials(): Credentials? {
        val server = this[Keys.server] ?: return null
        val user = this[Keys.username] ?: return null
        val pass = this[Keys.password] ?: return null
        return Credentials(server, user, pass)
    }
}
