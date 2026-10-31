package com.mwalczak.player.player

import androidx.media3.common.C
import com.mwalczak.player.ui.player.SilenceMeter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SilenceMeterTest {

    private var now = 0L
    private val meter = SilenceMeter { now }

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    /** [seconds] of sound, one buffer every 100 ms. */
    private fun feed(seconds: Int, vararg samples: Int) {
        repeat(seconds * 10) {
            now += 100
            meter.handleBuffer(pcm16(*samples))
        }
    }

    @Test
    fun `five seconds of digital silence counts as silent`() {
        meter.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        meter.reset()
        feed(4, 0, 1, -2, 0)
        assertFalse(meter.silentFor(5_000))
        feed(2, 0, 3, -1, 0)
        assertTrue(meter.silentFor(5_000))
    }

    @Test
    fun `quiet programme sound is not silence`() {
        meter.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        meter.reset()
        feed(10, 0, 120, -300, 80)
        assertFalse(meter.silentFor(5_000))
    }

    @Test
    fun `nothing measured (sound passed straight to a soundbar) is never silent`() {
        meter.reset()
        now += 20_000
        assertFalse(meter.silentFor(5_000))
    }
}
