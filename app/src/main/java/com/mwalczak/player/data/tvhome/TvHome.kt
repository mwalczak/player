package com.mwalczak.player.data.tvhome

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.mwalczak.player.R
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.reminders.ReminderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The TV's home screen: a "Bunny Player – Favorites" row of the favorite channels (Android TV
 * home) and the channel watched last in "Continue watching" (also Google TV). Each opens the app
 * on that channel (bunnyplayer://play/<id>). Launchers that don't support it just ignore it.
 *
 * (RestrictedApi: tvprovider 1.0.0's public builders inherit their setters from a restricted base
 * class, which lint wrongly reports.)
 */
@SuppressLint("RestrictedApi")
class TvHome(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tv_home", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Only TVs (Android 8+) have these home screen rows. */
    val supported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Shows [favorites] (in their order) as the app's row on the home screen. */
    fun publishFavorites(favorites: List<Channel>) {
        if (!supported) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val helper = PreviewChannelHelper(app)
                    val channelId = rowId(helper)
                    app.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
                    favorites.take(MAX_PROGRAMS).forEachIndexed { i, channel ->
                        helper.publishPreviewProgram(
                            PreviewProgram.Builder()
                                .setChannelId(channelId)
                                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                                .setTitle(channel.name)
                                .setPosterArtUri(channel.icon?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
                                .setIntentUri(ReminderStore.playUri(channel.streamId))
                                .setInternalProviderId(channel.streamId.toString())
                                .setLive(true)
                                .setWeight(MAX_PROGRAMS - i)
                                .build(),
                        )
                    }
                }
            }
        }
    }

    /** Puts [channel] in "Continue watching" (replacing the one before). */
    fun watched(channel: Channel) {
        if (!supported) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    prefs.getLong(WATCH_NEXT, -1).takeIf { it >= 0 }?.let { old ->
                        app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(old), null, null)
                    }
                    val id = PreviewChannelHelper(app).publishWatchNextProgram(
                        WatchNextProgram.Builder()
                            .setType(TvContractCompat.WatchNextPrograms.TYPE_CHANNEL)
                            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                            .setTitle(channel.name)
                            .setPosterArtUri(channel.icon?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
                            .setIntentUri(ReminderStore.playUri(channel.streamId))
                            .setInternalProviderId(channel.streamId.toString())
                            .setLive(true)
                            .build(),
                    )
                    prefs.edit().putLong(WATCH_NEXT, id).apply()
                }
            }
        }
    }

    /** The app's row, made (as the default one, shown without asking) the first time. */
    private fun rowId(helper: PreviewChannelHelper): Long {
        val saved = prefs.getLong(ROW, -1)
        if (saved >= 0 && runCatching { helper.getPreviewChannel(saved) }.getOrNull() != null) return saved
        val logo = ContextCompat.getDrawable(app, R.mipmap.ic_launcher)!!.toBitmap(160, 160)
        val id = helper.publishDefaultChannel(
            PreviewChannel.Builder()
                .setDisplayName("Bunny Player – Favorites")
                .setAppLinkIntentUri(Uri.parse("bunnyplayer://home"))
                .setLogo(logo)
                .build(),
        )
        prefs.edit().putLong(ROW, id).apply()
        return id
    }

    private companion object {
        const val ROW = "row"
        const val WATCH_NEXT = "watch_next"
        const val MAX_PROGRAMS = 30
    }
}
