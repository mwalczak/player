package com.mwalczak.player.ui.common

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwalczak.player.AppContainer
import com.mwalczak.player.data.Channel
import com.mwalczak.player.data.reminders.Reminder
import java.text.DateFormat
import java.util.Date

/** A show that hasn't started yet, to set (or remove) a reminder for. */
data class Show(val channel: Channel, val title: String, val start: Long, val stop: Long)

/** True when a reminder is set for the show on [streamId] starting at [start]. */
@Composable
fun hasReminder(container: AppContainer, streamId: Int, start: Long): Boolean {
    val reminders by container.reminders.reminders.collectAsStateWithLifecycle()
    return reminders.any { it.channel.streamId == streamId && it.start == start }
}

/**
 * "Remind me" (shortly before the start, with Watch) or "Switch automatically" (at the start)
 * for [show]; or removing the reminder that is set.
 */
@Composable
fun ReminderDialog(container: AppContainer, show: Show, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reminders by container.reminders.reminders.collectAsStateWithLifecycle()
    val existing = reminders.firstOrNull { it.channel.streamId == show.channel.streamId && it.start == show.start }
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    // Reminders go off as notifications when the app is closed (Android 13+ asks first).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun set(autoSwitch: Boolean) {
        container.reminders.set(Reminder(show.channel, show.title, show.start, show.stop, autoSwitch))
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        Toast.makeText(
            context,
            if (autoSwitch) "Will switch to ${show.channel.name} when ${show.title} starts" else "You'll be reminded 2 min before ${show.title}",
            Toast.LENGTH_LONG,
        ).show()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(show.title) },
        text = {
            Column {
                Text("${time.format(Date(show.start * 1000))} on ${show.channel.name}")
                if (existing != null) {
                    Text(
                        if (existing.autoSwitch) "Set to switch to it when it starts." else "Reminder set for 2 min before.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { set(autoSwitch = false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Notifications, null)
                    Text("  Remind me 2 min before")
                }
                TextButton(onClick = { set(autoSwitch = true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Autorenew, null)
                    Text("  Switch to it when it starts")
                }
                if (existing != null) {
                    TextButton(onClick = { container.reminders.remove(existing); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsOff, null)
                        Text("  Remove the reminder")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
