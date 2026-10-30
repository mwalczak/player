package com.mwalczak.player.data.epg

import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.ChannelMatcher
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.TimeZone

/** One programme in the guide. Times are epoch seconds. */
@Serializable
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String = "",
)

/**
 * Pure XMLTV helpers: name matching and a streaming parser over an [XmlPullParser]
 * (Android's in the app, kxml2 in unit tests).
 */
object Xmltv {

    private val separators = Regex("[ ._\\-]+")
    private val countryToken = Regex("\\bpl\\b")
    private val brackets = Regex("[()\\[\\]{}]")
    private val leadingCode = Regex("^\\W*([A-Za-z]{2,3})(?=\\s*[:|\\-)\\]▎]|\\s+\\()")
    private val countryAliases = mapOf("usa" to "us", "gb" to "uk", "aus" to "au")
    private val countries = setOf(
        "pl", "us", "uk", "ca", "nz", "au", "de", "fr", "it", "es", "nl", "be", "ie", "se", "no", "dk", "fi", "pt",
        "tr", "in", "ar", "br", "mx", "ru", "ua", "cz", "sk", "hu", "ro", "gr", "at", "ch", "hr", "rs", "bg", "al",
    )

    /** "20261030043000 +0100" -> epoch seconds; a missing offset means UTC. (No java.time: minSdk 24.) */
    fun parseTime(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 14 || !s.substring(0, 14).all(Char::isDigit)) return null
        fun num(from: Int, to: Int) = s.substring(from, to).toInt()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(num(0, 4), num(4, 6) - 1, num(6, 8), num(8, 10), num(10, 12), num(12, 14))
        }
        var epoch = cal.timeInMillis / 1000
        val offset = s.substring(14).trim()
        if (offset.length == 5 && (offset[0] == '+' || offset[0] == '-') && offset.substring(1).all(Char::isDigit)) {
            val seconds = offset.substring(1, 3).toInt() * 3600 + offset.substring(3, 5).toInt() * 60
            epoch -= if (offset[0] == '+') seconds else -seconds
        }
        return epoch
    }

    /**
     * Key used to match guide channels to ours: [ChannelMatcher.baseName] without the "pl"
     * country token and without spaces, so "PL: TVP 1 HD", "(PL) (MG) TVP1" and "TVP.1.pl" all
     * become "tvp1".
     */
    fun matchKey(name: String): String {
        val readable = name.replace(separators, " ")
        return ChannelMatcher.baseName(readable).replace(countryToken, " ").replace(" ", "")
    }

    /**
     * Guide-side key. Unlike provider names, brackets in guide names carry meaning
     * ("Discovery Channel (niem.)" is the German feed), so their content is kept.
     */
    fun guideKey(name: String): String = matchKey(name.replace(brackets, " "))

    /** Looser key for a second matching pass: also ignores filler words and a trailing "1". */
    fun looseKey(key: String): String = key
        .removePrefix("tv").removeSuffix("tv")
        .removeSuffix("channel").removePrefix("telewizja")
        .let { if (it.length > 2 && it.endsWith("1") && !it[it.length - 2].isDigit()) it.dropLast(1) else it }

    /** Country code a provider name starts with ("PL:", "(PL)", "US|", "USA-"), normalised. */
    fun countryOf(name: String): String? {
        val code = leadingCode.find(name)?.groupValues?.get(1)?.lowercase() ?: return null
        return countryAliases[code] ?: code.takeIf { it in countries }
    }

    /** Country of a guide channel id such as "TVP.Sport.pl". */
    fun guideCountry(id: String): String? =
        id.substringAfterLast('.', "").lowercase().takeIf { it.length == 2 && it.all(Char::isLetter) }?.let { countryAliases[it] ?: it }

    /**
     * Guide channel id -> our stream ids. Exact keys win over loose ones, each of our channels
     * takes its programmes from a single guide channel, and a channel marked with a country
     * ("US: CNN") only matches guide channels of that country.
     */
    fun match(guideChannels: Map<String, List<String>>, ours: List<Channel>): Map<String, List<Int>> {
        val exact = HashMap<String, MutableList<Channel>>()
        val loose = HashMap<String, MutableList<Channel>>()
        ours.forEach { ch ->
            val key = matchKey(ch.name)
            if (key.isEmpty()) return@forEach
            exact.getOrPut(key) { mutableListOf() }.add(ch)
            loose.getOrPut(looseKey(key)) { mutableListOf() }.add(ch)
        }
        val ourCountry = HashMap<Int, String?>().apply { ours.forEach { put(it.streamId, countryOf(it.name)) } }
        val assigned = HashMap<Int, Pair<String, Int>>() // stream id -> (guide id, tier)
        for (tier in 0..1) {
            guideChannels.forEach { (id, names) ->
                val country = guideCountry(id)
                val keys = (names + id).map(::guideKey).filter { it.isNotEmpty() }.distinct()
                val candidates = if (tier == 0) keys.flatMap { exact[it].orEmpty() }
                else keys.flatMap { loose[looseKey(it)].orEmpty() }
                candidates.forEach { ch ->
                    val theirs = ourCountry[ch.streamId]
                    if (theirs != null && country != null && theirs != country) return@forEach
                    if (ch.streamId !in assigned) assigned[ch.streamId] = id to tier
                }
            }
        }
        return assigned.entries.groupBy({ it.value.first }, { it.key })
    }

    /**
     * Streams through an XMLTV document and returns programmes per stream id for our [channels],
     * keeping only those overlapping [fromSec]..[toSec]. Channels must precede programmes, as
     * in every XMLTV file in practice.
     */
    fun parse(parser: XmlPullParser, channels: List<Channel>, fromSec: Long, toSec: Long): Map<Int, List<Programme>> {
        val guideChannels = LinkedHashMap<String, MutableList<String>>()
        var mapping: Map<String, List<Int>>? = null
        val out = HashMap<Int, MutableList<Programme>>()

        var currentChannel: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title: String? = null
        var desc: String? = null
        var text: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = null
                    when (parser.name) {
                        "channel" -> currentChannel = parser.getAttributeValue(null, "id")?.also {
                            guideChannels.getOrPut(it) { mutableListOf() }
                        }
                        "programme" -> {
                            if (mapping == null) mapping = match(guideChannels, channels)
                            val ch = parser.getAttributeValue(null, "channel")
                            val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
                            val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
                            if (ch != null && start != null && stop != null && stop > fromSec && start < toSec && mapping!!.containsKey(ch)) {
                                progChannel = ch
                                progStart = start
                                progStop = stop
                                title = null
                                desc = null
                            } else {
                                progChannel = null
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> text = parser.text
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "display-name" -> currentChannel?.let { id -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { guideChannels[id]?.add(it) } }
                        "channel" -> currentChannel = null
                        "title" -> if (progChannel != null && title == null) title = text?.trim()
                        "desc" -> if (progChannel != null && desc == null) desc = text?.trim()
                        "programme" -> progChannel?.let { ch ->
                            val p = Programme(progStart, progStop, title.orEmpty().ifEmpty { "—" }, desc.orEmpty())
                            mapping!![ch]!!.forEach { id -> out.getOrPut(id) { mutableListOf() }.add(p) }
                            progChannel = null
                        }
                    }
                    text = null
                }
            }
            event = parser.next()
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
    }
}

/** The programme on air at [nowSec], if any. */
fun List<Programme>.onAir(nowSec: Long): Programme? = firstOrNull { it.start <= nowSec && nowSec < it.stop }

/** The programme on air and the ones after it. */
fun List<Programme>.fromNow(nowSec: Long): List<Programme> = filter { it.stop > nowSec }
