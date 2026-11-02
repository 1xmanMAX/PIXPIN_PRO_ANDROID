package com.forge.pixpin.motormd

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * **Las marcas de texto pintadas en la nota** (2-nov-2026, como el lector de notas del PC): cada
 * marca está anclada en el Markdown tal cual (`sobre: "markdown"`, ver [AnclaDeTexto]) y aquí se
 * pasa a lo que se ve de cada bloque —el texto ya sin `**`, `#`, enlaces…—.
 *
 * Se buscan **por el texto del bloque**: el lector entero y el editor por bloques cortan el
 * documento distinto, pero el texto limpio de un párrafo es el mismo en los dos.
 */
object MarcasDeLaNota {
    data class Pintada(val desde: Int, val hasta: Int, val marca: String, val color: Color)

    fun colorDe(m: JsonObject): Color {
        val c = (m["color"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val bien = if (Regex("^#[0-9a-fA-F]{6}$").matches(c)) c else AnclaDeTexto.COLORES[4]
        return Color(0xFF000000 or bien.substring(1).toLong(16))
    }

    fun marcaDe(m: JsonObject): String =
        (m["marca"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in MarcasDeTexto.MARCAS } ?: "resaltar"

    /** Dónde cae cada letra limpia del bloque en su Markdown (lo mismo que [Vivo.aCrudo]). */
    internal fun mapa(crudo: String, limpio: String): IntArray {
        val salida = IntArray(limpio.length) { -1 }
        var r = 0
        for (k in limpio.indices) {
            while (r < crudo.length && crudo[r] != limpio[k]) r++
            if (r >= crudo.length) break
            salida[k] = r
            r++
        }
        return salida
    }

    /** Dónde está cada marca ahora (id → `[a, b)` del Markdown). */
    fun sitios(texto: String, marcas: List<JsonObject>): Map<String, Pair<Int, Int>> =
        marcas.mapNotNull { m -> AnclaDeTexto.ubicar(texto, m)?.let { MarcasDeTexto.id(m) to it } }.toMap()

    /** **Lo pintado de cada bloque**, por su texto limpio. */
    fun porBloque(texto: String, marcas: List<JsonObject>): Map<String, List<Pintada>> {
        if (marcas.isEmpty()) return emptyMap()
        val sitios = sitios(texto, marcas)
        if (sitios.isEmpty()) return emptyMap()
        val salida = HashMap<String, MutableList<Pintada>>()
        for (t in Vivo.trozos(texto)) {
            val crudo = t.de(texto)
            val tocan = marcas.filter { m -> sitios[MarcasDeTexto.id(m)]?.let { it.first < t.hasta && t.desde < it.second } == true }
            if (tocan.isEmpty()) continue
            val limpio = Vivo.contenidoDelTrozo(crudo)?.text ?: continue
            if (limpio.isEmpty()) continue
            val donde = mapa(crudo, limpio)
            for (m in tocan) {
                val (a, b) = sitios.getValue(MarcasDeTexto.id(m))
                var desde = -1
                var hasta = -1
                for (k in donde.indices) {
                    val r = donde[k]
                    if (r < 0) continue
                    val abs = t.desde + r
                    if (abs in a until b) { if (desde < 0) desde = k; hasta = k + 1 }
                }
                if (desde >= 0) salida.getOrPut(limpio) { ArrayList() } += Pintada(desde, hasta, marcaDe(m), colorDe(m))
            }
        }
        return salida
    }

    /** Las cajas de `[a, b)`, una por renglón. */
    private fun cajas(l: TextLayoutResult, a: Int, b: Int): List<Rect> {
        val n = l.layoutInput.text.length
        val x = a.coerceIn(0, n)
        val y = b.coerceIn(x, n)
        if (x >= y) return emptyList()
        val primera = l.getLineForOffset(x)
        val ultima = l.getLineForOffset(y - 1)
        return (primera..ultima).map { ln ->
            val izq = if (ln == primera) l.getHorizontalPosition(x, true) else l.getLineLeft(ln)
            val der = if (ln == ultima) l.getHorizontalPosition(y, true) else l.getLineRight(ln)
            Rect(minOf(izq, der), l.getLineTop(ln), maxOf(izq, der), l.getLineBottom(ln))
        }.filter { it.width > 0f }
    }

    /** Lo de debajo de la letra: el resaltado. */
    fun ContentDrawScope.pintarDebajo(l: TextLayoutResult?, pintadas: List<Pintada>) {
        if (l == null) return
        for (p in pintadas) if (p.marca == "resaltar") for (r in cajas(l, p.desde, p.hasta)) {
            drawRect(p.color.copy(alpha = 0.4f), Offset(r.left, r.top), Size(r.width, r.height))
        }
    }

    /** Lo de encima: las rayas y lo tapado. */
    fun ContentDrawScope.pintarEncima(l: TextLayoutResult?, pintadas: List<Pintada>, grosor: Float) {
        if (l == null) return
        for (p in pintadas) for (r in cajas(l, p.desde, p.hasta)) {
            val ln = l.getLineForVerticalPosition(r.center.y)
            val base = l.getLineBaseline(ln)
            when (p.marca) {
                "tapar" -> drawRect(p.color, Offset(r.left, r.top), Size(r.width, r.height))
                "subrayar" -> drawLine(p.color, Offset(r.left, base + grosor * 1.5f), Offset(r.right, base + grosor * 1.5f), grosor * 2f)
                "tachar" -> {
                    val y = (r.top + base) / 2f + grosor
                    drawLine(p.color, Offset(r.left, y), Offset(r.right, y), grosor * 2f)
                }
                "ondulada" -> {
                    val y = base + grosor * 2.5f
                    val paso = grosor * 3f
                    val alto = grosor * 1.4f
                    val camino = Path().apply {
                        moveTo(r.left, y)
                        var x = r.left
                        var arriba = true
                        while (x < r.right) {
                            val sig = minOf(x + paso, r.right)
                            quadraticTo((x + sig) / 2f, if (arriba) y - alto else y + alto, sig, y)
                            x = sig
                            arriba = !arriba
                        }
                    }
                    drawPath(camino, p.color, style = Stroke(width = grosor * 1.5f))
                }
            }
        }
    }
}

/** Las marcas pintadas de la nota abierta, por el texto limpio de cada bloque. Ver [MarcasDeLaNota]. */
val LocalMarcasDeLaNota = compositionLocalOf<Map<String, List<MarcasDeLaNota.Pintada>>> { emptyMap() }
