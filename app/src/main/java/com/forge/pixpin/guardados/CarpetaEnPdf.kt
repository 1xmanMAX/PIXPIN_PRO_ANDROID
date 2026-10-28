package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.forge.pixpin.motor.DrawFonts
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.HojaDePlantilla
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.encuadreEnPagina
import com.forge.pixpin.motor.paginaApaisada
import com.forge.pixpin.pin.ImageStore
import java.io.File

/**
 * **Una carpeta de páginas como PDF** (25-oct-2026, portado del PC: `compartir::carpeta`). El
 * usuario del PC: «al exportar, sale como un PDF: una página por elemento, y de cada lienzo solo lo
 * que va sobre la plantilla o sobre el marco; si no hay ninguno, todo lo dibujado».
 *
 * La regla de los lienzos es la de [HojaDePlantilla.hojasDeCuaderno]; lo pinta el mismo
 * [Renderer] que el PDF del lienzo ([com.forge.pixpin.motor.DrawPdf]), así que lo dibujado sale
 * **vectorial**, igual que al exportar un lienzo. Una hoja de plantilla sale **a ras**: la página
 * del PDF mide lo que la hoja (en A4, sin margen); un marco o el dibujo entero, en A4 con su
 * margen, como siempre. Una foto o una página de un PDF, una página con la imagen encajada; una
 * nota, su texto en las páginas que pida.
 *
 * Lo que el PC hace y aquí no: el **índice de marcadores** con el título de cada página («Día 1»,
 * «Día 2»). `PdfDocument` de Android no sabe escribirlo.
 */
object CarpetaEnPdf {

    private const val A4_ANCHO = 595.0
    private const val A4_ALTO = 842.0
    /** El margen de un marco o de un dibujo sin hoja, el mismo que el del PDF de un lienzo. */
    private const val MARGEN = 28.0
    /** El margen de una foto y de una nota. */
    private const val MARGEN_DE_TEXTO = 48f
    /** El ancho al que se pinta una página de un PDF o la primera de un archivo (y la página en imagen). */
    const val ANCHO_DE_PAGINA = 1600

    /**
     * **Escribe el PDF de [paginas]** (cada mensaje con su título, en orden) en `cache/share`, con el
     * nombre de la carpeta. Null si no salió ninguna página. Lee el disco: fuera del hilo de la
     * pantalla.
     */
    suspend fun escribir(context: Context, nombre: String, paginas: List<Mensaje>): File? {
        val documento = PdfDocument()
        val catalogo = CarpetasDelChat.catalogo(context)
        var numero = 0
        fun hoja(ancho: Double, alto: Double, pintar: (Canvas) -> Unit) {
            val w = ancho.toInt().coerceAtLeast(1)
            val h = alto.toInt().coerceAtLeast(1)
            val p = documento.startPage(PdfDocument.PageInfo.Builder(w, h, ++numero).create())
            runCatching { pintar(p.canvas) }
            documento.finishPage(p)
        }
        try {
            for (m in paginas) runCatching { pagina(context, m, catalogo, ::hoja) }
            if (numero == 0) return null
            val carpeta = File(context.cacheDir, "share").apply { mkdirs() }
            val base = if (nombre.isBlank()) "Carpeta" else ExportarProyecto.nombreDeArchivo(nombre)
            val destino = File(carpeta, "$base.pdf")
            destino.outputStream().use { documento.writeTo(it) }
            return destino.takeIf { it.length() > 0 }
        } finally {
            documento.close()
        }
    }

    /*
     * **La carpeta en la hoja de compartir** vive en [CompartirCarpeta] (28-oct-2026), con todos los
     * formatos de toda la aplicación; aquí queda el PDF, que también usa ella.
     */

