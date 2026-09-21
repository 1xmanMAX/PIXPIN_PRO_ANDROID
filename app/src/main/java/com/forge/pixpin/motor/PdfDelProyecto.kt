package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Rehace el PDF de un proyecto **desde la copia limpia**.
 *
 * ## Por qué se rehace y no se va añadiendo
 *
 * Al principio cada anotación se escribía encima de la anterior, que es lo que
 * el formato invita a hacer. Funciona una vez y se estropea a la segunda:
 *
 * - Retocar la misma página tres veces deja **tres capas** con tres versiones
 *   del mismo trazo, una encima de otra. Se ve el dibujo triplicado y no hay
 *   forma de quitar la de en medio.
 * - **Borrar no borra.** Quitas una raya del lienzo, se escribe una capa nueva
 *   sin ella… y la raya sigue viéndose, porque está en la capa de antes.
 * - Y el archivo crece con cada guardado.
 *
 * Guardando aparte una copia intacta, el problema entero desaparece: **lo que
 * manda es el dibujo**, no lo que se escribió la última vez. Cada guardado
 * parte del original y le pone encima las hojas tal como están **ahora**, así
 * que borrar borra, retocar veinte veces sigue dejando una capa por página, y
 * el resultado no depende del orden en que se hicieron las cosas.
 *
 * Cuesta rehacer el documento en cada guardado. Es un puñado de páginas y unos
 * milisegundos, y se paga con gusto a cambio de que el archivo sea siempre
 * exactamente lo que se ve en pantalla.
 */
object PdfDelProyecto {

    /**
     * Escribe el PDF del proyecto con todas sus hojas anotadas.
     *
     * Devuelve true si quedó escrito. Lo que no encuentra —una hoja sin dibujo,
     * un dibujo borrado, una página que ya no existe— se salta sin ruido: son
     * casos normales, no fallos.
     */
    fun rehacer(
        context: Context,
        proyecto: Proyecto,
        imageProvider: (String) -> Bitmap? = { null }
    ): Boolean = synchronized(CANDADO) { rehacerDeUnoEnUno(context, proyecto, imageProvider) }

    /**
     * **De uno en uno** (20-sep-2026). El lector rehace el documento en segundo plano al dejar de
     * anotar, y el editor al salir: si coincidían, los dos escribían **el mismo archivo temporal
     * a la vez** y el PDF del proyecto quedaba hecho de trozos de los dos. Un PDF así no se
     * abre, y lo que veía el usuario era su página anotada **sin el fondo**. Ahora hay candado,
     * el temporal es de cada cual, y no se pisa el documento con algo que no se deje leer.
     */
    private val CANDADO = Any()

    private fun rehacerDeUnoEnUno(
        context: Context,
        proyecto: Proyecto,
        imageProvider: (String) -> Bitmap?
    ): Boolean = runCatching {
        val destino = proyecto.pdfOrigen?.let { File(it) } ?: return false
        val limpio = proyecto.pdfLimpio?.let { File(it) }?.takeIf { it.exists() }
            // Sin copia limpia no se puede rehacer sin arriesgarse a duplicar lo
            // que ya hubiera dentro. Es el caso de los proyectos creados antes
            // de que existiera: mejor no tocar el archivo que estropearlo.
            ?: return false

        var bytes = limpio.readBytes()
        var algo = false

        for (hoja in proyecto.hojas) {
            val pagina = hoja.pagina ?: continue
            val dibujo = hoja.dibujo ?: continue
            val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, dibujo))
                ?: continue
            if (escena.contenidoVisible.isEmpty()) continue

            // La página pintada, y **del archivo limpio**: es la misma con la
            // que se renderizó para dibujar encima, así que la escala de lo
            // anotado no puede desincronizarse. Hace falta además para que el
            // mosaico tenga píxeles que tapar.
            val pintada = PdfDoc.render(limpio.path, pagina, PdfDoc.PAGE_WIDTH) ?: continue
            val salida = DrawPdf.anotarPagina(
                context, bytes, pagina, escena,
                pintada.width.toDouble(), pintada.height.toDouble(),
                nombreDeLaCapa = "$NOMBRE_DE_CAPA ${pagina + 1}",
                imageProvider = imageProvider,
                hojaPintada = pintada
            )
            if (!pintada.isRecycled) pintada.recycle()
            if (salida == null) continue
            bytes = salida
            algo = true
        }

        // **Las notas van detrás**, en el orden en que están en el proyecto.
        // Se escriben al rehacer, como las anotaciones: así el archivo es
        // siempre exactamente lo que dice el proyecto, y quitar una nota la
        // quita de verdad en vez de dejarla dentro para siempre.
        proyecto.hojas.forEach { hoja ->
            val nota = hoja.nota ?: return@forEach
            if (nota.isBlank()) return@forEach
            PdfDeNota.aniadir(bytes, nota, Detalle::medioParaPdf)?.let {
                bytes = it
                algo = true
            }
        }

        if (!algo) {
            // Nada anotado: el documento vuelve a ser el original. Es lo
            // correcto — si has borrado todo lo que habías dibujado, lo que
            // queda es el PDF de partida.
            if (destino.readBytes().contentEquals(bytes)) return true
        }

        // En un temporal y se cambia al final: si algo se tuerce a mitad, el
        // PDF de alguien no puede quedarse a medias.
        if (leerPdf(bytes)?.pagina(0) == null) return false
        val temporal = File(destino.parentFile, "${destino.name}.${System.nanoTime()}.nuevo")
        temporal.writeBytes(bytes)
        if (temporal.length() <= 0) { temporal.delete(); return false }
        temporal.copyTo(destino, overwrite = true)
        temporal.delete()
        true
    }.getOrDefault(false)

    /**
     * **La página, aunque el documento esté roto.** Si el PDF del proyecto no se deja pintar —le
     * pasó a los que se estropearon antes del candado—, se **rehace desde la copia limpia** y se
     * vuelve a intentar; y si ni así, se pinta la copia limpia, que lo anotado va encima de todos
     * modos. El fondo no desaparece nunca mientras exista la copia.
     */
    fun paginaSana(context: Context, proyecto: Proyecto?, ruta: String, pagina: Int, ancho: Int): Bitmap? {
        PdfDoc.render(ruta, pagina, ancho)?.let { return it }
        val limpio = proyecto?.pdfLimpio?.takeIf { File(it).exists() } ?: return null
        runCatching { File(limpio).copyTo(File(ruta), overwrite = true) }
        rehacer(context, proyecto)
        return PdfDoc.render(ruta, pagina, ancho) ?: PdfDoc.render(limpio, pagina, ancho)
    }

    /** Lo que se lee en el panel de capas de un lector de escritorio. */
    private const val NOMBRE_DE_CAPA = "PixPin — página"
}
