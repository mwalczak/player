package com.mwalczak.player.data.epg

import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.ChannelMatcher

/** A programme found by [GuideSearch], with the channel it is on. */
data class GuideHit(val channel: Channel, val programme: Programme)

/** Searches programme titles across the whole guide. */
object GuideSearch {

    /**
     * Programmes whose title contains every word of [query] (any order, accents and case ignored)
     * and that haven't ended yet. What's on now comes first, then by start time.
     */
    fun search(
        programmes: Map<Int, List<Programme>>,
        channels: List<Channel>,
        query: String,
        nowSec: Long,
        limit: Int = 300,
    ): List<GuideHit> {
        val terms = ChannelMatcher.searchKey(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val byId = channels.associateBy { it.streamId }
        return programmes.asSequence()
            .mapNotNull { (id, list) -> byId[id]?.let { it to list } }
            .flatMap { (channel, list) ->
                list.asSequence()
                    .filter { it.stop > nowSec }
                    .filter { p -> ChannelMatcher.searchKey(p.title).let { title -> terms.all { title.contains(it) } } }
                    .map { GuideHit(channel, it) }
            }
            .sortedWith(compareBy<GuideHit> { it.programme.start > nowSec }.thenBy { it.programme.start }.thenBy { it.channel.name })
            .take(limit)
            .toList()
    }
}
