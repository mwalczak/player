package com.mwalczak.player.data

import kotlinx.serialization.Serializable

data class Credentials(
    val server: String,
    val username: String,
    val password: String,
) {
    /** Stable key used to namespace per-account caches. */
    val accountKey: String get() = "${server.lowercase()}|$username".hashCode().toUInt().toString(16)
}

data class AccountInfo(
    val status: String,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
)

data class Category(
    val id: String,
    val name: String,
)

/**
 * A live channel. Serializable because favorites and user lists keep a snapshot of it,
 * so they render instantly without the full catalog being loaded.
 */
@Serializable
data class Channel(
    val streamId: Int,
    val name: String,
    val icon: String? = null,
    val categoryId: String? = null,
    val epgChannelId: String? = null,
    val num: Int = 0,
)

data class EpgEntry(
    val title: String,
    val description: String,
    val startEpochSec: Long,
    val endEpochSec: Long,
)

enum class StreamFormat(val extension: String, val label: String) {
    TS("ts", "MPEG-TS (.ts)"),
    HLS("m3u8", "HLS (.m3u8)"),
}

data class PlaybackSettings(
    val format: StreamFormat = StreamFormat.TS,
    /** When a channel fails, first try other feeds of the same channel (e.g. "ESPN HD" -> "ESPN FHD"). */
    val tryAlternates: Boolean = true,
    /** If no alternate works, move on to the next channel in the list being watched. */
    val nextInList: Boolean = true,
    /** How long the player may stay buffering before the channel is considered off. */
    val stallTimeoutSec: Int = 15,
)
