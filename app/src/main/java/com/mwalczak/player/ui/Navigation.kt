package com.mwalczak.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.widget.Toast
import com.mwalczak.player.cast.RemoteCommand
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mwalczak.player.AppContainer
import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.home.CategoryScreen
import com.mwalczak.player.ui.home.HomeScreen
import com.mwalczak.player.ui.lists.ChannelPickerScreen
import com.mwalczak.player.ui.lists.ListDetailScreen
import com.mwalczak.player.ui.login.LoginScreen
import com.mwalczak.player.ui.player.PlayerScreen
import kotlinx.coroutines.launch

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CATEGORY = "category/{id}"
    const val LIST = "list/{id}"
    const val PICKER = "picker/{id}"
    const val PLAYER = "player"

    fun category(id: String) = "category/${android.net.Uri.encode(id)}"
    fun list(id: String) = "list/$id"
    fun picker(id: String) = "picker/$id"
}

/** Callbacks shared by every screen that shows channels. */
class Actions(
    private val nav: NavHostController,
    private val container: AppContainer,
    private val context: Context,
) {
    /** Plays locally, or on the TV when one is selected with the cast button. */
    fun play(title: String, channels: List<Channel>, index: Int) {
        if (channels.isEmpty()) return
        val queue = PlaybackQueue(title, channels, index.coerceIn(channels.indices))
        val target = container.remote.target.value
        if (target != null) {
            container.appScope.launch {
                container.remote.play(target, queue)
                    .onSuccess { Toast.makeText(context, "▶ ${it.playing} on ${target.name}", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "${target.name}: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            return
        }
        playLocally(queue)
    }

    fun playLocally(queue: PlaybackQueue) {
        // A TV that was just signed in by the phone may still show the login screen.
        if (nav.currentDestination?.route == Routes.LOGIN) nav.navigate(Routes.HOME) { popUpTo(0) }
        container.pendingQueue = queue
        // Replace a player that is already open (e.g. when the phone switches channel on the TV),
        // otherwise keep the back stack so Back returns to the list the channel came from.
        if (nav.currentDestination?.route == Routes.PLAYER) {
            nav.navigate(Routes.PLAYER) { popUpTo(Routes.PLAYER) { inclusive = true } }
        } else {
            nav.navigate(Routes.PLAYER)
        }
    }

    fun openCategory(id: String) = nav.navigate(Routes.category(id))
    fun openList(id: String) = nav.navigate(Routes.list(id))
    fun openPicker(listId: String) = nav.navigate(Routes.picker(listId))
    fun back() = nav.popBackStack()

    fun loggedOut() = nav.navigate(Routes.LOGIN) { popUpTo(0) }
    fun loggedIn() = nav.navigate(Routes.HOME) { popUpTo(0) }
}

@Composable
fun AppNavigation(container: AppContainer) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val actions = remember(nav) { Actions(nav, container, context.applicationContext) }

    // Decide the start destination once, from whether credentials are saved.
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val creds = container.settings.currentCredentials()
        if (creds != null) container.appScope.launch { container.catalog.load(creds) }
        start = if (creds != null) Routes.HOME else Routes.LOGIN
    }

    val startDestination = start
    if (startDestination == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // Channels sent from a phone (this device acting as the TV).
    LaunchedEffect(Unit) {
        container.remote.commands.collect { command ->
            when (command) {
                is RemoteCommand.Play -> actions.playLocally(command.queue)
                else -> Unit
            }
        }
    }

    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.LOGIN) { LoginScreen(container, onLoggedIn = actions::loggedIn) }
        composable(Routes.HOME) { HomeScreen(container, actions) }
        composable(Routes.CATEGORY) { entry ->
            CategoryScreen(container, actions, categoryId = entry.arguments?.getString("id").orEmpty())
        }
        composable(Routes.LIST) { entry ->
            ListDetailScreen(container, actions, listId = entry.arguments?.getString("id").orEmpty())
        }
        composable(Routes.PICKER) { entry ->
            ChannelPickerScreen(container, actions, listId = entry.arguments?.getString("id").orEmpty())
        }
        composable(Routes.PLAYER) { PlayerScreen(container, onExit = { actions.back() }) }
    }
}
