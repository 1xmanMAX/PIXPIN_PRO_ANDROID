package com.forge.pixpin.pdf

import android.graphics.Point
import android.os.Build
import com.forge.pixpin.motor.MarcasDeTextoEnTinta
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.office.HojasOffice
import com.forge.pixpin.office.Pxof
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **Elegir el texto de una hoja para marcarlo** (2-nov-2026, el lector de PDF y Office del PC,
 * `lector_pdf/marcar.rs`): del punto donde se empezó al punto donde se soltó, las cajas de cada
 * renglón elegido, en puntos de la hoja.
 *
 * - **PDF**: el texto de verdad del PDF, con `PdfRenderer.Page.selectContent` (Android 15 o más).
 * - **Word y PowerPoint**: los trozos de texto de la hoja maquetada ([Pxof.Pagina.textos]).
 *
 * Las cajas pasan luego a las unidades de la tinta de la hoja (1400 de ancho, [PdfDoc.PAGE_WIDTH])
 * y la marca se hace con [MarcasDeTextoEnTinta], como el PC.
 */
object ElegirTextoDeLaHoja {

    /** Si en este teléfono se puede elegir el texto de un PDF. */
    val pdfSePuede: Boolean get() = Build.VERSION.SDK_INT >= 35

    /** Lo elegido: las cajas `[x0, y0, x1, y1]` de cada renglón (puntos) y su texto. */
    class Elegido(val cajas: List<FloatArray>, val texto: String)

    /** Lo elegido entre [a] y [b] (puntos de la hoja); null si no se puede. */
    fun cajas(ruta: String, i: Int, a: Pair<Float, Float>, b: Pair<Float, Float>): Elegido? =
        if (HojasOffice.es(ruta)) HojasOffice.yaAbierto(ruta)?.pagina(i)?.let { deOffice(it, a, b) }
        else if (pdfSePuede) dePdf(ruta, i, a, b) else null

    private fun dePdf(ruta: String, i: Int, a: Pair<Float, Float>, b: Pair<Float, Float>): Elegido? {
        if (Build.VERSION.SDK_INT < 35) return null
        return runCatching {
            android.os.ParcelFileDescriptor.open(File(ruta), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { doc ->
                    if (i !in 0 until doc.pageCount) return null
                    doc.openPage(i).use { p ->
                        val s = p.selectContent(
                            android.graphics.pdf.models.selection.SelectionBoundary(Point(a.first.toInt(), a.second.toInt())),
                            android.graphics.pdf.models.selection.SelectionBoundary(Point(b.first.toInt(), b.second.toInt()))
                        ) ?: return Elegido(emptyList(), "")
                        Elegido(
                            juntarPorRenglon(s.selectedTextContents.flatMap { it.bounds }.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }),
                            s.selectedTextContents.joinToString("") { it.text }
                        )
                    }
                }
            }
        }.getOrNull()
    }

    /** De Office: los trozos de texto en orden de lectura, del más cercano a [a] al más cercano a [b]. */
    internal fun deOffice(pag: Pxof.Pagina, a: Pair<Float, Float>, b: Pair<Float, Float>): Elegido {
        val t = pag.textos().filter { it.first.texto.isNotBlank() }
        if (t.isEmpty()) return Elegido(emptyList(), "")
        // Renglón a renglón, de arriba abajo y de izquierda a derecha.
        val orden = ArrayList<Pair<Pxof.Texto, FloatArray>>()
        val renglones = ArrayList<MutableList<Pair<Pxof.Texto, FloatArray>>>()
        for (p in t.sortedWith(compareBy({ it.first.y }, { it.second[0] }))) {
            val u = renglones.lastOrNull()
            if (u != null && abs(u[0].first.y - p.first.y) < p.first.tam * 0.3f) u += p else renglones += mutableListOf(p)
        }
        for (r in renglones) orden += r.sortedBy { it.second[0] }
        fun distancia(r: FloatArray, x: Float, y: Float): Float {
            val dx = max(0f, max(r[0] - x, x - (r[0] + r[2])))
            val dy = max(0f, max(r[1] - y, y - (r[1] + r[3])))
            return dx * dx + dy * dy * 4f
        }
        val ia = orden.indices.minByOrNull { distancia(orden[it].second, a.first, a.second) } ?: return Elegido(emptyList(), "")
        val ib = orden.indices.minByOrNull { distancia(orden[it].second, b.first, b.second) } ?: return Elegido(emptyList(), "")
        val trozo = orden.subList(min(ia, ib), max(ia, ib) + 1)
        val elegidos = trozo.map { (_, r) -> floatArrayOf(r[0], r[1], r[0] + r[2], r[1] + r[3]) }
        return Elegido(juntarPorRenglon(elegidos), trozo.joinToString(" ") { it.first.texto.trim() }.trim())
    }

    /** Las cajas del mismo renglón (las que se solapan de alto más de la mitad), en una. */
    internal fun juntarPorRenglon(cajas: List<FloatArray>): List<FloatArray> {
        val salida = ArrayList<FloatArray>()
        for (c in cajas.filter { it[2] > it[0] && it[3] > it[1] }.sortedWith(compareBy({ it[1] }, { it[0] }))) {
            val u = salida.lastOrNull()
            val solape = if (u == null) 0f else min(u[3], c[3]) - max(u[1], c[1])
            if (u != null && solape > 0.5f * min(u[3] - u[1], c[3] - c[1])) {
                u[0] = min(u[0], c[0]); u[1] = min(u[1], c[1]); u[2] = max(u[2], c[2]); u[3] = max(u[3], c[3])
            } else salida += c.copyOf()
        }
        return salida
    }

    /** De puntos de una hoja de [anchoPt] de ancho a las unidades de su tinta. */
    fun aTinta(cajas: List<FloatArray>, anchoPt: Double): List<MarcasDeTextoEnTinta.Caja> {
        val k = PdfDoc.PAGE_WIDTH / anchoPt
        return cajas.map { MarcasDeTextoEnTinta.Caja(it[0] * k, it[1] * k, it[2] * k, it[3] * k) }
    }
}
