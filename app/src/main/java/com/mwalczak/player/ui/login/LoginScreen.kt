package com.mwalczak.player.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.ServerUrls
import com.mwalczak.player.data.applySyncPayload
import com.mwalczak.player.ui.common.rememberImportPicker
import com.mwalczak.player.ui.common.summary
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var m3uLink by rememberSaveable { mutableStateOf("") }
    var showM3u by rememberSaveable { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (server.isEmpty()) server = container.settings.lastServer().orEmpty()
    }

    var info by remember { mutableStateOf<String?>(null) }

    // Settings file from a friend or another device: applies favorites/settings and, if the file
    // carries a login, signs in with it straight away.
    val pickImport = rememberImportPicker { backup ->
        error = null
        info = null
        scope.launch {
            applySyncPayload(container.settings, container.library, backup.data)
            val login = backup.login
            if (login == null) {
                info = "Imported ${backup.summary()}. Now sign in with your account."
                return@launch
            }
            val creds = Credentials(ServerUrls.normalizeServer(login.server), login.username, login.password)
            server = creds.server
            username = creds.username
            password = creds.password
            busy = true
            try {
                container.xtream.authenticate(creds)
                container.settings.saveCredentials(creds)
                container.appScope.launch { container.catalog.load(creds, force = true) }
                onLoggedIn()
            } catch (e: Exception) {
                error = "The login in the file didn't work: ${e.message ?: "could not connect"}"
            } finally {
                busy = false
            }
        }
    }

    fun signIn() {
        val creds = Credentials(ServerUrls.normalizeServer(server), username.trim(), password)
        if (creds.server.isEmpty() || creds.username.isEmpty() || creds.password.isEmpty()) {
            error = "Fill in the server URL, username and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                container.xtream.authenticate(creds)
                container.settings.saveCredentials(creds)
                container.appScope.launch { container.catalog.load(creds, force = true) }
                onLoggedIn()
            } catch (e: Exception) {
                error = e.message ?: "Could not connect to the server."
            } finally {
                busy = false
            }
        }
    }

    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.LiveTv, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Bunny Player", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sign in with the Xtream Codes details from your provider (e.g. the mybunny.tv account page or welcome email). " +
                    "You only need to do this once – the app remembers you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val form: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showM3u) {
                OutlinedTextField(
                    value = m3uLink,
                    onValueChange = { link ->
                        m3uLink = link
                        ServerUrls.parseM3uLink(link)?.let {
                            server = it.server
                            username = it.username
                            password = it.password
                        }
                    },
                    label = { Text("M3U playlist link") },
                    placeholder = { Text("http://host:port/get.php?username=…&password=…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }

            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server URL") },
                placeholder = { Text("http://example.com:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }

            Button(onClick = ::signIn, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
            TextButton(onClick = { showM3u = !showM3u }) {
                Text(if (showM3u) "Hide M3U link" else "I have an M3U link instead")
            }
            OutlinedButton(onClick = pickImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Text("  Import settings file")
            }
            Text(
                "Version ${com.mwalczak.player.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        // An old install must be able to update itself before signing in, too.
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp)) {
            com.mwalczak.player.ui.home.UpdateBanner(container)
        }
        if (maxWidth >= 600.dp) {
            // Landscape phone / tablet / TV: intro on the left, form on the right.
            Row(
                Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { header() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { form() }
            }
        } else {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                header()
                form()
            }
        }
    }
}
