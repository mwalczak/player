package com.mwalczak.player.data.sync

import com.mwalczak.player.data.Library
import kotlinx.serialization.Serializable

/** What sync saves and shares: favorites, lists, pinned categories and playback/guide settings. */
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
