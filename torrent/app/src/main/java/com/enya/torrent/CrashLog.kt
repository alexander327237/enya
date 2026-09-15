package com.enya.torrent

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes uncaught exceptions to a file so the last crash can be shown (and copied) on the next launch. */
object CrashLog {

    private const val MAX_BYTES = 64 * 1024

    private fun file(context: Context) = File(context.filesDir, "crash.log")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "=== $stamp thread=${thread.name} ===\n$sw\n"
                val f = file(app)
                val old = if (f.exists()) f.readText() else ""
                f.writeText((entry + old).take(MAX_BYTES))
            } catch (t: Throwable) {
                Log.e("CrashLog", "could not write crash log", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun marker(context: Context) = File(context.filesDir, "service.running")

    /** Called when the download service starts; the marker is removed on a clean stop. */
    fun markServiceRunning(context: Context) {
        try {
            marker(context).writeText("1")
        } catch (_: Throwable) {
        }
    }

    fun markServiceStopped(context: Context) {
        marker(context).delete()
    }

    /**
     * Native crashes (SIGSEGV/SIGBUS inside libtorrent) and kills by the system never reach the
     * Java handler. If the service marker survived the previous run, record that instead.
     */
    fun checkUncleanShutdown(context: Context) {
        val m = marker(context)
        if (!m.exists()) return
        m.delete()
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = "=== $stamp ===\nПроцесс был завершён без Java-исключения: нативный сбой " +
                "libtorrent или приложение убито системой (память / оптимизация батареи).\n\n"
            val f = file(context)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((entry + old).take(MAX_BYTES))
        } catch (_: Throwable) {
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun clear(context: Context) {
        file(context).delete()
    }
}
