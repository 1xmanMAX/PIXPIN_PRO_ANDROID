package com.forge.pixpin.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de fallos en disco. La app se instala por APK y se usa en el móvil,
 * sin cable ni logcat: si algo revienta, aquí queda la traza para poder
 * compartirla desde la pantalla principal.
 */
object CrashLog {

    private fun file(context: Context) = File(context.filesDir, "crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun hasReport(context: Context): Boolean = file(context).exists()

    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Intent para enviarse el informe por cualquier app (correo, chat…). */
    fun shareIntent(context: Context): Intent? {
        val report = read(context) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PixPin — informe de fallo")
            putExtra(Intent.EXTRA_TEXT, report.take(60_000))
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText(
            buildString {
                appendLine("PixPin crash — $stamp")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Hilo: ${thread.name}")
                appendLine()
                append(stack.toString())
            }
        )
    }

    /** Uri del informe para adjuntarlo como archivo si hiciera falta. */
    fun uri(context: Context) = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file(context))
    }.getOrNull()
}
