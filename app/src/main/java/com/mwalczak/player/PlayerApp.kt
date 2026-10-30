package com.mwalczak.player

import android.app.Application
import android.content.Context
import com.mwalczak.player.cast.RemoteControl
import com.mwalczak.player.data.CatalogRepository
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.epg.EpgRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    /** Sent from another device (phone -> TV); that device just handed its stream over. */
    val fromRemote: Boolean = false,
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
    val remote = RemoteControl(context, httpClient, settings, catalog, library, appScope)
    val epg = EpgRepository(context, httpClient, xtream)

    /** Current time in epoch seconds, ticking every 30 s, for "now" lines and progress bars. */
    val clock: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(30_000)
        }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis() / 1000)

    init {
        // Keep the guide in step with the channel list and the guide settings.
        appScope.launch {
            combine(catalog.state, settings.epg, settings.credentials) { c, e, creds ->
                val channels = (c as? CatalogState.Ready)?.takeIf { !it.refreshing }?.catalog?.channels
                Triple(channels, e, creds)
            }
                .distinctUntilChanged()
                .collectLatest { (channels, epgSettings, creds) ->
                    if (channels == null || creds == null) return@collectLatest
                    epg.refresh(creds, channels, epgSettings.url, epgSettings.enabled)
                }
        }
    }

    suspend fun refreshEpg() {
        val creds = settings.currentCredentials() ?: return
        val channels = catalog.catalog?.channels ?: return
        val e = settings.epg.first()
        epg.refresh(creds, channels, e.url, e.enabled, force = true)
    }

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
