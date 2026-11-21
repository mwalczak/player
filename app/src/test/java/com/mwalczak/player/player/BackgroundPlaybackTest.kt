package com.mwalczak.player.player

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.PlayerApp
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.ui.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundPlaybackTest {

    @Test
    fun `in the background nothing plays, and it comes back live`() {
        val app = ApplicationProvider.getApplicationContext<PlayerApp>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val channels = listOf(Channel(1, "TVN"), Channel(2, "TVP1"))
        val controller = PlayerController(app, app.container, Credentials("http://127.0.0.1:9", "u", "p"), PlaybackQueue("Test", channels, 0), scope)
        assertTrue(controller in app.container.players)

        controller.start()
        assertNotEquals(Player.STATE_IDLE, controller.player.playbackState)

        controller.toBackground()
        assertEquals(Player.STATE_IDLE, controller.player.playbackState)

        // Autoplay or failover switching channel in the background: the channel changes, nothing starts.
        controller.next()
        assertEquals("TVP1", controller.state.value.playing.name)
        assertEquals(Player.STATE_IDLE, controller.player.playbackState)

        controller.toForeground()
        assertNotEquals(Player.STATE_IDLE, controller.player.playbackState)
        assertTrue(controller.player.playWhenReady)

        controller.release()
        assertFalse(controller in app.container.players)
        scope.cancel()
    }
}
