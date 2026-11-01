package com.mwalczak.player.data.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GuideUpdatesTest {

    private val warsaw = TimeZone.getTimeZone("Europe/Warsaw")

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance(warsaw).apply {
        set(2026, Calendar.NOVEMBER, 1, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `the update runs at 4-30 the coming night`() {
        val minutes = GuideUpdates.millisUntilNight(at(21, 0), Calendar.getInstance(warsaw)) / 60_000
        assertEquals(7 * 60 + 30, minutes)
    }

    @Test
    fun `just after 4-30 it waits for the next night`() {
        val minutes = GuideUpdates.millisUntilNight(at(4, 31), Calendar.getInstance(warsaw)) / 60_000
        assertEquals(24 * 60 - 1, minutes)
    }

    @Test
    fun `early in the morning it's the same night`() {
        val minutes = GuideUpdates.millisUntilNight(at(1, 0), Calendar.getInstance(warsaw)) / 60_000
        assertEquals(3 * 60 + 30, minutes)
    }
}
