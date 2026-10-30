package com.mwalczak.player

import android.app.Application
import android.content.Context
import com.mwalczak.player.data.CatalogRepository
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.LibraryStore
import com.mwalczak.player.data.SettingsStore
import com.mwalczak.player.data.XtreamClient
import com.mwalczak.player.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

const val USER_AGENT = "BunnyPlayer/0.1 (Linux; Android)"

/** What the player should play: a list of channels (category, favorites, a user list...) and where to start. */
data class PlaybackQueue(
    val title: String,
    val channels: List<Channel>,
    val startIndex: Int,
)

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val settings = SettingsStore(context)
    val xtream = XtreamClient(httpClient)
    val catalog = CatalogRepository(context, xtream)
    val library = LibraryStore(context, appScope)
    val updater = Updater(context, httpClient, appScope, BuildConfig.VERSION_CODE)

    /** Set right before navigating to the player screen. */
    var pendingQueue: PlaybackQueue? = null
}

class PlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as PlayerApp).container
