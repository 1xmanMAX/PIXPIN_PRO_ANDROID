package com.forge.pixpin.planos

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * **Un plano DWG/DXF ya leído**, tal como lo deja el visor del PC en su caché (formato PXCAD v3,
 * `Modelo::a_bytes` de `crates/pixpin-cad/src/modelo.rs`). Lo escribe `libpixpincad.so` —la
 * misma lectura del PC, compilada para Android— en el proceso `:planos`; aquí solo se lee.
 *
 * Todo son palabras de 4 bytes, y cada lista va como «cuántos» + sus elementos. Las listas que
 * van a la tarjeta gráfica (vértices, índices, letras, arcos…) **no se copian**: son trozos del
 * archivo proyectado en memoria, que `glBufferData` sube tal cual. De los *tramos* —qué parte de
 * cada lista cae en qué sitio y de qué tamaño es— sí se hace copia: se recorren en cada fotograma
 * para dibujar solo lo que se ve.
 *
 * Coordenadas en `f32` respecto a [origenX]/[origenY] (el centro del plano, en `f64`): un plano en
 * coordenadas UTM se vería a saltos con `f32` a secas.
 */
class ModeloCad private constructor(
    val origenX: Double,
    val origenY: Double,
    /** `[x0, y0, x1, y1]`, respecto al origen. */
    val caja: FloatArray,
    /** x, y (f32) y color (RGBA, alfa 0 = «el color 7»): 12 bytes cada uno. */
    val vertices: Lista,
    /** Tiras de rayas separadas por [CORTE]. */
    val lineas: Lista,
    val triangulos: Lista,
    val tramosLineas: Tramos,
    val tramosTriangulos: Tramos,
    /** x, y, color y número de trama: 16 bytes. */
    val verticesTrama: Lista,
    val triangulosTrama: Lista,
    val tramosTrama: Tramos,
    /** 9 palabras: centro, inversa 2×2, escala, desde, cuántas. */
    val tramas: Lista,
    /** 16 palabras: base, dirección, paso, corrimiento, largo, n, 8 trazos. */
    val familias: Lista,
    /** [x, y] en em de todas las letras distintas. */
    val mallaLetras: Lista,
    /** [desde, cuántos] de cada letra en [mallaLetras]; el bit alto: es de rayas (SHX). */
    val glifos: Lista,
    /** 8 palabras: posición, matriz 2×2, color, glifo. */
    val letras: Lista,
    val tramosLetras: Tramos,
    /** 8 palabras: centro, radio, inicio, barrido, color y dos de relleno. */
    val arcos: Lista,
    val tramosArcos: Tramos,
    /** `$INSUNITS`: 4 mm, 5 cm, 6 m…; 0 sin decir. */
    val unidades: Int,
    /** Lo que no se pudo dibujar, por tipo (para decirlo). */
    val sinDibujar: List<Pair<String, Int>>,
) {
    /** Un trozo del archivo: [cuantos] elementos de [palabras] palabras cada uno. */
    class Lista(val datos: ByteBuffer, val cuantos: Int, val palabras: Int) {
        val bytes: Int get() = cuantos * palabras * 4
        fun palabra(elemento: Int, k: Int): Int = datos.getInt((elemento * palabras + k) * 4)
        fun real(elemento: Int, k: Int): Float = datos.getFloat((elemento * palabras + k) * 4)
    }

    /** Los tramos de una lista, de mayor a menor tamaño. */
    class Tramos(
        val desde: IntArray,
        val cuantos: IntArray,
        /** `[x0, y0, x1, y1]` de cada uno, seguidos. */
        val caja: FloatArray,
        val tamano: FloatArray,
        val clase: IntArray,
    ) {
        val n: Int get() = desde.size

        /**
         * Lo que se ve en la caja [vx0]..[vy1] y mide al menos [minimo]: pares `(desde, cuántos)`
         * en [salida], juntando los seguidos. Devuelve cuántos pares hay. Como van de mayor a
         * menor, en cuanto uno es pequeño, los demás también. (`gpu::visibles` del PC.)
         */
        fun visibles(vx0: Float, vy0: Float, vx1: Float, vy1: Float, minimo: Float, salida: IntArray): Int {
            var k = 0
            for (i in 0 until n) {
                if (tamano[i] < minimo) break
                val b = i * 4
                if (caja[b] > vx1 || caja[b + 2] < vx0 || caja[b + 1] > vy1 || caja[b + 3] < vy0) continue
                if (k > 0 && salida[k - 2] + salida[k - 1] == desde[i]) salida[k - 1] += cuantos[i]
                else {
                    if (k + 2 > salida.size) break
                    salida[k] = desde[i]; salida[k + 1] = cuantos[i]; k += 2
                }
            }
            return k / 2
        }

        fun corta(i: Int, vx0: Float, vy0: Float, vx1: Float, vy1: Float): Boolean {
            val b = i * 4
            return !(caja[b] > vx1 || caja[b + 2] < vx0 || caja[b + 1] > vy1 || caja[b + 3] < vy0)
        }
    }

    val vacio: Boolean
        get() = lineas.cuantos == 0 && triangulos.cuantos == 0 && triangulosTrama.cuantos == 0 && letras.cuantos == 0 && arcos.cuantos == 0

    class NoSeLee(mensaje: String) : Exception(mensaje)

    companion object {
        /** El índice que corta una tira de rayas (el reinicio fijo de OpenGL ES 3). */
        const val CORTE: Int = -1
        const val GLIFO_DE_RAYAS: Int = Int.MIN_VALUE
        private val MAGIA = byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), 0, 0, 3)

        /** Proyecta [archivo] en memoria y lo lee. */
        fun abrir(archivo: File): ModeloCad {
            val datos = RandomAccessFile(archivo, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            return deBytes(datos)
        }

        fun deBytes(b: ByteBuffer): ModeloCad {
            val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (d.remaining() < 8 || (d.remaining() - 8) % 4 != 0) throw NoSeLee("No es un plano leído")
            for (i in 0 until 8) if (d.get(i) != MAGIA[i]) throw NoSeLee("No es un plano leído (o es de otra versión)")
            d.position(8)
            val p = d.slice().order(ByteOrder.LITTLE_ENDIAN)
            val r = Lector(p)
            val o0 = java.lang.Double.longBitsToDouble((r.uno().toLong() and 0xffffffffL) or (r.uno().toLong() shl 32))
            val o1 = java.lang.Double.longBitsToDouble((r.uno().toLong() and 0xffffffffL) or (r.uno().toLong() shl 32))
            val caja = FloatArray(4) { java.lang.Float.intBitsToFloat(r.uno()) }
            val m = ModeloCad(
                o0, o1, caja,
                vertices = r.lista(3), lineas = r.lista(1), triangulos = r.lista(1),
                tramosLineas = r.tramos(), tramosTriangulos = r.tramos(),
                verticesTrama = r.lista(4), triangulosTrama = r.lista(1), tramosTrama = r.tramos(),
                tramas = r.lista(9), familias = r.lista(16),
                mallaLetras = r.lista(2), glifos = r.lista(2), letras = r.lista(8), tramosLetras = r.tramos(),
                arcos = r.lista(8), tramosArcos = r.tramos(),
                unidades = r.uno(),
                sinDibujar = run {
                    val n = r.uno().coerceIn(0, 10_000)
                    List(n) {
                        val largo = r.uno()
                        if (largo < 0 || largo > 4096) throw NoSeLee("Plano leído roto")
                        val bytes = ByteArray((largo + 3) / 4 * 4)
                        for (k in 0 until bytes.size / 4) {
                            val w = r.uno()
                            for (j in 0 until 4) bytes[k * 4 + j] = (w ushr (8 * j)).toByte()
                        }
                        String(bytes, 0, largo, Charsets.UTF_8) to r.uno()
                    }
                },
            )
            if (!m.valido()) throw NoSeLee("Plano leído roto")
            return m
        }

        private class Lector(val p: ByteBuffer) {
            var i = 0
            fun uno(): Int {
                if ((i + 1) * 4 > p.limit()) throw NoSeLee("Plano leído cortado")
                return p.getInt(i++ * 4)
            }

            fun lista(palabras: Int): Lista {
                val n = uno()
                val total = n.toLong() * palabras
                if (n < 0 || (i + total) * 4 > p.limit()) throw NoSeLee("Plano leído cortado")
                val trozo = p.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                trozo.position(i * 4); trozo.limit((i * 4 + total * 4).toInt())
                i += total.toInt()
                return Lista(trozo.slice().order(ByteOrder.LITTLE_ENDIAN), n, palabras)
            }

            fun tramos(): Tramos {
                val l = lista(8)
                val n = l.cuantos
                return Tramos(
                    IntArray(n) { l.palabra(it, 0) }, IntArray(n) { l.palabra(it, 1) },
                    FloatArray(n * 4) { l.real(it / 4, 2 + it % 4) },
                    FloatArray(n) { l.real(it, 6) }, IntArray(n) { l.palabra(it, 7) },
                )
            }
        }
    }

    /** Que ningún índice se salga: una caché rota no tumba la tarjeta (`Modelo::valido` del PC). */
    private fun valido(): Boolean {
        fun indices(l: Lista, n: Int, conCorte: Boolean): Boolean {
            for (k in 0 until l.cuantos) {
                val v = l.palabra(k, 0)
                if (conCorte && v == CORTE) continue
                if (v < 0 || v >= n) return false
            }
            return true
        }
        fun tramos(t: Tramos, n: Int): Boolean =
            (0 until t.n).all { t.desde[it] >= 0 && t.cuantos[it] >= 0 && t.desde[it].toLong() + t.cuantos[it] <= n }
        if (!indices(lineas, vertices.cuantos, true) || !indices(triangulos, vertices.cuantos, false)) return false
        if (!indices(triangulosTrama, verticesTrama.cuantos, false)) return false
        for (k in 0 until verticesTrama.cuantos) if (verticesTrama.palabra(k, 3) !in 0 until tramas.cuantos) return false
        for (k in 0 until tramas.cuantos) {
            val desde = tramas.palabra(k, 7).toLong() and 0xffffffffL
            val n = tramas.palabra(k, 8).toLong() and 0xffffffffL
            if (desde + n > familias.cuantos) return false
        }
        for (k in 0 until glifos.cuantos) {
            val desde = glifos.palabra(k, 0).toLong() and 0xffffffffL
            val n = (glifos.palabra(k, 1) and GLIFO_DE_RAYAS.inv()).toLong()
            if (desde + n > mallaLetras.cuantos) return false
        }
        for (k in 0 until letras.cuantos) if (letras.palabra(k, 7) !in 0 until glifos.cuantos) return false
        return tramos(tramosLineas, lineas.cuantos) && tramos(tramosTriangulos, triangulos.cuantos) &&
            tramos(tramosTrama, triangulosTrama.cuantos) && tramos(tramosLetras, letras.cuantos) && tramos(tramosArcos, arcos.cuantos)
    }
}
