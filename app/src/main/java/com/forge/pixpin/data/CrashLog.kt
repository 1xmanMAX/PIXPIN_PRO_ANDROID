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

    /**
     * **Qué pantalla estaba abierta.** Una muerte por falta de memoria no deja traza: el
     * sistema mata el proceso y solo guarda «sin memoria». Sin saber dónde se estaba, el
     * informe no dice nada (así llegó el del 14-sep-2026). Se apunta al entrar en cada
     * pantalla —una escritura pequeña, sin esperar— y se lee al recoger la muerte.
     */
    fun anotarPantalla(context: Context, nombre: String) {
        runCatching {
            val rt = Runtime.getRuntime()
            val usadoMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            context.getSharedPreferences("crashlog", Context.MODE_PRIVATE).edit()
                .putString("pantalla", nombre)
                .putLong("pantalla_cuando", System.currentTimeMillis())
                .putLong("pantalla_heap_mb", usadoMb)
                .apply()
        }
    }

    /**
     * La pantalla que se apuntó **en la vida anterior del proceso**. Se lee al instalar, antes
     * de que la primera pantalla de esta vida la sobrescriba.
     */
    @Volatile private var pantallaAnterior: String? = null

    fun install(context: Context) {
        runCatching {
            val p = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            pantallaAnterior = p.getString("pantalla", null)?.let {
                "$it (desde ${stamp.format(Date(p.getLong("pantalla_cuando", 0L)))}, " +
                    "heap Java ${p.getLong("pantalla_heap_mb", 0L)} MB)"
            }
        }
        if (context is android.app.Application) {
            context.registerActivityLifecycleCallbacks(object :
                android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: android.app.Activity) =
                    anotarPantalla(context, a.javaClass.simpleName)
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityPaused(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            })
        }
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

    /**
     * Recoge las muertes que el capturador de arriba **no puede ver**: los
     * reventones nativos (el rasterizador, la GPU) y los ANR. De esos no salta
     * ninguna excepción de JVM — el proceso muere entero— pero Android guarda
     * el motivo, y al siguiente arranque se puede leer y dejar en el informe.
     * Sin esto, «la app se cierra al hacer zoom» no dejaba ni una línea.
     */
    fun recogerMuertesDelSistema(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val yaVisto = prefs.getLong("ultima_muerte", 0L)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val muertes = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            var masReciente = yaVisto
            val interesantes = muertes.filter {
                it.timestamp > yaVisto && (
                    it.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                        it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                        it.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                    )
            }
            if (interesantes.isEmpty()) {
                muertes.maxOfOrNull { it.timestamp }?.let {
                    prefs.edit().putLong("ultima_muerte", it).apply()
                }
                return
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val texto = buildString {
                for (m in interesantes) {
                    masReciente = maxOf(masReciente, m.timestamp)
                    appendLine()
                    appendLine("— Muerte del proceso, ${stamp.format(Date(m.timestamp))} —")
                    appendLine(
                        "Motivo: " + when (m.reason) {
                            android.app.ApplicationExitInfo.REASON_CRASH -> "crash de JVM"
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash NATIVO"
                            android.app.ApplicationExitInfo.REASON_ANR -> "ANR (se quedó tiesa)"
                            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "sin memoria"
                            else -> "código ${m.reason}"
                        } + " (estado ${m.status})"
                    )
                    m.description?.takeIf { it.isNotBlank() }?.let { appendLine("Descripción: $it") }
                    // **En primer plano o de fondo.** De fondo, que el sistema mate una app
                    // para hacer sitio es lo normal en Android y no es un fallo; en primer
                    // plano, es que la app pidió demasiado.
                    appendLine(
                        "Estaba: " + when {
                            m.importance <= android.app.ActivityManager.RunningAppProcessInfo
                                .IMPORTANCE_FOREGROUND -> "en primer plano (usándose)"
                            m.importance <= android.app.ActivityManager.RunningAppProcessInfo
                                .IMPORTANCE_VISIBLE -> "visible (encima de otra app)"
                            m.importance <= android.app.ActivityManager.RunningAppProcessInfo
                                .IMPORTANCE_SERVICE -> "de fondo con un servicio"
                            else -> "de fondo, en caché"
                        } + " (importancia ${m.importance})"
                    )
                    appendLine("Memoria al morir: PSS ${m.pss / 1024} MB, RSS ${m.rss / 1024} MB")
                    pantallaAnterior?.let { appendLine("Última pantalla abierta: $it") }
                    // La traza que el sistema guardó, si la hay (ANR y nativos).
                    runCatching {
                        m.traceInputStream?.use { flujo ->
                            val traza = flujo.readBytes().toString(Charsets.UTF_8)
                            appendLine(traza.take(20_000))
                        }
                    }
                }
            }
            val f = file(context)
            if (f.exists()) f.appendText(texto) else f.writeText(
                "PixPin — muertes recogidas del sistema\n" +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n" + texto
            )
            prefs.edit().putLong("ultima_muerte", masReciente).apply()
        }
    }
}
