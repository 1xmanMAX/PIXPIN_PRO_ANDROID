package com.forge.pixpin.ia

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Bajar el modelo cuando el usuario lo pide**, nunca en el APK (`descarga.rs` del PC).
 *
 * Va a `noBackupFilesDir/modelos/<id>/`: son gigas que no deben entrar en la copia de seguridad
 * de Android ni en nada que sincronice (`filesDir/pins`). Cada fichero:
 *
 * 1. se baja a `<nombre>.parte`, **con rangos**: si la red corta a medias (pasa con descargas de
 *    cientos de MB), el siguiente intento sigue donde se quedó en vez de empezar de cero;
 * 2. se le comprueba la huella SHA-256; si no coincide, se borra (uno dañado no debe quedarse
 *    con el nombre bueno) y se dice;
 * 3. toma su nombre, y al lado queda `<nombre>.ok` con la huella: las veces siguientes basta con
 *    mirar el tamaño, sin volver a leer 1 GB.
 *
 * Sin Android: `java.net` y `java.io`, probado en la JVM con un servidor local.
 */
object Descarga {

    /** Por dónde va, para la barra de la tarjeta. */
    data class Progreso(val hecho: Long, val total: Long, val fichero: String, val verificando: Boolean) {
        val fraccion: Float get() = if (total <= 0) 0f else (hecho.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    }

    /** Cuántas veces se reanuda un fichero tras un corte de red antes de rendirse. */
    const val REINTENTOS_DE_RED = 6

    fun carpetaModelo(raiz: File, m: Modelo): File = File(raiz, m.id)

    private fun marca(ruta: File) = File(ruta.parentFile, ruta.name + ".ok")
    private fun parte(ruta: File) = File(ruta.parentFile, ruta.name + ".parte")

    /** ¿Está el fichero, con su tamaño y verificado alguna vez? */
    fun ficheroListo(carpeta: File, f: Fichero): Boolean {
        val ruta = File(carpeta, f.nombre)
        return ruta.length() == f.bytes && runCatching { marca(ruta).readText().trim() == f.sha256 }.getOrDefault(false)
    }

    fun modeloListo(raiz: File, m: Modelo): Boolean = m.ficheros.all { ficheroListo(carpetaModelo(raiz, m), it) }

    /** Lo que falta por bajar, en bytes (lo ya bajado de un `.parte` cuenta como hecho). */
    fun faltaPorBajar(raiz: File, m: Modelo): Long {
        val c = carpetaModelo(raiz, m)
        return m.ficheros.filterNot { ficheroListo(c, it) }.sumOf { f ->
            val ruta = File(c, f.nombre)
            if (ruta.length() == f.bytes) 0L else f.bytes - parte(ruta).length().coerceIn(0, f.bytes)
        }
    }

    /** La huella SHA-256 de un fichero, en hexadecimal. */
    fun huella(ruta: File, cancelado: AtomicBoolean): String {
        val h = MessageDigest.getInstance("SHA-256")
        ruta.inputStream().buffered(1 shl 20).use { e ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (cancelado.get()) throw ErrorIa.Cancelado
                val n = e.read(buf)
                if (n < 0) break
                h.update(buf, 0, n)
            }
        }
        return h.digest().joinToString("") { "%02x".format(it) }
    }

    /** Comprueba la huella y deja la marca; si no coincide, borra el fichero. */
    fun verificar(ruta: File, f: Fichero, cancelado: AtomicBoolean) {
        val h = huella(ruta, cancelado)
        if (h != f.sha256) {
            ruta.delete()
            marca(ruta).delete()
            throw ErrorIa.HuellaDistinta(f.nombre)
        }
        marca(ruta).writeText(h)
    }

