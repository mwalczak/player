package com.mwalczak.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.mwalczak.player.PlayerApp
import com.mwalczak.player.data.Channel
import com.mwalczak.player.ui.home.HomeScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `home shows favorites and lists, back leads home and then asks before leaving`() {
        val container = (compose.activity.application as PlayerApp).container
        container.library.toggleFavorite(Channel(1, "PL: TVN"))
        val sport = container.library.createList("Sport")
        container.library.toggleInList(sport, Channel(2, "Eleven 1"))
        compose.setContent {
            val nav = rememberNavController()
            val context = LocalContext.current
            HomeScreen(container, Actions(nav, container, context))
        }

        compose.onNodeWithText("Favorites").assertExists()
        compose.onNodeWithText("Sport").assertExists()
        compose.onNodeWithText("1 channels · PL: TVN").assertExists()

        // Another tab, then Back: Home again.
        compose.onNodeWithText("Search").performClick()
        compose.onAllNodesWithText("Bunny Player").assertCountEquals(0)
        back()
        compose.onNodeWithText("Bunny Player").assertExists()

        // Back on Home asks first; "Stay" keeps the app open.
        back()
        compose.onNodeWithText("Leave Bunny Player?").assertExists()
        compose.onNodeWithText("Stay").performClick()
        compose.onAllNodesWithText("Leave Bunny Player?").assertCountEquals(0)
        assertTrue(!compose.activity.isFinishing)

        back()
        compose.onNodeWithText("Leave").performClick()
        assertTrue(compose.activity.isFinishing)
    }
}
