package com.mwalczak.player

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash (and background errors) in a file so the next start can offer to share it:
 * there is no other way to see what went wrong on a phone or TV.
 */
object CrashReports {

    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    /** Records uncaught exceptions, then lets Android handle them as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "Crash on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records an error that was caught (e.g. in a background job) without crashing. */
    fun record(context: Context, what: String, error: Throwable) {
        runCatching { write(context.applicationContext, what, error) }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(context: Context, what: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText(
            buildString {
                appendLine("Bunny Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("$time – $what")
                appendLine()
                append(trace.take(20_000))
            },
        )
    }
}
