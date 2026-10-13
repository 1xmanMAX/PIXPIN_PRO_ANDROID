package com.forge.pixpin.excel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **Una hoja de Excel lista para pintar**, tal como la deja la lectura del PC (`crates/pixpin-hojas`,
 * compilada para Android en `libpixpinoffice.so`, ver `herramientas/pixpin-office-android/src/hojas.rs`).
 *
 * Tres formatos (little-endian), que aquí se leen a objetos sin nada de Android para poder
 * probarlos en la JVM:
 * - **PXHL**, el libro: sus hojas y la que estaba delante;
 * - **PXHG**, la geometría de una hoja: dónde empieza cada columna y cada fila (px al 100 % a
 *   96 ppp, como el PC), paneles inmovilizados, combinaciones, enlaces y dibujos;
 * - **PXHT**, un trozo de filas: cada celda con su texto ya formateado como lo enseña Excel
 *   (números, fechas, porcentajes, con la configuración regional del teléfono) y su estilo (letra,
 *   color, relleno y alineación, con lo de la tabla y el formato condicional ya puesto), y los
 *   bordes decididos lado a lado. Los estilos llegan una vez: cada trozo trae los nuevos.
 *
 * v2 (13-oct-2026, PC 9975385): barras de datos completas (desde/hasta, borde, eje), iconos,
 * minigráficos y comentarios en cada trozo; botones del autofiltro y listas desplegables en la
 * geometría.
 */
object Pxhj {
    const val VERSION = 2

    class NoSeLee(porque: String) : Exception(porque)

    // ----------------------------------------------------------------------------------- libro

    /** [visibilidad]: 0 visible, 1 oculta, 2 muy oculta. [tipo]: 0 hoja, 1 gráfico, 2 otra. */
    data class InfoHoja(val nombre: String, val visibilidad: Int, val tipo: Int) {
        val visible get() = visibilidad == 0
    }

    class Libro(val hojas: List<InfoHoja>, val activa: Int)

    // ------------------------------------------------------------------------------ geometría

    data class Rango(val f0: Int, val c0: Int, val f1: Int, val c1: Int) {
        fun contiene(f: Int, c: Int) = f in f0..f1 && c in c0..c1
        val celdas: Long get() = (f1 - f0 + 1).toLong() * (c1 - c0 + 1)
    }

    class Enlace(val rango: Rango, val externo: Boolean, val destino: String)

    sealed class Dibujo(val x: Float, val y: Float, val ancho: Float, val alto: Float) {
        /** [recorte]: izquierda, arriba, derecha, abajo, en fracción del original. */
        class Imagen(x: Float, y: Float, ancho: Float, alto: Float, val ruta: String, val recorte: FloatArray) : Dibujo(x, y, ancho, alto)
        class Grafico(
            x: Float, y: Float, ancho: Float, alto: Float,
            val titulo: String, val tipo: String, val categorias: List<String>, val series: List<Serie>
        ) : Dibujo(x, y, ancho, alto)
        /** [alinear]: 0 izquierda 1 centro 2 derecha; [vertical]: 0 arriba 1 centro 2 abajo. Colores 0 = sin. */
        class Forma(
            x: Float, y: Float, ancho: Float, alto: Float,
            val texto: String, val relleno: Int, val linea: Int, val alinear: Int, val vertical: Int
        ) : Dibujo(x, y, ancho, alto)
    }

    class Serie(val nombre: String, val color: Int, val valores: DoubleArray)

    /** Un botón del autofiltro en la fila de encabezado; [filtrado]: con un filtro puesto (el embudo). */
    class BotonDeFiltro(val fila: Int, val col: Int, val filtrado: Boolean)

