package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **Las letras de reserva de un PDF de texto** (15-oct-2026, como `crates/pixpin-pdf/src/letra.rs`
 * del PC, 14-oct: «PDF del lienzo: los símbolos salen con su letra y no como «?»»).
 *
 * El texto de las notas y las tablas va en Helvetica sin incrustar ([PdfDeNota]), que solo sabe
 * WinAnsi: ∑, π, ✓, las flechas o las griegas salían como «?». Ahora lo que la Helvetica no tiene
 * se escribe con **una letra de reserva** que sí lo tenga —como hace el sistema al pintar la
 * pantalla—, incrustada con solo sus glifos (`CIDFontType2`) y con su `ToUnicode`, así el texto se
 * sigue copiando y buscando. Las reservas se abren solo si hacen falta, en el orden de [candidatas],
 * y se respeta la licencia (el `fsType` de `OS/2`: una letra «restringida» no se incrusta).
 *
 * Lo que no tiene ninguna: si [dibujo] lo sabe pintar (un emoji, con la letra de color del
 * teléfono), va como imagen; si no, sale el cuadrito del hueco (`.notdef`) y se copia como U+FFFD,
 * nunca como un «?» callado.
 *
 * Solo TrueType con contornos (`glyf`): las CFF (`.otf`, las CJK del teléfono) y las de mapa de
 * bits (el emoji de color) no sirven aquí y se saltan solas.
 */
class LetrasDelPdf(
    private val candidatas: List<File>,
    /** Lo que ninguna letra tiene, pintado (un emoji): una imagen, o null. Ver [Detalle.emojiParaPdf]. */
    val dibujo: ((String) -> PdfDeNota.MedioParaPdf?)? = null
) {
    private val abiertas = ArrayList<LetraTrueType?>()
    private val elegidas = HashMap<Int, Pair<Int, Int>?>()

    /** La reserva [i] (ya abierta: solo las que dio [buscar]). */
    fun letra(i: Int): LetraTrueType? = abiertas.getOrNull(i)

    /** **Qué reserva tiene [cp]**: su número y el glifo. Null si ninguna. Cada carácter se resuelve una vez. */
    fun buscar(cp: Int): Pair<Int, Int>? = elegidas.getOrPut(cp) {
        var i = 0
        while (i < candidatas.size) {
            val l = abrir(i)
            if (l != null) {
                val g = l.glifo(cp)
                if (g > 0 && l.tieneContorno(g)) return@getOrPut i to g
            }
            i++
        }
        null
    }

    private val pintados = HashMap<String, PdfDeNota.MedioParaPdf?>()

    /** [texto] pintado por [dibujo], una vez por texto (medir una línea lo pide muchas veces). */
    fun pintado(texto: String): PdfDeNota.MedioParaPdf? {
        val d = dibujo ?: return null
        if (texto !in pintados) pintados[texto] = runCatching { d(texto) }.getOrNull()
        return pintados[texto]
    }

    /** Una letra para el cuadrito del hueco: la primera que se pueda abrir. */
    fun paraElHueco(): Int? = candidatas.indices.firstOrNull { abrir(it) != null }

    private fun abrir(i: Int): LetraTrueType? {
        while (abiertas.size <= i) {
            val f = candidatas[abiertas.size]
            abiertas += runCatching { LetraTrueType.leer(f.readBytes(), f.nameWithoutExtension) }.getOrNull()?.takeIf { it.embebible }
        }
        return abiertas[i]
    }

    companion object {
        /**
         * Las letras del teléfono que suelen tener lo que falta, en este orden: Roboto (griegas,
         * cirílico, sumatorios, flechas), los símbolos y las matemáticas de Noto, y la de reserva de
         * siempre. [primero] va delante (las de PixPin: Liberation, que mide como la Helvetica).
         */
        fun delSistema(primero: List<File> = emptyList(), dibujo: ((String) -> PdfDeNota.MedioParaPdf?)? = null): LetrasDelPdf {
            val sistema = File("/system/fonts")
            val nombres = listOf(
                "Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "NotoSansSymbols-Regular-Subsetted.ttf",
                "NotoSansSymbols-Regular-Subsetted2.ttf", "NotoSansSymbols-Regular.ttf", "NotoSansMath-Regular.ttf",
                "NotoSerif-Regular.ttf", "DejaVuSans.ttf", "DroidSansFallback.ttf", "DroidSansFallbackFull.ttf"
            )
            return LetrasDelPdf(primero.filter { it.isFile } + nombres.map { File(sistema, it) }.filter { it.isFile }, dibujo)
        }
    }
}

/**
 * **Una letra TrueType, lo justo para incrustarla**: sus tablas, el glifo de cada carácter (`cmap`
 * 3/10 formato 12, o 3/1 formato 4), sus anchos y el subconjunto con solo los glifos usados.
 */
