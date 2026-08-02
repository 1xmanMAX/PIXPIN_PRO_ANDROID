package com.forge.pixpin.croquis

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sacar el croquis de la aplicación.
 *
 * El PDF sale **vectorial**: `PdfDocument` entrega un `Canvas` corriente, así
 * que el mismo [CroquisRenderer] que pinta la pantalla escribe geometría y
 * texto de verdad en el documento. Se amplía sin pixelar y se imprime a escala.
 * Ninguna dependencia: `PdfDocument` viene en Android.
 */
object CroquisExport {

    /** A4 en puntos PostScript (1/72"), que es la unidad de PdfDocument. */
    private const val A4_ANCHO = 595
    private const val A4_ALTO = 842
    private const val MARGEN = 48

    /** Un punto mide esto en metros: 1/72 de pulgada. */
    private const val METROS_POR_PUNTO = 0.0254 / 72.0

    /**
     * `hojaA4` solo usa la vista cuando el croquis está vacío, y aquí un
     * croquis vacío ya se ha descartado antes. Vale cualquiera.
     */
    private val VISTA_NEUTRA = Vista(P(0.0, 0.0), 1.0)

    /**
     * Escribe el croquis como PDF en Descargas y devuelve su Uri, o null si no
     * hay nada que exportar o falla la escritura.
     */
    fun aPdf(
        context: Context,
        croquis: Croquis,
        fondo: Bitmap? = null,
        locale: Locale = Locale.getDefault()
    ): Uri? {
        // Apaisado si el dibujo es más ancho que alto: encaja mejor y evita
        // exportar un plano largo en una hoja donde no cabe.
        // Se exporta LA HOJA que se ve enmarcada en el editor, no el contorno
        // de lo dibujado: si no, lo que sale por la impresora no es lo que se
        // encuadró en pantalla.
        val hoja = CroquisGeometria.hojaA4(croquis, VISTA_NEUTRA, 1000, 1000) ?: return null
        val apaisado = hoja.ancho > hoja.alto
        val ancho = if (apaisado) A4_ALTO else A4_ANCHO
        val alto = if (apaisado) A4_ANCHO else A4_ALTO

        val vista = CroquisGeometria.vistaParaCaja(hoja, ancho, alto, MARGEN) ?: return null

        val doc = PdfDocument()
        return try {
            val pagina = doc.startPage(PdfDocument.PageInfo.Builder(ancho, alto, 1).create())
            pagina.canvas.drawColor(Color.WHITE)
            CroquisRenderer.dibujar(
                pagina.canvas, croquis, vista, ancho, alto, fondo, Color.BLACK, locale
            )
            pieDePagina(pagina.canvas, vista, alto, locale)
            doc.finishPage(pagina)
            escribirEnDescargas(context, doc)
        } catch (t: Throwable) {
            null
        } finally {
            doc.close()
        }
    }

    /**
     * La escala y la fecha, abajo.
     *
     * Un croquis acotado que circula sin constancia de su escala es una trampa
     * para quien lo reciba: las cotas dicen una cosa y la regla sobre el papel,
     * otra.
     */
    private fun pieDePagina(canvas: Canvas, vista: Vista, alto: Int, locale: Locale) {
        val metrosDePapelPorMetroReal = vista.pixelsPorMetro * METROS_POR_PUNTO
        if (metrosDePapelPorMetroReal <= 0.0) return
        val denominador = 1.0 / metrosDePapelPorMetroReal
        val fecha = SimpleDateFormat("dd/MM/yyyy", locale).format(Date())
        val texto = String.format(locale, "Escala 1:%.0f  ·  %s  ·  PixPin", denominador, fecha)

        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 9f
        }
        canvas.drawText(texto, MARGEN.toFloat(), alto - MARGEN / 2f, tinta)
    }

    private fun escribirEnDescargas(context: Context, doc: PdfDocument): Uri? {
        val nombre = "PixPin_croquis_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.pdf"
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nombre)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PixPin")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: return null
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * El croquis rasterizado, para compartir como imagen. [anchoPx] fija la
     * calidad; el alto sale de la proporción del propio dibujo.
     */
    fun aBitmap(
        croquis: Croquis,
        fondo: Bitmap? = null,
        anchoPx: Int = 2000,
        locale: Locale = Locale.getDefault()
    ): Bitmap? {
        // Misma hoja que enseña el pin, para que lo copiado tenga **la misma
        // forma** que lo que se estaba viendo. Antes se encuadraba el contorno
        // del dibujo y la copia salía con otra proporción que el pin.
        val hoja = CroquisGeometria.hojaA4(croquis, VISTA_NEUTRA, 1000, 1000) ?: return null
        val altoPx = (anchoPx * hoja.alto / hoja.ancho).toInt().coerceIn(200, 6000)
        val margen = anchoPx / 40

        val vista = CroquisGeometria.vistaParaCaja(hoja, anchoPx, altoPx, margen) ?: return null
        val bmp = Bitmap.createBitmap(anchoPx, altoPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        CroquisRenderer.dibujar(canvas, croquis, vista, anchoPx, altoPx, fondo, Color.BLACK, locale)
        return bmp
    }
}
