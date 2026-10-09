package com.forge.pixpin.planos

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * **Imprimir partes del plano** (9-oct-2026, pedido por el usuario: «ponerle un marco a una parte
 * y mandarlo a imprimir, como los marcos del lienzo»). Cada marco es una hoja; el papel y la
 * impresora —o «Guardar como PDF»— los elige el diálogo del sistema, como en [com.forge.pixpin.guardados.Imprimir].
 *
 * Se dibuja **en vectores**, con el lienzo de Android sobre el PDF de la impresión: las rayas, los
 * rellenos, los círculos, las letras (cada una con su forma, no como imagen) y las cotas puestas.
 * Va en los colores del fondo claro (el papel es blanco): el color 7 en negro y los muy claros
 * oscurecidos, igual que [PintorDePlano] en claro. Los sombreados con patrón salen como un tono
 * de su color, sin las rayas del patrón.
 */
object ImprimirPlano {
    /** Un marco: `[x0, y0, x1, y1]` en el plano (respecto a su origen), ya ordenado. */
    class Marco(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        val ancho get() = x1 - x0
        val alto get() = y1 - y0

        companion object {
            fun entre(ax: Double, ay: Double, bx: Double, by: Double) = Marco(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
        }
    }

    private const val MARGEN = 28f

    fun imprimir(actividad: Activity, m: ModeloCad, marcos: List<Marco>, cotas: List<List<DoubleArray>>, nombre: String) {
        if (marcos.isEmpty()) return
        val apaisada = marcos.first().let { it.ancho > it.alto }
        val gestor = actividad.getSystemService(Activity.PRINT_SERVICE) as PrintManager
        gestor.print(
            nombre.ifBlank { "Plano" },
            Adaptador(actividad, m, marcos, cotas, nombre),
            PrintAttributes.Builder()
                .setMediaSize(if (apaisada) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4.asPortrait())
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
        )
    }

    private class Adaptador(
        val actividad: Activity, val m: ModeloCad, val marcos: List<Marco>, val cotas: List<List<DoubleArray>>, val nombre: String
    ) : PrintDocumentAdapter() {
        private var atributos: PrintAttributes? = null

        override fun onLayout(viejos: PrintAttributes?, nuevos: PrintAttributes, cancelar: CancellationSignal, respuesta: LayoutResultCallback, extras: Bundle?) {
            if (cancelar.isCanceled) { respuesta.onLayoutCancelled(); return }
            atributos = nuevos
            val info = PrintDocumentInfo.Builder("${nombre.ifBlank { "plano" }}.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(marcos.size)
                .build()
            respuesta.onLayoutFinished(info, true)
        }

        override fun onWrite(paginas: Array<out PageRange>, destino: ParcelFileDescriptor, cancelar: CancellationSignal, respuesta: WriteResultCallback) {
            val doc = PrintedPdfDocument(actividad, atributos ?: return respuesta.onWriteFailed("Sin papel"))
            try {
                marcos.forEachIndexed { i, marco ->
                    if (cancelar.isCanceled) { respuesta.onWriteCancelled(); return }
                    if (paginas.none { i in it.start..it.end }) return@forEachIndexed
                    val hoja = doc.startPage(i)
                    val c = hoja.canvas
                    dibujar(c, m, marco, cotas, c.width.toFloat(), c.height.toFloat())
                    doc.finishPage(hoja)
                }
                FileOutputStream(destino.fileDescriptor).use { doc.writeTo(it) }
                respuesta.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Throwable) {
                respuesta.onWriteFailed(e.message)
            } finally {
                doc.close()
            }
        }
    }

    /** El color en papel blanco: el 7 en negro, y lo muy claro oscurecido (`tinta` en claro). */
    fun colorEnPapel(c: Int): Int {
        val a = (c ushr 24) and 255
        if (a == 0) return Color.BLACK
        var r = (c and 255) / 255f; var g = ((c ushr 8) and 255) / 255f; var b = ((c ushr 16) and 255) / 255f
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (l > 0.62f) { val k = 0.5f / l; r *= k; g *= k; b *= k }
        return Color.argb(a, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    /** Dibuja el [marco] del plano en un lienzo de [w] × [h] puntos, encajado con su margen. */
    fun dibujar(c: Canvas, m: ModeloCad, marco: Marco, cotas: List<List<DoubleArray>>, w: Float, h: Float) {
        c.drawColor(Color.WHITE)
        val cw = w - 2 * MARGEN; val ch = h - 2 * MARGEN
        val s = min(cw / marco.ancho, ch / marco.alto).toFloat()
        if (!(s > 0) || !s.isFinite()) return
        val ox = MARGEN + (cw - marco.ancho.toFloat() * s) / 2
        val oy = MARGEN + (ch - marco.alto.toFloat() * s) / 2
        fun X(x: Float) = ox + (x - marco.x0.toFloat()) * s
        fun Y(y: Float) = oy + (marco.y1.toFloat() - y) * s
        c.save()
        c.clipRect(ox, oy, ox + marco.ancho.toFloat() * s, oy + marco.alto.toFloat() * s)
        val vx0 = marco.x0.toFloat(); val vy0 = marco.y0.toFloat(); val vx1 = marco.x1.toFloat(); val vy1 = marco.y1.toFloat()
        // Lo que en papel mediría menos de una décima de punto no se dibuja.
        val px = 0.1f / s
        val salida = IntArray(2 * maxOf(m.tramosLineas.n, m.tramosTriangulos.n, m.tramosTrama.n, m.tramosArcos.n, 1) + 2)
        val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val raya = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.35f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val vx = m.vertices

        fun triangulos(indices: ModeloCad.Lista, verts: ModeloCad.Lista, tramos: ModeloCad.Tramos, alfa: Int) {
            val n = tramos.visibles(vx0, vy0, vx1, vy1, px, salida)
            val camino = Path()
            var color = 0
            fun soltar() { if (!camino.isEmpty) { relleno.color = colorEnPapel(color); relleno.alpha = alfa; c.drawPath(camino, relleno); camino.reset() } }
            for (k in 0 until n) {
                var i = salida[2 * k]
                val fin = i + salida[2 * k + 1]
                while (i + 3 <= fin) {
                    val a = indices.palabra(i, 0); val b = indices.palabra(i + 1, 0); val d = indices.palabra(i + 2, 0)
                    val col = verts.palabra(a, 2)
                    if (col != color) { soltar(); color = col }
                    camino.moveTo(X(verts.real(a, 0)), Y(verts.real(a, 1)))
                    camino.lineTo(X(verts.real(b, 0)), Y(verts.real(b, 1)))
                    camino.lineTo(X(verts.real(d, 0)), Y(verts.real(d, 1)))
                    camino.close()
                    i += 3
                }
            }
            soltar()
        }
        // 1. Rellenos y sombreados (estos, como un tono).
        triangulos(m.triangulos, vx, m.tramosTriangulos, 255)
        if (m.triangulosTrama.cuantos > 0) triangulos(m.triangulosTrama, m.verticesTrama, m.tramosTrama, 70)
        // 2. Rayas: cada tira, un camino.
        run {
            val n = m.tramosLineas.visibles(vx0, vy0, vx1, vy1, px * 0.75f, salida)
            val camino = Path()
            var color = 0
            fun soltar() { if (!camino.isEmpty) { raya.color = colorEnPapel(color); c.drawPath(camino, raya); camino.reset() } }
            for (k in 0 until n) {
                var nueva = true
                for (i in salida[2 * k] until salida[2 * k] + salida[2 * k + 1]) {
                    val v = m.lineas.palabra(i, 0)
                    if (v == ModeloCad.CORTE) { nueva = true; continue }
                    val col = vx.palabra(v, 2)
                    if (col != color) { soltar(); color = col; nueva = true }
                    val x = X(vx.real(v, 0)); val y = Y(vx.real(v, 1))
                    if (nueva || camino.isEmpty) camino.moveTo(x, y) else camino.lineTo(x, y)
                    nueva = false
                }
            }
            soltar()
        }
        // 3. Círculos y arcos.
        run {
            val n = m.tramosArcos.visibles(vx0, vy0, vx1, vy1, px * 0.75f, salida)
            val ovalo = android.graphics.RectF()
            for (k in 0 until n) for (i in salida[2 * k] until salida[2 * k] + salida[2 * k + 1]) {
                val cx = m.arcos.real(i, 0); val cy = m.arcos.real(i, 1); val r = m.arcos.real(i, 2)
                val a0 = m.arcos.real(i, 3); val b = m.arcos.real(i, 4)
                raya.color = colorEnPapel(m.arcos.palabra(i, 5))
                ovalo.set(X(cx - r), Y(cy + r), X(cx + r), Y(cy - r))
                // En el papel la y baja: los ángulos van al revés.
                if (b >= 6.2831f) c.drawOval(ovalo, raya)
                else c.drawArc(ovalo, -Math.toDegrees((a0 + b).toDouble()).toFloat(), Math.toDegrees(b.toDouble()).toFloat(), false, raya)
            }
        }
        // 4. Letras: un texto de menos de un punto no se lee.
        run {
            val t = m.tramosLetras
            val camino = Path()
            val trazo = Path()
            for (k in 0 until t.n) {
                if (t.tamano[k] < 1f / s) break
                if (!t.corta(k, vx0, vy0, vx1, vy1)) continue
                for (i in t.desde[k] until t.desde[k] + t.cuantos[k]) {
                    val pxl = m.letras.real(i, 0); val pyl = m.letras.real(i, 1)
                    val a = m.letras.real(i, 2); val b = m.letras.real(i, 3); val cc = m.letras.real(i, 4); val d = m.letras.real(i, 5)
                    val col = colorEnPapel(m.letras.palabra(i, 6))
                    val g = m.letras.palabra(i, 7)
                    val desde = m.glifos.palabra(g, 0)
                    val bruto = m.glifos.palabra(g, 1)
                    val rayas = bruto and ModeloCad.GLIFO_DE_RAYAS != 0
                    val nv = bruto and ModeloCad.GLIFO_DE_RAYAS.inv()
                    fun ex(j: Int) = X(pxl + a * m.mallaLetras.real(desde + j, 0) + b * m.mallaLetras.real(desde + j, 1))
                    fun ey(j: Int) = Y(pyl + cc * m.mallaLetras.real(desde + j, 0) + d * m.mallaLetras.real(desde + j, 1))
                    if (rayas) {
                        var j = 0
                        while (j + 1 < nv) { trazo.moveTo(ex(j), ey(j)); trazo.lineTo(ex(j + 1), ey(j + 1)); j += 2 }
                        raya.color = col; c.drawPath(trazo, raya); trazo.reset()
                    } else {
                        var j = 0
                        while (j + 2 < nv) {
                            camino.moveTo(ex(j), ey(j)); camino.lineTo(ex(j + 1), ey(j + 1)); camino.lineTo(ex(j + 2), ey(j + 2)); camino.close()
                            j += 3
                        }
                        relleno.color = col; relleno.alpha = 255; c.drawPath(camino, relleno); camino.reset()
                    }
                }
            }
        }
        // 5. Las cotas, en naranja con su medida.
        val naranja = Color.rgb(0xE0, 0x6A, 0x00)
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = naranja; textSize = 7f }
        raya.color = naranja; raya.strokeWidth = 0.8f
        for (cadena in cotas) {
            var total = 0.0
            for (k in 1 until cadena.size) {
                val p = cadena[k - 1]; val q = cadena[k]
                c.drawLine(X(p[0].toFloat()), Y(p[1].toFloat()), X(q[0].toFloat()), Y(q[1].toFloat()), raya)
                val dd = hypot(q[0] - p[0], q[1] - p[1]); total += dd
                c.drawText(Medidas.medida(dd, m.unidades), (X(p[0].toFloat()) + X(q[0].toFloat())) / 2 + 2, (Y(p[1].toFloat()) + Y(q[1].toFloat())) / 2 - 2, letra)
            }
            if (cadena.size > 2) cadena.last().let { u -> c.drawText("Σ " + Medidas.medida(total, m.unidades), X(u[0].toFloat()) + 4, Y(u[1].toFloat()) + 10, letra) }
        }
        c.restore()
        // El borde del marco, fino.
        raya.color = Color.rgb(0x88, 0x88, 0x88); raya.strokeWidth = 0.5f
        c.drawRect(ox, oy, ox + marco.ancho.toFloat() * s, oy + marco.alto.toFloat() * s, raya)
    }
}
