package com.ilukezippo.lapfold

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Keeps the last error so it can be shown (and copied) in the app instead of vanishing. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_error.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(val c: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            write(c, e)
            prev?.uncaughtException(t, e)
        }
    }

    fun write(c: Context, e: Throwable) {
        runCatching {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            val info = "LapFold ${c.packageManager.getPackageInfo(c.packageName, 0).versionName} · " +
                "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n\n"
            file(c).writeText(info + sw.toString().take(6000))
        }
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
