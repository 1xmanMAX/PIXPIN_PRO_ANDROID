package com.forge.pixpin.ia

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **De las palabras de una foto o un PDF, con su caja, a una tabla** (`tabla_escaneada.rs` del PC,
 * portado tal cual). El modelo NO lee las celdas: solo decide «tabla o Excel», el nombre y si la
 * primera fila es cabecera. Todo se mide en altos de letra.
 *
 * 1. **Filas**: por el centro vertical, juntando mientras se solapen más de la mitad del alto menor.
 * 2. **Celdas**: palabras separadas menos de [HUECO_EN_CELDA] altos de letra.
 * 3. **Columnas**: de la celda más estrecha a la más ancha; una que pisa dos o más es combinada.
 */
object TablaEscaneada {
    data class Palabra(val texto: String, val x: Double, val y: Double, val ancho: Double, val alto: Double) {
        val fin get() = x + ancho
        val centroY get() = y + alto / 2.0
    }

    data class Combinada(val fila: Int, val columna: Int, val columnas: Int)

    data class TablaLeida(val filas: List<List<String>> = emptyList(), val combinadas: List<Combinada> = emptyList()) {
        val columnas: Int get() = filas.firstOrNull()?.size ?: 0

        /** Si la primera fila parece cabecera: ningún número y alguna letra, con números debajo. */
        fun pareceCabecera(): Boolean {
            val primera = filas.firstOrNull() ?: return false
            fun esNumero(s: String): Boolean {
                val t = s.filter { it !in " ,.%$-/" }
                return t.isNotEmpty() && t.all { it in '0'..'9' }
            }
            val sinNumeros = primera.none(::esNumero) && primera.any { it.isNotBlank() }
            val abajo = filas.drop(1).flatten().any(::esNumero)
            return sinNumeros && (abajo || filas.size > 1)
        }

        fun aTsv(): String = filas.joinToString("\n") { it.joinToString("\t") }
    }

    const val HUECO_EN_CELDA = 0.9

    /** **Un número escrito como en un papel**, en la forma que entiende la hoja (`1250.5`); null si no lo es. */
    fun comoNumero(s: String): String? {
        var t = s.trim().trimStart('$', '€', 'S', '/', '.').trim()
        t = t.filter { !it.isWhitespace() && it != ' ' }
        val signo = if (t.startsWith("-")) { t = t.substring(1); "-" } else ""
        if (t.isEmpty() || !t.all { it in '0'..'9' || it == '.' || it == ',' } || t[0] !in '0'..'9') return null
        val i = t.lastIndexOfAny(charArrayOf('.', ','))
        val limpio = if (i < 0) t else {
            val sep = t[i]
            val otros = t.count { it == '.' || it == ',' }
            val decimales = t.length - i - 1
            val mezcla = '.' in t && ',' in t
            if (mezcla || (otros == 1 && decimales != 3)) {
                t.substring(0, i).filter { it in '0'..'9' } + "." + t.substring(i + 1)
            } else if (t.count { it == sep } == otros) {
                val grupos = t.split(sep)
                if (grupos.drop(1).all { it.length == 3 }) grupos.joinToString("") else return null
            } else return null
        }
        limpio.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val sinCeros = limpio.trimStart('0')
        val l = if (sinCeros.isEmpty() || sinCeros.startsWith(".")) "0$sinCeros" else sinCeros
        return "$signo$l"
    }

    private class Celda(var a: Double, var b: Double, val texto: StringBuilder, val alto: Double)

