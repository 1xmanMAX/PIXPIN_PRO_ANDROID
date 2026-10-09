package com.forge.pixpin.planos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File
import java.util.concurrent.Executors

/**
 * La puerta a `libpixpincad.so`: la lectura de DWG/DXF del PC (`opencadcodec`, MPL-2.0, y la
 * conversión de `crates/pixpin-cad`), compilada para arm64. Ver `herramientas/pixpin-cad-android`.
 * Solo se llama desde [LectorDePlanos], en su propio proceso.
 */
object PlanoNativo {
    val disponible: Boolean by lazy { runCatching { System.loadLibrary("pixpincad") }.isSuccess }

    /** Null si salió bien; si no, por qué. Bloquea (de segundos a un minuto en un plano grande). */
    @JvmStatic external fun convertir(entrada: String, salida: String, carpetaShx: String): String?
}

/**
 * **Leer un plano, en un proceso aparte** (`:planos`), como hace el PC con su `--cad-convertir`:
 * la biblioteca se defiende de un archivo roto, pero si aun así se cae —o se queda sin memoria con
 * un plano enorme— cae solo este proceso y la aplicación dice «no se pudo abrir» en vez de cerrarse.
 * De paso, la memoria de la lectura se devuelve entera al acabar.
 *
 * Lo leído queda en `cache/planos/<clave>.pxcad` (el formato de la caché del PC): abrir otra vez
 * el mismo plano es leer eso, sin pasar por el DWG. La clave es la del PC: ruta, peso y fecha.
 *
 * Se habla por archivos, sin enlazar el servicio: el pedido va en el `Intent`; el resultado es el
 * `.pxcad`, o un `.error` con el motivo; y un `.leyendo` mientras tanto, que si se queda solo
 * —sin resultado y sin el proceso vivo— quiere decir que la lectura tumbó el proceso.
 */
class LectorDePlanos : Service() {
    private val cola = Executors.newSingleThreadExecutor()
    private val enCurso = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entrada = intent?.getStringExtra(ENTRADA)?.let { File(it) }
        val salida = intent?.getStringExtra(SALIDA)?.let { File(it) }
        if (entrada == null || salida == null) { stopSelf(startId); return START_NOT_STICKY }
        enCurso.incrementAndGet()
        cola.execute {
            val error = errorDe(salida)
            val leyendo = leyendoDe(salida)
            try {
                if (!salida.exists()) {
                    runCatching { leyendo.writeText(android.os.Process.myPid().toString()) }
                    val motivo = when {
                        !PlanoNativo.disponible -> "Este teléfono no puede leer planos (hace falta uno de 64 bits)"
                        !entrada.isFile -> "No se encuentra el archivo del plano"
                        else -> try {
                            PlanoNativo.convertir(entrada.absolutePath, salida.absolutePath, carpetaShx(this).absolutePath)
                        } catch (e: Throwable) {
                            e.message ?: e.javaClass.simpleName
                        }
                    }
                    if (motivo != null) runCatching { error.writeText(motivo) }
                }
            } finally {
                leyendo.delete()
                if (enCurso.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        /** El nombre del proceso, como en el manifiesto. */
        const val PROCESO = ":planos"
        private const val ENTRADA = "entrada"
        private const val SALIDA = "salida"
        /** Cuántos planos se guardan ya leídos (los más recientes), como en el PC. */
        private const val EN_CACHE = 24

        fun esPlano(nombre: String?): Boolean {
            val n = nombre?.lowercase() ?: return false
            return n.endsWith(".dwg") || n.endsWith(".dxf")
        }

        /** Las fuentes SHX que quiera usar el usuario (en un teléfono no hay AutoCAD que las traiga). */
        fun carpetaShx(c: Context): File = File(c.filesDir, "fuentes-shx")

        fun carpeta(c: Context): File = File(c.cacheDir, "planos").apply { mkdirs() }

        /** La clave del PC (`plano_cad::clave`): FNV-1a de 64 bits de «ruta|peso|fecha». */
        fun clave(archivo: File): String = claveDe("${archivo.absolutePath}|${archivo.length()}|${archivo.lastModified()}")

        fun claveDe(texto: String): String {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in texto.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h).padStart(16, '0')
        }

        private fun errorDe(salida: File) = File(salida.path + ".error")
        private fun leyendoDe(salida: File) = File(salida.path + ".leyendo")

        sealed interface Resultado {
            class Listo(val archivo: File) : Resultado
            class Fallo(val motivo: String) : Resultado
        }

        /**
         * Lee [plano] (de la caché, o pidiéndoselo al proceso aparte) y espera. Bloquea: llamar
         * fuera del hilo de la interfaz. Si en [tope] no acabó, se mata ese proceso.
         */
        fun leer(c: Context, plano: File, tope: Long = 300_000L): Resultado {
            val carpeta = carpeta(c)
            val salida = File(carpeta, clave(plano) + ".pxcad")
            if (salida.isFile && salida.length() > 8) {
                salida.setLastModified(System.currentTimeMillis())
                return Resultado.Listo(salida)
            }
            errorDe(salida).delete(); leyendoDe(salida).delete()
            podar(carpeta)
            try {
                c.startService(
                    Intent(c, LectorDePlanos::class.java).putExtra(ENTRADA, plano.absolutePath).putExtra(SALIDA, salida.absolutePath)
                ) ?: return Resultado.Fallo("No se pudo empezar a leer el plano")
            } catch (e: Throwable) {
                return Resultado.Fallo("No se pudo empezar a leer el plano")
            }
            val desde = System.currentTimeMillis()
            while (System.currentTimeMillis() - desde < tope) {
                if (salida.isFile) return Resultado.Listo(salida)
                errorDe(salida).takeIf { it.isFile }?.let { e ->
                    val motivo = runCatching { e.readText() }.getOrDefault("").ifBlank { "No se pudo leer el plano" }
                    e.delete()
                    return Resultado.Fallo(motivo)
                }
                // Empezó a leer y el proceso ya no está: se cayó con este plano.
                val leyendo = leyendoDe(salida)
                val pasado = System.currentTimeMillis() - desde
                if (((leyendo.isFile && pasado > 1500) || pasado > 15_000) && !vivo(c)) {
                    leyendo.delete()
                    return Resultado.Fallo("El lector de planos se cerró con este archivo (¿está dañado o es muy grande?)")
                }
                Thread.sleep(80)
            }
            matar(c)
            return Resultado.Fallo("El plano tarda demasiado en leerse")
        }

        private fun vivo(c: Context): Boolean = runCatching {
            val gestor = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            gestor.runningAppProcesses?.any { it.processName == c.packageName + PROCESO } == true
        }.getOrDefault(true)

        private fun matar(c: Context) {
            runCatching {
                val gestor = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                gestor.runningAppProcesses?.firstOrNull { it.processName == c.packageName + PROCESO }
                    ?.let { android.os.Process.killProcess(it.pid) }
            }
        }

        /** Deja solo los [EN_CACHE] planos más recientes. */
        private fun podar(carpeta: File) {
            val v = carpeta.listFiles()?.filter { it.name.endsWith(".pxcad") }?.sortedByDescending { it.lastModified() } ?: return
            v.drop(EN_CACHE).forEach { it.delete() }
            // Y los restos de lecturas que se quedaron a medias.
            carpeta.listFiles()?.filter { it.name.endsWith(".tmp") && System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        }
    }
}
