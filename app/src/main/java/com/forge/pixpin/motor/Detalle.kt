package com.forge.pixpin.motor

import android.content.Context
import java.io.File

/**
 * **Cuánto pesa lo que se está editando, y cuánto su proyecto.**
 *
 * Es la pestaña «Detalle» de los tres editores (lienzo, croquis 3D y notas): el nombre del
 * archivo y su tamaño, y el del proyecto en el que vive con lo que suma todo lo suyo —el
 * PDF, cada lienzo con sus fotos, cada croquis, cada nota—. Lo pidió el usuario
 * (5-sep-2026): con las fotos del chat entrando solas como hojas, un proyecto engorda sin
 * que se vea dónde.
 */
object Detalle {

    /** Un archivo: cómo se llama y cuánto pesa (todo lo que arrastra: un lienzo suma sus fotos). */
    class Archivo(val nombre: String, val bytes: Long)

    /** Un proyecto: su nombre, cuántas hojas y cuánto pesa todo lo suyo. */
    class DelProyecto(val nombre: String, val hojas: Int, val bytes: Long)

    /** Un lienzo: el `.excalidraw` comprimido más las fotos que usa. */
    fun delLienzo(context: Context, id: String): Archivo {
        val ruta = ExcalidrawStore.rutaDe(context, id)
        var bytes = File(ruta).length()
        ExcalidrawStore.cargar(ruta)?.files?.values?.forEach { f ->
            f.path?.let { bytes += File(it).length() }
        }
        return Archivo("$id.excalidraw", bytes)
    }

    fun delCroquis(context: Context, id: String): Archivo {
        val archivo = File(File(context.filesDir, "croquis3d"), "$id.croquis.gz")
        return Archivo(archivo.name, archivo.length())
    }

    fun deLaNota(id: String, texto: String): Archivo = Archivo("$id.md", texto.toByteArray(Charsets.UTF_8).size.toLong())

    fun delProyecto(context: Context, proyecto: Proyecto): DelProyecto {
        var bytes = 0L
        proyecto.pdfOrigen?.let { bytes += File(it).length() }
        proyecto.pdfLimpio?.let { bytes += File(it).length() }
        val vistos = HashSet<String>()
        for (h in proyecto.hojas) {
            h.dibujo?.takeIf { vistos.add("d$it") }?.let { bytes += delLienzo(context, it).bytes }
            h.nota?.let { bytes += it.toByteArray(Charsets.UTF_8).size }
        }
        for (c in proyecto.croquis) bytes += delCroquis(context, c).bytes
        return DelProyecto(proyecto.nombre, proyecto.hojas.size, bytes)
    }

    /** El proyecto en el que vive un lienzo, si alguno lo tiene como hoja. */
    fun proyectoDelLienzo(proyectos: List<Proyecto>, dibujo: String): Proyecto? =
        proyectos.firstOrNull { p -> p.hojas.any { it.dibujo == dibujo } }

    /** `1,2 MB`, `340 KB`, `812 B`. */
    fun legible(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