    /**
     * **Deja listo el modelo**, bajando solo lo que falte. Tarda minutos la primera vez: se llama
     * en un hilo de fondo. [cancelado] corta en el acto (lo bajado se queda en `.parte` para
     * seguir otro día).
     */
    fun asegurar(raiz: File, m: Modelo, avance: (Progreso) -> Unit, cancelado: AtomicBoolean) {
        val carpeta = carpetaModelo(raiz, m)
        if (!carpeta.isDirectory && !carpeta.mkdirs()) throw ErrorIa.Descarga(m.nombre, "no se pudo crear la carpeta")
        val faltan = m.ficheros.filterNot { ficheroListo(carpeta, it) }
        val total = faltan.sumOf { it.bytes }
        var hecho = 0L
        for (f in faltan) {
            val ruta = File(carpeta, f.nombre)
            // Del tamaño justo pero sin marca (se cortó al verificar): se verifica sin bajarlo.
            if (ruta.length() != f.bytes) {
                ruta.delete()
                bajar(f, ruta, hecho, total, avance, cancelado)
            }
            avance(Progreso(hecho + f.bytes, total, f.nombre, verificando = true))
            verificar(ruta, f, cancelado)
            hecho += f.bytes
        }
    }

    /** Baja un fichero a su `.parte` y, entero, lo renombra. Reanuda con `Range` si se corta. */
    internal fun bajar(
        f: Fichero, destino: File, base: Long, total: Long,
        avance: (Progreso) -> Unit, cancelado: AtomicBoolean,
        abrir: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
    ) {
        val p = parte(destino)
        if (p.length() > f.bytes) p.delete()
        var cortes = 0
        while (p.length() < f.bytes) {
            if (cancelado.get()) throw ErrorIa.Cancelado
            val antes = p.length()
            try {
                tramo(f, p, base, total, avance, cancelado, abrir)
            } catch (e: ErrorIa) {
                throw e
            } catch (e: IOException) {
                // Un corte de red: se sigue desde lo que haya, si avanzó algo o quedan intentos.
                if (p.length() == antes) cortes++
                if (cortes > REINTENTOS_DE_RED) throw ErrorIa.Descarga(f.nombre, e.message ?: "corte de red")
                Thread.sleep(minOf(500L * cortes, 3000L))
            }
        }
        if (p.length() != f.bytes) throw ErrorIa.Descarga(f.nombre, "tamaño ${p.length()} y no ${f.bytes}")
        if (!p.renameTo(destino)) throw ErrorIa.Descarga(f.nombre, "no se pudo renombrar")
    }

    private fun tramo(
        f: Fichero, p: File, base: Long, total: Long,
        avance: (Progreso) -> Unit, cancelado: AtomicBoolean,
        abrir: (String) -> HttpURLConnection
    ) {
        val desde = p.length()
        val c = abrir(f.url)
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "PixPin")
        if (desde > 0) c.setRequestProperty("Range", "bytes=$desde-")
        try {
            val codigo = c.responseCode
            val reanuda = codigo == 206
            if (codigo != 200 && !reanuda) throw ErrorIa.Descarga(f.nombre, "HTTP $codigo")
            // Un 200 a una petición con rango: el servidor no reanuda, se empieza de cero.
            RandomAccessFile(p, "rw").use { out ->
                if (!reanuda) out.setLength(0)
                out.seek(if (reanuda) desde else 0)
                var escritos = if (reanuda) desde else 0
                c.inputStream.use { e ->
                    val buf = ByteArray(256 * 1024)
                    var ultimoAviso = 0L
                    while (true) {
                        if (cancelado.get()) throw ErrorIa.Cancelado
                        val n = e.read(buf)
                        if (n < 0) break
                        if (escritos + n > f.bytes) throw ErrorIa.Descarga(f.nombre, "más grande de lo esperado")
                        out.write(buf, 0, n)
                        escritos += n
                        if (escritos - ultimoAviso >= 1_000_000 || escritos == f.bytes) {
                            ultimoAviso = escritos
                            avance(Progreso(base + escritos, total, f.nombre, verificando = false))
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** Borra un modelo bajado (sus ficheros, marcas y partes). */
    fun borrar(raiz: File, m: Modelo) {
        carpetaModelo(raiz, m).deleteRecursively()
    }
}
