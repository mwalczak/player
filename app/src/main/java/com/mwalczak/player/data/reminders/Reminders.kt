package com.mwalczak.player.data.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mwalczak.player.MainActivity
import com.mwalczak.player.PlayerApp
import com.mwalczak.player.R
import com.mwalczak.player.data.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A show to be reminded of (shortly before it starts) or switched to (when it starts). */
@Serializable
data class Reminder(
    val channel: Channel,
    val title: String,
    /** Start and end, in seconds since the epoch. */
    val start: Long,
    val stop: Long,
    /** Switch to the channel when the show starts, instead of asking. */
    val autoSwitch: Boolean = false,
) {
    val id: String get() = "${channel.streamId}@$start"

    /** When it goes off: the start for switching, a little before it for a reminder. */
    val dueMillis: Long get() = (if (autoSwitch) start else start - EARLY_SEC) * 1000

    companion object {
        const val EARLY_SEC = 2 * 60L
    }
}

/**
 * Reminders for shows: kept on the device, set as alarms (also after a reboot), and when one goes
 * off it is shown in the app if it's open ([due]) or as a notification otherwise.
 */
class ReminderStore(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "reminders.json")
    private val serializer = ListSerializer(Reminder.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private val _reminders = MutableStateFlow(read().filter { it.stop * 1000 > System.currentTimeMillis() })
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private val _due = MutableSharedFlow<Reminder>(replay = 1, extraBufferCapacity = 4)
    /** Reminders going off while the app is open, for its UI to act on. */
    val due: SharedFlow<Reminder> = _due.asSharedFlow()

    /** Set by the activity: whether the app is on screen. */
    @Volatile var foreground = false

    fun find(streamId: Int, start: Long): Reminder? = _reminders.value.firstOrNull { it.channel.streamId == streamId && it.start == start }

    fun set(reminder: Reminder) = change { list -> list.filterNot { it.id == reminder.id } + reminder }

    fun remove(reminder: Reminder) = change { list -> list.filterNot { it.id == reminder.id } }

    /** The UI has dealt with [reminder] (watched or dismissed). */
    fun consumed() {
        _due.resetReplayCache()
    }

    /** Puts every upcoming reminder on the alarm clock (on start and after a reboot). */
    fun reschedule() {
        val now = System.currentTimeMillis()
        _reminders.value.forEach { if (it.stop * 1000 > now) schedule(it) }
    }

    /** An alarm went off for [id]. */
    fun fire(id: String) {
        val reminder = _reminders.value.firstOrNull { it.id == id } ?: return
        remove(reminder)
        if (foreground) {
            _due.tryEmit(reminder)
        } else {
            notify(reminder)
        }
    }

    private fun change(transform: (List<Reminder>) -> List<Reminder>) {
        val before = _reminders.value
        _reminders.update { list -> transform(list).filter { it.stop * 1000 > System.currentTimeMillis() }.sortedBy { it.start } }
        val after = _reminders.value
        before.filter { old -> after.none { it.id == old.id } }.forEach(::unschedule)
        after.forEach(::schedule)
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(serializer, _reminders.value)) } }
    }

    private fun read(): List<Reminder> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun alarmIntent(reminder: Reminder, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            app,
            reminder.id.hashCode(),
            Intent(app, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, reminder.id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun schedule(reminder: Reminder) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = alarmIntent(reminder, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = maxOf(reminder.dueMillis, System.currentTimeMillis() + 1_000)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, intent)
            }
        }
    }

    private fun unschedule(reminder: Reminder) {
        val intent = alarmIntent(reminder, PendingIntent.FLAG_NO_CREATE) ?: return
        app.getSystemService(AlarmManager::class.java)?.cancel(intent)
    }

    private fun notify(reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(app)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH).setName("Show reminders").build(),
        )
        val open = PendingIntent.getActivity(
            app,
            reminder.id.hashCode(),
            Intent(Intent.ACTION_VIEW, playUri(reminder.channel.streamId), app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.start * 1000))
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText("$time on ${reminder.channel.name}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .addAction(0, "Watch", open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(reminder.id.hashCode(), notification) }
    }

    companion object {
        const val ACTION_FIRE = "com.mwalczak.player.REMINDER"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "reminders"

        /** Opens the app on a channel (from a reminder or the TV home screen). */
        fun playUri(streamId: Int): Uri = Uri.parse("bunnyplayer://play/$streamId")
    }
}

/** Alarms for reminders, and putting them back after a reboot. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = (context.applicationContext as PlayerApp).container.reminders
        when (intent.action) {
            ReminderStore.ACTION_FIRE -> intent.getStringExtra(ReminderStore.EXTRA_ID)?.let(reminders::fire)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> reminders.reschedule()
        }
    }
}
