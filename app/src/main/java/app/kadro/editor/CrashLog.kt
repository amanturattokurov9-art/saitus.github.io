package app.kadro.editor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why the app died so the next launch can show it. Without this a crash on someone's
 * phone leaves nothing to go on.
 */
object CrashLog {
    private const val PREFS = "crashlog"
    private const val SEEN = "seenExit"

    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    private fun header(ctx: Context): String {
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
        val mem = ctx.getSystemService(ActivityManager::class.java)
        return "Kadro $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
            " (API ${Build.VERSION.SDK_INT}) · heap ${mem.largeMemoryClass} MB\n"
    }

    /** What the user was doing, kept in memory and written out with the crash. */
    @Volatile
    var breadcrumb: String = ""

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText(
                    header(app) + "$time · поток ${thread.name}\n" +
                        (if (breadcrumb.isNotEmpty()) "Действие: $breadcrumb\n" else "") +
                        Log.getStackTraceString(error).take(6000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A report from the previous run, or null if it ended normally. */
    fun pending(ctx: Context): String? {
        val f = file(ctx)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        if (Build.VERSION.SDK_INT < 30) return null
        // Deaths that never reach Java: native crashes in a codec, the system reclaiming memory.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val exit = runCatching {
            ctx.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        if (exit.timestamp <= prefs.getLong(SEEN, 0)) return null
        // Being cleared out while in the background is normal Android life, not a crash.
        val onScreen = exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val why = when {
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой в системном коде (обычно видеокодек)"
            exit.reason == ApplicationExitInfo.REASON_CRASH -> "ошибка приложения"
            exit.reason == ApplicationExitInfo.REASON_ANR && onScreen -> "приложение зависло"
            exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY && onScreen -> "системе не хватило памяти"
            // A signal other than a plain kill while on screen: usually a native crash too.
            exit.reason == ApplicationExitInfo.REASON_SIGNALED && onScreen && exit.status != 9 ->
                "процесс остановлен сигналом ${exit.status}"
            else -> {
                prefs.edit().putLong(SEEN, exit.timestamp).apply()
                return null
            }
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
        return header(ctx) + "$time · $why\n" +
            "reason=${exit.reason} status=${exit.status} pss=${exit.pss / 1024} MB rss=${exit.rss / 1024} MB\n" +
            (exit.description ?: "")
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(SEEN, System.currentTimeMillis()).apply()
        }
    }
}
