package com.mwalczak.player

import android.app.Application
import android.content.Context
import com.mwalczak.player.cast.RemoteControl
import com.mwalczak.player.data.CatalogRepository
import com.mwalczak.player.data.CatalogState
import com.mwalczak.player.data.epg.EpgRepository
import com.mwalczak.player.data.sync.SyncClient
import com.mwalczak.player.data.sync.SyncManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /** Start with autoplay on: move to the next channel every few minutes (see Settings). */
    val autoplay: Boolean = false,
)

class AppContainer(context: Context) {
    /** Background work never takes the app down: failures are recorded for the crash report instead. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> CrashReports.record(context, "Background error (app kept running)", error) },
    )

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
    val epg = EpgRepository(context, httpClient, xtream, appScope)
    val sync = SyncManager(context, SyncClient(httpClient), settings, library, catalog, appScope)

    init {
        // The provider renumbers its categories when one is added or removed: keep pins (and
        // favorites and lists) on the same things by name whenever the catalog or library changes.
        appScope.launch {
            catalog.state.map { (it as? CatalogState.Ready)?.catalog }.filterNotNull().distinctUntilChanged()
                .collect { library.fitTo(it, channelsToo = true) }
        }
        appScope.launch {
            library.library.collect { catalog.catalog?.let { c -> library.fitTo(c, channelsToo = false) } }
        }
    }

    /** Current time in epoch seconds, ticking every 30 s, for "now" lines and progress bars. */
    val clock: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(30_000)
        }
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis() / 1000)

    /** Downloads and builds the guide. Only on request (Settings), never automatically for now. */
    suspend fun refreshEpg() {
        val creds = settings.currentCredentials() ?: return
        val current = catalog.catalog ?: return
        epg.refresh(creds, current, settings.epg.first(), guidePriority(), force = true)
    }

    /** Favorites and list channels get the provider's guide first when no XMLTV guide covers them. */
    private fun guidePriority(): List<Channel> = library.library.value.let { lib -> lib.favorites + lib.lists.flatMap { it.channels } }

    /** When the browsing preview last let go of its stream (it hands over to full screen). */
    @Volatile var previewStoppedAtMillis: Long = 0

    /** When full screen last let go of its stream (the preview behind it takes over). */
    @Volatile var playerStoppedAtMillis: Long = 0

    /** The channel full screen played last, so the list behind it can put the remote back on it. */
    @Volatile var lastPlayedStreamId: Int? = null

    /** Set right before navigating to the player screen. */
    var pendingQueue: PlaybackQueue? = null
}

class PlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as PlayerApp).container