class LetraTrueType private constructor(private val b: ByteArray, val nombre: String) {
    private val tablas = HashMap<String, IntArray>()
    var unidades = 1000; private set
    var glifos = 0; private set
    private var locaLarga = false
    private var metricas = 0
    var ascenso = 800; private set
    var descenso = -200; private set
    var caja = intArrayOf(0, -200, 1000, 800); private set
    private var tipoDeLicencia = 0
    private val mapa = HashMap<Int, Int>()

    /** Si la licencia deja incrustarla y tiene contornos que incrustar. */
    val embebible: Boolean get() = "glyf" in tablas && "loca" in tablas && (tipoDeLicencia and 0x000F) != 0x0002 && (tipoDeLicencia and 0x0200) == 0

    private fun u16(o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun s16(o: Int) = u16(o).toShort().toInt()
    private fun u32(o: Int) = (u16(o).toLong() shl 16 or u16(o + 2).toLong()).toInt()

    private fun leerTodo() {
        val n = u16(4)
        for (i in 0 until n) {
            val r = 12 + i * 16
            val tag = String(b, r, 4, Charsets.ISO_8859_1)
            val desde = u32(r + 8); val largo = u32(r + 12)
            if (desde >= 0 && largo >= 0 && desde.toLong() + largo <= b.size) tablas[tag] = intArrayOf(desde, largo)
        }
        tablas["head"]?.let { (o, _) ->
            unidades = u16(o + 18).coerceAtLeast(16)
            caja = intArrayOf(s16(o + 36), s16(o + 38), s16(o + 40), s16(o + 42))
            locaLarga = s16(o + 50) == 1
        }
        tablas["maxp"]?.let { (o, _) -> glifos = u16(o + 4) }
        tablas["hhea"]?.let { (o, _) -> ascenso = s16(o + 4); descenso = s16(o + 6); metricas = u16(o + 34) }
        tablas["OS/2"]?.let { (o, l) -> if (l >= 10) tipoDeLicencia = u16(o + 8) }
        leerCmap()
    }

    private fun leerCmap() {
        val (o, _) = tablas["cmap"] ?: return
        val n = u16(o + 2)
        var f12 = -1; var f4 = -1
        for (i in 0 until n) {
            val r = o + 4 + i * 8
            val plataforma = u16(r); val codificacion = u16(r + 2); val sub = o + u32(r + 4)
            if (sub + 2 > b.size) continue
            val formato = u16(sub)
            if (formato == 12 && (plataforma == 3 && codificacion == 10 || plataforma == 0)) f12 = sub
            if (formato == 4 && (plataforma == 3 && (codificacion == 1 || codificacion == 0) || plataforma == 0) && f4 < 0) f4 = sub
        }
        if (f12 >= 0) {
            val grupos = u32(f12 + 12)
            for (g in 0 until grupos) {
                val r = f12 + 16 + g * 12
                val desde = u32(r); val hasta = u32(r + 4); val primero = u32(r + 8)
                if (hasta - desde > 0x20000) continue
                for (c in desde..hasta) mapa[c] = primero + (c - desde)
            }
        } else if (f4 >= 0) {
            val segmentos = u16(f4 + 6) / 2
            val finales = f4 + 14
            val inicios = finales + segmentos * 2 + 2
            val deltas = inicios + segmentos * 2
            val rangos = deltas + segmentos * 2
            for (s in 0 until segmentos) {
                val fin = u16(finales + s * 2); val ini = u16(inicios + s * 2)
                val delta = s16(deltas + s * 2); val rango = u16(rangos + s * 2)
                if (ini > fin || ini == 0xFFFF) continue
                for (c in ini..fin) {
                    val g = if (rango == 0) (c + delta) and 0xFFFF
                    else {
                        val donde = rangos + s * 2 + rango + (c - ini) * 2
                        if (donde + 1 >= b.size) 0 else u16(donde).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                    }
                    if (g != 0) mapa[c] = g
                }
            }
        }
    }

    /** El glifo de [cp]; 0 si no lo tiene. */
    fun glifo(cp: Int): Int = mapa[cp]?.takeIf { it < glifos } ?: 0

    /** El avance de [g], en milésimas del cuerpo (lo que pide el `/W` del PDF). */
    fun ancho(g: Int): Int {
        val (o, _) = tablas["hmtx"] ?: return 1000
        val i = if (g < metricas) g else metricas - 1
        if (i < 0) return 1000
        return Math.round(u16(o + i * 4) * 1000.0 / unidades).toInt()
    }

    private fun desplazamiento(g: Int): Int {
        val (o, _) = tablas["loca"]!!
        return if (locaLarga) u32(o + g * 4) else u16(o + g * 2) * 2
    }

    /** Si [g] dibuja algo (un espacio no; un emoji de una letra de mapa de bits, tampoco). */
    fun tieneContorno(g: Int): Boolean {
        if (g <= 0 || g >= glifos || "loca" !in tablas) return false
        return desplazamiento(g + 1) > desplazamiento(g)
    }

    /**
     * **La letra con solo los glifos [usados]** (y el hueco, el 0, y las piezas de los compuestos).
     * Los demás quedan vacíos con su mismo número: el PDF los pide por número (`CIDToGIDMap
     * /Identity`). Van `head`, `hhea`, `maxp`, `hmtx`, `loca`, `glyf` y las instrucciones.
     */
    fun subconjunto(usados: Set<Int>): ByteArray {
        val (glyf, largoGlyf) = tablas.getValue("glyf")
        val todos = java.util.TreeSet<Int>()
        val pendientes = ArrayDeque(usados.filter { it in 0 until glifos } + 0)
        while (pendientes.isNotEmpty()) {
            val g = pendientes.removeFirst()
            if (!todos.add(g)) continue
            val ini = desplazamiento(g); val fin = desplazamiento(g + 1)
            if (fin - ini < 10 || ini < 0 || fin > largoGlyf) continue
            // Un compuesto: sus piezas también van.
            if (s16(glyf + ini) < 0) {
                var p = glyf + ini + 10
                while (p + 4 <= glyf + fin) {
                    val banderas = u16(p); val pieza = u16(p + 2)
                    pendientes += pieza
                    p += 4 + (if (banderas and 0x1 != 0) 4 else 2) +
                        when { banderas and 0x8 != 0 -> 2; banderas and 0x40 != 0 -> 4; banderas and 0x80 != 0 -> 8; else -> 0 }
                    if (banderas and 0x20 == 0) break
                }
            }
        }
        val nuevoGlyf = ByteArrayOutputStream()
        val nuevaLoca = ByteArrayOutputStream()
        fun u32En(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
        for (g in 0 until glifos) {
            u32En(nuevaLoca, nuevoGlyf.size())
            if (g in todos) {
                val ini = desplazamiento(g); val fin = desplazamiento(g + 1)
                if (fin > ini && fin <= largoGlyf) {
                    nuevoGlyf.write(b, glyf + ini, fin - ini)
                    while (nuevoGlyf.size() % 4 != 0) nuevoGlyf.write(0)
                }
            }
        }
        u32En(nuevaLoca, nuevoGlyf.size())
        val salida = linkedMapOf<String, ByteArray>()
        for (tag in listOf("cvt ", "fpgm", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "prep")) {
            salida[tag] = when (tag) {
                "glyf" -> nuevoGlyf.toByteArray()
                "loca" -> nuevaLoca.toByteArray()
                else -> tablas[tag]?.let { (o, l) -> b.copyOfRange(o, o + l) } ?: continue
            }
        }
        // `head`: la loca va larga, y la suma del archivo se deja a cero (nadie la mira).
        salida["head"]?.let { h -> if (h.size >= 54) { h[50] = 0; h[51] = 1; for (i in 8 until 12) h[i] = 0 } }
        return escribirSfnt(salida)
    }

    companion object {
        /** Lee [bytes] como TrueType (o la primera de una colección `ttcf`). Null si no lo es. */
        fun leer(bytes: ByteArray, nombre: String): LetraTrueType? {
            if (bytes.size < 12) return null
            val cabeza = String(bytes, 0, 4, Charsets.ISO_8859_1)
            val datos = when (cabeza) {
                "\u0000\u0001\u0000\u0000", "true" -> bytes
                else -> return null
            }
            return LetraTrueType(datos, nombre.replace(Regex("[^A-Za-z0-9-]"), "").ifBlank { "Letra" }.take(40)).also { it.leerTodo() }
        }

        private fun escribirSfnt(tablas: Map<String, ByteArray>): ByteArray {
            val o = ByteArrayOutputStream()
            fun u16(v: Int) { o.write(v ushr 8); o.write(v) }
            fun u32(v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
            val n = tablas.size
            var potencia = 1; var exponente = 0
            while (potencia * 2 <= n) { potencia *= 2; exponente++ }
            u32(0x00010000); u16(n); u16(potencia * 16); u16(exponente); u16(n * 16 - potencia * 16)
            var desde = 12 + n * 16
            for ((tag, datos) in tablas) {
                o.write(tag.toByteArray(Charsets.ISO_8859_1))
                u32(sumaDe(datos)); u32(desde); u32(datos.size)
                desde += (datos.size + 3) / 4 * 4
            }
            for (datos in tablas.values) {
                o.write(datos)
                repeat((4 - datos.size % 4) % 4) { o.write(0) }
            }
            return o.toByteArray()
        }

        private fun sumaDe(d: ByteArray): Int {
            var s = 0
            var i = 0
            while (i < d.size) {
                var v = 0
                for (k in 0 until 4) v = (v shl 8) or (if (i + k < d.size) d[i + k].toInt() and 0xff else 0)
                s += v
                i += 4
            }
            return s
        }
    }
}
