package com.mwalczak.player.player

import android.app.Application
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.mwalczak.player.ui.player.describeTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrackNamesTest {

    private fun audio(language: String?, channels: Int = 2, mime: String = MimeTypes.AUDIO_AAC, label: String? = null) =
        Format.Builder().setLanguage(language).setChannelCount(channels).setSampleMimeType(mime).setLabel(label).build()

    @Test
    fun `sound tracks read as language, channels and Dolby`() {
        assertEquals("Polish · stereo", describeTrack(audio("pol"), 1))
        assertEquals("English · 5.1 · Dolby", describeTrack(audio("en", 6, MimeTypes.AUDIO_AC3), 2))
        assertEquals("German · Audio description · stereo", describeTrack(audio("deu", label = "Audio description"), 3))
    }

    @Test
    fun `tracks without a language are numbered`() {
        assertEquals("Track 2 · stereo", describeTrack(audio(null), 2))
        assertEquals("Track 1 · mono", describeTrack(audio("und", 1), 1))
    }

    @Test
    fun `subtitles read as their language`() {
        val dvb = Format.Builder().setLanguage("pl").setSampleMimeType(MimeTypes.APPLICATION_DVBSUBS).build()
        assertEquals("Polish", describeTrack(dvb, 1))
    }
}
