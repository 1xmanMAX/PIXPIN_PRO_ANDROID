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

    /**
     * **La lámina, como en los planos de verdad** (9-oct-2026, el usuario: «un frame predeterminado,
     * como un membrete»): el recuadro con los márgenes de la norma (ISO 5457: 20 mm a la izquierda
     * para encuadernar, 10 mm en los demás) y el membrete abajo a la derecha, con el proyecto, el
     * título, quién lo dibujó, la escala, la fecha y el número de lámina. Con [escalaNormal] el
     * plano va a una escala de las de siempre (1:50, 1:100…) en vez de estirarse al papel.
     */
    data class Lamina(
        val conMembrete: Boolean = true,
        val proyecto: String = "",
        val titulo: String = "",
        val autor: String = "",
        val fecha: String = "",
        val escalaNormal: Boolean = true,
    )

    /** Los milímetros que mide una unidad del plano (`$INSUNITS`), o null si no lo dice. */
    fun mmPorUnidad(unidades: Int): Double? = when (unidades) {
        1 -> 25.4
        2 -> 304.8
        4 -> 1.0
        5 -> 10.0
        6 -> 1000.0
        7 -> 1_000_000.0
        14 -> 100.0
        else -> null
    }

    /** Las escalas de siempre (1:n). */
    val ESCALAS = intArrayOf(
        1, 2, 5, 10, 20, 25, 50, 75, 100, 125, 150, 200, 250, 300, 400, 500, 750, 1000, 1250, 1500, 2000, 2500,
        5000, 7500, 10000, 12500, 15000, 20000, 25000, 50000, 75000, 100000
    )

    /**
     * La escala normal más grande con que [anchoMm] × [altoMm] de la realidad caben en [papelAncho] ×
     * [papelAlto] mm: la primera 1:n con n ≥ lo justo. Null si ni la más pequeña cabe.
     */
    fun escalaQueCabe(anchoMm: Double, altoMm: Double, papelAncho: Double, papelAlto: Double): Int? {
        if (papelAncho <= 0 || papelAlto <= 0) return null
        val justa = maxOf(anchoMm / papelAncho, altoMm / papelAlto)
        return ESCALAS.firstOrNull { it >= justa * 0.9999 }
    }

    private const val MM = 72f / 25.4f

    fun imprimir(actividad: Activity, m: ModeloCad, marcos: List<Marco>, cotas: List<List<DoubleArray>>, nombre: String, lamina: Lamina = Lamina(conMembrete = false, escalaNormal = false)) {
        if (marcos.isEmpty()) return
        val apaisada = marcos.first().let { it.ancho > it.alto }
        val gestor = actividad.getSystemService(Activity.PRINT_SERVICE) as PrintManager
        gestor.print(
            nombre.ifBlank { "Plano" },
            Adaptador(actividad, m, marcos, cotas, nombre, lamina),
            PrintAttributes.Builder()
                .setMediaSize(if (apaisada) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4.asPortrait())
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
        )
    }

    private class Adaptador(
        val actividad: Activity, val m: ModeloCad, val marcos: List<Marco>, val cotas: List<List<DoubleArray>>, val nombre: String,
        val lamina: Lamina
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
                    hoja(c, m, marco, cotas, c.width.toFloat(), c.height.toFloat(), lamina, i + 1, marcos.size)
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

    /**
     * Una hoja entera de [w] × [h] puntos: con [lamina] y su membrete, el recuadro y el plano en lo
     * que queda; si no, el plano encajado con un margen, como siempre.
     */
    fun hoja(c: Canvas, m: ModeloCad, marco: Marco, cotas: List<List<DoubleArray>>, w: Float, h: Float, lamina: Lamina, numero: Int, total: Int) {
        c.drawColor(Color.WHITE)
        if (!lamina.conMembrete) {
            val escala = escalaDe(m, marco, lamina, w - 2 * MARGEN, h - 2 * MARGEN)
            dibujar(c, m, marco, cotas, MARGEN, MARGEN, w - 2 * MARGEN, h - 2 * MARGEN, escala?.second)
            return
        }
        // El recuadro: 20 mm a la izquierda (para encuadernar) y 10 en lo demás; si el papel es
        // muy pequeño, menos.
        val izq = min(20 * MM, w * 0.07f); val otro = min(10 * MM, w * 0.035f)
        val bx0 = izq; val by0 = otro; val bx1 = w - otro; val by1 = h - otro
        val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
        // El membrete, abajo a la derecha: 180 mm de ancho (o lo que quepa) y 4 filas.
        val anchoM = min(180 * MM, (bx1 - bx0))
        val fila = min(9 * MM, (by1 - by0) * 0.06f)
        val altoM = fila * 4
        val mx0 = bx1 - anchoM; val my0 = by1 - altoM
        // El plano: dentro del recuadro y encima del membrete, con aire.
        val aire = 4 * MM
        val ax = bx0 + aire; val ay = by0 + aire; val aw = (bx1 - bx0) - 2 * aire; val ah = (my0 - by0) - 2 * aire
        val escala = escalaDe(m, marco, lamina, aw, ah)
        dibujar(c, m, marco, cotas, ax, ay, aw, ah, escala?.second)
        // Recuadro (grueso) y membrete (fino por dentro).
        linea.strokeWidth = 1.2f
        c.drawRect(bx0, by0, bx1, by1, linea)
        c.drawRect(mx0, my0, bx1, by1, linea)
        linea.strokeWidth = 0.5f
        for (k in 1 until 4) c.drawLine(mx0, my0 + k * fila, bx1, my0 + k * fila, linea)
        // Filas: proyecto | lámina; título; dibujó | escala | fecha | unidades.
        val col = anchoM * 0.72f
        c.drawLine(mx0 + col, my0, mx0 + col, my0 + fila, linea)
        val cuarto = anchoM / 4
        for (k in 1 until 4) c.drawLine(mx0 + k * cuarto, my0 + 2 * fila, mx0 + k * cuarto, by1, linea)
        val rotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x55, 0x55, 0x55); textSize = fila * 0.24f }
        val dato = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = fila * 0.42f; isFakeBoldText = true }
        fun celda(x0: Float, y0: Float, ancho: Float, etiqueta: String, valor: String, alto: Float = fila) {
            c.drawText(etiqueta, x0 + 1.5f * MM, y0 + rotulo.textSize + 1f * MM, rotulo)
            var t = valor
            while (t.length > 1 && dato.measureText(t) > ancho - 3 * MM) t = t.dropLast(1)
            if (t != valor) t = t.dropLast(1) + "…"
            c.drawText(t, x0 + 1.5f * MM, y0 + alto - 1.6f * MM, dato)
        }
        celda(mx0, my0, col, "PROYECTO", lamina.proyecto)
        celda(mx0 + col, my0, anchoM - col, "LÁMINA", "$numero / $total")
        celda(mx0, my0 + fila, anchoM, "TÍTULO", lamina.titulo, fila * 2)
        val unidad = Medidas.sufijo(m.unidades).trim().ifBlank { "—" }
        celda(mx0, my0 + 3 * fila, cuarto, "DIBUJÓ", lamina.autor)
        celda(mx0 + cuarto, my0 + 3 * fila, cuarto, "ESCALA", escala?.first?.let { "1:$it" } ?: "Sin escala")
        celda(mx0 + 2 * cuarto, my0 + 3 * fila, cuarto, "FECHA", lamina.fecha)
        celda(mx0 + 3 * cuarto, my0 + 3 * fila, cuarto, "UNIDADES", unidad)
    }

    /**
     * La escala de la hoja: con [Lamina.escalaNormal] y unidades conocidas, la 1:n de siempre que
     * cabe en [aw] × [ah] puntos y los puntos por unidad que le tocan. Null: encajar sin más.
     */
    private fun escalaDe(m: ModeloCad, marco: Marco, lamina: Lamina, aw: Float, ah: Float): Pair<Int, Float>? {
        if (!lamina.escalaNormal) return null
        val mm = mmPorUnidad(m.unidades) ?: return null
        val n = escalaQueCabe(marco.ancho * mm, marco.alto * mm, (aw / MM).toDouble(), (ah / MM).toDouble()) ?: return null
        return n to (mm / n * MM).toFloat()
    }

    /**
     * Dibuja el [marco] del plano en la caja [ax], [ay], [aw] × [ah] (puntos), centrado. Con [fija]
     * (puntos por unidad del plano) va a esa escala; si no, lo más grande que quepa.
     */
    fun dibujar(c: Canvas, m: ModeloCad, marco: Marco, cotas: List<List<DoubleArray>>, ax: Float, ay: Float, aw: Float, ah: Float, fija: Float? = null) {
        val cw = aw; val ch = ah
        val s = fija ?: min(cw / marco.ancho, ch / marco.alto).toFloat()
        if (!(s > 0) || !s.isFinite()) return
        val ox = ax + (cw - marco.ancho.toFloat() * s) / 2
        val oy = ay + (ch - marco.alto.toFloat() * s) / 2
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
