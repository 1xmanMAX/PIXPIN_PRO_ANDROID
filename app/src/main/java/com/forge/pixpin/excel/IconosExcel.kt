package com.forge.pixpin.excel

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * **Los 20 conjuntos de iconos del formato condicional de Excel**, dibujados con polígonos, círculos
 * y rayas (sin imágenes): nítidos a cualquier zoom. Es `pixpin_render::hoja_iconos` del PC
 * (9975385), con los mismos colores y las mismas formas.
 *
 * El número de conjunto es el orden de `Conjunto::TODOS` del PC (lo manda el .so en el PXHT).
 */
object IconosExcel {
    const val FLECHAS_3 = 0
    const val FLECHAS_GRIS_3 = 1
    const val BANDERAS_3 = 2
    const val SEMAFORO_3 = 3
    const val SEMAFORO_3_BORDE = 4
    const val SENALES_3 = 5
    const val SIMBOLOS_3 = 6
    const val SIMBOLOS_3_SIN = 7
    const val FLECHAS_4 = 8
    const val FLECHAS_GRIS_4 = 9
    const val ROJO_NEGRO_4 = 10
    const val VALORACION_4 = 11
    const val SEMAFORO_4 = 12
    const val FLECHAS_5 = 13
    const val FLECHAS_GRIS_5 = 14
    const val VALORACION_5 = 15
    const val CUARTOS_5 = 16
    const val ESTRELLAS_3 = 17
    const val TRIANGULOS_3 = 18
    const val CAJAS_5 = 19

    private const val VERDE = 0xFF3FA94F.toInt()
    private const val AMARILLO = 0xFFF5BE1E.toInt()
    private const val ROJO = 0xFFE03C31.toInt()
    private const val ROSA = 0xFFF09A96.toInt()
    private const val GRIS = 0xFF808080.toInt()
    private const val GRIS_CLARO = 0xFFC8C8C8.toInt()
    private const val NEGRO = 0xFF262626.toInt()
    private const val BLANCO = 0xFFFFFFFF.toInt()
    private const val DORADO = 0xFFF2B400.toInt()

    /** Cuántos iconos tiene el conjunto (3, 4 o 5). */
    fun cantidad(conjunto: Int): Int = when (conjunto) {
        FLECHAS_4, FLECHAS_GRIS_4, ROJO_NEGRO_4, VALORACION_4, SEMAFORO_4 -> 4
        FLECHAS_5, FLECHAS_GRIS_5, VALORACION_5, CUARTOS_5, CAJAS_5 -> 5
        else -> 3
    }

    private val lleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val raya = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val camino = Path()

