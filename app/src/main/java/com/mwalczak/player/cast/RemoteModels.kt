package com.mwalczak.player.cast

import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Library
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Another Bunny Player found on the local network (typically the TV). */
data class RemoteDevice(val name: String, val host: String, val port: Int) {
    val baseUrl: String
        get() = "http://${if (host.contains(':')) "[${host.replace("%", "%25")}]" else host}:$port"
}

/** Everything "Copy to TV" sends: favorites, lists, pinned categories and settings. */
@Serializable
data class SyncPayload(
    val library: Library,
    val format: String,
    val tryAlternates: Boolean,
    val nextInList: Boolean,
    val stallTimeoutSec: Int,
    val epgEnabled: Boolean,
    val epgUrl: String,
    /** Chosen guide sources; null = automatic. Optional so older apps' payloads still read. */
    val epgSources: List<String>? = null,
    val epgWifiOnly: Boolean = true,
)

/** Body of `POST /command`. [type] is one of play, next, previous, last, stop, login, sync. */
@Serializable
data class RemoteRequest(
    val type: String,
    val title: String? = null,
    val channels: List<Channel> = emptyList(),
    val index: Int = 0,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val sync: SyncPayload? = null,
)

/** What a receiver reports from `GET /status` and after every command. */
@Serializable
data class RemoteStatus(
    val device: String,
    val signedIn: Boolean,
    val playing: String? = null,
    val title: String? = null,
    val index: Int = -1,
    val count: Int = 0,
    /** Number of favorites on the receiver; -1 when it runs an older version that doesn't say. */
    val favorites: Int = -1,
    val error: String? = null,
)

/** Commands for the UI of the receiving app, emitted on the main thread. */
sealed interface RemoteCommand {
    data class Play(val queue: PlaybackQueue) : RemoteCommand
    data object Next : RemoteCommand
    data object Previous : RemoteCommand
    data object Last : RemoteCommand
    data object Stop : RemoteCommand
    /** Favorites and settings were just copied here from another device. */
    data object Synced : RemoteCommand
}

/** What the local player is showing, published for remote status requests. */
data class NowPlaying(val channel: String, val title: String, val index: Int, val count: Int)

internal val remoteJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
