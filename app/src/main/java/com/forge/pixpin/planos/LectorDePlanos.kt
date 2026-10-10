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

    /** Un modelo 3D (plano en 3D, LandXML, puntos, IFC, Revit) al formato PX3D. Null si salió bien. */
    @JvmStatic external fun convertir3d(entrada: String, salida: String): String?

    /** Un Revit pasado a IFC4. Null si salió bien. */
    @JvmStatic external fun revitAIfc(entrada: String, salida: String): String?

    /** Si es un LandXML o un fichero de puntos de Civil 3D (mirando dentro). */
    @JvmStatic external fun esDeCivil(ruta: String): Boolean
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
        val modo = intent?.getStringExtra(MODO) ?: MODO_PLANO
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
                            when (modo) {
                                MODO_3D -> PlanoNativo.convertir3d(entrada.absolutePath, salida.absolutePath)
                                MODO_REVIT -> PlanoNativo.revitAIfc(entrada.absolutePath, salida.absolutePath)
                                else -> PlanoNativo.convertir(entrada.absolutePath, salida.absolutePath, carpetasShx(this))
                            }
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
        private const val MODO = "modo"
        private const val MODO_PLANO = "plano"
        private const val MODO_3D = "3d"
        private const val MODO_REVIT = "revit"

        /** Lo que se ve en el visor 3D aunque no sea un plano: Revit, LandXML y puntos (estos, mirando dentro). */
        fun esRevit(nombre: String?) = nombre?.lowercase()?.endsWith(".rvt") == true

        /** Un .xml, .csv o .txt que es de Civil 3D (un LandXML o puntos PNEZD/ENZ…). Lee el principio del archivo. */
        fun esDeCivil(ruta: String?, nombre: String?): Boolean {
            val n = (nombre ?: ruta ?: return false).lowercase()
            if (!(n.endsWith(".xml") || n.endsWith(".csv") || n.endsWith(".txt") || n.endsWith(".pnezd") || n.endsWith(".penzd") || n.endsWith(".nez"))) return false
            return ruta != null && PlanoNativo.disponible && runCatching { PlanoNativo.esDeCivil(ruta) }.getOrDefault(false)
        }

        /**
         * El modelo 3D de [archivo] (de la caché o leyéndolo aparte). Bloquea. [nombre] es el que
         * se ve (el del mensaje): la lectura nativa decide qué es **por la extensión**, y un adjunto
         * puede estar guardado sin ella (`.bin`, o con un nombre interno).
         */
        fun leer3D(c: Context, archivo: File, nombre: String? = null): Resultado {
            val entrada = conSuExtension(c, archivo, nombre)
            return pedir(c, entrada, File(carpeta(c), clave(archivo) + ".px3d"), MODO_3D, 300_000L)
        }

        /** Lo que la lectura nativa sabe abrir por su extensión en el visor 3D. */
        private val EXTENSIONES_3D = setOf("ifc", "rvt", "dwg", "dxf", "xml", "csv", "txt", "pnezd", "penzd", "nez")

        /**
         * [archivo], o un enlace a él con la extensión de [nombre] si la suya no es la que toca. El
         * enlace (o una copia, si el sistema no deja enlazar) vive en la caché de los planos.
         */
        fun conSuExtension(c: Context, archivo: File, nombre: String?): File {
            val buena = nombre?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (buena !in EXTENSIONES_3D || archivo.extension.lowercase() == buena) return archivo
            val enlace = File(File(carpeta(c), "entradas").apply { mkdirs() }, clave(archivo) + "." + buena)
            if (enlace.exists()) return enlace
            val enlazado = runCatching { android.system.Os.symlink(archivo.absolutePath, enlace.absolutePath); true }.getOrDefault(false)
            if (!enlazado) runCatching { archivo.copyTo(enlace, overwrite = true) }.onFailure { return archivo }
            return enlace
        }

        /** Lo que se abre en el visor 3D (y no en el croquis): IFC y Revit, como en el PC. */
        fun esDelVisor3D(nombre: String?): Boolean {
            val n = nombre?.lowercase() ?: return false
            return n.endsWith(".ifc") || n.endsWith(".rvt")
        }

        /** Un Revit como IFC (de la caché o pasándolo aparte), para el croquis 3D. Bloquea. */
        fun revitAIfc(c: Context, archivo: File): Resultado =
            pedir(c, archivo, File(carpeta(c), clave(archivo) + ".ifc"), MODO_REVIT, 600_000L)
        /** Cuántos planos se guardan ya leídos (los más recientes), como en el PC. */
        private const val EN_CACHE = 24
        /** La de `MAGIA` en `modelo.rs` del PC (y en [ModeloCad]). */
        const val VERSION_DEL_FORMATO = 5
        /** Sube cuando cambia cómo se leen las letras (las SHX de Hershey, el volteo): lo leído antes se rehace. */
        const val REVISION_DE_LETRAS = 2

        fun esPlano(nombre: String?): Boolean {
            val n = nombre?.lowercase() ?: return false
            return n.endsWith(".dwg") || n.endsWith(".dxf")
        }

        /** Las fuentes SHX que quiera usar el usuario (en un teléfono no hay AutoCAD que las traiga). */
        fun carpetaShx(c: Context): File = File(c.filesDir, "fuentes-shx")

        /**
         * Las SHX de PixPin (las de Hershey, `assets/fuentes-shx`), sacadas a disco la primera vez:
         * la lectura nativa abre archivos, no `assets`. Se rehacen si cambia la versión de la app.
         */
        private fun shxDePixPin(c: Context): File {
            val carpeta = File(c.filesDir, "fuentes-shx-pixpin")
            val marca = File(carpeta, ".version")
            val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toString() }.getOrDefault("0")
            if (marca.isFile && runCatching { marca.readText() }.getOrNull() == version) return carpeta
            runCatching {
                carpeta.mkdirs()
                for (n in c.assets.list("fuentes-shx").orEmpty().filter { it.endsWith(".shx") }) {
                    c.assets.open("fuentes-shx/$n").use { i -> File(carpeta, n).outputStream().use { i.copyTo(it) } }
                }
                marca.writeText(version)
            }
            return carpeta
        }

        /** Donde buscar las SHX, una por renglón: primero las del usuario (mandan), luego las de PixPin. */
        fun carpetasShx(c: Context): String = carpetaShx(c).absolutePath + "\n" + shxDePixPin(c).absolutePath

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
            // Con la versión del formato en el nombre: lo leído con otra versión no se usa (ni se
            // confunde con roto) y se va solo al podar.
            val salida = File(carpeta, clave(plano) + ".v$VERSION_DEL_FORMATO-$REVISION_DE_LETRAS.pxcad")
            return pedir(c, plano, salida, MODO_PLANO, tope)
        }

        private fun pedir(c: Context, plano: File, salida: File, modo: String, tope: Long): Resultado {
            val carpeta = salida.parentFile ?: carpeta(c)
            if (salida.isFile && salida.length() > 8) {
                salida.setLastModified(System.currentTimeMillis())
                return Resultado.Listo(salida)
            }
            errorDe(salida).delete(); leyendoDe(salida).delete()
            podar(carpeta)
            try {
                c.startService(
                    Intent(c, LectorDePlanos::class.java).putExtra(ENTRADA, plano.absolutePath).putExtra(SALIDA, salida.absolutePath).putExtra(MODO, modo)
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
            val v = carpeta.listFiles()?.filter { it.name.endsWith(".pxcad") || it.name.endsWith(".px3d") || it.name.endsWith(".ifc") }
                ?.sortedByDescending { it.lastModified() } ?: return
            v.drop(EN_CACHE).forEach { it.delete() }
            // Los enlaces (o copias) con la extensión que tocaba: los de más de un día sobran.
            // La fecha **del enlace**, no la del archivo al que apunta (que puede ser de hace meses).
            File(carpeta, "entradas").listFiles()?.filter { f ->
                val hecho = runCatching { java.nio.file.Files.getLastModifiedTime(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(f.lastModified())
                System.currentTimeMillis() - hecho > 86_400_000
            }?.forEach { it.delete() }
            // Y los restos de lecturas que se quedaron a medias.
            carpeta.listFiles()?.filter { it.name.endsWith(".tmp") && System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        }
    }
}