    /**
     * Pinta el icono [idx] (0 = el peor) de [conjunto] centrado en ([cx], [cy]) dentro de un cuadrado
     * de lado [lado]. Solo desde el hilo de la pantalla (las pinturas son compartidas).
     */
    fun pintar(canvas: Canvas, conjunto: Int, idx: Int, cx: Float, cy: Float, lado: Float) {
        val r = lado / 2f
        val k = cantidad(conjunto)
        val i = idx.coerceIn(0, k - 1)
        val semaforo = intArrayOf(ROJO, AMARILLO, VERDE)
        fun px(x: Float) = cx + x * r
        fun py(y: Float) = cy + y * r
        fun poli(v: FloatArray, color: Int) {
            camino.rewind()
            camino.moveTo(px(v[0]), py(v[1]))
            var j = 2
            while (j + 1 < v.size) { camino.lineTo(px(v[j]), py(v[j + 1])); j += 2 }
            camino.close()
            lleno.color = color
            canvas.drawPath(camino, lleno)
        }
        fun circulo(x: Float, y: Float, radio: Float, color: Int) { lleno.color = color; canvas.drawCircle(x, y, radio, lleno) }
        fun linea(x0: Float, y0: Float, x1: Float, y1: Float, grosor: Float, color: Int) {
            raya.color = color; raya.strokeWidth = grosor
            canvas.drawLine(px(x0), py(y0), px(x1), py(y1), raya)
        }
        when (conjunto) {
            FLECHAS_3, FLECHAS_GRIS_3, FLECHAS_4, FLECHAS_GRIS_4, FLECHAS_5, FLECHAS_GRIS_5 -> {
                val gris = conjunto == FLECHAS_GRIS_3 || conjunto == FLECHAS_GRIS_4 || conjunto == FLECHAS_GRIS_5
                val (grados, color) = when (k) {
                    3 -> when (i) { 0 -> 180f to ROJO; 1 -> 90f to AMARILLO; else -> 0f to VERDE }
                    4 -> when (i) { 0 -> 180f to ROJO; 1 -> 135f to AMARILLO; 2 -> 45f to AMARILLO; else -> 0f to VERDE }
                    else -> when (i) { 0 -> 180f to ROJO; 1 -> 135f to AMARILLO; 2 -> 90f to AMARILLO; 3 -> 45f to AMARILLO; else -> 0f to VERDE }
                }
                // Una flecha hacia arriba, girada en el sentido de las agujas del reloj.
                val base = floatArrayOf(0f, -1f, 0.9f, -0.05f, 0.38f, -0.05f, 0.38f, 1f, -0.38f, 1f, -0.38f, -0.05f, -0.9f, -0.05f)
                val a = Math.toRadians(grados.toDouble())
                val s = sin(a).toFloat(); val c = cos(a).toFloat()
                val girada = FloatArray(base.size)
                var j = 0
                while (j < base.size) { girada[j] = base[j] * c - base[j + 1] * s; girada[j + 1] = base[j] * s + base[j + 1] * c; j += 2 }
                poli(girada, if (gris) GRIS else color)
            }
            BANDERAS_3 -> {
                linea(-0.7f, -0.95f, -0.7f, 0.95f, max(r * 0.2f, 1f), GRIS)
                poli(floatArrayOf(-0.7f, -0.9f, 0.9f, -0.55f, -0.7f, 0.1f), semaforo[i])
            }
            SEMAFORO_3, SEMAFORO_3_BORDE, SEMAFORO_4 -> {
                val color = if (k == 4) intArrayOf(NEGRO, ROJO, AMARILLO, VERDE)[i] else semaforo[i]
                circulo(cx, cy, r * 0.9f, color)
                if (conjunto == SEMAFORO_3_BORDE) {
                    raya.color = NEGRO; raya.strokeWidth = max(r * 0.16f, 1f)
                    canvas.drawCircle(cx, cy, r * 0.86f, raya)
                }
            }
            ROJO_NEGRO_4 -> circulo(cx, cy, r * 0.9f, intArrayOf(NEGRO, GRIS, ROSA, ROJO)[i])
            SENALES_3 -> when (i) {
                0 -> poli(floatArrayOf(0f, -1f, 0.95f, 0f, 0f, 1f, -0.95f, 0f), ROJO)
                1 -> poli(floatArrayOf(0f, -0.9f, 1f, 0.85f, -1f, 0.85f), AMARILLO)
                else -> circulo(cx, cy, r * 0.9f, VERDE)
            }
            SIMBOLOS_3, SIMBOLOS_3_SIN -> {
                val conCirculo = conjunto == SIMBOLOS_3
                val color = semaforo[i]
                val tinta = if (conCirculo) BLANCO else color
                if (conCirculo) circulo(cx, cy, r * 0.9f, color)
                val g = max(r * 0.26f, 1.2f)
                when (i) {
                    0 -> { linea(-0.4f, -0.4f, 0.4f, 0.4f, g, tinta); linea(0.4f, -0.4f, -0.4f, 0.4f, g, tinta) }
                    1 -> { linea(0f, -0.5f, 0f, 0.15f, g, tinta); circulo(px(0f), py(0.55f), g * 0.55f, tinta) }
                    else -> {
                        raya.color = tinta; raya.strokeWidth = g
                        camino.rewind(); camino.moveTo(px(-0.45f), py(0f)); camino.lineTo(px(-0.1f), py(0.4f)); camino.lineTo(px(0.5f), py(-0.4f))
                        canvas.drawPath(camino, raya)
                    }
                }
            }
            VALORACION_4, VALORACION_5 -> {
                val llenas = if (k == 4) i + 1 else i
                for (n in 0 until 4) {
                    val x0 = -0.95f + n * 0.5f
                    val alto = 0.5f + 0.5f * n / 3f
                    val arriba = 1f - 2f * alto
                    lleno.color = if (n < llenas) NEGRO else GRIS_CLARO
                    canvas.drawRect(px(x0), py(arriba), px(x0) + r * 0.38f, py(arriba) + r * 2f * alto, lleno)
                }
            }
            CUARTOS_5 -> {
                val radio = r * 0.88f
                circulo(cx, cy, radio, BLANCO)
                if (i > 0) {
                    lleno.color = NEGRO
                    canvas.drawArc(cx - radio, cy - radio, cx + radio, cy + radio, -90f, 90f * i, true, lleno)
                }
                raya.color = NEGRO; raya.strokeWidth = max(r * 0.14f, 1f)
                canvas.drawCircle(cx, cy, radio, raya)
            }
            CAJAS_5 -> {
                val l = cx - r * 0.85f; val t = cy - r * 0.85f; val lado2 = r * 1.7f
                if (i > 0) {
                    val alto = lado2 * i / 4f
                    lleno.color = NEGRO
                    canvas.drawRect(l, t + lado2 - alto, l + lado2, t + lado2, lleno)
                }
                raya.color = NEGRO; raya.strokeWidth = max(r * 0.14f, 1f)
                canvas.drawRect(l, t, l + lado2, t + lado2, raya)
            }
            ESTRELLAS_3 -> {
                camino.rewind()
                for (n in 0 until 10) {
                    val a = -PI / 2 + n * PI / 5
                    val rr = if (n % 2 == 0) r else r * 0.42f
                    val x = cx + (cos(a) * rr).toFloat(); val y = cy + r * 0.06f + (sin(a) * rr).toFloat()
                    if (n == 0) camino.moveTo(x, y) else camino.lineTo(x, y)
                }
                camino.close()
                when (i) {
                    2 -> { lleno.color = DORADO; canvas.drawPath(camino, lleno) }
                    1 -> {
                        lleno.color = GRIS_CLARO; canvas.drawPath(camino, lleno)
                        canvas.save()
                        canvas.clipRect(cx - r * 1.1f, cy - r * 1.1f, cx, cy + r * 1.1f)
                        lleno.color = DORADO; canvas.drawPath(camino, lleno)
                        canvas.restore()
                    }
                    else -> { lleno.color = GRIS_CLARO; canvas.drawPath(camino, lleno) }
                }
            }
            TRIANGULOS_3 -> when (i) {
                0 -> poli(floatArrayOf(-0.9f, -0.6f, 0.9f, -0.6f, 0f, 0.8f), ROJO)
                1 -> { lleno.color = AMARILLO; canvas.drawRect(cx - r * 0.9f, cy - r * 0.14f, cx + r * 0.9f, cy + r * 0.14f, lleno) }
                else -> poli(floatArrayOf(-0.9f, 0.6f, 0.9f, 0.6f, 0f, -0.8f), VERDE)
            }
            else -> {}
        }
    }
}
