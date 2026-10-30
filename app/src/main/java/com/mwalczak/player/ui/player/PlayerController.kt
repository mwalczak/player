package com.mwalczak.player.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.mwalczak.player.AppContainer
import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.USER_AGENT
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.ChannelMatcher
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.EpgEntry
import com.mwalczak.player.data.epg.fromNow
import com.mwalczak.player.data.PlaybackSettings
import com.mwalczak.player.data.ServerUrls
import com.mwalczak.player.data.StreamFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PlaybackStatus {
    data object Loading : PlaybackStatus
    data object Playing : PlaybackStatus
    data class Failed(val message: String) : PlaybackStatus
}

data class PlayerUiState(
    val queueTitle: String,
    val channels: List<Channel>,
    /** Position in [channels] the user selected. */
    val index: Int,
    /** What is actually on screen: the selected channel or, after a failover, an alternate feed of it. */
    val playing: Channel,
    val status: PlaybackStatus = PlaybackStatus.Loading,
    val notice: String? = null,
    val epg: List<EpgEntry> = emptyList(),
    /** The channel watched before the current one, for the "last channel" button. */
    val previousIndex: Int? = null,
)

/**
 * Owns the ExoPlayer instance and the zapping / failover logic:
 * a failing channel is retried once, then (depending on settings) replaced by another feed of the
 * same channel from the catalog, then by the next channel in the list being watched.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val container: AppContainer,
    private val creds: Credentials,
    queue: PlaybackQueue,
    private val scope: CoroutineScope,
    /** False for the small preview while browsing: it shouldn't fill "Recently watched". */
    private val recordRecent: Boolean = true,
) {
    var settings: PlaybackSettings = PlaybackSettings()

    val player: ExoPlayer = buildPlayer(context)

    private val _state = MutableStateFlow(
        PlayerUiState(
            queue.title,
            queue.channels,
            queue.startIndex,
            queue.channels[queue.startIndex],
            // Coming back to the player: "last channel" starts as the channel watched before,
            // if it is in the list being watched.
            previousIndex = container.library.library.value.recent
                .firstOrNull { it.streamId != queue.channels[queue.startIndex].streamId }
                ?.let { prev -> queue.channels.indexOfFirst { it.streamId == prev.streamId } }
                ?.takeIf { it >= 0 },
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val failed = mutableSetOf<Int>()
    private val retried = mutableSetOf<Int>()
    private var hops = 0
    private var stallJob: Job? = null
    private var retryJob: Job? = null
    private var limitJob: Job? = null
    private var epgJob: Job? = null
    private var noticeJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> armStallWatchdog()
                Player.STATE_READY -> onPlaying()
                Player.STATE_ENDED -> onFailure("the stream ended")
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            onFailure(describe(error), refused = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        }
    }

    init {
        player.addListener(listener)
    }

    fun start() = select(_state.value.index)

    fun next() = select(_state.value.index + 1)

    fun previous() = select(_state.value.index - 1)

    /** Jumps back to the channel watched before this one (and pressing again comes back). */
    fun last() {
        _state.value.previousIndex?.let(::select)
    }

    /** User-initiated channel change: resets all failover bookkeeping. */
    fun select(index: Int) {
        val channels = _state.value.channels
        val wrapped = ((index % channels.size) + channels.size) % channels.size
        failed.clear()
        retried.clear()
        hops = 0
        _state.update { it.copy(index = wrapped, previousIndex = if (wrapped != it.index) it.index else it.previousIndex) }
        play(channels[wrapped], notice = null)
    }

    /** Re-tunes to the live edge, e.g. when returning from the background. */
    fun resumeLive() {
        if (_state.value.status is PlaybackStatus.Failed) return
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }

    fun release() {
        stallJob?.cancel()
        retryJob?.cancel()
        limitJob?.cancel()
        epgJob?.cancel()
        noticeJob?.cancel()
        player.removeListener(listener)
        player.release()
    }

    private fun play(channel: Channel, notice: String?) {
        stallJob?.cancel()
        retryJob?.cancel()
        limitJob?.cancel()
        _state.update { it.copy(playing = channel, status = PlaybackStatus.Loading, epg = emptyList()) }
        showNotice(notice)

        val format = settings.format
        val item = MediaItem.Builder()
            .setUri(ServerUrls.liveStream(creds, channel.streamId, format))
            .setMimeType(if (format == StreamFormat.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .setMediaId(channel.streamId.toString())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        loadEpg(channel)
    }

    private fun onPlaying() {
        stallJob?.cancel()
        val current = _state.value
        if (recordRecent && current.status != PlaybackStatus.Playing) container.library.addRecent(current.playing)
        failed.clear()
        retried.clear()
        hops = 0
        _state.update { it.copy(status = PlaybackStatus.Playing) }
    }

    private fun armStallWatchdog() {
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            delay(settings.stallTimeoutSec * 1000L)
            if (player.playbackState == Player.STATE_BUFFERING) onFailure("it kept buffering")
        }
    }

    private fun onFailure(reason: String, refused: Boolean = false) {
        stallJob?.cancel()
        if (retryJob?.isActive == true || limitJob?.isActive == true) return
        val s = _state.value
        val current = s.playing

        // 1. One quick retry of the same stream: servers often drop a connection or two.
        if (retried.add(current.streamId)) {
            retryJob = scope.launch {
                delay(1_000)
                player.stop()
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }
            return
        }

        // 2. Refused by the server: if every connection on the account is taken, someone else is
        //    watching. Say so instead of hopping through channels that would all be refused too.
        if (refused) {
            limitJob = scope.launch {
                val usage = connectionUsage()
                if (usage != null && usage.second > 0 && usage.first >= usage.second) {
                    fail(
                        "Stopped: all ${usage.second} stream${if (usage.second == 1) "" else "s"} on your account " +
                            "${if (usage.second == 1) "is" else "are"} in use – someone else started watching. " +
                            "Retry takes it back (their stream will likely stop).",
                    )
                } else {
                    failOver(reason)
                }
            }
            return
        }
        failOver(reason)
    }

    /** Active and maximum connections of the account, or null if the server didn't answer quickly. */
    suspend fun connectionUsage(): Pair<Int, Int>? = withTimeoutOrNull(5_000) {
        runCatching { container.xtream.authenticate(creds) }.getOrNull()
            ?.let { (it.activeConnections ?: 0) to (it.maxConnections ?: 0) }
    }

    private fun failOver(reason: String) {
        val s = _state.value
        val current = s.playing
        failed += current.streamId
        val wanted = s.channels[s.index]
        if (++hops > MAX_HOPS) {
            fail("Nothing played after $MAX_HOPS attempts. Check your connection.")
            return
        }

        // 3. Another feed of the channel the user asked for.
        if (settings.tryAlternates) {
            val catalog = container.catalog.catalog?.channels.orEmpty()
            val alternate = ChannelMatcher.alternatesFor(wanted, catalog).firstOrNull { it.streamId !in failed }
            if (alternate != null) {
                play(alternate, "${wanted.name} is off – playing ${alternate.name}")
                return
            }
        }

        // 4. The next channel in the list.
        if (settings.nextInList && s.channels.size > 1) {
            val nextIndex = (1 until s.channels.size)
                .map { (s.index + it) % s.channels.size }
                .firstOrNull { s.channels[it].streamId !in failed }
            if (nextIndex != null) {
                val next = s.channels[nextIndex]
                _state.update { it.copy(index = nextIndex) }
                play(next, "${wanted.name} is off – switched to ${next.name}")
                return
            }
        }

        fail("${wanted.name} is not available right now: $reason.")
    }

    private fun fail(message: String) {
        player.stop()
        _state.update { it.copy(status = PlaybackStatus.Failed(message)) }
    }

    private fun showNotice(notice: String?) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = notice) }
        if (notice != null) {
            noticeJob = scope.launch {
                delay(6_000)
                _state.update { it.copy(notice = null) }
            }
        }
    }

    private fun loadEpg(channel: Channel) {
        epgJob?.cancel()
        epgJob = scope.launch {
            val now = System.currentTimeMillis() / 1000
            // The guide (external XMLTV + provider fill) first; ask the provider directly otherwise.
            val guide = container.epg.programmesFor(channel.streamId).fromNow(now)
            val entries = if (guide.isNotEmpty()) {
                guide.map { EpgEntry(it.title, it.desc, it.start, it.stop) }
            } else {
                runCatching { container.xtream.shortEpg(creds, channel.streamId, limit = 12) }
                    .getOrDefault(emptyList())
                    .filter { it.endEpochSec > now }
            }
            if (_state.value.playing.streamId == channel.streamId) _state.update { it.copy(epg = entries) }
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "the server refused the stream (too many connections or channel offline)"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "network connection failed"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "the stream data is broken"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "this device cannot decode the stream"
        else -> error.errorCodeName.lowercase().replace('_', ' ')
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(container.httpClient).setUserAgent(USER_AGENT)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val loadControl = DefaultLoadControl.Builder()
            // Start playing quickly after a zap; live streams don't benefit from a huge buffer.
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private companion object {
        const val MAX_HOPS = 8
    }
}
