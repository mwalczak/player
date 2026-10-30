package com.mwalczak.player.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Keeps the app up to date from GitHub Releases.
 *
 * On start (and when the app comes back) it asks GitHub for the latest release. If its build
 * number is higher than ours, the home screen offers the update. Tapping it streams the APK
 * straight into Android's installer, which asks to confirm (on Android 12+ that step can be
 * skipped when the system allows it).
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    currentBuild: Int,
) {
    private val _state = MutableStateFlow(UpdateState(currentBuild))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheck = 0L
    private var release: Release? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onInstallStatus(intent)
    }

    init {
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    /** Asks GitHub for the newest build, at most every few minutes unless [force]d. */
    fun check(force: Boolean = false) {
        val s = _state.value
        if (s.currentBuild <= 1 || s.busy) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        scope.launch(Dispatchers.IO) {
            try {
                val latest = http.newCall(Request.Builder().url(Releases.LATEST_RELEASE_API)
                    .header("Accept", "application/vnd.github+json").build())
                    .execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        Releases.parseLatest(r.body?.string().orEmpty())
                    }
                if (latest != null) {
                    release = latest
                    _state.update { it.copy(latestBuild = latest.build) }
                }
            } catch (e: Exception) {
                // No internet or GitHub unhappy: just try again later.
                lastCheck = 0L
            }
        }
    }

    /** Called when the update banner is tapped. */
    fun install(activity: Activity) {
        val s = _state.value
        if (!s.available || s.busy) return
        val target = release ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            // One-time permission: "Allow from this source" in the system settings.
            _state.update { it.copy(message = "Allow Bunny Player to install updates, then come back and tap Update again") }
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        _state.update { it.copy(message = null, progress = 0f) }
        scope.launch(Dispatchers.IO) { downloadAndInstall(target) }
    }

    private fun downloadAndInstall(target: Release) {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (target.size > 0) params.setSize(target.size)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                http.newCall(Request.Builder().url(target.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: target.size
                    body.byteStream().use { input ->
                        session.openWrite(Releases.APK_NAME, 0, total).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReported = 0f
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done.toFloat() / total).coerceIn(0f, 1f)
                                    if (p - lastReported >= 0.01f) {
                                        lastReported = p
                                        _state.update { it.copy(progress = p) }
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                _state.update { it.copy(progress = 1f) }
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            _state.update { it.copy(progress = -1f, message = "Update failed (${e.message ?: "no internet?"}). Tap Update to try again") }
        }
    }

    private fun onInstallStatus(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Android restarts us as the new version.
                _state.update { it.copy(message = "Updated! Restarting…") }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // "Cancel" was pressed; offer the update again.
                _state.update { it.copy(progress = -1f, message = null) }
            }
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                _state.update { it.copy(progress = -1f, message = "Update failed ($why). Tap Update to try again") }
            }
        }
    }

    private companion object {
        const val ACTION_INSTALL_STATUS = "com.mwalczak.player.INSTALL_STATUS"
        const val CHECK_EVERY_MS = 10 * 60 * 1000L
    }
}
