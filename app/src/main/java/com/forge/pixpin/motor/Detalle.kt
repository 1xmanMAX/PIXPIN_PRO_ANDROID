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

    /**
     * Lo que un medio de una nota aporta al PDF: una imagen en JPEG a tamaño razonable, o
     * la etiqueta de un archivo («informe.pdf · PDF · 1,2 MB»). Ver [PdfDeNota.MedioParaPdf].
     */
    fun medioParaPdf(ruta: String): PdfDeNota.MedioParaPdf? {
        val archivo = File(ruta)
        if (!archivo.exists()) return null
        val extension = ruta.substringAfterLast('.', "").lowercase()
        if (extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "gif")) {
            val medidas = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(ruta, medidas)
            if (medidas.outWidth > 0) {
                var muestra = 1
                while (medidas.outWidth / muestra > 1400) muestra *= 2
                val bmp = android.graphics.BitmapFactory.decodeFile(ruta, android.graphics.BitmapFactory.Options().apply { inSampleSize = muestra })
                if (bmp != null) {
                    val salida = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, salida)
                    val r = PdfDeNota.MedioParaPdf(salida.toByteArray(), bmp.width, bmp.height, null)
                    bmp.recycle()
                    return r
                }
            }
        }
        val tipo = when (extension) {
            "pdf" -> "PDF"
            "m4a", "mp3", "ogg", "oga", "opus", "wav", "flac", "aac", "amr", "3gp" -> "Audio"
            "mp4", "mkv", "mov", "webm" -> "Vídeo"
            "" -> "Archivo"
            else -> extension.uppercase()
        }
        return PdfDeNota.MedioParaPdf(null, 0, 0, "${archivo.name} · $tipo · ${legible(archivo.length())}")
    }

    /**
     * **Las letras de reserva del PDF de una nota** (15-oct-2026): las del teléfono para lo que la
     * Helvetica no tiene (∑, π, ✓, flechas, griegas) y los emoji pintados con la letra de color.
     * Ver [LetrasDelPdf]. Se abre cada una solo si hace falta.
     */
    fun letrasParaPdf(): LetrasDelPdf = LetrasDelPdf.delSistema(dibujo = ::emojiParaPdf)

    /**
     * **Un emoji (o lo que ninguna letra con contornos tiene) pintado**, sobre blanco, para ir en el
     * PDF como imagen: la letra de color del teléfono no tiene contornos que incrustar. Null si no
     * pinta nada (un carácter que el teléfono tampoco sabe).
     */
    fun emojiParaPdf(texto: String): PdfDeNota.MedioParaPdf? = runCatching {
        val pincel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 96f; color = android.graphics.Color.BLACK }
        if (!pincel.hasGlyph(texto)) return null
        val ancho = pincel.measureText(texto).toInt().coerceAtLeast(1)
        val fm = pincel.fontMetricsInt
        val alto = (fm.descent - fm.ascent).coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(ancho, alto, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply { drawColor(android.graphics.Color.WHITE); drawText(texto, 0f, -fm.ascent.toFloat(), pincel) }
        val salida = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, salida)
        bmp.recycle()
        PdfDeNota.MedioParaPdf(salida.toByteArray(), ancho, alto, null)
    }.getOrNull()

    /** `1,2 MB`, `340 KB`, `812 B`. */
    fun legible(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
