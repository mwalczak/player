package com.mwalczak.player.data

import java.text.Collator

/**
 * Channels A–Z by name: case and accents don't matter, and numbers count as numbers
 * ("Sky Sports 2" before "Sky Sports 10").
 */
fun List<Channel>.sortedByName(): List<Channel> {
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    val chunks = Regex("\\d+|\\D+")
    val byName = Comparator<String> { a, b ->
        val x = chunks.findAll(a).map { it.value }.toList()
        val y = chunks.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val result = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                collator.compare(p, q)
            }
            if (result != 0) return@Comparator result
        }
        x.size.compareTo(y.size)
    }
    return sortedWith(compareBy(byName) { it.name.trim() })
}

/** These channels in the order of [order] (by stream id); any not in it keep their place at the end. */
fun List<Channel>.inOrderOf(order: List<Channel>): List<Channel> {
    val position = order.withIndex().associate { (i, c) -> c.streamId to i }
    return sortedBy { position[it.streamId] ?: Int.MAX_VALUE }
}
