package com.forge.pixpin.guardados

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.forge.pixpin.motor.Diapositivas
import com.forge.pixpin.motor.Diapositivas.Alineacion
import com.forge.pixpin.motor.Diapositivas.Ancla
import com.forge.pixpin.motor.Diapositivas.Geometria
import java.io.File
import java.util.zip.ZipFile

/**
 * **Un PowerPoint convertido en PDF**, una página por diapositiva (14-sep-2026).
 *
 * Así una presentación entra por el mismo camino que un PDF —proyecto con una hoja por
 * página, anotar encima, presentar— sin un segundo visor. Se pinta en **vectores** con
 * `PdfDocument`: el texto sigue siendo texto y se lee nítido a cualquier aumento; las fotos
 * van a la resolución que necesita su caja, no a la que traían (una foto de 12 Mpx en una
 * esquina de la diapositiva no puede costar 12 Mpx de memoria: ver la muerte por falta de
 * memoria del 14-sep).
 *
 * Qué se lee y qué no: ver [Diapositivas].
 */
object DiapositivasAPdf {

    /** Resolución de las fotos dentro del PDF: la de imprimir, como [com.forge.pixpin.pdf.ComprimirPdf]. */
    private const val PPP_DE_LAS_FOTOS = 200f

    /** Lado máximo de una foto decodificada, en píxeles, por muy grande que sea su caja. */
    private const val LADO_MAXIMO = 2400

    /**
     * Convierte [archivo] (llegado como [nombre]) en un PDF en [destino]. Devuelve cuántas
     * páginas salieron; lanza [Diapositivas.NoSeLee] con el porqué si no se puede.
     */
    fun convertir(archivo: File, nombre: String, destino: File): Int {
        val presentacion = Diapositivas.leer(archivo, nombre)
        val ancho = (presentacion.ancho / Diapositivas.EMU_POR_PUNTO).toInt().coerceAtLeast(1)
        val alto = (presentacion.alto / Diapositivas.EMU_POR_PUNTO).toInt().coerceAtLeast(1)
        val documento = PdfDocument()
        try {
            ZipFile(archivo).use { zip ->
                for ((i, d) in presentacion.diapositivas.withIndex()) {
                    val pagina = documento.startPage(PdfDocument.PageInfo.Builder(ancho, alto, i + 1).create())
                    val pintor = Pintor(zip, pagina.canvas, ancho, alto)
                    runCatching { pintor.diapositiva(d) }
                    documento.finishPage(pagina)
                    // Las fotos se sueltan **después** de cerrar la página: el PDF puede no
                    // haberlas escrito aún al pintarlas.
                    pintor.soltar.forEach { it.recycle() }
                }
            }
            destino.parentFile?.mkdirs()
            destino.outputStream().use { documento.writeTo(it) }
        } finally {
            documento.close()
        }
        return presentacion.diapositivas.size
    }

    private fun pt(emu: Double): Float = (emu / Diapositivas.EMU_POR_PUNTO).toFloat()

    private class Pintor(val zip: ZipFile, val lienzo: Canvas, val ancho: Int, val alto: Int) {

        val soltar = ArrayList<Bitmap>()

