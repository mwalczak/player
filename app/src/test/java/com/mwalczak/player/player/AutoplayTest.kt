package com.mwalczak.player.player

import com.mwalczak.player.ui.player.Autoplay
import com.mwalczak.player.ui.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoplayTest {

    @Test
    fun `counts down while a channel plays, then asks for the next`() {
        var autoplay: Autoplay? = Autoplay(60)
        var seconds = 0
        while (autoplay != null) {
            autoplay = autoplay.tick(PlaybackStatus.Playing)
            seconds++
        }
        assertEquals(60, seconds)
    }

    @Test
    fun `waits while paused or loading`() {
        assertEquals(Autoplay(42, paused = true), Autoplay(42, paused = true).tick(PlaybackStatus.Playing))
        assertEquals(Autoplay(42), Autoplay(42).tick(PlaybackStatus.Loading))
    }

    @Test
    fun `a channel that fails is left after a few seconds`() {
        val failed = PlaybackStatus.Failed("offline")
        assertEquals(Autoplay(2), Autoplay(60).tick(failed))
        assertEquals(Autoplay(1), Autoplay(2).tick(failed))
        assertNull(Autoplay(1).tick(failed))
    }
}
