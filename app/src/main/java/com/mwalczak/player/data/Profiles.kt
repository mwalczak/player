package com.mwalczak.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One profile: a login with its own favorites, lists, pins, hidden categories, recently watched,
 * sync, channel list and TV guide. Display and playback settings are shared by the device.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val server: String = "",
    val username: String = "",
    val password: String = "",
) {
    val credentials: Credentials? get() = if (username.isBlank()) null else Credentials(server, username, password)

    /** This profile's copy of a file ("library.json" → "library-<id>.json"); the first keeps the old names. */
    fun scoped(base: String): String = scopedName(id, base)
}

/** The first profile: what the app had before there were profiles. */
const val FIRST_PROFILE = "main"

internal fun scopedName(profileId: String, base: String): String {
    if (profileId == FIRST_PROFILE) return base
    val dot = base.lastIndexOf('.')
    return if (dot < 0) "$base-$profileId" else base.substring(0, dot) + "-" + profileId + base.substring(dot)
}

@Serializable
data class Profiles(val profiles: List<Profile>, val active: String) {
    val current: Profile get() = profiles.firstOrNull { it.id == active } ?: profiles.first()
}

/** The profiles on this device and which one is in use (profiles.json). */
class ProfileStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "profiles.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<Profiles> = _profiles.asStateFlow()

    val current: Profile get() = _profiles.value.current

    fun get(id: String): Profile? = _profiles.value.profiles.firstOrNull { it.id == id }

    /** A new profile for [creds] (not switched to yet). */
    fun add(name: String, creds: Credentials): Profile {
        val profile = Profile(UUID.randomUUID().toString().take(8), name.trim().ifBlank { creds.username }, creds.server, creds.username, creds.password)
        change { it.copy(profiles = it.profiles + profile) }
        return profile
    }

    fun setActive(id: String) = change { if (it.profiles.any { p -> p.id == id }) it.copy(active = id) else it }

    fun rename(id: String, name: String) = change { state ->
        state.copy(profiles = state.profiles.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it })
    }

    /** Removes a profile that isn't in use. */
    fun remove(id: String) = change { state ->
        if (id == state.active) state else state.copy(profiles = state.profiles.filterNot { it.id == id })
    }

    /** The login in use changed (signing in, out or another server): it belongs to the current profile. */
    fun updateCurrentLogin(creds: Credentials?) = change { state ->
        state.copy(
            profiles = state.profiles.map { p ->
                when {
                    p.id != state.active -> p
                    creds == null -> p
                    // The first profile is named after its login until it's renamed.
                    else -> p.copy(
                        name = if (p.name == DEFAULT_NAME) creds.username else p.name,
                        server = creds.server,
                        username = creds.username,
                        password = creds.password,
                    )
                }
            },
        )
    }

    private fun change(transform: (Profiles) -> Profiles) {
        val before = _profiles.value
        _profiles.update(transform)
        if (_profiles.value != before) runCatching { file.writeText(json.encodeToString(Profiles.serializer(), _profiles.value)) }
    }

    private fun read(): Profiles =
        runCatching { json.decodeFromString(Profiles.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.profiles.isNotEmpty() }
            ?: Profiles(listOf(Profile(FIRST_PROFILE, DEFAULT_NAME)), FIRST_PROFILE)

    private companion object {
        const val DEFAULT_NAME = "Profile 1"
    }
}
