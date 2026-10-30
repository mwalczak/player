package com.mwalczak.player.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Whether a newer build is on GitHub, and how installing it is going. */
data class UpdateState(
    /** Build number of the running app (1 for local builds, which never update). */
    val currentBuild: Int,
    /** Build number of the newest release, or 0 if unknown. */
    val latestBuild: Int = 0,
    /** 0..1 while downloading, otherwise -1. */
    val progress: Float = -1f,
    /** Shown instead of the usual "update available" text, e.g. after an error. */
    val message: String? = null,
) {
    val available: Boolean get() = currentBuild > 1 && latestBuild > currentBuild
    val busy: Boolean get() = progress >= 0f
}

data class Release(val build: Int, val apkUrl: String, val size: Long)

object Releases {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/mwalczak/player/releases/latest"
    const val APK_NAME = "bunny-player.apk"

    private val json = Json { ignoreUnknownKeys = true }

    /** "build-12" -> 12, anything else -> 0. */
    fun buildFromTag(tag: String): Int =
        tag.removePrefix("build-").takeIf { tag.startsWith("build-") }?.toIntOrNull() ?: 0

    /** Reads a GitHub "latest release" response; null if it has no APK we can install. */
    fun parseLatest(body: String): Release? {
        val release = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val build = buildFromTag(release["tag_name"]?.jsonPrimitive?.content.orEmpty())
        if (build == 0) return null
        val asset = release["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it.string("name") == APK_NAME }
            ?: return null
        val url = asset.string("browser_download_url") ?: return null
        return Release(build, url, asset["size"]?.jsonPrimitive?.longOrNull ?: -1L)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
}
