package com.forge.pixpin.motor

import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **El plano leído, empaquetado para que quepa en una página web.**
 *
 * Lo que sale de [PlanoDePdf] son casi dos millones de puntos: escritos a lo bruto —«M 12.34
 * 56.78 L …»— son treinta megas de texto, y eso no se manda por WhatsApp. Aquí se aprieta
 * hasta que cabe, en tres pasos que se apoyan uno en otro:
 *
 * 1. **Se empalman los tramos.** AutoCAD escribe cada segmento de una polilínea por separado
 *    —`m … l … S` seiscientas mil veces— aunque el final de uno sea el principio del
 *    siguiente. Como las coordenadas vienen en punto fijo, dos extremos que coinciden
 *    coinciden **exactamente**, y volver a coserlos quita a la vez órdenes, puntos repetidos y
 *    trabajo de pintado al navegador.
 * 2. **Se escriben diferencias.** Cada punto se guarda como lo que cambia respecto del
 *    anterior, en pasos de 1/[PlanoDePdf.FINEZA] de punto y con longitud variable: un tramo
 *    corto ocupa dos bytes, uno largo tres. Ordenar los caminos por dónde caen en el papel
 *    hace que los saltos entre uno y otro también sean pequeños.
 * 3. **Se comprime.** El resultado pasa por el mismo compresor que un ZIP y sale en base64.
 *    El visor lo descomprime en el navegador con [VisorPlano.INFLAR], que son cien líneas: no
 *    se depende de que el navegador traiga `DecompressionStream`, ni se pide nada por la red.
 *
 * Con el plano del usuario —A0, 800.000 segmentos— eso deja el dibujo entero en unos pocos
 * megas, **nítido a cualquier aumento**, donde la fotografía de 4.000 píxeles que se mandaba
 * antes pesaba parecido y se veía borrosa pasando de tres aumentos. Ver [PlanoDePdf].
 */
object PlanoWeb {

    /**
     * El ancho en unidades del dibujo, que es el que gasta el resto de PixPin para el papel
     * de un PDF (`PdfDoc.PAGE_WIDTH`). Va aquí como número y no importado **porque el motor
     * separado no puede tocar Android**, y `PdfDoc` sí lo toca.
     */
    const val ANCHO_EN_UNIDADES = 1400.0

    /**
     * **La página de un PDF lista para el documento web, o null si no compensa.**
     *
     * Devuelve null en los tres casos en los que mandar geometría sería peor que mandar la
     * fotografía de siempre: si el archivo no se entiende, si la página **pinta algo que aquí
     * no está** —una imagen que no se pudo pasar tal cual, un degradado, un patrón: entonces
     * la lámina saldría incompleta, y una lámina a la que le falta algo es peor que una
     * borrosa, porque lo borroso se ve y lo que falta no— y si apenas tiene geometría. Ver
     * [PlanoDePdf.Plano.valeLaPena] y [PlanoDePdf.Plano.sinEntender].
     *
     * Va con red: leer un plano enorme puede quedarse sin memoria en un teléfono modesto, y
     * eso aquí es un null y volver a la imagen, no un cierre de la aplicación.
     */
    fun deArchivo(
        ruta: String,
        pagina: Int,
        anchoEnUnidades: Double = ANCHO_EN_UNIDADES
    ): String? = runCatching {
        val plano = PlanoDePdf.deArchivo(ruta, pagina) ?: return null
        if (!plano.valeLaPena || plano.sinEntender > 0) return null
        aJson(plano, anchoEnUnidades)
    }.getOrNull()