    private suspend fun pagina(
        context: Context,
        m: Mensaje,
        catalogo: HojaDePlantilla.Catalogo,
        hoja: (Double, Double, (Canvas) -> Unit) -> Unit
    ) {
        when (m.clase) {
            Clase.DIBUJO -> {
                val ref = m.referencia ?: return
                val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, ref)) ?: return
                val renderizador = Renderer(
                    imageProvider = { id -> escena.files[id]?.path?.let { ImageStore.load(it) } },
                    typefaces = DrawFonts.provider(context),
                    paraExportar = true
                )
                for (h in HojaDePlantilla.hojasDeCuaderno(escena, catalogo)) {
                    val (ancho, alto) = if (h.aRas) aRas(h.caja.width, h.caja.height)
                    else if (paginaApaisada(h.caja)) A4_ALTO to A4_ANCHO else A4_ANCHO to A4_ALTO
                    val vista = encuadreEnPagina(h.caja, ancho, alto, if (h.aRas) 0.0 else MARGEN) ?: continue
                    hoja(ancho, alto) { c ->
                        renderizador.renderScene(c, escena.copy(elements = h.contenido, viewport = vista), ancho, alto)
                    }
                }
            }
            Clase.PAGINA -> {
                val pdf = m.ruta ?: return
                val n = m.pagina ?: return
                val mapa = paginaAnotada(
                    context, pdf, n, m.referencia,
                    m.referencia?.let { ExcalidrawStore.rutaDe(context, it) }, ANCHO_DE_PAGINA
                ) ?: return
                imagen(mapa, hoja, margen = 0f)
            }
            Clase.IMAGEN -> {
                val mapa = m.ruta?.let { ImageStore.load(it) } ?: return
                imagen(mapa, hoja, MARGEN_DE_TEXTO)
            }
            Clase.ARCHIVO -> {
                val ruta = m.ruta ?: return
                val mapa = when {
                    ruta.endsWith(".pdf", ignoreCase = true) -> PdfDoc.render(ruta, 0, ANCHO_DE_PAGINA)
                    else -> ImageStore.load(ruta)
                } ?: return
                imagen(mapa, hoja, if (ruta.endsWith(".pdf", ignoreCase = true)) 0f else MARGEN_DE_TEXTO)
            }
            Clase.NOTA -> texto(m.texto, hoja)
            else -> Unit
        }
    }

    /** Lo que mide la página de una hoja a ras: su forma, con el lado largo de un A4. */
    private fun aRas(w: Double, h: Double): Pair<Double, Double> {
        val (pw, ph) = if (w > h) A4_ALTO to A4_ANCHO else A4_ANCHO to A4_ALTO
        val escala = minOf(pw / w, ph / h)
        return w * escala to h * escala
    }

    /** Una imagen encajada en una página de su misma forma. */
    private fun imagen(mapa: Bitmap, hoja: (Double, Double, (Canvas) -> Unit) -> Unit, margen: Float) {
        val (ancho, alto) = aRas(mapa.width.toDouble() + margen * 2, mapa.height.toDouble() + margen * 2)
        hoja(ancho, alto) { c ->
            c.drawColor(Color.WHITE)
            val e = minOf(ancho / (mapa.width + margen * 2), alto / (mapa.height + margen * 2)).toFloat()
            val x = (ancho.toFloat() - mapa.width * e) / 2
            val y = (alto.toFloat() - mapa.height * e) / 2
            c.drawBitmap(mapa, null, RectF(x, y, x + mapa.width * e, y + mapa.height * e), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (!mapa.isRecycled) mapa.recycle()
    }

    /** Una nota: su texto, en A4, partido en las páginas que pida. */
    private fun texto(texto: String, hoja: (Double, Double, (Canvas) -> Unit) -> Unit) {
        if (texto.isBlank()) return
        val pintura = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.BLACK }
        val util = (A4_ANCHO - MARGEN_DE_TEXTO * 2).toInt()
        val capa = StaticLayout.Builder.obtain(texto, 0, texto.length, pintura, util)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .build()
        val alto = A4_ALTO - MARGEN_DE_TEXTO * 2
        var linea = 0
        while (linea < capa.lineCount) {
            val desde = capa.getLineTop(linea)
            var hasta = linea
            while (hasta < capa.lineCount && capa.getLineBottom(hasta) - desde <= alto) hasta++
            if (hasta == linea) hasta = linea + 1
            val fin = capa.getLineBottom(hasta - 1)
            hoja(A4_ANCHO, A4_ALTO) { c ->
                c.drawColor(Color.WHITE)
                c.save()
                c.translate(MARGEN_DE_TEXTO, MARGEN_DE_TEXTO - desde)
                c.clipRect(0f, desde.toFloat(), util.toFloat(), fin.toFloat())
                capa.draw(c)
                c.restore()
            }
            linea = hasta
        }
    }
}
