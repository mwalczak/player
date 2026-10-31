package com.mwalczak.player.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Listens to the decoded sound on its way to the speakers (without changing it), to tell a
 * channel that shows a still "temporarily unavailable" screen: those are silent, real channels
 * practically never are for seconds on end.
 *
 * Only decoded (PCM) sound can be measured: when the device passes Dolby / DTS straight through
 * to a soundbar, nothing arrives here and [silentFor] never says "silent".
 */
@OptIn(UnstableApi::class)
class SilenceMeter(private val clock: () -> Long = SystemClock::elapsedRealtime) : TeeAudioProcessor.AudioBufferSink {
    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var lastPcm = 0L
    @Volatile private var lastSound = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val loud = when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var found = false
                var i = data.position()
                while (i + 1 < data.limit()) {
                    if (abs(data.getShort(i).toInt()) > PCM16_THRESHOLD) {
                        found = true
                        break
                    }
                    i += 2
                }
                found
            }
            C.ENCODING_PCM_FLOAT -> {
                var found = false
                var i = data.position()
                while (i + 3 < data.limit()) {
                    if (abs(data.getFloat(i)) > FLOAT_THRESHOLD) {
                        found = true
                        break
                    }
                    i += 4
                }
                found
            }
            else -> return
        }
        val now = clock()
        lastPcm = now
        if (loud) lastSound = now
    }

    /** A new channel: nothing heard from it yet. */
    fun reset() {
        lastPcm = 0
        lastSound = clock()
    }

    /** Sound has been coming through for the last [millis] ms, all of it (next to) silent. */
    fun silentFor(millis: Long): Boolean {
        val now = clock()
        return lastPcm > now - 1_500 && now - lastSound >= millis
    }

    private companion object {
        // About -58 dB: far below any programme, above the dither of a digitally silent stream.
        const val PCM16_THRESHOLD = 40
        const val FLOAT_THRESHOLD = 0.0012f
    }
}
