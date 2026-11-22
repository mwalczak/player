package com.mwalczak.player.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.FIRST_PROFILE
import com.mwalczak.player.data.Profile
import com.mwalczak.player.ui.Actions
import kotlinx.coroutines.launch
import java.net.URI

/** "user @ host" for a profile's login. */
private fun Profile.loginLabel(): String {
    if (username.isBlank()) return "Not signed in"
    val host = runCatching { URI(server).host }.getOrNull() ?: server
    return "$username @ $host"
}

/** Settings → Profiles: switch, add, rename, remove. */
@Composable
fun ProfilesSection(container: AppContainer, actions: Actions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.profiles.profiles.collectAsStateWithLifecycle()
    var switchingTo by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var removing by remember { mutableStateOf<Profile?>(null) }

    renaming?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename profile") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = { container.profiles.rename(profile.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { profile ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${profile.name}”?") },
            text = { Text("Its login, favorites, lists and guide are removed from this device. What it saved online stays and comes back when you add the same login again.") },
            confirmButton = { TextButton(onClick = { container.removeProfile(profile.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    state.profiles.forEach { profile ->
        val current = profile.id == state.active
        var menu by remember(profile.id) { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(profile.name, fontWeight = if (current) FontWeight.SemiBold else null) },
            supportingContent = { Text((if (current) "In use · " else "Tap to switch · ") + profile.loginLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                if (switchingTo == profile.id) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (current) Icons.Filled.Person else Icons.Outlined.Person,
                        null,
                        tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More for ${profile.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = profile })
                        if (!current && profile.id != FIRST_PROFILE) {
                            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; removing = profile })
                        }
                    }
                }
            },
            modifier = Modifier.clickable(enabled = !current && switchingTo == null) {
                switchingTo = profile.id
                scope.launch {
                    container.switchProfile(profile.id)
                    switchingTo = null
                    Toast.makeText(context, "Profile: ${profile.name}", Toast.LENGTH_SHORT).show()
                    actions.loggedIn()
                }
            },
        )
    }
    ListItem(
        headlineContent = { Text("Add profile") },
        supportingContent = { Text("Another login with its own favorites, lists and guide (e.g. a second provider)") },
        leadingContent = { Icon(Icons.Default.PersonAdd, null) },
        modifier = Modifier.clickable { actions.addProfile() },
    )
}

/** On Home, with 2+ profiles: which one is in use, and a quick switch. */
@Composable
fun ProfileBar(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.profiles.profiles.collectAsStateWithLifecycle()
    if (state.profiles.size < 2) return
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Switch profile") },
            text = {
                Column {
                    state.profiles.forEach { profile ->
                        val current = profile.id == state.active
                        TextButton(
                            onClick = {
                                choosing = false
                                if (current) return@TextButton
                                busy = true
                                scope.launch {
                                    container.switchProfile(profile.id)
                                    busy = false
                                    Toast.makeText(context, "Profile: ${profile.name}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(if (current) Icons.Filled.Person else Icons.Outlined.Person, null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(profile.name, fontWeight = if (current) FontWeight.SemiBold else null)
                                Text(profile.loginLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary)
        Text(state.current.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
        TextButton(onClick = { choosing = true }, enabled = !busy) { Text("Switch profile") }
    }
}
