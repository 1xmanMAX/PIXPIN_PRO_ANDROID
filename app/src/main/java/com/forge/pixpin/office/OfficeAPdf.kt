package com.forge.pixpin.office

import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * **Un Word o un PowerPoint, a PDF en vectores**, con la misma maquetación y el mismo pintor que el
 * lector ([PintorOffice]): el texto sale como texto (se busca y se copia), las formas como formas.
 *
 * Es la base de lo que se comparte, se imprime o pasa a un proyecto desde el lector: cada hoja del
 * PDF mide lo mismo que la del documento, así que la tinta (puesta en unidades de la hoja, con su
 * `.hoja`) cae en el mismo sitio sin convertir nada.
 */
object OfficeAPdf {
    /** Escribe en [destino] todas las hojas de [d] (maqueta lo que falte). Devuelve cuántas; 0 si nada. */
    fun hacer(d: DocumentoOffice, destino: File): Int {
        if (!d.terminado) d.maquetarTodo { }
        val total = d.cuantas()
        if (total <= 0) return 0
        val pdf = PdfDocument()
        try {
            val pintor = d.pintor()
            var hechas = 0
            for (i in 0 until total) {
                val p = d.pagina(i) ?: continue
                val info = PdfDocument.PageInfo.Builder(
                    kotlin.math.ceil(p.ancho.toDouble()).toInt().coerceAtLeast(1),
                    kotlin.math.ceil(p.alto.toDouble()).toInt().coerceAtLeast(1),
                    i + 1
                ).create()
                val hoja = pdf.startPage(info)
                // Un punto del documento es un punto del PDF; las fotos, a unos 200 ppp.
                pintor.pintar(hoja.canvas, p, 1f, total, nitidezDeFotos = 3f)
                pdf.finishPage(hoja)
                hechas++
            }
            destino.parentFile?.mkdirs()
            val tmp = File(destino.path + ".tmp")
            tmp.outputStream().use { pdf.writeTo(it) }
            if (!tmp.renameTo(destino)) { tmp.copyTo(destino, overwrite = true); tmp.delete() }
            return hechas
        } finally {
            pdf.close()
        }
    }

    /**
     * El PDF de [d] en la caché, hecho una vez por documento (ruta, fecha y peso del original):
     * compartir dos veces no lo rehace.
     */
    fun enCache(c: android.content.Context, d: DocumentoOffice): File? = runCatching {
        val original = File(d.ruta)
        val huella = "${d.ruta}|${original.lastModified()}|${original.length()}".hashCode().toUInt().toString(16)
        val carpeta = File(c.cacheDir, "office-pdf").apply { mkdirs() }
        val destino = File(carpeta, "$huella.pdf")
        if (destino.isFile && destino.length() > 0) return@runCatching destino
        carpeta.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        if (hacer(d, destino) > 0) destino else null
    }.getOrNull()
}
