package com.mwalczak.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mwalczak.player.ui.AppNavigation
import com.mwalczak.player.ui.theme.PlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openFrom(intent)
        setContent {
            PlayerTheme {
                AppNavigation(appContainer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** bunnyplayer://play/<stream id>: a reminder's "Watch" or a channel on the TV home screen. */
    private fun openFrom(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "bunnyplayer" && data.host == "play") {
            data.lastPathSegment?.toIntOrNull()?.let { appContainer.openChannel.value = it }
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.reminders.foreground = true
        // Advertise this device and look for others (phone -> TV remote) while the app is open.
        appContainer.remote.start()
        // Fetch favorites and lists saved from other devices, and keep them in sync while open.
        appContainer.sync.resume()
    }

    override fun onStop() {
        appContainer.reminders.foreground = false
        appContainer.remote.stop()
        appContainer.sync.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        appContainer.updater.check()
    }
}
