package com.mwalczak.player.data

import java.text.Normalizer

/**
 * Finds other feeds of the same channel, so playback can fail over when one is off.
 * IPTV catalogs typically carry "US: ESPN HD", "US| ESPN FHD", "ESPN ᴴᴰ (Backup)"... which all
 * reduce to the same base name "espn".
 */
object ChannelMatcher {

    private val countryPrefix = Regex("^\\s*[^\\p{L}\\p{N}\\s]*\\s*[\\p{L}\\p{N}]{1,5}\\s*[:|▎]+\\s*")
    private val bracketed = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val qualityTokens = Regex(
        "\\b(hd|fhd|uhd|sd|hq|lq|4k|8k|hevc|h264|h265|x264|x265|1080p?|720p?|2160p?|576p?|50fps|60fps|fps|backup|bkp|bk|alt|raw|direct|vip)\\b",
    )
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    private val diacritics = Regex("\\p{Mn}+")

    fun baseName(name: String): String {
        // NFKD turns styled characters such as "ᴴᴰ" or "ＨＤ" into plain "HD" and splits accents off.
        var s = fold(name)
        s = countryPrefix.replace(s, "")
        s = bracketed.replace(s, " ")
        s = s.replace(nonAlnum, " ")
        s = qualityTokens.replace(s, " ")
        s = s.trim().replace(Regex("\\s+"), " ")
        return s.ifEmpty { name.trim().lowercase() }
    }

    /** Other channels in [catalog] that look like the same channel as [channel], best candidates first. */
    fun alternatesFor(channel: Channel, catalog: List<Channel>): List<Channel> {
        val key = baseName(channel.name)
        return catalog
            .filter { it.streamId != channel.streamId && baseName(it.name) == key }
            .sortedWith(compareByDescending<Channel> { it.categoryId == channel.categoryId }.thenBy { it.num })
    }

    /** Case- and accent-insensitive "contains" used by search. */
    fun searchKey(text: String): String = fold(text)

    /** Lowercase, accents removed, and letters NFKD can't split transliterated ("Fabuła" -> "fabula"). */
    private fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(diacritics, "").lowercase()
        if (decomposed.none { it in transliterations }) return decomposed
        return buildString { decomposed.forEach { c -> append(transliterations[c] ?: c) } }
    }

    private val transliterations = mapOf('ł' to "l", 'ø' to "o", 'đ' to "d", 'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ı' to "i")
}