        fun diapositiva(d: Diapositivas.Diapositiva) {
            lienzo.drawColor(d.fondo ?: 0xFFFFFFFF.toInt())
            d.fotoDeFondo?.let { entrada ->
                foto(entrada, ancho.toFloat(), alto.toFloat())?.let { bmp ->
                    lienzo.drawBitmap(bmp, null, RectF(0f, 0f, ancho.toFloat(), alto.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    soltar += bmp
                }
            }
            for (p in d.piezas) runCatching { pieza(p) }
        }

        private fun pieza(p: Diapositivas.Pieza) {
            val c = p.caja
            val r = RectF(pt(c.x), pt(c.y), pt(c.x + c.ancho), pt(c.y + c.alto))
            lienzo.save()
            if (p.giro != 0.0) lienzo.rotate(p.giro.toFloat(), r.centerX(), r.centerY())
            when (p) {
                is Diapositivas.Foto -> fotoEn(p, r)
                is Diapositivas.Forma -> forma(p, r)
                is Diapositivas.Tabla -> tabla(p, r)
            }
            lienzo.restore()
        }

        private fun fotoEn(f: Diapositivas.Foto, r: RectF) {
            val bmp = foto(f.entrada, r.width(), r.height(), f.recorte) ?: return
            // El recorte ya se hizo al decodificar: el mapa es solo la parte que se ve.
            lienzo.save()
            if (f.volteadaH) lienzo.scale(-1f, 1f, r.centerX(), r.centerY())
            if (f.volteadaV) lienzo.scale(1f, -1f, r.centerX(), r.centerY())
            lienzo.drawBitmap(bmp, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            lienzo.restore()
            soltar += bmp
        }

        /**
         * La foto de [entrada], **decodificada al tamaño de su caja** y con su recorte hecho.
         * Una foto de móvil pegada en pequeño en una esquina no puede costar sus doce megapíxeles.
         */
        fun foto(entrada: String, anchoPt: Float, altoPt: Float, recorte: FloatArray? = null): Bitmap? {
            val e = zip.getEntry(entrada) ?: return null
            val bytes = zip.getInputStream(e).use { it.readBytes() }
            val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medidas)
            if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null   // EMF, WMF…
            val (l, t, der, b) = (recorte ?: floatArrayOf(0f, 0f, 0f, 0f)).toList()
            val visibleX = (1f - l - der).coerceIn(0.01f, 1f)
            val visibleY = (1f - t - b).coerceIn(0.01f, 1f)
            // Cuántos píxeles de la foto entera harían falta para que lo visible salga a la
            // resolución buscada.
            val quiereAncho = (anchoPt / 72f * PPP_DE_LAS_FOTOS / visibleX).coerceAtMost(LADO_MAXIMO / visibleX)
            val quiereAlto = (altoPt / 72f * PPP_DE_LAS_FOTOS / visibleY).coerceAtMost(LADO_MAXIMO / visibleY)
            var muestra = 1
            while (medidas.outWidth / (muestra * 2) >= quiereAncho && medidas.outHeight / (muestra * 2) >= quiereAlto) {
                muestra *= 2
            }
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra }
            ) ?: return null
            if (l == 0f && t == 0f && der == 0f && b == 0f) return bmp
            val x = (bmp.width * l.coerceAtLeast(0f)).toInt()
            val y = (bmp.height * t.coerceAtLeast(0f)).toInt()
            val w = (bmp.width * visibleX).toInt().coerceIn(1, bmp.width - x)
            val h = (bmp.height * visibleY).toInt().coerceIn(1, bmp.height - y)
            val recortada = Bitmap.createBitmap(bmp, x, y, w, h)
            if (recortada !== bmp) bmp.recycle()
            return recortada
        }

        private fun forma(f: Diapositivas.Forma, r: RectF) {
            lienzo.save()
            if (f.geometria != Geometria.LINEA) {
                if (f.volteadaH) lienzo.scale(-1f, 1f, r.centerX(), r.centerY())
                if (f.volteadaV) lienzo.scale(1f, -1f, r.centerX(), r.centerY())
            }
            val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = pt(f.grosor).coerceAtLeast(0.5f)
            }
            fun dibujar(p: Paint) {
                when (f.geometria) {
                    Geometria.RECTANGULO -> lienzo.drawRect(r, p)
                    Geometria.REDONDEADO -> {
                        val radio = minOf(r.width(), r.height()) * 0.1667f
                        lienzo.drawRoundRect(r, radio, radio, p)
                    }
                    Geometria.ELIPSE -> lienzo.drawOval(r, p)
                    Geometria.TRIANGULO -> lienzo.drawPath(Path().apply {
                        moveTo(r.centerX(), r.top); lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close()
                    }, p)
                    Geometria.ROMBO -> lienzo.drawPath(Path().apply {
                        moveTo(r.centerX(), r.top); lineTo(r.right, r.centerY())
                        lineTo(r.centerX(), r.bottom); lineTo(r.left, r.centerY()); close()
                    }, p)
                    Geometria.LINEA -> {
                        val x1 = if (f.volteadaH) r.right else r.left
                        val x2 = if (f.volteadaH) r.left else r.right
                        val y1 = if (f.volteadaV) r.bottom else r.top
                        val y2 = if (f.volteadaV) r.top else r.bottom
                        lienzo.drawLine(x1, y1, x2, y2, p)
                    }
                }
            }
            if (f.geometria != Geometria.LINEA) f.relleno?.let { relleno.color = it; dibujar(relleno) }
            f.borde?.let { borde.color = it; dibujar(borde) }
            lienzo.restore()
            f.texto?.let { texto(it, r) }
        }

