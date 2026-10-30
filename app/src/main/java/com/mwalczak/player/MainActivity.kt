package com.mwalczak.player

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
        setContent {
            PlayerTheme {
                AppNavigation(appContainer)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Advertise this device and look for others (phone -> TV remote) while the app is open.
        appContainer.remote.start()
    }

    override fun onStop() {
        appContainer.remote.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        appContainer.updater.check()
    }
}
