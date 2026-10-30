package com.mwalczak.player.data.sync

import android.content.Context
import com.mwalczak.player.CrashReports
import com.mwalczak.player.cast.SyncPayload
import com.mwalczak.player.data.Libraries
import com.mwalczak.player.data.CatalogRepository
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.Library
import com.mwalczak.player.data.LibraryStore
import com.mwalczak.player.data.SettingsStore
import com.mwalczak.player.data.applySyncPayload
import com.mwalczak.player.data.currentSyncPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** What Settings shows about syncing. */
data class SyncStatus(
    /** This person's share code, once their setup is saved online. */
    val code: String? = null,
    /** Share code of the setup this device follows, if any. */
    val following: String? = null,
    val busy: Boolean = false,
    val lastSyncedMillis: Long = 0,
    val error: String? = null,
)

/**
 * Keeps favorites, lists, pinned categories and settings on the sync server:
 * - every device signed in with the same login shares one setup (both ways), so a reinstall gets
 *   it all back after signing in;
 * - another person can follow a setup by its share code (it replaces theirs whenever it changes)
 *   or copy it once.
 * Nothing is uploaded from a device before it has fetched what's saved, so a fresh install never
 * overwrites the saved setup with an empty one.
 */
class SyncManager(
    context: Context,
    private val client: SyncClient,
    private val settings: SettingsStore,
    private val library: LibraryStore,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** True once this process has fetched the saved setup for the current login. */
    @Volatile private var linked = false
    private var cachedKey: Pair<Credentials, String>? = null
    private var loop: Job? = null

    init {
        // Upload changes made on this device (a moment after the last one).
        @OptIn(FlowPreview::class)
        scope.launch {
            combine(library.library, settings.playback, settings.epg) { _, _, _ -> }
                .debounce(1_500)
                .collect { if (linked && following() == null) syncNow() }
        }
        // Fetch right after signing in, e.g. on a fresh install.
        scope.launch {
            settings.credentials.map { it?.username }.distinctUntilChanged().drop(1).collect {
                linked = false
                if (it != null) syncNow()
            }
        }
    }

    /** While the app is open: sync now and every few minutes. */
    fun resume() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                syncNow()
                delay(5 * 60_000L)
            }
        }
    }

    fun pause() {
        loop?.cancel()
        loop = null
    }

    fun syncNowInBackground() {
        scope.launch { syncNow() }
    }

    suspend fun syncNow() = locked {
        val code = following()
        if (code != null) pullFollowed(code) else syncOwn()
    }

    /** Fetches a setup by its share code without using it, to show what it has. */
    suspend fun preview(code: String): Result<SyncProfile> = runCatching {
        when (val fetched = client.shared(code)) {
            is Fetched.Found -> fetched.profile
            else -> throw IllegalArgumentException("No setup with code ${formatShareCode(code.uppercase())}")
        }
    }

    /** Replaces this device's setup with [profile] and keeps getting its updates. */
    suspend fun follow(profile: SyncProfile) = locked {
        apply(remapped(profile.data))
        prefs.edit().putString(FOLLOW_CODE, profile.code).putInt(FOLLOW_REVISION, profile.revision).apply()
        _status.update { it.copy(following = profile.code) }
    }

    /** Replaces this device's setup with a copy of [profile]; from then on it's this person's own. */
    suspend fun copy(profile: SyncProfile) = locked {
        apply(remapped(profile.data))
        prefs.edit().remove(FOLLOW_CODE).remove(FOLLOW_REVISION).apply()
        _status.update { it.copy(following = null) }
        if (linked) syncOwn()
    }

    /** This login's saved versions, newest first. */
    suspend fun history(): Result<List<SyncVersion>> = runCatching {
        val creds = settings.currentCredentials() ?: throw IllegalStateException("Sign in first")
        client.versions(keyFor(creds))
    }

    /**
     * Puts an older version back: on this device now, and saved as the newest version so the
     * other devices get it too. The version it replaces stays in the history.
     */
    suspend fun restore(revision: Int) = locked {
        val creds = settings.currentCredentials() ?: return@locked
        val key = keyFor(creds)
        val data = client.version(key, revision).data ?: throw java.io.IOException("That version has no data")
        apply(data)
        var base = prefs.getInt(REVISION, 0).takeIf { it > 0 }
        repeat(2) {
            when (val result = client.save(key, base, data)) {
                is Saved.Ok -> {
                    record(result.code, result.revision)
                    linked = true
                    return@locked
                }
                // Saved elsewhere in the meantime: restoring wins anyway.
                is Saved.Conflict -> base = result.current.revision
            }
        }
    }

    /** Keeps what this device has now as its own setup, without further updates. */
    suspend fun stopFollowing() = locked {
        prefs.edit().remove(FOLLOW_CODE).remove(FOLLOW_REVISION).apply()
        _status.update { it.copy(following = null) }
        syncOwn()
    }

    private fun following(): String? = prefs.getString(FOLLOW_CODE, null)

    /** One sync step at a time; errors end up in [status] (and the crash report, for the odd ones). */
    private suspend fun locked(block: suspend () -> Unit) = mutex.withLock {
        _status.update { it.copy(busy = true) }
        val result = runCatching { block() }
        result.exceptionOrNull()?.let { if (it !is java.io.IOException) CrashReports.record(app, "Sync", it) }
        _status.update {
            it.copy(
                busy = false,
                error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                lastSyncedMillis = if (result.isSuccess) System.currentTimeMillis() else it.lastSyncedMillis,
            )
        }
        if (result.isSuccess) prefs.edit().putLong(LAST_SYNCED, System.currentTimeMillis()).apply()
    }

    private suspend fun syncOwn() {
        val creds = settings.currentCredentials() ?: return
        val key = keyFor(creds)
        val account = key.take(16)
        if (prefs.getString(ACCOUNT, null) != account) {
            // Another login on this device: its setup has nothing to do with the last one's.
            prefs.edit().putString(ACCOUNT, account).remove(REVISION).remove(HASH).remove(CODE).apply()
            _status.update { it.copy(code = null) }
        }
        val known = prefs.getInt(REVISION, 0).takeIf { it > 0 }
        val local = currentSyncPayload(settings, library)
        val localChanged = hash(local) != prefs.getString(HASH, null)

        when (val fetched = client.mine(key, since = known)) {
            Fetched.NotFound -> save(key, null, local)
            Fetched.Unchanged -> if (localChanged) save(key, known, local)
            is Fetched.Found -> {
                val saved = fetched.profile
                when {
                    // First time on this device (e.g. after a reinstall): take what's saved, plus anything already here.
                    known == null -> {
                        val merged = if (local.library.isEmpty()) saved.data else saved.data.withLibrary(Libraries.merge(saved.data.library, local.library))
                        apply(merged)
                        if (merged == saved.data) record(saved.code, saved.revision) else save(key, saved.revision, merged)
                    }
                    !localChanged -> {
                        apply(saved.data)
                        record(saved.code, saved.revision)
                    }
                    // Changed here and elsewhere: keep both.
                    else -> {
                        val merged = saved.data.withLibrary(Libraries.merge(saved.data.library, local.library))
                        apply(merged)
                        save(key, saved.revision, merged)
                    }
                }
            }
        }
        linked = true
    }

    private suspend fun save(key: String, base: Int?, payload: SyncPayload, retry: Boolean = true) {
        when (val result = client.save(key, base, payload)) {
            is Saved.Ok -> {
                prefs.edit().putInt(REVISION, result.revision).putString(HASH, hash(payload)).putString(CODE, result.code).apply()
                _status.update { it.copy(code = result.code) }
            }
            is Saved.Conflict -> {
                val current = result.current
                val merged = current.data.withLibrary(Libraries.merge(current.data.library, payload.library))
                apply(merged)
                if (retry) save(key, current.revision, merged, retry = false) else record(current.code, current.revision)
            }
        }
    }

    private suspend fun pullFollowed(code: String) {
        val known = prefs.getInt(FOLLOW_REVISION, 0).takeIf { it > 0 }
        when (val fetched = client.shared(code, since = known)) {
            Fetched.Unchanged -> Unit
            Fetched.NotFound -> throw java.io.IOException("The setup ${formatShareCode(code)} no longer exists")
            is Fetched.Found -> {
                apply(remapped(fetched.profile.data))
                prefs.edit().putInt(FOLLOW_REVISION, fetched.profile.revision).apply()
            }
        }
    }

    /** Marks what's on this device now as the saved revision. */
    private suspend fun record(code: String, revision: Int) {
        prefs.edit()
            .putInt(REVISION, revision)
            .putString(HASH, hash(currentSyncPayload(settings, library)))
            .putString(CODE, code)
            .apply()
        _status.update { it.copy(code = code) }
    }

    private suspend fun apply(payload: SyncPayload) {
        if (payload != currentSyncPayload(settings, library)) applySyncPayload(settings, library, payload)
    }

    /** Someone else's channels, pointed at this account's (their provider may number them differently). */
    private fun remapped(payload: SyncPayload) = payload.withLibrary(Libraries.remap(payload.library, catalog.catalog?.channels))

    private fun keyFor(creds: Credentials): String =
        cachedKey?.takeIf { it.first == creds }?.second ?: SyncKey.forLogin(creds).also { cachedKey = creds to it }

    private fun loadStatus() = SyncStatus(
        code = prefs.getString(CODE, null),
        following = prefs.getString(FOLLOW_CODE, null),
        lastSyncedMillis = prefs.getLong(LAST_SYNCED, 0),
    )

    private companion object {
        const val ACCOUNT = "account"
        const val REVISION = "revision"
        const val HASH = "hash"
        const val CODE = "code"
        const val FOLLOW_CODE = "follow_code"
        const val FOLLOW_REVISION = "follow_revision"
        const val LAST_SYNCED = "last_synced"
    }
}

private fun Library.isEmpty() = favorites.isEmpty() && lists.isEmpty() && pinnedCategories.isEmpty()

private fun SyncPayload.withLibrary(library: Library) = copy(library = library)

internal fun hash(payload: SyncPayload): String =
    MessageDigest.getInstance("SHA-256")
        .digest(syncJson.encodeToString(SyncPayload.serializer(), payload).toByteArray())
        .joinToString("") { "%02x".format(it) }
