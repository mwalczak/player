package com.mwalczak.player.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Xtream Codes panels are loose with types: ids arrive as numbers or strings, booleans as "0"/"1",
 * missing values as null or "". Parsing via JsonElement keeps us tolerant of all of that.
 */
object XtreamParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class AuthException(message: String) : Exception(message)

    fun parseAccount(body: String): AccountInfo {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AuthException("The server did not return a valid Xtream Codes response. Check the server URL.")
        val user = root["user_info"] as? JsonObject
            ?: throw AuthException("Invalid username or password.")
        if (user.int("auth") != 1) throw AuthException("Invalid username or password.")
        val status = user.str("status") ?: "Unknown"
        if (!status.equals("Active", ignoreCase = true)) {
            throw AuthException("Account status: $status")
        }
        return AccountInfo(
            status = status,
            expiresAtEpochSec = user.long("exp_date"),
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            isTrial = user.int("is_trial") == 1,
        )
    }

    fun parseCategories(body: String): List<Category> =
        array(body).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id = id, name = o.str("category_name")?.trim().orEmpty().ifEmpty { "Category $id" })
        }

    fun parseLiveStreams(body: String): List<Channel> =
        array(body).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("stream_id") ?: return@mapNotNull null
            Channel(
                streamId = id,
                name = o.str("name")?.trim().orEmpty().ifEmpty { "Channel $id" },
                icon = o.str("stream_icon")?.takeIf { it.startsWith("http") },
                categoryId = o.str("category_id"),
                epgChannelId = o.str("epg_channel_id"),
                num = o.int("num") ?: 0,
            )
        }

    /** Short EPG titles/descriptions are base64 encoded; [decodeBase64] is injected to stay Android-free. */
    fun parseShortEpg(body: String, decodeBase64: (String) -> String): List<EpgEntry> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val listings = root["epg_listings"] as? JsonArray ?: return emptyList()
        return listings.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val end = o.long("stop_timestamp") ?: o.long("end_timestamp") ?: return@mapNotNull null
            EpgEntry(
                title = o.str("title")?.let { runCatching { decodeBase64(it) }.getOrDefault(it) }.orEmpty(),
                description = o.str("description")?.let { runCatching { decodeBase64(it) }.getOrDefault(it) }.orEmpty(),
                startEpochSec = start,
                endEpochSec = end,
            )
        }
    }

    private fun array(body: String): JsonArray =
        runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { JsonArray(emptyList()) }

    private fun JsonObject.prim(key: String): JsonPrimitive? {
        val el: JsonElement = this[key] ?: return null
        if (el is JsonNull) return null
        return el as? JsonPrimitive
    }

    private fun JsonObject.str(key: String): String? = prim(key)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}
