package com.forge.pixpin.motor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * **Pinta un texto rico del lienzo** (25-oct-2026) con el [Canvas] de Android: compone con
 * [TextoRico.componer] y dibuja sus piezas —letras y rayas— con las letras del lienzo. Como lo
 * llama el [Renderer], sale igual en pantalla, en las miniaturas, en el PNG y en el PDF.
 *
 * Vive aparte del [Renderer] a propósito: allí solo hay un gancho al principio de `drawText`, y
 * todo lo del texto rico está aquí y en [TextoRico], sin tocar el resto del pintado.
 *
 * **Componer se recuerda**: es medir palabra a palabra y leer las fórmulas, y el pintado corre
 * por fotograma. La firma recoge todo lo que cambia la maqueta y el id del elemento es la llave,
 * como los renglones del texto plano.
 */
class PintorDeTextoRico(private val typefaces: (Int?) -> Typeface?) {

    private val medida = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letras = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayas = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val camino = Path()
    private val metricas = Paint.FontMetrics()

    private data class Firma(val fuente: String, val opciones: TextoRico.Opciones, val familia: Int?)
    private class Recordada(val firma: Firma, val maqueta: TextoRico.Maqueta)

    private val recordadas = object : LinkedHashMap<String, Recordada>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Recordada>) = size > 512
    }

    /** El medidor con la letra de una familia: el mismo `measureText` que el texto plano. */
    fun medidor(familia: Int?): TextoRico.Medidor {
        val letra = typefaces(familia)
        return TextoRico.Medidor { s, tam, _, _ ->
            medida.typeface = letra
            medida.textSize = tam.toFloat()
            medida.measureText(s).toDouble()
        }
    }

    /** La maqueta de un texto, recordada mientras no cambie nada de lo que la forma. */
    fun maqueta(e: Element, fuente: String, anchoMax: Double?): TextoRico.Maqueta {
        val firma = Firma(fuente, TextoRico.opcionesDe(e, anchoMax), e.fontFamily)
        recordadas[e.id]?.let { if (it.firma == firma) return it.maqueta }
        val m = TextoRico.componer(fuente, firma.opciones, medidor(e.fontFamily))
        recordadas[e.id] = Recordada(firma, m)
        return m
    }

    /**
     * Pinta [fuente] en la caja del texto, con su esquina en ([x], [y]) y su ancho [ancho].
     * [colorDelElemento] lleva ya la opacidad y el modo noche; [tinta] hace lo mismo con el
     * color de un tramo (ARGB opaco), para que un tramo rojo se apague igual que el resto.
     */
    fun pintar(
        canvas: Canvas, e: Element, fuente: String,
        x: Double, y: Double, ancho: Double,
        colorDelElemento: Int, tinta: (Int) -> Int
    ) {
        // Dentro de una figura se parte por su ancho; suelto, no (como en el PC: la caja de un
        // texto suelto es la de lo compuesto, y partirla por una medida de otra letra rompería
        // la última palabra).
        val anchoMax = if (e.containerId != null) ancho.coerceAtLeast(1.0) else null
        val m = maqueta(e, fuente, anchoMax)
        // Centrado o a la derecha, el bloque dentro de su caja (si la caja es más ancha).
        val fraccion = when (e.textAlign) {
            TextAlign.CENTER -> 0.5
            TextAlign.RIGHT -> 1.0
            else -> 0.0
        }
        val x0 = x + ((ancho - m.ancho) * fraccion).coerceAtLeast(0.0)
        val letra = typefaces(e.fontFamily)
        for (p in m.piezas) when (p) {
            is CajasRicas.Pieza.Texto -> {
                if (p.texto.isBlank()) continue
                letras.reset()
                letras.isAntiAlias = true
                letras.typeface = letra
                letras.textSize = p.tam.toFloat()
                letras.isFakeBoldText = p.negrita
                letras.textSkewX = if (p.cursiva) SESGO else 0f
                letras.color = p.color?.let(tinta) ?: colorDelElemento
                letras.getFontMetrics(metricas)
                // La línea base donde la pone el texto plano: la caja arriba y su ascendente.
                canvas.drawText(p.texto, (x0 + p.x).toFloat(), (y + p.y - metricas.ascent).toFloat(), letras)
            }
            is CajasRicas.Pieza.Raya -> {
                if (p.puntos.size < 2) continue
                rayas.color = p.color?.let(tinta) ?: colorDelElemento
                rayas.strokeWidth = p.grosor.toFloat()
                camino.reset()
                p.puntos.forEachIndexed { k, q ->
                    val px = (x0 + q.x).toFloat()
                    val py = (y + q.y).toFloat()
                    if (k == 0) camino.moveTo(px, py) else camino.lineTo(px, py)
                }
                canvas.drawPath(camino, rayas)
            }
        }
    }

    private companion object {
        /** La misma inclinación que la cursiva del texto plano (`SESGO_DE_LA_CURSIVA`). */
        const val SESGO = -0.25f
    }
}
