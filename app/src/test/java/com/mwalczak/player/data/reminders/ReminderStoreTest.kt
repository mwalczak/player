package com.mwalczak.player.data.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.mwalczak.player.data.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReminderStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val inAnHour = System.currentTimeMillis() / 1000 + 3600

    private fun show(autoSwitch: Boolean = false) = Reminder(Channel(1, "TVN"), "Fakty", inAnHour, inAnHour + 1800, autoSwitch)

    @Test
    fun `a reminder rings 2 min before the show, switching at the start`() {
        val store = ReminderStore(context, scope)
        store.set(show())
        assertEquals((inAnHour - 120) * 1000, alarms.nextScheduledAlarm?.triggerAtTime)

        store.set(show(autoSwitch = true)) // same show: replaces the reminder
        assertEquals(1, store.reminders.value.size)
        assertEquals(inAnHour * 1000, alarms.nextScheduledAlarm?.triggerAtTime)

        store.remove(store.reminders.value.single())
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun `going off with the app open is shown in the app`(): Unit = runBlocking {
        val store = ReminderStore(context, scope)
        val reminder = show()
        store.set(reminder)
        store.foreground = true
        store.fire(reminder.id)
        assertEquals(reminder, withTimeout(1_000) { store.due.first() })
        assertTrue(store.reminders.value.isEmpty())
    }

    @Test
    fun `going off with the app closed is a notification`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = ReminderStore(context, scope)
        val reminder = show()
        store.set(reminder)
        store.fire(reminder.id)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, notifications.size)
        assertEquals("Fakty", shadowOf(notifications.single()).contentTitle)
    }

    @Test
    fun `reminders are kept across restarts`() {
        ReminderStore(context, scope).set(show())
        Thread.sleep(300) // written in the background
        assertEquals(listOf(show()), ReminderStore(context, scope).reminders.value)
    }
}
