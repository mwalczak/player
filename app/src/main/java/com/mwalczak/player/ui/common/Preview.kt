package com.mwalczak.player.ui.common

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mwalczak.player.AppContainer
import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.PlaybackSettings
import com.mwalczak.player.ui.player.PlaybackStatus
import com.mwalczak.player.ui.player.PlayerController
import kotlinx.coroutines.delay

/** Which channel the browsing preview shows, shared by a list and its preview pane. */
@Stable
class PreviewState {
    /** Index in the list being browsed; null until a channel is focused or tapped. */
    var index by mutableStateOf<Int?>(null)
    internal var controller: PlayerController? = null

    /** Lets go of the stream before full screen starts (the account may allow only one). */
    fun stop(container: AppContainer) {
        controller?.let {
            controller = null
            it.release()
            container.previewStoppedAtMillis = System.currentTimeMillis()
        }
    }
}

/** True where a preview fits next to the list: TV, tablet, landscape phone, unless turned off or casting. */
@Composable
fun previewEnabled(container: AppContainer): Boolean {
    val enabled by container.settings.previewWhileBrowsing.collectAsStateWithLifecycle(true)
    val target by container.remote.target.collectAsStateWithLifecycle()
    return enabled && target == null && LocalConfiguration.current.screenWidthDp >= 700
}

/**
 * The live picture of the channel being browsed, with its name and what's on. Starts a moment
 * after the selection settles, so scrolling through a list doesn't open a stream per channel.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPane(
    container: AppContainer,
    channels: List<Channel>,
    state: PreviewState,
    onFullscreen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val creds by produceState<Credentials?>(null) { value = container.settings.currentCredentials() }
    val c = creds
    Column(
        modifier.fillMaxSize().padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (c == null || channels.isEmpty()) return@Column
        PreviewContent(container, c, channels, state, onFullscreen)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PreviewContent(
    container: AppContainer,
    creds: Credentials,
    channels: List<Channel>,
    state: PreviewState,
    onFullscreen: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback by container.settings.playback.collectAsStateWithLifecycle(PlaybackSettings())
    val controller = remember(channels) {
        PlayerController(context, container, creds, PlaybackQueue("Preview", channels, 0), scope, recordRecent = false)
            .also { state.controller = it }
    }
    DisposableEffect(controller) {
        onDispose {
            if (state.controller === controller) state.stop(container) else controller.release()
        }
    }
    // A preview hopping to the next channel on failure would be confusing.
    LaunchedEffect(playback) { controller.settings = playback.copy(nextInList = false) }

    // With an account that allows one stream, previewing would cut off someone else: ask once.
    var allowed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val usage = controller.connectionUsage()
        allowed = usage == null || usage.second <= 0 || usage.first < usage.second
    }

    val index = state.index?.takeIf { it in channels.indices }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(index, allowed, controller) {
        if (index == null || allowed != true || state.controller !== controller) return@LaunchedEffect
        delay(if (started) 600 else 300) // let fast scrolling pass
        started = true
        controller.select(index)
    }
    val ui by controller.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(CardShape)
            .background(Color.Black)
            .clickable(enabled = index != null) { index?.let(onFullscreen) },
        contentAlignment = Alignment.Center,
    ) {
        if (started) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        player = controller.player
                    }
                },
                update = { it.player = controller.player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            allowed == false -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text(
                    "All streams on your account are in use – a preview would stop the other viewer.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { allowed = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Preview anyway") }
            }
            index == null -> Text(
                "Select a channel to preview it",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(16.dp),
            )
            ui.status is PlaybackStatus.Loading -> CircularProgressIndicator()
            ui.status is PlaybackStatus.Failed -> Text(
                (ui.status as PlaybackStatus.Failed).message,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    val channel = index?.let { channels[it] }
    if (channel != null) {
        Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (started && ui.playing.streamId == channel.streamId) {
            ui.epg.firstOrNull()?.let { now ->
                Text("Now: ${now.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ui.epg.getOrNull(1)?.let { next ->
                Text(
                    "Next: ${next.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "OK / tap again for full screen",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
