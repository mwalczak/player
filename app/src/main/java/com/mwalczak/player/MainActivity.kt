package com.mwalczak.player

import android.content.Intent
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        // ⏮ / ⏭ on the small window come back as broadcasts to this app only.
        ContextCompat.registerReceiver(
            this,
            pipButtons,
            IntentFilter().apply {
                addAction(PIP_PREVIOUS)
                addAction(PIP_NEXT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Android 12+: going home from the player shrinks it into the small window by itself (smoothly).
        if (pipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                appContainer.inPlayer.collect { runCatching { setPictureInPictureParams(pipParams(autoEnter = it)) } }
            }
        }
        setContent {
            PlayerTheme {
                AppNavigation(appContainer)
            }
        }
    }

    private val pipButtons = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PIP_PREVIOUS -> appContainer.pictureInPictureCommands.tryEmit("previous")
                PIP_NEXT -> appContainer.pictureInPictureCommands.tryEmit("next")
            }
        }
    }

    /** Phones and tablets (not TVs) that can show an app in a small floating window. */
    private val pipSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        fun button(action: String, icon: Int, label: String) = RemoteAction(
            Icon.createWithResource(this, icon),
            label,
            label,
            PendingIntent.getBroadcast(this, action.hashCode(), Intent(action).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(button(PIP_PREVIOUS, R.drawable.ic_pip_previous, "Previous channel"), button(PIP_NEXT, R.drawable.ic_pip_next, "Next channel")))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(autoEnter)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    /** Going home while watching (Android 8–11): keep watching in the small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && pipSupported && appContainer.inPlayer.value) {
            runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        appContainer.inPictureInPicture.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipButtons) }
        super.onDestroy()
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

private const val PIP_PREVIOUS = "com.mwalczak.player.PIP_PREVIOUS"
private const val PIP_NEXT = "com.mwalczak.player.PIP_NEXT"