        private fun tabla(t: Diapositivas.Tabla, r: RectF) {
            val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 0.75f; color = 0xFF9E9E9E.toInt()
            }
            val fondo = Paint().apply { style = Paint.Style.FILL }
            var y = r.top
            for ((fi, fila) in t.celdas.withIndex()) {
                val altoFila = pt(t.filas.getOrElse(fi) { 0.0 })
                var x = r.left
                for ((ci, celda) in fila.withIndex()) {
                    val anchoCol = pt(t.columnas.getOrElse(ci) { 0.0 })
                    if (celda != null) {
                        val ancho = (ci until ci + celda.abarcaColumnas).sumOf { t.columnas.getOrElse(it) { 0.0 } }
                        val altoC = (fi until fi + celda.abarcaFilas).sumOf { t.filas.getOrElse(it) { 0.0 } }
                        val caja = RectF(x, y, x + pt(ancho), y + pt(altoC))
                        celda.relleno?.let { fondo.color = it; lienzo.drawRect(caja, fondo) }
                        lienzo.drawRect(caja, linea)
                        texto(celda.texto, caja)
                    }
                    x += anchoCol
                }
                y += altoFila
            }
        }

        private fun texto(t: Diapositivas.Texto, r: RectF) {
            if (t.parrafos.isEmpty()) return
            val izq = pt(t.margenes[0]); val arriba = pt(t.margenes[1])
            val der = pt(t.margenes[2]); val abajo = pt(t.margenes[3])
            val anchoUtil = (r.width() - izq - der).coerceAtLeast(4f)
            val altoUtil = (r.height() - arriba - abajo).coerceAtLeast(1f)

            var escala = t.escala.toFloat()
            var layout = armar(t, escala, anchoUtil)
            // **Encoger hasta que quepa**, como hace PowerPoint con los huecos de texto: si no,
            // un título largo se sale por debajo de la diapositiva.
            var intentos = 0
            while (layout.height > altoUtil * 1.04f && escala > 0.45f && intentos < 12) {
                escala *= 0.92f
                layout = armar(t, escala, anchoUtil)
                intentos++
            }
            val sobra = altoUtil - layout.height
            val dy = when (t.ancla) {
                Ancla.ARRIBA -> 0f
                Ancla.CENTRO -> sobra / 2f
                Ancla.ABAJO -> sobra
            }
            lienzo.save()
            lienzo.translate(r.left + izq, r.top + arriba + dy)
            layout.draw(lienzo)
            lienzo.restore()
        }

        private fun armar(t: Diapositivas.Texto, escala: Float, ancho: Float): StaticLayout {
            val base = 10f
            val pintura = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = base; color = 0xFF000000.toInt() }
            val sb = SpannableStringBuilder()
            for ((i, p) in t.parrafos.withIndex()) {
                val inicio = sb.length
                val primerTamano = (p.tramos.firstOrNull()?.tamano ?: 18.0).toFloat() * escala
                p.vineta?.let { v ->
                    val s = sb.length
                    sb.append(v).append("  ")
                    sb.setSpan(RelativeSizeSpan(primerTamano / base), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    p.tramos.firstOrNull()?.let {
                        sb.setSpan(ForegroundColorSpan(it.color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                for (tr in p.tramos) {
                    if (tr.texto.isEmpty()) continue
                    val s = sb.length
                    sb.append(tr.texto.replace('\u000B', '\n'))
                    val e = sb.length
                    sb.setSpan(RelativeSizeSpan(tr.tamano.toFloat() * escala / base), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(tr.color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val estilo = (if (tr.negrita) Typeface.BOLD else 0) or (if (tr.cursiva) Typeface.ITALIC else 0)
                    if (estilo != 0) sb.setSpan(StyleSpan(estilo), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (tr.subrayado) sb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    familia(tr.fuente)?.let { sb.setSpan(TypefaceSpan(it), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                if (sb.length == inicio) {
                    // Párrafo vacío: una línea de su tamaño.
                    sb.append("\u200B")
                    sb.setSpan(RelativeSizeSpan(primerTamano / base), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (i < t.parrafos.size - 1) sb.append('\n')
                val fin = sb.length
                val alineacion = when (p.alineacion) {
                    Alineacion.CENTRO -> Layout.Alignment.ALIGN_CENTER
                    Alineacion.DERECHA -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                sb.setSpan(AlignmentSpan.Standard(alineacion), inicio, fin, Spanned.SPAN_PARAGRAPH)
                val sangria = pt(p.sangria).toInt().coerceIn(0, (ancho * 0.6f).toInt())
                if (sangria > 0) {
                    val colgante = if (p.vineta != null) (primerTamano * 0.9f).toInt().coerceAtMost(sangria) else 0
                    sb.setSpan(LeadingMarginSpan.Standard(sangria - colgante, sangria), inicio, fin, Spanned.SPAN_PARAGRAPH)
                }
            }
            return StaticLayout.Builder.obtain(sb, 0, sb.length, pintura, ancho.toInt().coerceAtLeast(1))
                .setIncludePad(false)
                .setLineSpacing(0f, 1.0f)
                .build()
        }

        /** Las fuentes de Office que Android no tiene, a su pariente más cercano. */
        private fun familia(fuente: String?): String? {
            val f = fuente?.lowercase() ?: return null
            return when {
                "mono" in f || "courier" in f || "consolas" in f -> "monospace"
                "times" in f || "georgia" in f || "cambria" in f || "garamond" in f || "serif" in f && "sans" !in f -> "serif"
                "narrow" in f || "condensed" in f -> "sans-serif-condensed"
                else -> null
            }
        }
    }
}
