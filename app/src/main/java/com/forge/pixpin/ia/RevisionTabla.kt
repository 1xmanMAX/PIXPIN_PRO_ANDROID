package com.forge.pixpin.ia

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * **Las cuentas de una tabla, comprobadas aquí** (PC `revision_tabla.rs`, 16-oct-2026: «marca los
 * errores de calculo» salía una vez de cada tres sin marcar nada, y el anotador llegó a marcar como
 * error una fila que estaba bien). Los cálculos no los hace el modelo:
 *
 * - **Productos por fila**: una columna que, en casi todas las filas, es el producto de otras dos.
 * - **Totales**: una fila «Total» cuyo valor es la suma de la columna de arriba.
 */
object RevisionTabla {
    data class Descuadre(val fila: Int, val columna: Int, val dice: Double, val deberia: Double, val texto: String)

    private fun numero(s: String) = TablaEscaneada.comoNumero(s)?.toDoubleOrNull()
    private fun cerca(a: Double, b: Double) = abs(a - b) <= 0.005 * max(max(abs(a), abs(b)), 1.0)

    fun bonito(x: Double): String = if (abs(x - Math.round(x)) < 1e-6) x.roundToLong().toString() else "%.2f".format(java.util.Locale.ROOT, x)

    private fun esTotal(fila: List<String>) = fila.any { c ->
        val t = Acciones.normalizar(c.trim()); t.startsWith("total") || t.startsWith("suma") || t == "subtotal"
    }

    fun revisar(filas: List<List<String>>): List<Descuadre> {
        val columnas = filas.maxOfOrNull { it.size } ?: 0
        if (filas.size < 3 || columnas < 3) return emptyList()
        fun celda(f: Int, c: Int) = filas[f].getOrNull(c) ?: ""
        val cabecera = (0 until columnas).all { numero(celda(0, it)) == null }
        fun nombre(c: Int): String {
            val n = if (cabecera) celda(0, c).trim() else ""
            return if (n.isEmpty()) "la columna ${'A' + c}" else "«$n»"
        }
        val datos = ((if (cabecera) 1 else 0) until filas.size).filter { !esTotal(filas[it]) }
        val errores = ArrayList<Descuadre>()
        val corregido = filas.map { f -> f.map { numero(it) }.toMutableList() }
        val usada = BooleanArray(columnas)
        for (k in 0 until columnas) {
            var mejor: IntArray? = null
            for (i in 0 until columnas) for (j in i + 1 until columnas) {
                if (i == k || j == k) continue
                var con = 0; var bien = 0
                for (f in datos) {
                    val a = numero(celda(f, i)) ?: continue; val b = numero(celda(f, j)) ?: continue; val c = numero(celda(f, k)) ?: continue
                    con++; if (cerca(a * b, c)) bien++
                }
                if (con >= 3 && bien * 10 >= con * 6 && (mejor == null || bien > mejor[2])) mejor = intArrayOf(i, j, bien, con)
            }
            val m = mejor ?: continue
            val i = m[0]; val j = m[1]
            usada[k] = true
            for (f in datos) {
                val a = numero(celda(f, i)) ?: continue; val b = numero(celda(f, j)) ?: continue; val c = numero(celda(f, k)) ?: continue
                if (!cerca(a * b, c)) {
                    val d = a * b
                    if (k < corregido[f].size) corregido[f][k] = d
                    errores += Descuadre(f, k, c, d, "fila ${f + 1} (${celda(f, 0).trim()}): ${bonito(a)} x ${bonito(b)} = ${bonito(d)}, pero ${nombre(k)} dice ${bonito(c)}")
                }
            }
        }
        for (f in filas.indices.filter { esTotal(filas[it]) }) {
            for (c in 0 until columnas) {
                val dice = numero(celda(f, c)) ?: continue
                val arriba = datos.filter { it < f }
                val valores = arriba.mapNotNull { numero(celda(it, c)) }
                if (valores.size < 2) continue
                val suma = valores.sum()
                val sumaBien = arriba.mapNotNull { corregido[it].getOrNull(c) }.sum()
                if (!cerca(suma, dice)) {
                    errores += Descuadre(f, c, dice, suma, "fila ${f + 1} (${celda(f, 0).trim()}): la suma de ${nombre(c)} es ${bonito(suma)}, pero dice ${bonito(dice)}")
                } else if (usada[c] && !cerca(sumaBien, dice)) {
                    errores += Descuadre(f, c, dice, sumaBien,
                        "fila ${f + 1} (${celda(f, 0).trim()}): suma bien lo que hay (${bonito(dice)}), pero con los parciales corregidos el total seria ${bonito(sumaBien)}")
                }
            }
        }
        return errores
    }

    /** Lo revisado dicho para el modelo; null si la tabla no tiene cuentas que revisar. */
    fun paraElModelo(filas: List<List<String>>): String? {
        val columnas = filas.maxOfOrNull { it.size } ?: 0
        val numeros = filas.flatten().count { numero(it) != null }
        if (filas.size < 3 || columnas < 3 || numeros < 6) return null
        val e = revisar(filas)
        return if (e.isEmpty()) "Cuentas comprobadas por PixPin (productos por fila y totales): todo cuadra."
        else "Cuentas comprobadas por PixPin (exactas; no las rehagas): ${e.joinToString("; ") { it.texto }}."
    }
}
