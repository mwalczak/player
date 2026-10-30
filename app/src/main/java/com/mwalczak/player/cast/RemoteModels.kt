package com.mwalczak.player.cast

import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.data.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Another Bunny Player found on the local network (typically the TV). */
data class RemoteDevice(val name: String, val host: String, val port: Int) {
    val baseUrl: String
        get() = "http://${if (host.contains(':')) "[${host.replace("%", "%25")}]" else host}:$port"
}

/** Body of `POST /command`. [type] is one of play, next, previous, stop, login. */
@Serializable
data class RemoteRequest(
    val type: String,
    val title: String? = null,
    val channels: List<Channel> = emptyList(),
    val index: Int = 0,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
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
    val error: String? = null,
)

/** Commands for the UI of the receiving app, emitted on the main thread. */
sealed interface RemoteCommand {
    data class Play(val queue: PlaybackQueue) : RemoteCommand
    data object Next : RemoteCommand
    data object Previous : RemoteCommand
    data object Stop : RemoteCommand
}

/** What the local player is showing, published for remote status requests. */
data class NowPlaying(val channel: String, val title: String, val index: Int, val count: Int)

internal val remoteJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