    class Geometria(
        val version: Long,
        val completa: Boolean,
        val filasFijas: Int,
        val columnasFijas: Int,
        val cuadricula: Boolean,
        val encabezados: Boolean,
        val zoom: Int,
        val primeraFila: Int,
        val primeraColumna: Int,
        val filaActiva: Int,
        val columnaActiva: Int,
        val colorPestana: Int,
        val anchoDefecto: Int,
        val altoDefecto: Int,
        val ultimaFila: Int,
        val ultimaColumna: Int,
        val anchoUsado: Int,
        val altoUsado: Int,
        /** x de cada columna, de la 0 a la `xs.size - 1` (la última es el final de la anterior). */
        val xs: IntArray,
        val ys: IntArray,
        /** Rellenos de columnas enteras: (min, max, argb) seguidos. */
        val rellenosDeColumnas: IntArray,
        val combinadas: List<Rango>,
        val enlaces: List<Enlace>,
        val dibujos: List<Dibujo>,
        /** Los botones del autofiltro (de la hoja y de las tablas). */
        val botones: List<BotonDeFiltro> = emptyList(),
        /** Las listas desplegables: los rangos de cada una. */
        val listas: List<List<Rango>> = emptyList()
    ) {
        /** Si la celda tiene una lista desplegable. */
        fun conLista(f: Int, c: Int): Boolean = listas.any { l -> l.any { it.contiene(f, c) } }

        /** Dónde empieza la columna [c] (px de hoja al 100 %). */
        fun x(c: Int): Long {
            val n = xs.size - 1
            return if (c <= n) xs[c.coerceAtLeast(0)].toLong() else xs[n].toLong() + (c - n).toLong() * anchoDefecto
        }

        fun y(f: Int): Long {
            val n = ys.size - 1
            return if (f <= n) ys[f.coerceAtLeast(0)].toLong() else ys[n].toLong() + (f - n).toLong() * altoDefecto
        }

        fun ancho(c: Int) = (x(c + 1) - x(c)).toInt()
        fun alto(f: Int) = (y(f + 1) - y(f)).toInt()

        /** La columna que contiene la x. */
        fun columnaEn(x: Double): Int = posicion(xs, x, anchoDefecto, MAX_COLUMNAS)

        fun filaEn(y: Double): Int = posicion(ys, y, altoDefecto, MAX_FILAS)

        /** La combinación que contiene la celda, si la hay. */
        fun combinadaEn(f: Int, c: Int): Rango? {
            for (r in combinadas) if (r.contiene(f, c)) return r
            return null
        }

        fun enlaceEn(f: Int, c: Int): Enlace? = enlaces.firstOrNull { it.rango.contiene(f, c) }

        private fun posicion(v: IntArray, p: Double, porDefecto: Int, max: Int): Int {
            val n = v.size - 1
            if (p >= v[n]) {
                if (porDefecto <= 0) return n.coerceAtMost(max - 1)
                return (n + ((p - v[n]) / porDefecto).toInt()).coerceIn(0, max - 1)
            }
            // La última posición con v[i] <= p.
            var lo = 0
            var hi = n
            while (lo < hi) {
                val m = (lo + hi + 1) ushr 1
                if (v[m] <= p) lo = m else hi = m - 1
            }
            return lo.coerceIn(0, max - 1)
        }
    }

    // ---------------------------------------------------------------------------------- trozo

    /**
     * Un estilo de celda ya resuelto. [letra]: 1 negrita, 2 cursiva, 4 tachado, 8 subrayado,
     * 16 doble subrayado, 32 superíndice, 64 subíndice. [relleno] 0 = sin. [horizontal]: 0
     * general (sin contenido), 1 izquierda, 2 centro, 3 derecha, 4 rellenar, 5 justificar, 6 centrar
     * en la selección, 7 distribuido. [vertical]: 0 arriba, 1 centro, 2 abajo, 3 justificar, 4
     * distribuido. [giro]: el del XML (0-90 contrarreloj, 91-180 = -1..-90, 255 apilado).
     */
    data class Estilo(
        val familia: String,
        val puntos: Float,
        val letra: Int,
        val colorTexto: Int,
        val relleno: Int,
        val horizontal: Int,
        val vertical: Int,
        val ajustar: Boolean,
        val encoger: Boolean,
        val sangria: Int,
        val giro: Int
    ) {
        val negrita get() = letra and 1 != 0
        val cursiva get() = letra and 2 != 0
        val tachado get() = letra and 4 != 0
        val subrayado get() = letra and 24 != 0

        /** El giro en grados contrarreloj (-90..90); null si es apilado. */
        val grados: Float? get() = when {
            giro == 255 -> null
            giro <= 90 -> giro.toFloat()
            giro <= 180 -> -(giro - 90).toFloat()
            else -> 0f
        }
    }

    class Tramo(val largo: Int, val familia: String, val puntos: Float, val letra: Int, val argb: Int)

    /**
     * Una barra de datos ya resuelta: de [desde] a [hasta] (fracciones del ancho útil), con
     * degradado o lisa, su borde (0 = sin) y la línea del eje en [eje] (< 0 = sin).
     */
    class Barra(val desde: Float, val hasta: Float, val argb: Int, val degradado: Boolean, val borde: Int, val eje: Float, val colorEje: Int)

