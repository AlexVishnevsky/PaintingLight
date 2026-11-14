package io.github.alexvishnevsky.flashlight3d

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Makes crashes debuggable from the phone alone. It records how far startup got,
 * saves Java stack traces, and can assemble a text report (including what the
 * system and logcat say about the previous run) to copy or share.
 */
object Diagnostics {
    private const val TAG = "Flashlight3D"
    private const val PREFS = "diagnostics"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, "crash.txt").writeText("${now()} thread ${thread.name}\n$text")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** True if the previous launch began starting up and never finished. */
    fun previousStartFailed(context: Context): Boolean =
        prefs(context).getBoolean("starting", false)

    /** Records the startup step that is about to run. Synchronous on purpose. */
    fun stage(context: Context, name: String) {
        Log.i(TAG, "stage: $name")
        prefs(context).edit().putBoolean("starting", true).putString("stage", name).commit()
    }

    fun started(context: Context) {
        Log.i(TAG, "startup complete")
        prefs(context).edit().putBoolean("starting", false).putString("stage", "працює").apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().putBoolean("starting", false).apply()
        File(context.filesDir, "crash.txt").delete()
    }

    /** Slow (runs logcat); call off the main thread. */
    fun report(context: Context): String {
        val sb = StringBuilder()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        sb.appendLine("Flashlight 3D ${info.versionName}")
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}; SoC: ${Build.HARDWARE}")
        sb.appendLine("Остання стадія запуску: ${prefs(context).getString("stage", "—")}")
        sb.appendLine("Налаштування: ${Settings.describe()}")

        val crash = File(context.filesDir, "crash.txt")
        if (crash.exists()) {
            sb.appendLine().appendLine("== Java-виняток ==")
            sb.appendLine(crash.readText().take(6000))
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val exits = am.getHistoricalProcessExitReasons(null, 0, 4)
                if (exits.isNotEmpty()) sb.appendLine().appendLine("== Останні завершення процесу ==")
                for (exit in exits) {
                    sb.appendLine(
                        "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(exit.timestamp))} " +
                            "reason=${exit.reason} status=${exit.status} ${exit.description ?: ""}",
                    )
                    // Native crashes come with a tombstone; pull the readable strings
                    // (abort message, frame names) out of it.
                    if (exit === exits.first()) {
                        exit.traceInputStream?.use { stream ->
                            val bytes = stream.readBytes()
                            val strings = Regex("[\\x20-\\x7e]{6,}").findAll(String(bytes, Charsets.ISO_8859_1))
                                .map { it.value }.take(80).joinToString("\n")
                            sb.appendLine(strings.take(6000))
                        }
                    }
                }
            } catch (e: Throwable) {
                sb.appendLine("exit reasons: $e")
            }
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "-v", "threadtime"))
            val lines = process.inputStream.bufferedReader().readLines()
            val wanted = Regex(" [EF] |Filament|$TAG|AndroidRuntime|libc|DEBUG")
            val kept = lines.filter { wanted.containsMatchIn(it) }.takeLast(120)
            sb.appendLine().appendLine("== logcat ==")
            kept.forEach { sb.appendLine(it.take(400)) }
        } catch (e: Throwable) {
            sb.appendLine("logcat: $e")
        }
        return sb.toString()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
