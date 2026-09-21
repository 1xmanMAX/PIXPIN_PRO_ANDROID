package com.forge.pixpin.pdf

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.forge.pixpin.motor.PdfDoc
import java.io.File
import java.util.concurrent.Executors

/**
 * **pdfsqueeze, en un proceso aparte** (21-sep-2026) — la biblioteca en Rust del usuario
 * (github.com/1xmanMAX/Thesis), que va en el APK como `libpdfsqueeze.so`.
 *
 * Entró llamándose desde el mismo hilo que guarda lo que se comparte a PixPin, y el usuario lo
 * notó enseguida: compartía un PDF, tocaba «nuevo proyecto» y no pasaba nada. Medido en un
 * teléfono con el `.so` del APK: 3–8 segundos un documento corriente, y crece con las hojas.
 * Así que ahora hay **dos caminos**, los dos aquí, en el proceso `:pdfsqueeze` —si el código
 * nativo se cae, se cae solo y la aplicación sigue abierta—:
 *
 * - **Al entrar un PDF** ([encolar]): el archivo se guarda tal cual, al instante, y se aligera
 *   **después**, sin que nadie espere. Al acabar se cambia en su sitio —de un golpe, con
 *   `rename`— junto con sus copias idénticas de la carpeta de proyectos (la «copia limpia»).
 * - **Cuando se pide a mano** ([ahora]): se espera el resultado, que ahí sí hay alguien mirando.
 *
 * Si pdfsqueeze no puede con un documento, lo aligera el compresor en Kotlin de siempre.
 */
