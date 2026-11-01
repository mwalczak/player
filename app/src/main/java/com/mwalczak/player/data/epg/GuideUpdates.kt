package com.mwalczak.player.data.epg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mwalczak.player.PlayerApp
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The nightly TV guide update: once a day around [HOUR]:30, on Wi-Fi when the guide is set to
 * Wi-Fi only, not on a low battery. Turned off in Settings → TV guide.
 */
object GuideUpdates {
    private const val WORK = "guide-nightly"
    const val HOUR = 4

    fun schedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!enabled) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<GuideUpdateWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(millisUntilNight(System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** From [nowMillis] to the next HOUR:30 local time. */
    fun millisUntilNight(nowMillis: Long, calendar: Calendar = Calendar.getInstance()): Long {
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, HOUR)
        calendar.set(Calendar.MINUTE, 30)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis - nowMillis
    }
}

/** Updates the guide if it's more than a day old (the work runs even with the app closed). */
class GuideUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PlayerApp).container
        return if (runCatching { container.updateGuideIfStale() }.isSuccess) Result.success() else Result.retry()
    }
}
