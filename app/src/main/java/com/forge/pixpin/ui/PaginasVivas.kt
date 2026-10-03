package com.forge.pixpin.ui

import android.content.Context
import android.graphics.Bitmap
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motormd.ClaseDeMedio
import com.forge.pixpin.motormd.Incrustados
import com.forge.pixpin.motormd.Markdown
import com.forge.pixpin.motormd.MarkdownBlock
import com.forge.pixpin.sincro.Codigos
import java.io.File

/**
 * **Las páginas vivas de una nota** (las mete el PC desde el 30-sep-2026; ver
 * `docs/investigacion/2026-09-30-paginas-vivas-android.md` del PC): una foto
 * `vivo-<código de la hoja>.png` que es la hoja pintada, y que se repinta cuando la hoja cambia.
 *
 * Al abrir la nota (y al volver a ella) se mira cada una: si su hoja —un lienzo o una página de
 * PDF con lo anotado— es más nueva que la foto, se vuelve a pintar a 1440 px como mucho y se
 * escribe de un tirón (temporal y cambio de nombre), porque la nota puede estar leyéndola y la
 * sincronización no debe llevarse una a medias. Las tablas, notas y croquis se quedan con la
 * última copia que pintó el PC (aquí no hay con qué pintarlas como allí).
 */
object PaginasVivas {
    private const val ANCHO = 1440

    class Encontrada(val proyecto: Proyecto, val hoja: Hoja)

    /** La hoja con ese código, primero en [preferido] y si no en todos los proyectos. */
    fun hoja(app: PixPinApp, codigo: String, preferido: String? = null): Encontrada? {
        val todos = app.proyectos.proyectos.value
        val orden = todos.sortedByDescending { it.id == preferido || Codigos.unico(it) == preferido }
        for (p in orden) p.hojas.firstOrNull { it.uid == codigo || Codigos.unico(it) == codigo }?.let { return Encontrada(p, it) }
        return null
    }

    /** El archivo de una ruta de la nota (`pixpin:files/…` o de este aparato). */
    fun archivo(context: Context, ruta: String): File = when {
        ruta.startsWith("pixpin:files/") -> File(context.filesDir, ruta.removePrefix("pixpin:files/"))
        ruta.startsWith("file://") -> File(android.net.Uri.parse(ruta).path.orEmpty())
        else -> File(ruta)
    }

    /**
     * Repinta las páginas vivas viejas de [nota]. Devuelve cuántas se repintaron (para que quien
     * enseña la nota las vuelva a leer). Trabajo de disco: fuera del hilo de la pantalla.
     */
    suspend fun ponerAlDia(context: Context, nota: String, proyectoDeLaNota: String? = null): Int {
        val app = context.applicationContext as? PixPinApp ?: return 0
        var hechas = 0
        for (m in medios(Markdown.parse(nota))) {
            val codigo = Incrustados.paginaViva(m.ruta) ?: continue
            val png = archivo(context, m.ruta)
            val h = hoja(app, codigo, proyectoDeLaNota) ?: continue
            val fuente = fechaDeLaFuente(context, h) ?: continue
            if (png.isFile && png.lastModified() >= fuente) continue
            val mapa = runCatching { pintar(context, h) }.getOrNull() ?: continue
            runCatching {
                png.parentFile?.mkdirs()
                val tmp = File(png.parentFile, png.name + ".tmp")
                tmp.outputStream().use { mapa.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(png)) { tmp.copyTo(png, overwrite = true); tmp.delete() }
                hechas++
            }
        }
        return hechas
    }

    private fun medios(bloques: List<MarkdownBlock>): List<MarkdownBlock.Medio> = bloques.flatMap {
        when (it) {
            is MarkdownBlock.Medio -> if (it.clase == ClaseDeMedio.IMAGEN) listOf(it) else emptyList()
            is MarkdownBlock.Caja -> medios(it.dentro)
            else -> emptyList()
        }
    }

    /** Cuándo cambió por última vez lo que pinta la hoja; null si no es de las que se repintan aquí. */
    private fun fechaDeLaFuente(context: Context, e: Encontrada): Long? {
        val h = e.hoja
        if (h.tabla != null || h.nota != null || h.croquis != null) return null
        val lienzo = h.dibujo?.let { File(ExcalidrawStore.rutaDe(context, it)).takeIf { f -> f.isFile }?.lastModified() } ?: 0L
        val pdf = if (h.pagina != null) listOfNotNull(e.proyecto.pdfOrigen).map { File(it) }.firstOrNull { it.isFile }?.lastModified() ?: 0L else 0L
        return maxOf(lienzo, pdf).takeIf { it > 0 }
    }

    /** Pinta [h] ya, para meterla como página viva; null si es de las que aquí no se pintan. */
    suspend fun pintarAhora(context: Context, p: Proyecto, h: Hoja): Bitmap? {
        if (h.tabla != null || h.nota != null || h.croquis != null) return null
        return pintar(context, Encontrada(p, h))
    }

    private suspend fun pintar(context: Context, e: Encontrada): Bitmap? {
        val h = e.hoja
        val p = e.proyecto
        if (h.pagina != null) {
            val pdf = listOfNotNull(p.pdfLimpio, p.pdfOrigen).firstOrNull { File(it).exists() } ?: return null
            return com.forge.pixpin.guardados.paginaAnotada(context, pdf, h.pagina!!, h.dibujo, h.dibujo?.let { ExcalidrawStore.rutaDe(context, it) }, ANCHO)
        }
        val escena = h.dibujo?.let { ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, it)) } ?: return null
        val caja = runCatching { com.forge.pixpin.motor.getCommonBounds(escena.contenidoVisible) }.getOrNull() ?: return null
        val ancho = (caja.x2 - caja.x1).coerceAtLeast(1.0)
        val escala = (ANCHO / ancho).coerceIn(0.2, 3.0)
        return DrawExport.aBitmap(escena, escala) { id -> escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } }
    }
}