    /** **Reconstruye la tabla.** Sin palabras, tabla vacía. */
    fun reconstruir(palabras: List<Palabra>): TablaLeida {
        val ps = palabras.filter { it.texto.isNotBlank() && it.alto > 0.0 && it.ancho >= 0.0 }.sortedBy { it.centroY }
        if (ps.isEmpty()) return TablaLeida()
        val filas = ArrayList<MutableList<Palabra>>()
        var banda = 0.0 to 0.0
        for (p in ps) {
            val solapa = filas.isNotEmpty() && run {
                val arriba = max(banda.first, p.y)
                val abajo = min(banda.second, p.y + p.alto)
                val menor = min(banda.second - banda.first, p.alto)
                abajo - arriba > menor * 0.5
            }
            if (solapa) {
                val f = filas.last().also { it += p }
                val y0 = f.sumOf { it.y } / f.size
                val alto = f.sumOf { it.alto } / f.size
                banda = y0 to y0 + alto
            } else {
                banda = p.y to p.y + p.alto
                filas += mutableListOf(p)
            }
        }
        val celdas = filas.map { f ->
            val cs = ArrayList<Celda>()
            for (p in f.sortedBy { it.x }) {
                val ultima = cs.lastOrNull()
                if (ultima != null && p.x - ultima.b < HUECO_EN_CELDA * max(ultima.alto, p.alto)) {
                    ultima.b = max(ultima.b, p.fin)
                    ultima.texto.append(' ').append(p.texto.trim())
                    continue
                }
                cs += Celda(p.x, p.fin, StringBuilder(p.texto.trim()), p.alto)
            }
            cs
        }
        val todas = celdas.flatten().map { it.a to it.b }.sortedBy { it.second - it.first }
        val columnas = ArrayList<DoubleArray>()
        for ((a, b) in todas) {
            val pisa = columnas.indices.filter { sePisan(a to b, columnas[it][0] to columnas[it][1]) }
            when (pisa.size) {
                0 -> columnas += doubleArrayOf(a, b)
                1 -> { val c = columnas[pisa[0]]; c[0] = min(c[0], a); c[1] = max(c[1], b) }
                else -> {}
            }
        }
        columnas.sortBy { it[0] }
        val unidas = ArrayList<DoubleArray>()
        for (c in columnas) {
            val u = unidas.lastOrNull()
            if (u != null && c[0] < u[1]) u[1] = max(u[1], c[1]) else unidas += c.copyOf()
        }
        val n = max(unidas.size, 1)
        val salida = ArrayList<List<String>>()
        val combinadas = ArrayList<Combinada>()
        celdas.forEachIndexed { f, cs ->
            val fila = Array(n) { StringBuilder() }
            for (c in cs) {
                val pisa = unidas.indices.filter { sePisan(c.a to c.b, unidas[it][0] to unidas[it][1]) }
                val primera = pisa.firstOrNull() ?: masCercana(unidas, (c.a + c.b) / 2.0)
                if (pisa.size > 1) combinadas += Combinada(f, primera, pisa.size)
                val sb = fila[primera]
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(c.texto)
            }
            salida += fila.map { it.toString() }
        }
        return TablaLeida(salida, combinadas)
    }

    private fun sePisan(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean {
        val comun = min(a.second, b.second) - max(a.first, b.first)
        val menor = max(min(a.second - a.first, b.second - b.first), 1e-9)
        return comun > 0.0 && comun / menor > 0.25
    }

    private fun masCercana(columnas: List<DoubleArray>, x: Double): Int =
        columnas.indices.minByOrNull { abs((columnas[it][0] + columnas[it][1]) / 2.0 - x) } ?: 0

    /**
     * **La tabla del chat de unas filas** (`pedidos::tabla_de_filas` del PC): A1 arriba a la
     * izquierda, las celdas vacías no se escriben, los números de papel pasan a número, la cabecera
     * en negrita y cada columna del ancho de su texto más largo.
     */
    fun tablaDeFilas(nombre: String, filas: List<List<String>>, cabecera: Boolean, ahora: Long): com.forge.pixpin.motor.TablaDeCalculo {
        val celdas = LinkedHashMap<String, String>()
        val estilos = LinkedHashMap<String, com.forge.pixpin.motor.EstiloDeCelda>()
        filas.take(5000).forEachIndexed { f, fila ->
            fila.take(200).forEachIndexed { c, t0 ->
                val t = t0.trim()
                val ref = com.forge.pixpin.motor.Celdas.nombre(f, c)
                val valor = if (f == 0 && cabecera) t else comoNumero(t) ?: t
                if (valor.isNotEmpty()) celdas[ref] = valor
                if (f == 0 && cabecera && t.isNotEmpty()) estilos[ref] = com.forge.pixpin.motor.EstiloDeCelda(n = true)
            }
        }
        val anchos = LinkedHashMap<String, Int>()
        val columnas = minOf(filas.maxOfOrNull { it.size } ?: 0, 200)
        for (c in 0 until columnas) {
            val letras = filas.mapNotNull { it.getOrNull(c) }.maxOfOrNull { it.trim().codePointCount(0, it.trim().length) } ?: 0
            val ancho = (letras * 8 + 16).coerceIn(64, 320)
            if (ancho > 88) anchos[com.forge.pixpin.motor.Celdas.nombre(0, c).trimEnd { it in '0'..'9' }] = ancho
        }
        return com.forge.pixpin.motor.TablaDeCalculo(nombre.trim().ifEmpty { "Tabla" }, celdas, anchos, estilos, ahora)
    }
}
