package com.forge.pixpin.excel

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * **Un gráfico de la hoja, sencillo**, con los datos que Excel dejó guardados (como el PC: algo
 * parecido, o al menos el recuadro con su título): columnas o barras, líneas, y tarta.
 */
internal object GraficoExcel {
    /** Los colores de las series cuando el libro no los trae (los acentos de Office). */
    private val COLORES = intArrayOf(0xFF4472C4.toInt(), 0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt())
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val camino = Path()

    fun pintar(canvas: Canvas, d: Pxhj.Dibujo.Grafico, r: RectF, u: Float, texto: TextPaint) {
        relleno.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(r, relleno)
        trazo.color = 0xFFD9D9D9.toInt(); trazo.strokeWidth = u
        canvas.drawRect(r, trazo)
        canvas.save()
        canvas.clipRect(r)
        var arriba = r.top + 6f
        if (d.titulo.isNotBlank()) {
            texto.textAlign = Paint.Align.CENTER
            texto.textSize = 14f
            canvas.drawText(d.titulo, r.centerX(), arriba + 14f, texto)
            texto.textAlign = Paint.Align.LEFT
            texto.textSize = 12f
            arriba += 22f
        }
        val zona = RectF(r.left + 36f, arriba + 4f, r.right - 10f, r.bottom - 22f)
        val series = d.series.filter { it.valores.isNotEmpty() }
        if (series.isNotEmpty() && zona.width() > 20f && zona.height() > 20f) {
            val tipo = d.tipo.lowercase()
            when {
                "pie" in tipo || "doughnut" in tipo -> tarta(canvas, series.first(), RectF(r.left + 8f, arriba + 4f, r.right - 8f, r.bottom - 8f))
                else -> ejes(canvas, d, series, zona, u, texto, "line" in tipo || "scatter" in tipo || "area" in tipo)
            }
        }
        canvas.restore()
    }

    private fun color(s: Pxhj.Serie, i: Int) = if (s.color != 0) s.color else COLORES[i % COLORES.size]

    private fun ejes(canvas: Canvas, d: Pxhj.Dibujo.Grafico, series: List<Pxhj.Serie>, z: RectF, u: Float, texto: TextPaint, lineas: Boolean) {
        var lo = min(0.0, series.minOf { s -> s.valores.filter { it.isFinite() }.minOrNull() ?: 0.0 })
        var hi = max(0.0, series.maxOf { s -> s.valores.filter { it.isFinite() }.maxOrNull() ?: 0.0 })
        if (hi == lo) hi = lo + 1
        val paso = pasoBonito((hi - lo) / 5)
        lo = floor(lo / paso) * paso
        hi = ceil(hi / paso) * paso
        val n = series.maxOf { it.valores.size }
        fun yDe(v: Double) = (z.bottom - (v - lo) / (hi - lo) * z.height()).toFloat()
        // Las rayas de la escala.
        trazo.color = 0xFFE6E6E6.toInt(); trazo.strokeWidth = u
        texto.textAlign = Paint.Align.RIGHT
        var v = lo
        while (v <= hi + paso / 2) {
            val y = yDe(v)
            canvas.drawLine(z.left, y, z.right, y, trazo)
            canvas.drawText(etiqueta(v), z.left - 4f, y + 4f, texto)
            v += paso
        }
        texto.textAlign = Paint.Align.LEFT
        val hueco = z.width() / max(1, n)
        if (lineas) {
            series.forEachIndexed { i, s ->
                trazo.color = color(s, i); trazo.strokeWidth = 2f
                camino.reset()
                s.valores.forEachIndexed { k, valor ->
                    val x = z.left + hueco * (k + 0.5f)
                    if (k == 0) camino.moveTo(x, yDe(valor)) else camino.lineTo(x, yDe(valor))
                }
                canvas.drawPath(camino, trazo)
            }
        } else {
            val ancho = hueco * 0.7f / series.size
            series.forEachIndexed { i, s ->
                relleno.color = color(s, i)
                s.valores.forEachIndexed { k, valor ->
                    val x = z.left + hueco * k + hueco * 0.15f + ancho * i
                    val y0 = yDe(0.0); val y1 = yDe(valor)
                    canvas.drawRect(x, min(y0, y1), x + ancho, max(y0, y1), relleno)
                }
            }
        }
        // Las categorías, si caben.
        if (d.categorias.isNotEmpty() && hueco > 24f) {
            texto.textAlign = Paint.Align.CENTER
            d.categorias.take(n).forEachIndexed { k, c -> canvas.drawText(c.take(12), z.left + hueco * (k + 0.5f), z.bottom + 14f, texto) }
            texto.textAlign = Paint.Align.LEFT
        }
    }

    private fun tarta(canvas: Canvas, s: Pxhj.Serie, r: RectF) {
        val total = s.valores.filter { it.isFinite() && it > 0 }.sum()
        if (total <= 0) return
        val lado = min(r.width(), r.height())
        val caja = RectF(r.centerX() - lado / 2, r.centerY() - lado / 2, r.centerX() + lado / 2, r.centerY() + lado / 2)
        var angulo = -90f
        s.valores.forEachIndexed { k, v ->
            if (!v.isFinite() || v <= 0) return@forEachIndexed
            val barrido = (v / total * 360).toFloat()
            relleno.color = COLORES[k % COLORES.size]
            canvas.drawArc(caja, angulo, barrido, true, relleno)
            angulo += barrido
        }
    }

    private fun pasoBonito(bruto: Double): Double {
        if (bruto <= 0 || !bruto.isFinite()) return 1.0
        val p = 10.0.pow(floor(log10(bruto)))
        val m = bruto / p
        return p * when { m <= 1 -> 1.0; m <= 2 -> 2.0; m <= 5 -> 5.0; else -> 10.0 }
    }

    private fun etiqueta(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9 && abs(v) < 1e12) v.toLong().toString() else String.format("%.2f", v)
}
