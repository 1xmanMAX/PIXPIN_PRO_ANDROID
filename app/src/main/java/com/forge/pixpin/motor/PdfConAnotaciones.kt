package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap

/**
 * **El PDF con todo lo anotado, y que sigue siendo el PDF** (29-sep-2026).
 *
 * Lo pidió el usuario: exportar un PDF del chat como PDF salía **sin** lo anotado, y lo que quiere
 * es «el PDF editable, más los bordes expandidos, más las anotaciones, más los marcadores; una
 * función más completa y más confiable».
 *
 * Se parte del archivo **tal cual** y se le pegan revisiones al final (ver [PdfEscritura.incremental]):
 * ni un byte del original se mueve, así que el texto se sigue seleccionando, los vectores siguen
 * siendo vectores y un lector que no sepa de capas lo abre igual. Por hoja, en este orden:
 *
 * 1. La **matriz** de lo anotado, medida con la caja **original**: sobre ella se dibujó.
 * 2. Si el lector tenía márgenes para anotar, la hoja se **ensancha** lo mismo ([PdfAnotado.ensanchar]).
 * 3. Lo anotado encima, **como vectores**, en la capa de PixPin, que se puede apagar
 *    ([DrawPdf.anotarPagina]). Con la hoja ya ancha, lo de los márgenes cabe y no se recorta.
 *
 * Y al final los **marcadores** en el índice ([PdfAnotado.conMarcadores]).
 *
 * Lo que no sale bien se salta y sigue: una hoja que no se deja anotar se queda como estaba. Si al
 * final el archivo no se vuelve a leer, null: mejor no compartir nada que compartir un PDF roto.
 */
object PdfConAnotaciones {

    /**
     * [base] es el PDF **sin nada anotado dentro** (en un proyecto, su copia limpia). [escenaDe]
     * da lo anotado de cada hoja en unidades de [PdfDoc.PAGE_WIDTH], con el cero en la esquina de
     * la hoja —el margen izquierdo son equis negativas—. [margen] es cuánto mide cada margen del
     * lector en esas unidades.
     */
    fun hacer(
        context: Context,
        base: ByteArray,
        escenaDe: (Int) -> Scene?,
        izquierda: Boolean = false,
        derecha: Boolean = false,
        margen: Double = PdfDoc.PAGE_WIDTH * Lectura.MARGEN_DEL_PDF.toDouble(),
        marcadores: List<PdfAnotado.Marcador> = emptyList(),
        hojaPintada: (Int) -> Bitmap? = { null }
    ): ByteArray? = runCatching {
        var bytes = base
        val cuantas = leerPdf(bytes)?.takeIf { !it.cifrado }?.paginas()?.size ?: return null
        for (i in 0 until cuantas) {
            val archivo = leerPdf(bytes) ?: break
            val escena = escenaDe(i)?.takeIf { it.contenidoVisible.isNotEmpty() }
            val caja = PdfAnotado.cajaDePagina(archivo, i) ?: continue
            val giro = PdfAnotado.giroDePagina(archivo, i)
            val anchoPt = if (giro == 90 || giro == 270) caja[3] - caja[1] else caja[2] - caja[0]
            val altoPt = if (giro == 90 || giro == 270) caja[2] - caja[0] else caja[3] - caja[1]
            if (anchoPt <= 0 || altoPt <= 0) continue
            val ancho = PdfDoc.PAGE_WIDTH.toDouble()
            val alto = ancho * altoPt / anchoPt
            val matriz = PdfAnotado.matrizDePagina(archivo, i, ancho, alto) ?: continue
            val porUnidad = anchoPt / ancho

            // Los márgenes, lo mismo que en el lector.
            if (izquierda || derecha) {
                PdfAnotado.ensanchar(
                    archivo, i,
                    if (izquierda) margen * porUnidad else 0.0,
                    if (derecha) margen * porUnidad else 0.0
                )?.let { bytes = it }
            }
            if (escena == null) continue
            // La hoja pintada solo la necesita un mosaico, que coge los píxeles de lo que tapa.
            val pintada = if (escena.contenidoVisible.any { it.type == ElementType.MOSAIC }) hojaPintada(i) else null
            DrawPdf.anotarPagina(
                context, bytes, i, escena, ancho, alto,
                nombreDeLaCapa = "PixPin · hoja ${i + 1}",
                imageProvider = { id -> escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                hojaPintada = pintada,
                matrizFija = matriz
            )?.let { bytes = it }
            pintada?.takeIf { !it.isRecycled }?.recycle()
        }
        if (marcadores.isNotEmpty()) leerPdf(bytes)?.let { a -> PdfAnotado.conMarcadores(a, marcadores)?.let { bytes = it } }
        bytes.takeIf { leerPdf(it)?.pagina(0) != null }
    }.getOrNull()

    /** Los marcadores del lector ([Marcas]) como los del índice: «🔖 Hoja 3». */
    fun marcadoresDe(marcas: List<Marca>): List<PdfAnotado.Marcador> =
        Marcas.enOrden(marcas).map { m ->
            val pagina = Marcas.paginaDe(m)
            PdfAnotado.Marcador("${m.emoji} Hoja ${pagina + 1}", pagina, m.y - pagina)
        }
}