    /**
     * El plano como JSON, listo para meter en el documento web.
     *
     * [anchoEnUnidades] es lo que mide el papel en el dibujo: todo sale ya en esas unidades,
     * así que el visor no tiene que saber nada de puntos de PDF.
     */
    fun aJson(plano: PlanoDePdf.Plano, anchoEnUnidades: Double = ANCHO_EN_UNIDADES): String {
        val escala = anchoEnUnidades / plano.ancho
        val porPaso = escala / PlanoDePdf.FINEZA
        val flujo = Flujo()
        val cabeceras = ArrayList<String>(plano.brochas.size)

        for (b in plano.brochas) {
            val ordenes = escribirBrocha(b, flujo)
            if (ordenes == 0) continue
            cabeceras += buildString {
                append("{\"c\":").append(b.capa)
                append(",\"t\":\"").append(hex(b.color)).append('"')
                append(",\"g\":").append(numero(b.grosor * escala))
                if (b.relleno) append(",\"r\":1")
                if (b.alfa < 0.999) append(",\"o\":").append(numero(b.alfa))
                if (b.raya.isNotEmpty()) {
                    append(",\"d\":[")
                    b.raya.forEachIndexed { i, v ->
                        if (i > 0) append(',')
                        append(numero(v * escala))
                    }
                    append(']')
                }
                append(",\"n\":").append(ordenes).append('}')
            }
        }

        return buildString(1024 + flujo.tam) {
            append("{\"a\":").append(numero(anchoEnUnidades))
            append(",\"b\":").append(numero(plano.alto * escala))
            append(",\"e\":").append(numero(porPaso, 9))
            append(",\"capas\":[")
            plano.capas.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append("{\"n\":\"").append(texto(c.nombre)).append('"')
                if (!c.encendida) append(",\"v\":0")
                append('}')
            }
            append("],\"brochas\":[")
            cabeceras.forEachIndexed { i, c -> if (i > 0) append(','); append(c) }
            append("],\"fotos\":[")
            plano.fotos.forEachIndexed { i, f ->
                if (i > 0) append(',')
                append("{\"c\":").append(f.capa)
                append(",\"m\":[")
                append(numero(f.a * escala)).append(',').append(numero(f.b * escala)).append(',')
                append(numero(f.c * escala)).append(',').append(numero(f.d * escala)).append(',')
                append(numero(f.x * escala)).append(',').append(numero(f.y * escala))
                append("],\"u\":\"data:").append(f.tipo).append(";base64,")
                append(java.util.Base64.getEncoder().encodeToString(f.datos)).append('"')
                if (f.alfa < 0.999) append(",\"o\":").append(numero(f.alfa))
                append('}')
            }
            append("],\"textos\":[")
            plano.textos.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append("{\"c\":").append(t.capa)
                append(",\"t\":\"").append(hex(t.color)).append('"')
                append(",\"s\":\"").append(texto(t.texto)).append('"')
                append(",\"m\":[")
                append(numero(t.a * escala)).append(',').append(numero(t.b * escala)).append(',')
                append(numero(t.c * escala)).append(',').append(numero(t.d * escala)).append(',')
                append(numero(t.x * escala)).append(',').append(numero(t.y * escala))
                append("],\"w\":").append(numero(t.ancho, 4))
                if (t.familia != "sans-serif") append(",\"f\":\"").append(t.familia).append('"')
                if (t.negrita) append(",\"n\":1")
                if (t.cursiva) append(",\"i\":1")
                if (t.alfa < 0.999) append(",\"o\":").append(numero(t.alfa))
                append('}')
            }
            append("],\"datos\":\"").append(comprimido(flujo.bytes())).append("\"}")
        }
    }

    // ---------------------------------------------------------------------
    // El flujo de órdenes
    // ---------------------------------------------------------------------

    /**
     * Escribe una brocha y devuelve cuántas órdenes ocupó.
     *
     * El camino se parte en subcaminos, se empalman los que se tocan y se ordenan por dónde
     * caen; después se escriben como diferencias. Ver la explicación de arriba.
     */
    private fun escribirBrocha(b: PlanoDePdf.Brocha, flujo: Flujo): Int {
        val trozos = trocear(b) ?: return 0
        if (trozos.isEmpty()) return 0
        val orden = if (b.relleno) trozos else empalmar(b, trozos)
        var ordenes = 0
        for (t in orden) ordenes += escribirTrozo(b, t, flujo)
        return ordenes
    }

    /** Un subcamino: dónde empiezan y acaban sus órdenes y sus puntos. */
    private class Trozo(
        val op0: Int, val op1: Int, val pt0: Int, val pt1: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val cerrado: Boolean
    ) {
        var siguiente: Trozo? = null
        var usado = false
    }

    private fun trocear(b: PlanoDePdf.Brocha): List<Trozo>? {
        if (b.ops.isEmpty()) return null
        val out = ArrayList<Trozo>(64)
        var i = 0
        var p = 0
        while (i < b.ops.size) {
            if (b.ops[i] != PlanoDePdf.MOVER) { i++; continue }
            val op0 = i
            val pt0 = p
            val x0 = b.xs[p]
            val y0 = b.ys[p]
            var cerrado = false
            var ultimo = p
            i++; p++
            bucle@ while (i < b.ops.size) {
                when (b.ops[i]) {
                    PlanoDePdf.MOVER -> break@bucle
                    PlanoDePdf.LINEA -> { ultimo = p; p++; i++ }
                    PlanoDePdf.CURVA -> { ultimo = p + 2; p += 3; i++ }
                    PlanoDePdf.CERRAR -> { cerrado = true; i++ }
                    else -> i++
                }
            }
            if (p > pt0 + 0 && ultimo < b.xs.size) {
                out += Trozo(op0, i, pt0, p, x0, y0, b.xs[ultimo], b.ys[ultimo], cerrado)
            }
        }
        return out
    }

    /**
     * **Cose los subcaminos que se tocan.**
     *
     * Se indexan por dónde empiezan y se va tirando del hilo: el que acaba donde empieza otro
     * lo arrastra, y así hasta que no haya con quién seguir. Lo que quede suelto se ordena por
     * bandas del papel —una banda de sesenta y cuatro puntos, de arriba abajo y de izquierda a
     * derecha— para que el salto de un camino al siguiente sea corto y el compresor lo note.
     */
    private fun empalmar(b: PlanoDePdf.Brocha, trozos: List<Trozo>): List<Trozo> {
        val porInicio = HashMap<Long, ArrayList<Trozo>>(trozos.size * 2)
        for (t in trozos) {
            if (t.cerrado) continue
            porInicio.getOrPut(clave(t.x0, t.y0)) { ArrayList(2) }.add(t)
        }
        val cabezas = ArrayList<Trozo>(trozos.size)
        for (t in trozos) {
            if (t.usado) continue
            t.usado = true
            cabezas += t
            var actual = t
            var largo = 0
            while (largo++ < 100_000) {
                val candidatos = porInicio[clave(actual.x1, actual.y1)] ?: break
                val sig = candidatos.firstOrNull { !it.usado } ?: break
                if (sig.cerrado) break
                sig.usado = true
                actual.siguiente = sig
                actual = sig
            }
        }
        val banda = PlanoDePdf.FINEZA * 64
        cabezas.sortWith(compareBy({ it.y0.floorDiv(banda) }, { it.x0 }))
        return cabezas
    }

    private fun clave(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** Escribe un camino y los que le siguen empalmados. */
    private fun escribirTrozo(b: PlanoDePdf.Brocha, cabeza: Trozo, flujo: Flujo): Int {
        var ordenes = 0
        var t: Trozo? = cabeza
        var primero = true
        while (t != null) {
            var i = t.op0
            var p = t.pt0
            while (i < t.op1) {
                when (b.ops[i]) {
                    PlanoDePdf.MOVER -> {
                        // El empalme es justo esto: el segundo camino no vuelve a levantar la
                        // pluma, sigue desde donde estaba el primero.
                        if (primero) { flujo.op(PlanoDePdf.MOVER); flujo.punto(b.xs[p], b.ys[p]); ordenes++ }
                        p++
                    }
                    PlanoDePdf.LINEA -> {
                        flujo.op(PlanoDePdf.LINEA); flujo.punto(b.xs[p], b.ys[p]); ordenes++; p++
                    }
                    PlanoDePdf.CURVA -> {
                        flujo.op(PlanoDePdf.CURVA)
                        flujo.punto(b.xs[p], b.ys[p])
                        flujo.punto(b.xs[p + 1], b.ys[p + 1])
                        flujo.punto(b.xs[p + 2], b.ys[p + 2])
                        ordenes++; p += 3
                    }
                    PlanoDePdf.CERRAR -> { flujo.op(PlanoDePdf.CERRAR); ordenes++ }
                }
                i++
            }
            primero = false
            t = t.siguiente
        }
        return ordenes
    }

    /**
     * El flujo de bytes: una orden y sus diferencias.
     *
     * Los números van en zigzag y de longitud variable —siete bits por byte, el octavo dice si
     * sigue—, que es lo mismo que hacen los formatos que mandan geometría por la red: los
     * valores pequeños, que son casi todos, ocupan un byte.
     */
    private class Flujo {
        private var b = ByteArray(1 shl 16)
        private var n = 0
        private var ux = 0
        private var uy = 0
        val tam get() = n

        fun op(o: Byte) {
            sitio(1)
            b[n++] = o
        }

        fun punto(x: Int, y: Int) {
            varint(x - ux)
            varint(y - uy)
            ux = x
            uy = y
        }

        private fun varint(v: Int) {
            var z = (v shl 1) xor (v shr 31)
            sitio(5)
            while (true) {
                if (z and 0x7F.inv() == 0) { b[n++] = z.toByte(); return }
                b[n++] = ((z and 0x7F) or 0x80).toByte()
                z = z ushr 7
            }
        }

        private fun sitio(cuantos: Int) {
            if (n + cuantos > b.size) b = b.copyOf(maxOf(b.size * 2, n + cuantos))
        }

        fun bytes(): ByteArray = b.copyOf(n)
    }

    /** Comprimido como un ZIP y en base64, que es lo que sabe leer [VisorPlano]. */
    internal fun comprimido(datos: ByteArray): String {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(datos)
        d.finish()
        val salida = java.io.ByteArrayOutputStream(maxOf(64, datos.size / 3))
        val buffer = ByteArray(64 * 1024)
        while (!d.finished()) {
            val leidos = d.deflate(buffer)
            if (leidos <= 0) break
            salida.write(buffer, 0, leidos)
        }
        d.end()
        return java.util.Base64.getEncoder().encodeToString(salida.toByteArray())
    }

    // ---------------------------------------------------------------------
    // Escribir JSON a mano
    // ---------------------------------------------------------------------

    private fun hex(color: Int): String = "#%06x".format(color and 0xFFFFFF)

    /**
     * Un número con los decimales justos.
     *
     * En un archivo con miles de brochas y de rótulos, escribir `0.9166666666666666` donde
     * basta `0.917` son kilobytes de nada.
     */
    internal fun numero(v: Double, decimales: Int = 3): String {
        if (!v.isFinite()) return "0"
        val f = when (decimales) { 9 -> 1e9; 4 -> 1e4; else -> 1e3 }
        val r = (v * f).roundToInt() / f
        if (abs(r - r.toLong()) < 1e-12) return r.toLong().toString()
        return r.toString().trimEnd('0').trimEnd('.')
    }

    /** Texto dentro de una cadena JSON que además va dentro de un `<script>` de un HTML. */
    internal fun texto(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                // `</script` dentro del JSON cerraría la etiqueta que lo envuelve.
                c == '<' -> append("\\u003c")
                c == '>' -> append("\\u003e")
                c == '&' -> append("\\u0026")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