class PdfSqueezeService : Service() {
    private val cola = Executors.newSingleThreadExecutor()
    private val enCurso = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archivo = intent?.getStringExtra(ARCHIVO)?.let { File(it) }
        val nivel = intent?.getStringExtra(NIVEL) ?: ComprimirPdf.NIVEL_POR_DEFECTO
        val aviso = intent?.getStringExtra(AVISO)?.let { File(it) }
        val decirlo = intent?.getBooleanExtra(DECIRLO, false) == true
        if (archivo == null) { stopSelf(startId); return START_NOT_STICKY }
        enCurso.incrementAndGet()
        cola.execute {
            val ganado = runCatching { aligerar(archivo, nivel) }.getOrDefault(0L)
            runCatching { aviso?.writeText(ganado.toString()) }
            // A la aplicación —que es otro proceso—: que ponga al día el peso que enseña el chat.
            if (ganado > 0) runCatching { sendBroadcast(Intent(ALIGERADO).setPackage(packageName)) }
            if (decirlo && ganado > 0) Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    "PDF aligerado: ${com.forge.pixpin.motor.Detalle.legible(archivo.length() + ganado)} → ${com.forge.pixpin.motor.Detalle.legible(archivo.length())}",
                    Toast.LENGTH_LONG
                ).show()
            }
            if (enCurso.decrementAndGet() == 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Aligera [archivo] en su sitio, y sus copias idénticas. Devuelve los bytes ganados. */
    private fun aligerar(archivo: File, nivel: String): Long {
        if (!archivo.isFile) return 0
        val original = archivo.readBytes()
        val ligero = (try {
            dev.pdfsqueeze.PdfSqueeze.compress(original, ComprimirPdf.opcionesDe(nivel))
        } catch (e: Throwable) {
            null
        })?.takeIf { it.isNotEmpty() && it.size < original.size }
            // Sin pérdida no hay plan B con pérdida: si pdfsqueeze no pudo, se queda como está.
            ?: (if (nivel == ComprimirPdf.SIN_PERDIDA) null else ComprimirPdf.comprimir(original))
            ?: return 0
        if (ligero.size > original.size * ComprimirPdf.loQueTieneQueBajar(nivel)) return 0
        // Las copias idénticas, **miradas antes de cambiar nada**: la copia limpia de un proyecto
        // nace del mismo archivo un instante después de entrar.
        val gemelos = (archivo.parentFile?.listFiles().orEmpty().toList() +
            File(filesDir, "proyectos").listFiles().orEmpty().toList())
            .filter { it != archivo && it.isFile && it.name.endsWith(".pdf", true) && it.length() == original.size.toLong() }
            .distinctBy { it.absolutePath }
            .filter { runCatching { it.readBytes().contentEquals(original) }.getOrDefault(false) }
        // Si mientras tanto alguien lo cambió —se anotó, llegó otra versión—, no se pisa.
        if (archivo.length() != original.size.toLong()) return 0
        if (!ponerEnSuSitio(archivo, ligero, PdfDoc.pageCount(archivo.path))) return 0
        for (g in gemelos) if (g.length() == original.size.toLong()) ponerEnSuSitio(g, ligero, -1)
        return (original.size - ligero.size).toLong()
    }

    /** Escribe al lado, comprueba que Android lo abre con las mismas páginas, y cambia de un golpe. */
    private fun ponerEnSuSitio(destino: File, bytes: ByteArray, paginas: Int): Boolean {
        val temporal = File(destino.parentFile, destino.name + ".ligero")
        return try {
            temporal.writeBytes(bytes)
            val ahora = PdfDoc.pageCount(temporal.path)
            if (ahora <= 0 || (paginas > 0 && ahora != paginas)) return false
            if (!temporal.renameTo(destino)) temporal.copyTo(destino, overwrite = true)
            true
        } catch (e: Throwable) {
            false
        } finally {
            temporal.delete()
        }
    }

    companion object {
        private const val ARCHIVO = "archivo"
        private const val NIVEL = "nivel"
        private const val AVISO = "aviso"
        private const val DECIRLO = "decirlo"
        /** Se acaba de aligerar un PDF: lo oye [com.forge.pixpin.PixPinApp]. */
        const val ALIGERADO = "com.forge.pixpin.PDF_ALIGERADO"
        /** El nombre del proceso, como en el manifiesto. */
        const val PROCESO = ":pdfsqueeze"

        /** Lo aligera **después**, sin esperar. False si Android no deja arrancar el servicio (segundo plano). */
        fun encolar(contexto: Context, archivo: File, nivel: String): Boolean = try {
            contexto.startService(
                Intent(contexto, PdfSqueezeService::class.java)
                    .putExtra(ARCHIVO, archivo.absolutePath).putExtra(NIVEL, nivel).putExtra(DECIRLO, true)
            ) != null
        } catch (e: Throwable) {
            false
        }

        /**
         * Lo aligera **y espera**: los bytes ganados, o null si no se pudo pedir o no acabó en
         * [tope] milisegundos (entonces se mata ese proceso, que es solo suyo). Bloquea.
         */
        fun ahora(contexto: Context, archivo: File, nivel: String, tope: Long = 180_000L): Long? {
            val aviso = File(File(contexto.cacheDir, "pdfsqueeze").apply { mkdirs() }, "aviso-${System.nanoTime()}")
            return try {
                contexto.startService(
                    Intent(contexto, PdfSqueezeService::class.java)
                        .putExtra(ARCHIVO, archivo.absolutePath).putExtra(NIVEL, nivel).putExtra(AVISO, aviso.absolutePath)
                ) ?: return null
                val hasta = System.currentTimeMillis() + tope
                while (!aviso.exists() && System.currentTimeMillis() < hasta) Thread.sleep(80)
                if (!aviso.exists()) { matarElProceso(contexto); return null }
                Thread.sleep(30)
                aviso.readText().trim().toLongOrNull()
            } catch (e: Throwable) {
                null
            } finally {
                aviso.delete()
            }
        }

        private fun matarElProceso(contexto: Context) {
            runCatching {
                val gestor = contexto.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                gestor.runningAppProcesses?.firstOrNull { it.processName == contexto.packageName + PROCESO }
                    ?.let { android.os.Process.killProcess(it.pid) }
            }
        }
    }
}