    /** [clase]: 0 vacía, 1 número, 2 texto, 3 lógico, 4 error. [corte] -1 = sin; [conjunto] -1 = sin icono. */
    class Celda(
        val col: Int,
        val estilo: Int,
        val clase: Int,
        val texto: String,
        val barra: Barra?,
        val corte: Int,
        val tramos: List<Tramo>?,
        /** El conjunto de iconos (orden de `Conjunto::TODOS` del PC, ver [IconosExcel]) y el icono (0 = el peor). */
        val conjunto: Int = -1,
        val icono: Int = 0
    ) {
        /** Lo medido al pintar (ancho del texto a 1 px por punto de letra), por quien pinta. */
        @JvmField var medida: Float = -1f
        @JvmField var cache: Any? = null
    }

    class Fila(val indice: Int, val oculta: Boolean, val sinAlto: Boolean, val relleno: Int, val celdas: Array<Celda>) {
        fun celda(col: Int): Celda? {
            var lo = 0
            var hi = celdas.size - 1
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                val c = celdas[m].col
                if (c < col) lo = m + 1 else if (c > col) hi = m - 1 else return celdas[m]
            }
            return null
        }

        /** La primera celda con columna ≥ [col]. */
        fun desde(col: Int): Int {
            var lo = 0
            var hi = celdas.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (celdas[m].col < col) lo = m + 1 else hi = m
            }
            return lo
        }
    }

    /**
     * Los bordes del trozo, de menos a más fuertes. [tipos]: 0 horizontal (arriba de la fila),
     * 1 vertical (a la izquierda de la columna), 2 diagonal que baja, 3 diagonal que sube.
     * [estilos]: el orden de `EstiloLinea` del PC (ver [Linea]).
     */
    class Lados(val tipos: ByteArray, val filas: IntArray, val columnas: IntArray, val estilos: ByteArray, val colores: IntArray) {
        val size get() = tipos.size
    }

    /**
     * Un minigráfico ya calculado. [tipo]: 0 línea, 1 columnas, 2 ganancia/pérdida. [marcas]: 1
     * marcadores, 2 alto, 4 bajo, 8 primero, 16 último, 32 negativos, 64 eje, 128 unir los huecos.
     * [colores]: serie, negativo, eje, marcadores, primero, último, alto, bajo. [puntos]: NaN = hueco.
     */
    class Mini(
        val fila: Int, val col: Int, val tipo: Int, val marcas: Int, val peso: Float,
        val minimo: Double, val maximo: Double, val colores: IntArray, val puntos: FloatArray
    ) {
        fun tiene(m: Int) = marcas and m != 0
    }

    /** Una nota o un comentario encadenado ([encadenado]) con sus respuestas (autor, texto). */
    class Comentario(val fila: Int, val col: Int, val encadenado: Boolean, val autor: String, val texto: String, val respuestas: List<Pair<String, String>>) {
        /** Todo lo que dice, para enseñarlo: autor y texto, y las respuestas debajo. */
        fun completo(): String = buildString {
            if (autor.isNotBlank()) append(autor).append(": ")
            append(texto.trim())
            for ((a, t) in respuestas) append("\n").append(if (a.isNotBlank()) "$a: " else "").append(t.trim())
        }
    }

    class Trozo(
        val version: Long,
        val f0: Int,
        val f1: Int,
        /** Desde qué número de estilo vienen los [estilos] nuevos. */
        val desde: Int,
        val estilos: List<Estilo>,
        val filas: List<Fila>,
        val lados: Lados,
        val minis: List<Mini> = emptyList(),
        val comentarios: List<Comentario> = emptyList()
    ) {
        private val porIndice = HashMap<Int, Fila>(filas.size * 2).apply { filas.forEach { put(it.indice, it) } }
        fun fila(f: Int): Fila? = porIndice[f]
        fun comentario(f: Int, c: Int): Comentario? = comentarios.firstOrNull { it.fila == f && it.col == c }
    }

    /** Los estilos de línea del PC (`EstiloLinea`), por su número. */
    object Linea {
        const val NINGUNO = 0
        const val PELO = 1
        const val PUNTEADO = 2
        const val RAYA_PUNTO_PUNTO = 3
        const val RAYA_PUNTO = 4
        const val DISCONTINUO = 5
        const val FINO = 6
        const val MEDIO_RAYA_PUNTO_PUNTO = 7
        const val RAYA_PUNTO_INCLINADA = 8
        const val MEDIO_RAYA_PUNTO = 9
        const val MEDIO_DISCONTINUO = 10
        const val MEDIO = 11
        const val DOBLE = 12
        const val GRUESO = 13

        /** Grosor en px al 100 % (como `EstiloLinea::grosor`). */
        fun grosor(e: Int): Float = when (e) {
            NINGUNO -> 0f
            MEDIO, MEDIO_DISCONTINUO, MEDIO_RAYA_PUNTO, MEDIO_RAYA_PUNTO_PUNTO, RAYA_PUNTO_INCLINADA -> 2f
            GRUESO, DOBLE -> 3f
            else -> 1f
        }
    }

    const val MAX_COLUMNAS = 16_384
    const val MAX_FILAS = 1_048_576

    // ---------------------------------------------------------------------------------- leer

    fun libro(bytes: ByteArray): Libro = leyendo(bytes, "PXHL") { l ->
        val n = l.cuenta()
        val hojas = List(n) { InfoHoja(l.str(), l.u8(), l.u8()) }
        Libro(hojas, l.u32())
    }

    fun geometria(bytes: ByteArray): Geometria = leyendo(bytes, "PXHG") { l ->
        val version = l.b.long
        val completa = l.u8() != 0
        val filasFijas = l.u32()
        val columnasFijas = l.u32()
        val marcas = l.u8()
        val zoom = l.u16()
        val primeraFila = l.u32()
        val primeraColumna = l.u32()
        val filaActiva = l.b.int
        val columnaActiva = l.b.int
        val pestana = l.b.int
        val anchoDefecto = l.u32()
        val altoDefecto = l.u32()
        val ultimaFila = l.u32()
        val ultimaColumna = l.u32()
        val anchoUsado = l.u32()
        val altoUsado = l.u32()
        val xs = IntArray(l.cuenta(4) + 1) { l.b.int }
        // Las filas: las leídas, con los altos que no son el de por defecto.
        val ny = l.u32().coerceAtMost(MAX_FILAS)
        val ys = IntArray(ny + 1)
        val n = l.cuenta(8)
        val fs = IntArray(n)
        val altos = IntArray(n)
        for (k in 0 until n) { fs[k] = l.u32(); altos[k] = l.u32() }
        var k = 0
        for (f in 0 until ny) {
            // En orden (si vinieran desordenadas, lo que ya pasó se salta).
            while (k < n && fs[k] < f) k++
            ys[f + 1] = ys[f] + if (k < n && fs[k] == f) altos[k] else altoDefecto
        }
        val nr = l.cuenta(12)
        val rellenos = IntArray(nr * 3) { l.b.int }
        val combinadas = List(l.cuenta(16)) { l.rango() }
        val enlaces = List(l.cuenta(17)) { Enlace(l.rango(), l.u8() != 0, l.str()) }
        val dibujos = List(l.cuenta(17)) {
            val x = l.b.float
            val y = l.b.float
            val an = l.b.float
            val al = l.b.float
            when (val t = l.u8()) {
                0 -> Dibujo.Imagen(x, y, an, al, l.str(), FloatArray(4) { l.b.float })
                1 -> {
                    val titulo = l.str()
                    val tipo = l.str()
                    val cats = List(l.cuenta()) { l.str() }
                    val series = List(l.cuenta()) { Serie(l.str(), l.b.int, DoubleArray(l.cuenta(8)) { l.b.double }) }
                    Dibujo.Grafico(x, y, an, al, titulo, tipo, cats, series)
                }
                2 -> Dibujo.Forma(x, y, an, al, l.str(), l.b.int, l.b.int, l.u8(), l.u8())
                else -> throw NoSeLee("dibujo de tipo $t")
            }
        }
        val botones = List(l.cuenta(9)) { BotonDeFiltro(l.u32(), l.u32(), l.u8() != 0) }
        val listas = List(l.cuenta(4)) { List(l.cuenta(16)) { l.rango() } }
        Geometria(
            version, completa, filasFijas, columnasFijas, marcas and 1 != 0, marcas and 2 != 0, zoom,
            primeraFila, primeraColumna, filaActiva, columnaActiva, pestana, anchoDefecto, altoDefecto,
            ultimaFila, ultimaColumna, anchoUsado, altoUsado, xs, ys, rellenos, combinadas, enlaces, dibujos, botones, listas
        )
    }

    fun trozo(bytes: ByteArray): Trozo = leyendo(bytes, "PXHT") { l ->
        val version = l.b.long
        val f0 = l.u32()
        val f1 = l.u32()
        val desde = l.u32()
        val estilos = List(l.cuenta(24)) {
            val familia = l.str()
            val puntos = l.b.float
            val letra = l.u16()
            val color = l.b.int
            val relleno = l.b.int
            val h = l.u8()
            val v = l.u8()
            val m = l.u8()
            val sangria = l.u8()
            val giro = l.u16()
            Estilo(familia, puntos, letra, color, relleno, h, v, m and 1 != 0, m and 2 != 0, sangria, giro)
        }
        val filas = List(l.cuenta(13)) {
            val indice = l.u32()
            val m = l.u8()
            val relleno = l.b.int
            val celdas = Array(l.cuenta(14)) {
                val col = l.u32()
                val estilo = l.u32()
                if (estilo >= desde + estilos.size) throw NoSeLee("estilo $estilo que no ha llegado")
                val clase = l.u8()
                val marcas = l.u8()
                val texto = l.str()
                val barra = if (marcas and 1 != 0) Barra(l.b.float, l.b.float, l.b.int, l.u8() != 0, l.b.int, l.b.float, l.b.int) else null
                val corte = if (marcas and 2 != 0) l.u32() else -1
                val tramos = if (marcas and 4 != 0) List(l.cuenta(18)) { Tramo(l.u32(), l.str(), l.b.float, l.u16(), l.b.int) } else null
                var conjunto = -1
                var icono = 0
                if (marcas and 8 != 0) { conjunto = l.u8(); icono = l.u8() }
                Celda(col, estilo, clase, texto, barra, corte, tramos, conjunto, icono)
            }
            Fila(indice, m and 1 != 0, m and 2 != 0, relleno, celdas)
        }
        val n = l.cuenta(14)
        val tipos = ByteArray(n)
        val fs = IntArray(n)
        val cs = IntArray(n)
        val es = ByteArray(n)
        val colores = IntArray(n)
        for (i in 0 until n) {
            tipos[i] = l.b.get(); fs[i] = l.u32(); cs[i] = l.u32(); es[i] = l.b.get(); colores[i] = l.b.int
        }
        val minis = List(l.cuenta(62)) {
            val fila = l.u32(); val col = l.u32(); val tipo = l.u8(); val m = l.u8(); val peso = l.b.float
            val lo = l.b.double; val hi = l.b.double
            val colores8 = IntArray(8) { l.b.int }
            val puntos = FloatArray(l.cuenta(4)) { l.b.float }
            Mini(fila, col, tipo, m, peso, lo, hi, colores8, puntos)
        }
        val comentarios = List(l.cuenta(21)) {
            val fila = l.u32(); val col = l.u32(); val enc = l.u8() != 0
            val autor = l.str(); val texto = l.str()
            Comentario(fila, col, enc, autor, texto, List(l.cuenta(8)) { l.str() to l.str() })
        }
        Trozo(version, f0, f1, desde, estilos, filas, Lados(tipos, fs, cs, es, colores), minis, comentarios)
    }

    private inline fun <T> leyendo(bytes: ByteArray, magia: String, leer: (Lector) -> T): T = try {
        val l = Lector(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        val m = ByteArray(4).also { l.b.get(it) }
        if (String(m, Charsets.US_ASCII) != magia) throw NoSeLee("no es $magia")
        val v = l.u16()
        if (v != VERSION) throw NoSeLee("$magia versión $v (se sabe leer la $VERSION)")
        leer(l)
    } catch (e: NoSeLee) {
        throw e
    } catch (e: RuntimeException) {
        // Cortado (BufferUnderflow) o con números que no cuadran: no se pinta a medias.
        throw NoSeLee("$magia roto: ${e.javaClass.simpleName}")
    }

    private class Lector(val b: ByteBuffer) {
        fun u8() = b.get().toInt() and 0xff
        fun u16() = b.short.toInt() and 0xffff
        fun u32(): Int {
            val v = b.int
            if (v < 0) throw NoSeLee("cuenta imposible")
            return v
        }

        /** Una cuenta de cosas que ocupan al menos [minimo] bytes cada una: no más de las que caben. */
        fun cuenta(minimo: Int = 1): Int {
            val n = u32()
            if (n.toLong() * minimo > b.remaining()) throw NoSeLee("cuenta de $n que no cabe")
            return n
        }

        fun str(): String {
            val n = cuenta()
            val s = String(b.array(), b.arrayOffset() + b.position(), n, Charsets.UTF_8)
            b.position(b.position() + n)
            return s
        }

        fun rango() = Rango(u32(), u32(), u32(), u32())
    }
}
