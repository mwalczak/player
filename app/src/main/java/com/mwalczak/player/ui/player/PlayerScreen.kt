@file:OptIn(UnstableApi::class)

package com.mwalczak.player.ui.player

import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Autorenew
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.EpgEntry
import com.mwalczak.player.data.PlaybackSettings
import com.mwalczak.player.ui.common.ChannelLogo
import com.mwalczak.player.ui.common.CastButton
import com.mwalczak.player.cast.NowPlaying
import com.mwalczak.player.cast.RemoteCommand
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

@Composable
fun PlayerScreen(container: AppContainer, onExit: () -> Unit) {
    val queue = remember { container.pendingQueue }
    val creds by produceState<Credentials?>(null) { value = container.settings.currentCredentials() }
    if (queue == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    val c = creds ?: return Box(Modifier.fillMaxSize().background(Color.Black))
    PlayerContent(container, c, queue, onExit)
}

@Composable
private fun PlayerContent(
    container: AppContainer,
    creds: Credentials,
    queue: com.mwalczak.player.PlaybackQueue,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { PlayerController(context, container, creds, queue, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by container.settings.playback.collectAsStateWithLifecycle(null)
    val library by container.library.library.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf(false) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var interaction by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    // Start once the settings are known, and keep the controller in sync if they change.
    var started by remember { mutableStateOf(false) }
    // Before the first channel: warn when every stream on the account is already in use, because
    // starting would most likely cut off whoever is watching. Skipped when a phone hands its
    // stream over to this TV (the "other viewer" is that phone).
    var busyWarning by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Also skipped right after the browsing preview handed its stream over: that "other viewer" is us.
    var cleared by remember {
        mutableStateOf(queue.fromRemote || System.currentTimeMillis() - container.previewStoppedAtMillis < 15_000)
    }
    LaunchedEffect(Unit) {
        if (!cleared) {
            val usage = controller.connectionUsage()
            if (usage != null && usage.second > 0 && usage.first >= usage.second) busyWarning = usage else cleared = true
        }
    }

    LaunchedEffect(settings, cleared) {
        val s = settings ?: return@LaunchedEffect
        controller.settings = s
        if (!started && cleared) {
            started = true
            controller.autoplaySeconds = container.settings.autoplaySeconds.first()
            controller.start()
            if (queue.autoplay) controller.startAutoplay()
        }
    }
    // A new autoplay time in Settings applies from the next channel.
    val autoplaySeconds by container.settings.autoplaySeconds.collectAsStateWithLifecycle(DEFAULT_AUTOPLAY_SECONDS)
    LaunchedEffect(autoplaySeconds) { if (started) controller.autoplaySeconds = autoplaySeconds }

    busyWarning?.let { (active, max) ->
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text("All streams are in use") },
            text = {
                Text(
                    "Your account allows $max stream${if (max == 1) "" else "s"} at a time and $active " +
                        "${if (active == 1) "is" else "are"} in use right now – probably someone else is watching. " +
                        "If you start, their stream will most likely stop (or yours won't start).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    busyWarning = null
                    cleared = true
                }) { Text("Watch anyway") }
            },
            dismissButton = { TextButton(onClick = onExit) { Text("Cancel") } },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            container.remote.nowPlaying.value = null
            controller.release()
        }
    }

    // Remote control from a phone, when this device is the TV.
    LaunchedEffect(Unit) {
        container.remote.commands.collect { command ->
            when (command) {
                RemoteCommand.Next -> controller.next()
                RemoteCommand.Previous -> controller.previous()
                RemoteCommand.Last -> controller.last()
                RemoteCommand.Stop -> onExit()
                is RemoteCommand.Play -> Unit // handled by navigation
            }
        }
    }
    LaunchedEffect(state.playing, state.index) {
        container.remote.nowPlaying.value = NowPlaying(state.playing.name, state.queueTitle, state.index, state.channels.size, state.playing.streamId)
    }
    FullscreenLandscape()

    // Pause in the background; jump back to the live edge on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.player.pause()
                Lifecycle.Event.ON_START -> if (started) controller.resumeLive()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Remote: which overlay row to put focus on (top controls / bottom zapping buttons), and whether
    // focus is on the picture itself or inside the overlay.
    var focusTopTick by remember { mutableIntStateOf(0) }
    var focusBottomTick by remember { mutableIntStateOf(0) }
    var pictureFocused by remember { mutableStateOf(true) }
    var overlayHasFocus by remember { mutableStateOf(false) }
    var addingToList by remember { mutableStateOf(false) }

    // Auto-hide the overlay after a few seconds without interaction (not while a remote is on its buttons).
    LaunchedEffect(overlay, interaction, state.status, overlayHasFocus, addingToList) {
        if (overlay && !panel && !schedule && !overlayHasFocus && !addingToList && state.status == PlaybackStatus.Playing) {
            delay(5_000)
            overlay = false
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BackHandler(enabled = panel || schedule || overlay) {
        when {
            panel -> panel = false
            schedule -> schedule = false
            else -> overlay = false
        }
    }
    // When the overlay goes away, give the remote back to the picture.
    LaunchedEffect(overlay) { if (!overlay && !panel && !schedule) runCatching { focus.requestFocus() } }

    fun poke() {
        overlay = true
        interaction++
    }

    val isFavorite = library.favorites.any { it.streamId == state.playing.streamId }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onFocusChanged { pictureFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Play / pause keys run autoplay wherever the remote is (picture, controls, channel list).
                when (event.key) {
                    Key.MediaPlay -> { controller.playAutoplay(); poke(); return@onKeyEvent true }
                    Key.MediaPause -> { controller.pauseAutoplay(); poke(); return@onKeyEvent true }
                    Key.MediaPlayPause -> { controller.toggleAutoplay(); poke(); return@onKeyEvent true }
                }
                if (panel || schedule) return@onKeyEvent false
                // Keys pressed while a button in the overlay is focused belong to that button / focus moves.
                if (!pictureFocused) return@onKeyEvent false
                // Guide button on TV remotes: today's schedule for this channel.
                if (event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_GUIDE) {
                    schedule = true
                    return@onKeyEvent true
                }
                // Red / bookmark button on TV remotes: favorite the channel being watched.
                if (event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_PROG_RED || event.key == Key.Bookmark) {
                    container.library.toggleFavorite(state.playing)
                    poke()
                    return@onKeyEvent true
                }
                when (event.key) {
                    // ◀ ▶ zap, like CH- / CH+.
                    Key.ChannelUp, Key.DirectionRight -> { controller.next(); poke(); true }
                    Key.ChannelDown, Key.DirectionLeft -> { controller.previous(); poke(); true }
                    // ▲ goes to the controls at the top (favorite, add to list, …), ▼ to the zapping buttons.
                    Key.DirectionUp -> { poke(); focusTopTick++; true }
                    Key.DirectionDown -> { poke(); focusBottomTick++; true }
                    Key.LastChannel -> { controller.last(); poke(); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { panel = true; true }
                    Key.Info, Key.Menu -> { poke(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (overlay) overlay = false else poke() })
            }
            .pointerInput(Unit) {
                // Swipe up / down anywhere to zap, like flipping channels on a TV.
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > 120f) {
                            if (total < 0) controller.next() else controller.previous()
                            poke()
                        }
                    },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = controller.player
                }
            },
            update = { it.resizeMode = resizeModes[resizeIndex].first },
            modifier = Modifier.fillMaxSize(),
        )

        when (val status = state.status) {
            PlaybackStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is PlaybackStatus.Failed -> FailurePanel(
                message = status.message,
                onRetry = { controller.select(state.index) },
                onNext = { controller.next() },
                modifier = Modifier.align(Alignment.Center),
            )
            PlaybackStatus.Playing -> Unit
        }

        AnimatedVisibility(
            visible = state.notice != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 72.dp),
        ) {
            Text(
                state.notice.orEmpty(),
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xCC5A2A12), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        state.autoplay?.let { autoplay ->
            AutoplayBadge(autoplay, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 72.dp, end = 16.dp))
        }

        AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Overlay(
                focusTopTick = focusTopTick,
                focusBottomTick = focusBottomTick,
                onFocusInside = { overlayHasFocus = it },
                onAddToList = { addingToList = true },
                state = state,
                isFavorite = isFavorite,
                resizeLabel = resizeModes[resizeIndex].second,
                onBack = onExit,
                onFavorite = { container.library.toggleFavorite(state.playing); poke() },
                onResize = { resizeIndex = (resizeIndex + 1) % resizeModes.size; poke() },
                onList = { panel = true },
                onSchedule = { schedule = true },
                onAutoplay = { controller.toggleAutoplay(); poke() },
                onStopAutoplay = { controller.stopAutoplay(); poke() },
                castButton = {
                    // Hand the channel being watched over to the TV and stop playing here.
                    CastButton(container, tint = Color.White) { device ->
                        scope.launch {
                            val queue = com.mwalczak.player.PlaybackQueue(
                                state.queueTitle,
                                state.channels,
                                state.index,
                                autoplay = state.autoplay?.paused == false,
                            )
                            container.remote.play(device, queue)
                                .onSuccess { onExit() }
                                .onFailure {
                                    android.widget.Toast.makeText(context, "${device.name}: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                },
                onPrevious = { controller.previous(); poke() },
                onNext = { controller.next(); poke() },
                onLast = { controller.last(); poke() },
            )
        }

        AnimatedVisibility(
            visible = panel,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ChannelPanel(state) { index ->
                controller.select(index)
                panel = false
                poke()
            }
        }

        AnimatedVisibility(
            visible = schedule,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SchedulePanel(state)
        }
    }

    if (addingToList) {
        com.mwalczak.player.ui.common.AddToListDialog(container, state.playing, onDismiss = { addingToList = false })
    }

    // Give key focus back to the player when the panel closes, so the remote keeps zapping.
    LaunchedEffect(panel, schedule) { if (!panel && !schedule) runCatching { focus.requestFocus() } }
}

@Composable
private fun Overlay(
    focusTopTick: Int,
    focusBottomTick: Int,
    onFocusInside: (Boolean) -> Unit,
    onAddToList: () -> Unit,
    state: PlayerUiState,
    isFavorite: Boolean,
    resizeLabel: String,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onResize: () -> Unit,
    onList: () -> Unit,
    onSchedule: () -> Unit,
    onAutoplay: () -> Unit,
    onStopAutoplay: () -> Unit,
    castButton: @Composable () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
) {
    val scrim = Color(0xB3000000)
    val topFocus = remember { FocusRequester() }
    val bottomFocus = remember { FocusRequester() }
    LaunchedEffect(focusTopTick) {
        if (focusTopTick > 0) {
            delay(80) // let the overlay appear first
            runCatching { topFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusBottomTick) {
        if (focusBottomTick > 0) {
            delay(80)
            runCatching { bottomFocus.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().onFocusChanged { onFocusInside(it.hasFocus) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
                .safeDrawingPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            ChannelLogo(state.playing.icon, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    state.playing.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${state.index + 1} / ${state.channels.size} · ${state.queueTitle}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            // Autoplay: start / pause / resume, and turn off while it's on.
            val autoplay = state.autoplay
            IconButton(onClick = onAutoplay, enabled = state.channels.size > 1, modifier = Modifier.focusRing()) {
                Icon(
                    when {
                        autoplay == null -> Icons.Default.Autorenew
                        autoplay.paused -> Icons.Default.PlayArrow
                        else -> Icons.Default.Pause
                    },
                    when {
                        autoplay == null -> "Start autoplay"
                        autoplay.paused -> "Resume autoplay"
                        else -> "Pause autoplay"
                    },
                    tint = if (autoplay != null) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            if (autoplay != null) {
                IconButton(onClick = onStopAutoplay, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.Close, "Turn autoplay off", tint = Color.White)
                }
            }
            IconButton(onClick = onFavorite, modifier = Modifier.focusRequester(topFocus).focusRing()) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = onAddToList, modifier = Modifier.focusRing()) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to list", tint = Color.White)
            }
            IconButton(onClick = onResize, modifier = Modifier.focusRing()) { Icon(Icons.Default.AspectRatio, "Aspect: $resizeLabel", tint = Color.White) }
            Box(Modifier.focusRing()) { castButton() }
            IconButton(onClick = onSchedule, modifier = Modifier.focusRing()) { Icon(Icons.Default.Schedule, "Today's schedule", tint = Color.White) }
            IconButton(onClick = onList, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.List, "Channel list", tint = Color.White) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.epg.firstOrNull()?.let { NowPlaying(it, state.epg.getOrNull(1)) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = state.channels.size > 1, modifier = Modifier.focusRequester(bottomFocus).focusRing()) {
                    Icon(Icons.Default.SkipPrevious, "Previous channel", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(32.dp))
                IconButton(onClick = onNext, enabled = state.channels.size > 1, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.SkipNext, "Next channel", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(32.dp))
                // "Last channel": flip between this channel and the one watched before.
                val last = state.previousIndex?.let { state.channels.getOrNull(it) }
                TextButton(onClick = onLast, enabled = last != null, modifier = Modifier.focusRing()) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = if (last != null) Color.White else Color.White.copy(alpha = 0.38f),
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        last?.name ?: "Last channel",
                        color = if (last != null) Color.White else Color.White.copy(alpha = 0.38f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp).widthIn(max = 180.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(now: EpgEntry, next: EpgEntry?) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val nowSec = System.currentTimeMillis() / 1000
    val progress = ((nowSec - now.startEpochSec).toFloat() / (now.endEpochSec - now.startEpochSec).coerceAtLeast(1))
        .coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Now: ${now.title}",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.5f))
        if (next != null) {
            Text(
                "${time.format(Date(next.startEpochSec * 1000))}  Next: ${next.title}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A white ring around whichever overlay button the TV remote is on. */
@Composable
private fun Modifier.focusRing(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(50))
}

@Composable
private fun SchedulePanel(state: PlayerUiState) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val now = System.currentTimeMillis() / 1000
    val first = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .width(360.dp)
            .background(Color(0xE6101418))
            .safeDrawingPadding(),
    ) {
        item {
            Text(
                state.playing.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.epg.isEmpty()) {
            item {
                Text(
                    "No guide data for this channel.",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        itemsIndexed(state.epg) { index, entry ->
            val onAir = entry.startEpochSec <= now && now < entry.endEpochSec
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    .background(if (onAir) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .focusable()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "${time.format(Date(entry.startEpochSec * 1000))} – ${time.format(Date(entry.endEpochSec * 1000))}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(entry.title, color = Color.White, fontWeight = if (onAir) FontWeight.SemiBold else FontWeight.Normal)
                if (onAir && entry.description.isNotBlank()) {
                    Text(
                        entry.description,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    // Give the remote something to scroll from.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ChannelPanel(state: PlayerUiState, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 3).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0xE6101418))
            .safeDrawingPadding(),
    ) {
        item {
            Text(
                state.queueTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp),
            )
        }
        itemsIndexed(state.channels, key = { _, c -> c.streamId }) { index, channel ->
            val selected = index == state.index
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (selected) Modifier.focusRequester(first) else Modifier)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${index + 1}", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.width(32.dp))
                ChannelLogo(channel.icon, Modifier.size(36.dp))
                Text(channel.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // Put D-pad focus on the current channel so a remote can scroll from there.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun FailurePanel(message: String, onRetry: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(24.dp)
            .background(Color(0xCC000000), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = onNext) { Text("Next channel") }
        }
    }
}

/** Landscape + hidden system bars while the player is on screen; restored when leaving. */
@Composable
private fun FullscreenLandscape() {
    val view = LocalView.current
    val activity = LocalActivity.current ?: return
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, view)
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
        }
    }
}

/** Shown while autoplay is on: when the next channel comes, or that it's paused. */
@Composable
private fun AutoplayBadge(autoplay: Autoplay, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (autoplay.paused) Icons.Default.Pause else Icons.Default.Autorenew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        val left = autoplay.secondsLeft
        Text(
            if (autoplay.paused) "Autoplay paused" else "Next in ${left / 60}:${"%02d".format(left % 60)}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
