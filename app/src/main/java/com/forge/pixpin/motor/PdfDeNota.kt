package com.forge.pixpin.motor

import com.forge.pixpin.motormd.Alineacion
import com.forge.pixpin.motormd.InlineText
import com.forge.pixpin.motormd.Markdown
import com.forge.pixpin.motormd.MarkdownBlock
import com.forge.pixpin.motormd.Paginado
import com.forge.pixpin.motormd.SpanKind
import com.forge.pixpin.motormd.Tablas
import com.forge.pixpin.motormd.tramosDe

/**
 * Escribe una nota dentro de un PDF, **como texto de verdad**.
 *
 * ## Por qué texto y no un dibujo
 *
 * Lo que ya había escribe los dibujos en curvas, porque un trazo a mano alzada
 * no es otra cosa. Una nota sí lo es: son letras. Escribirlas como letras hace
 * que en el PDF final **se puedan seleccionar, copiar y buscar**, que es media
 * razón de entregar un documento en vez de una foto. Y de paso pesa una
 * fracción: una página de texto en curvas son miles de puntos, y en texto son
 * los caracteres.
 *
 * ## Las fuentes no se incrustan
 *
 * PDF trae catorce fuentes que cualquier lector tiene obligación de conocer, y
 * entre ellas están la Helvetica y la Courier. Usándolas no hay que meter ningún
 * archivo de fuente dentro del documento: el PDF sale pequeño y se abre igual en
 * cualquier sitio. A cambio no se puede usar una tipografía propia, que en una
 * nota de trabajo no hace ninguna falta.
 *
 * El texto va en `WinAnsiEncoding`, que cubre los acentos, las eñes, las comillas
 * y la viñeta. **Lo que se salga** —∑, π, ✓, las flechas, las griegas— **va con
 * una letra de reserva incrustada** que sí lo tenga ([LetrasDelPdf], 15-oct-2026,
 * como el PC el 14-oct), con su `ToUnicode` para que se copie y se busque; un
 * emoji, como imagen si quien exporta lo sabe pintar. Lo que no tiene ninguna
 * sale como el cuadrito del hueco, **nunca como un «?» callado**.
 */
object PdfDeNota {

    private const val A4_ANCHO = 595.0
    private const val A4_ALTO = 842.0
    private const val MARGEN = 56.0

    /** El tamaño del texto corriente. Lo demás se mide en proporción a esto. */
    private const val CUERPO = 11.0

    /** Cuánto baja de un renglón al siguiente. */
    private const val RENGLON = 1.45

    /**
     * Mete la nota al final del PDF, **una página del papel por página de la
     * nota**.
     *
     * Devuelve el PDF entero ya escrito, o null si no había nada que meter.
     */
    /**
     * Lo que un medio de la nota aporta al PDF: una imagen como JPEG con sus medidas, o
     * —para un audio, un PDF, cualquier archivo— una **etiqueta** («informe.pdf · PDF ·
     * 1,2 MB») que se pinta en un recuadro. Lo da quien exporta, que es quien puede leer
     * el archivo. Lo pidió el usuario (6-sep-2026): en el PDF de una nota no salían las
     * imágenes ni se decía qué archivos llevaba.
     */
    class MedioParaPdf(val jpeg: ByteArray?, val ancho: Int, val alto: Int, val etiqueta: String?)

    fun aniadir(
        original: ByteArray,
        texto: String,
        /** Las letras para lo que la Helvetica no tiene. Sin ellas, lo que falta sale como un cuadrito. */
        letras: LetrasDelPdf? = null,
        medio: (String) -> MedioParaPdf? = { null }
    ): ByteArray? = runCatching {
        val archivo = leerPdf(original) ?: return null
        if (archivo.cifrado) return null

        val paginas = Paginado.paginas(Markdown.parse(texto))
            .filter { it.isNotEmpty() }
        if (paginas.isEmpty()) return null

        var bytes = original
        paginas.forEach { bloques ->
            val actual = leerPdf(bytes) ?: return@forEach
            val hoja = Hoja(StringBuilder(), medio, PdfEscritura.primerNumeroLibre(actual), letras)
            bloques.forEach { hoja.bloque(it) }
            val fuentes = hoja.cerrarLetras()
            bytes = PdfAnotado.aniadirPagina(
                actual,
                hoja.salida.toString().toByteArray(Charsets.ISO_8859_1),
                A4_ANCHO,
                A4_ALTO,
                recursos = recursos(hoja.imagenes, fuentes),
                extra = hoja.extra
            ) ?: bytes
        }
        bytes
    }.getOrNull()

    /** Las catorce de siempre, sin incrustar nada, y las reservas que hicieron falta ([Hoja.cerrarLetras]). */
    private fun recursos(imagenes: Map<String, PdfValor.Ref> = emptyMap(), reservas: Map<String, PdfValor.Ref> = emptyMap()): PdfValor.Dicc = PdfValor.Dicc(
        linkedMapOf<String, PdfValor>(
            "Font" to PdfValor.Dicc(
                linkedMapOf<String, PdfValor>(
                    "F1" to fuente("Helvetica"),
                    "F2" to fuente("Helvetica-Bold"),
                    "F3" to fuente("Helvetica-Oblique"),
                    "F4" to fuente("Courier")
                ).also { it.putAll(reservas) }
            )
        ).also { if (imagenes.isNotEmpty()) it["XObject"] = PdfValor.Dicc(imagenes) }
    )

    private fun fuente(nombre: String): PdfValor.Dicc = PdfValor.Dicc(
        linkedMapOf(
            "Type" to PdfValor.Nombre("Font"),
            "Subtype" to PdfValor.Nombre("Type1"),
            "BaseFont" to PdfValor.Nombre(nombre),
            "Encoding" to PdfValor.Nombre("WinAnsiEncoding")
        )
    )

    /**
     * Una hoja a medio escribir: sabe por dónde va y va bajando.
     *
     * El papel mide de abajo arriba, así que escribir hacia abajo es restar. Se
     * lleva la cuenta en [y] y cada bloque la mueve lo suyo.
     */
    private class Hoja(
        val salida: StringBuilder,
        val medio: (String) -> MedioParaPdf? = { null },
        primerNumero: Int = 1,
        val letras: LetrasDelPdf? = null
    ) {
        var y = A4_ALTO - MARGEN
        /** Las imágenes de esta página, como objetos del PDF, y su nombre en los recursos. */
        val extra = ArrayList<ObjetoPdf>()
        val imagenes = LinkedHashMap<String, PdfValor.Ref>()
        private var siguiente = primerNumero

        fun bloque(b: MarkdownBlock) {
            when (b) {
                is MarkdownBlock.Heading -> {
                    val tam = when (b.level) {
                        1 -> CUERPO * 1.8
                        2 -> CUERPO * 1.5
                        3 -> CUERPO * 1.25
                        else -> CUERPO * 1.1
                    }
                    y -= tam * 0.6
                    renglones(b.content, tam, negrita = true)
                    y -= tam * 0.3
                }

                is MarkdownBlock.Paragraph -> renglones(b.content, CUERPO)

                is MarkdownBlock.Bullet -> conMarca("•  ", b.content)
                is MarkdownBlock.Numbered -> conMarca("${b.number}.  ", b.content)
                is MarkdownBlock.Tarea ->
                    conMarca(if (b.hecha) "[x]  " else "[ ]  ", b.content)

                // La cita lleva su barra a la izquierda y el texto metido.
                is MarkdownBlock.Quote -> {
                    val desde = y
                    renglones(b.content, CUERPO, sangria = 14.0)
                    linea(MARGEN + 2, desde + CUERPO * 0.8, MARGEN + 2, y + CUERPO * 0.4, 2.0)
                }

                is MarkdownBlock.Code -> {
                    b.text.split('\n').forEach { linea ->
                        escribir(linea, MARGEN + 6, CUERPO * 0.95, "F4")
                        y -= CUERPO * RENGLON
                    }
                    y -= CUERPO * 0.4
                }

                MarkdownBlock.Rule -> {
                    y -= CUERPO * 0.6
                    linea(MARGEN, y, A4_ANCHO - MARGEN, y, 0.6)
                    y -= CUERPO * 0.8
                }

                // La fórmula, tal como se escribió y centrada. Componerla aquí
                // exigiría repetir el compositor de la pantalla en operadores de
                // PDF; enseñarla entera y centrada dice lo mismo y no se
                // desincroniza con el otro.
                is MarkdownBlock.Formula -> {
                    y -= CUERPO * 0.4
                    val ancho = anchoDe(b.latex, CUERPO * 1.05)
                    escribir(b.latex, (A4_ANCHO - ancho) / 2, CUERPO * 1.05, "F3")
                    y -= CUERPO * RENGLON * 1.4
                }

                is MarkdownBlock.Tabla -> tabla(b)

                // **Una imagen se ve; lo demás se representa.** El JPEG entra como
                // XObject a lo ancho de la caja; un audio o un archivo, como un recuadro
                // con su nombre, su tipo y su peso — el archivo en sí no tiene sitio en un
                // PDF, pero sí que se sepa que estaba.
                is MarkdownBlock.Medio -> {
                    val m = medio(b.ruta)
                    val jpeg = m?.jpeg
                    if (jpeg != null && m.ancho > 0 && m.alto > 0) {
                        val anchoCaja = A4_ANCHO - 2 * MARGEN
                        var w = anchoCaja
                        var h = w * m.alto / m.ancho
                        val altoMax = minOf(360.0, (y - MARGEN).coerceAtLeast(80.0))
                        if (h > altoMax) { h = altoMax; w = h * m.ancho / m.alto }
                        val nombre = imagen(jpeg, m.ancho, m.alto)
                        y -= h + CUERPO * 0.4
                        val x = MARGEN + (anchoCaja - w) / 2
                        salida.append("q ").append(PdfEscritura.numero(w)).append(" 0 0 ").append(PdfEscritura.numero(h))
                            .append(' ').append(PdfEscritura.numero(x)).append(' ').append(PdfEscritura.numero(y))
                            .append(" cm /").append(nombre).append(" Do Q\n")
                        if (b.alt.isNotBlank() && b.alt.lowercase() !in setOf("imagen", "image", "foto")) {
                            escribir(b.alt, MARGEN, CUERPO * 0.9, "F3")
                            y -= CUERPO * RENGLON
                        }
                    } else {
                        val etiqueta = m?.etiqueta ?: b.alt.ifBlank { b.ruta.substringAfterLast('/') }
                        val alto = CUERPO * 2.6
                        y -= alto
                        recuadro(MARGEN, y, A4_ANCHO - 2 * MARGEN, alto)
                        escribir(etiqueta, MARGEN + 10, CUERPO, "F2", base = y + alto / 2 - CUERPO * 0.35)
                        y -= CUERPO * 0.4
                    }
                }

                is MarkdownBlock.Caja -> {
                    b.titulo.takeIf { it.isNotEmpty() }?.let {
                        escribir(it, MARGEN, CUERPO, "F2")
                        y -= CUERPO * RENGLON
                    }
                    b.dentro.forEach { bloque(it) }
                }
            }
            y -= CUERPO * 0.35
        }

        /** Una imagen JPEG como objeto de la página; devuelve su nombre en los recursos. Las iguales, una vez. */
        private fun imagen(jpeg: ByteArray, ancho: Int, alto: Int, clave: String? = null): String {
            clave?.let { k -> porClave[k]?.let { return it } }
            val numero = siguiente++
            val nombre = "Im${imagenes.size + 1}"
            extra += ObjetoPdf(
                numero,
                PdfValor.Flujo(
                    PdfValor.Dicc(
                        linkedMapOf(
                            "Type" to PdfValor.Nombre("XObject"),
                            "Subtype" to PdfValor.Nombre("Image"),
                            "Width" to PdfValor.Numero(ancho.toDouble()),
                            "Height" to PdfValor.Numero(alto.toDouble()),
                            "ColorSpace" to PdfValor.Nombre("DeviceRGB"),
                            "BitsPerComponent" to PdfValor.Numero(8.0),
                            "Filter" to PdfValor.Nombre("DCTDecode")
                        )
                    ),
                    jpeg
                )
            )
            imagenes[nombre] = PdfValor.Ref(numero, 0)
            clave?.let { porClave[it] = nombre }
            return nombre
        }

        private val porClave = HashMap<String, String>()

        /** Una marca a la izquierda —viñeta, número, casilla— y el texto al lado. */
        private fun conMarca(marca: String, contenido: InlineText) {
            escribir(marca, MARGEN, CUERPO, "F1")
            renglones(contenido, CUERPO, sangria = anchoDe(marca, CUERPO))
        }

        /**
         * Escribe un texto doblándolo por el ancho de la página.
         *
         * La negrita y la cursiva salen del **primer tramo** de cada línea y no
         * carácter a carácter: cambiar de fuente a mitad de línea obliga a
         * medir y a colocar cada trozo por separado, y en una nota el caso
         * corriente es una frase entera en negrita, no una palabra suelta.
         */
        fun renglones(
            contenido: InlineText,
            tam: Double,
            negrita: Boolean = false,
            sangria: Double = 0.0
        ) {
            val disponible = A4_ANCHO - MARGEN * 2 - sangria
            val tramos = tramosDe(contenido.spans, contenido.text.length)
            val gordo = negrita || tramos.any { it.tiene(SpanKind.BOLD) }
            val tumbado = tramos.any { it.tiene(SpanKind.ITALIC) }
            val fuente = when {
                gordo -> "F2"
                tumbado -> "F3"
                tramos.any { it.tiene(SpanKind.CODE) } -> "F4"
                else -> "F1"
            }

            partirEnLineas(contenido.text, tam, disponible).forEach { linea ->
                escribir(linea, MARGEN + sangria, tam, fuente)
                y -= tam * RENGLON
            }
        }

        /** Parte un texto en líneas que quepan, sin cortar palabras por la mitad. */
        private fun partirEnLineas(texto: String, tam: Double, ancho: Double): List<String> {
            if (texto.isEmpty()) return listOf("")
            val lineas = mutableListOf<String>()
            texto.split('\n').forEach { parrafo ->
                var actual = StringBuilder()
                parrafo.split(' ').forEach { palabra ->
                    val prueba = if (actual.isEmpty()) palabra else "$actual $palabra"
                    if (anchoDe(prueba, tam) > ancho && actual.isNotEmpty()) {
                        lineas += actual.toString()
                        actual = StringBuilder(palabra)
                    } else {
                        actual = StringBuilder(prueba)
                    }
                }
                lineas += actual.toString()
            }
            return lineas
        }

        private fun tabla(t: MarkdownBlock.Tabla) {
            val rejilla = Tablas.rejilla(t)
            val columnas = t.columnas.coerceAtLeast(1)
            val ancho = (A4_ANCHO - MARGEN * 2) / columnas
            val alto = CUERPO * 1.9

            if (t.titulo.text.isNotEmpty()) {
                val w = anchoDe(t.titulo.text, CUERPO)
                escribir(t.titulo.text, (A4_ANCHO - w) / 2, CUERPO, "F2")
                y -= CUERPO * RENGLON
            }

            val arriba = y
            rejilla.forEachIndexed { f, fila ->
                val cima = arriba - f * alto
                fila.forEachIndexed { c, hueco ->
                    if (hueco == null || !hueco.esElAncla) return@forEachIndexed
                    val celda = hueco.celda
                    val x = MARGEN + c * ancho
                    val suyo = ancho * celda.anchoEnColumnas
                    val texto = celda.contenido.text
                    val w = anchoDe(texto, CUERPO * 0.9)
                    val dentro = when (celda.alineacion) {
                        Alineacion.CENTRO -> x + (suyo - w) / 2
                        Alineacion.DERECHA -> x + suyo - w - 4
                        else -> x + 4
                    }
                    escribir(
                        texto, dentro, CUERPO * 0.9,
                        if (celda.cabecera) "F2" else "F1",
                        base = cima - alto * 0.65
                    )
                }
                // La raya de debajo de cada fila.
                linea(MARGEN, cima - alto, MARGEN + ancho * columnas, cima - alto, 0.5)
            }
            // El marco y las rayas de las columnas.
            val abajo = arriba - rejilla.size * alto
            linea(MARGEN, arriba, MARGEN + ancho * columnas, arriba, 0.5)
            for (c in 0..columnas) {
                val x = MARGEN + c * ancho
                linea(x, arriba, x, abajo, 0.5)
            }
            y = abajo - CUERPO * 0.6
        }

        /**
         * Un texto en su sitio, con su fuente y su tamaño. Lo que la Helvetica no tiene va con su
         * reserva en el mismo bloque (el lector avanza solo); un emoji pintado o el cuadrito del
         * hueco cortan el bloque y se ponen donde va la cuenta de los anchos ([piezasDe]).
         */
        private fun escribir(
            texto: String,
            x: Double,
            tam: Double,
            fuente: String,
            base: Double = y
        ) {
            if (texto.isEmpty()) return
            var cx = x
            var abierto = false
            var puesta: String? = null
            fun cerrar() { if (abierto) { salida.append("ET\n"); abierto = false } }
            for (p in piezasDe(texto, fuente, apuntar = true)) {
                when (p) {
                    is Pieza.Texto -> {
                        if (!abierto) {
                            salida.append("BT ").append(PdfEscritura.numero(cx)).append(' ').append(PdfEscritura.numero(base)).append(" Td ")
                            abierto = true; puesta = null
                        }
                        if (puesta != p.fuente) { salida.append('/').append(p.fuente).append(' ').append(PdfEscritura.numero(tam)).append(" Tf "); puesta = p.fuente }
                        salida.append(p.cadena).append(" Tj ")
                    }
                    is Pieza.Dibujo -> {
                        cerrar()
                        val m = p.medio
                        val alto = tam * 0.95
                        val ancho = alto * m.ancho / m.alto.coerceAtLeast(1)
                        val nombre = imagen(m.jpeg!!, m.ancho, m.alto, clave = "dibujo:" + p.texto)
                        salida.append("q ").append(PdfEscritura.numero(ancho)).append(" 0 0 ").append(PdfEscritura.numero(alto))
                            .append(' ').append(PdfEscritura.numero(cx)).append(' ').append(PdfEscritura.numero(base - tam * 0.18))
                            .append(" cm /").append(nombre).append(" Do Q\n")
                    }
                    is Pieza.Hueco -> {
                        cerrar()
                        recuadro(cx + tam * 0.08, base, tam * 0.45, tam * 0.7)
                    }
                }
                cx += p.ancho * tam / 1000.0
            }
            cerrar()
        }

        // ------------------------------------------------------ las letras

        /** Los glifos de cada reserva usados en la página, con el carácter que dibujan (para el `ToUnicode`). */
        private val usadas = java.util.TreeMap<Int, java.util.TreeMap<Int, String>>()

        /** Un trozo del texto: con una letra, pintado, o el cuadrito. [ancho] en milésimas del cuerpo. */
        private sealed class Pieza(val ancho: Double) {
            class Texto(val fuente: String, val cadena: String, ancho: Double) : Pieza(ancho)
            class Dibujo(val texto: String, val medio: MedioParaPdf, ancho: Double) : Pieza(ancho)
            class Hueco : Pieza(600.0)
        }

        /**
         * **Con qué se escribe cada carácter** de [texto] en [fuente]: los de WinAnsi en la Helvetica
         * (o la Courier), los demás con la primera reserva que los tenga, y lo que no tiene ninguna,
         * pintado o como el hueco. Los seguidos con la misma letra van juntos. Con [apuntar] se
         * anotan los glifos usados (solo al escribir: medir no cuenta).
         */
        private fun piezasDe(texto: String, fuente: String, apuntar: Boolean = false): List<Pieza> {
            val salida = ArrayList<Pieza>()
            var actual: String? = null
            val cadena = StringBuilder()
            var ancho = 0.0
            fun soltar() {
                val f = actual ?: return
                salida += Pieza.Texto(f, if (f.startsWith("R")) "<$cadena>" else "($cadena)", ancho)
                actual = null; cadena.setLength(0); ancho = 0.0
            }
            fun reserva(i: Int, g: Int, letra: String) {
                val f = "R$i"
                if (actual != f) { soltar(); actual = f }
                cadena.append("%04X".format(g))
                ancho += letras?.letra(i)?.ancho(g)?.toDouble() ?: 600.0
                if (apuntar) usadas.getOrPut(i) { java.util.TreeMap() }.putIfAbsent(g, letra)
            }
            val cps = texto.codePoints().toArray()
            var k = 0
            while (k < cps.size) {
                val cp = cps[k]
                val ch = if (cp == '\t'.code) ' '.code else cp
                val byte = winAnsi(ch)
                when {
                    byte != null -> {
                        if (actual != fuente) { soltar(); actual = fuente }
                        when (byte.toChar()) {
                            '(', ')', '\\' -> cadena.append('\\').append(byte.toChar())
                            else -> cadena.append(byte.toChar())
                        }
                        ancho += anchoWinAnsi(byte, fuente)
                    }
                    invisible(ch) -> {}
                    else -> {
                        val donde = letras?.buscar(ch)
                        if (donde != null) reserva(donde.first, donde.second, String(Character.toChars(ch)))
                        else {
                            // Un emoji y lo que lo sigue (tono de piel, unión, variante) van juntos.
                            var fin = k + 1
                            while (fin < cps.size && (cps[fin] == 0x200D || cps[fin] in 0xFE00..0xFE0F || cps[fin] in 0x1F3FB..0x1F3FF ||
                                    cps[fin] in 0xE0020..0xE007F || (cps[fin - 1] == 0x200D))) fin++
                            val trozo = String(cps, k, fin - k)
                            val pintado = letras?.pintado(trozo)?.takeIf { it.jpeg != null && it.ancho > 0 && it.alto > 0 }
                            if (pintado != null) {
                                soltar()
                                salida += Pieza.Dibujo(trozo, pintado, 950.0 * pintado.ancho / pintado.alto)
                                k = fin
                                continue
                            }
                            // El hueco de una reserva (se copia como U+FFFD); sin ninguna, un cuadrito dibujado.
                            val i = letras?.paraElHueco()
                            if (i != null) reserva(i, 0, "\uFFFD") else { soltar(); salida += Pieza.Hueco() }
                        }
                    }
                }
                k++
            }
            soltar()
            return salida
        }

        /** Lo que mide [texto] en [fuente] a [tam], con los anchos de verdad de cada letra. */
        fun anchoDe(texto: String, tam: Double, fuente: String = "F1"): Double =
            piezasDe(texto, fuente).sumOf { it.ancho } * tam / 1000.0

        /**
         * **Las reservas usadas, como objetos del PDF**: cada una incrustada con solo sus glifos
         * (`Type0` → `CIDFontType2`, `Identity-H`), sus anchos y su `ToUnicode`. Devuelve su nombre
         * en los recursos (`R0`, `R1`…). Se llama al acabar la página.
         */
        fun cerrarLetras(): Map<String, PdfValor.Ref> {
            val salida = LinkedHashMap<String, PdfValor.Ref>()
            for ((i, glifos) in usadas) {
                val l = letras?.letra(i) ?: continue
                val datos = runCatching { l.subconjunto(glifos.keys) }.getOrNull() ?: continue
                val nombre = "PX" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[i % 26] + "ABCD"[(i / 26) % 4] + "NT+" + l.nombre
                val archivo = siguiente++; val descripcion = siguiente++; val cid = siguiente++; val unicode = siguiente++; val tipo0 = siguiente++
                fun mil(v: Int) = PdfValor.Numero(Math.round(v * 1000.0 / l.unidades).toDouble())
                extra += ObjetoPdf(archivo, PdfValor.Flujo(
                    PdfValor.Dicc(linkedMapOf("Filter" to PdfValor.Nombre("FlateDecode"), "Length1" to PdfValor.Numero(datos.size.toDouble()))),
                    PdfEscritura.desinflar(datos)
                ))
                extra += ObjetoPdf(descripcion, PdfValor.Dicc(linkedMapOf(
                    "Type" to PdfValor.Nombre("FontDescriptor"),
                    "FontName" to PdfValor.Nombre(nombre),
                    "Flags" to PdfValor.Numero(4.0),
                    "FontBBox" to PdfValor.Lista(l.caja.map { mil(it) }),
                    "ItalicAngle" to PdfValor.Numero(0.0),
                    "Ascent" to mil(l.ascenso),
                    "Descent" to mil(l.descenso),
                    "CapHeight" to mil(l.ascenso),
                    "StemV" to PdfValor.Numero(80.0),
                    "FontFile2" to PdfValor.Ref(archivo, 0)
                )))
                val anchos = ArrayList<PdfValor>()
                for (g in glifos.keys) { anchos += PdfValor.Numero(g.toDouble()); anchos += PdfValor.Lista(listOf(PdfValor.Numero(l.ancho(g).toDouble()))) }
                extra += ObjetoPdf(cid, PdfValor.Dicc(linkedMapOf(
                    "Type" to PdfValor.Nombre("Font"),
                    "Subtype" to PdfValor.Nombre("CIDFontType2"),
                    "BaseFont" to PdfValor.Nombre(nombre),
                    "CIDSystemInfo" to PdfValor.Dicc(linkedMapOf(
                        "Registry" to PdfValor.Cadena("Adobe".toByteArray()),
                        "Ordering" to PdfValor.Cadena("Identity".toByteArray()),
                        "Supplement" to PdfValor.Numero(0.0)
                    )),
                    "FontDescriptor" to PdfValor.Ref(descripcion, 0),
                    "CIDToGIDMap" to PdfValor.Nombre("Identity"),
                    "DW" to PdfValor.Numero(1000.0),
                    "W" to PdfValor.Lista(anchos)
                )))
                extra += ObjetoPdf(unicode, PdfAnotado.flujoComprimido(aUnicode(glifos).toByteArray(Charsets.ISO_8859_1)))
                extra += ObjetoPdf(tipo0, PdfValor.Dicc(linkedMapOf(
                    "Type" to PdfValor.Nombre("Font"),
                    "Subtype" to PdfValor.Nombre("Type0"),
                    "BaseFont" to PdfValor.Nombre(nombre),
                    "Encoding" to PdfValor.Nombre("Identity-H"),
                    "DescendantFonts" to PdfValor.Lista(listOf(PdfValor.Ref(cid, 0))),
                    "ToUnicode" to PdfValor.Ref(unicode, 0)
                )))
                salida["R$i"] = PdfValor.Ref(tipo0, 0)
            }
            return salida
        }

        /** Un rectángulo de borde fino, para representar un archivo. */
        private fun recuadro(x: Double, y: Double, w: Double, h: Double) {
            salida.append("q 0.6 w 0.55 G ")
                .append(PdfEscritura.numero(x)).append(' ').append(PdfEscritura.numero(y)).append(' ')
                .append(PdfEscritura.numero(w)).append(' ').append(PdfEscritura.numero(h))
                .append(" re S Q\n")
        }

        private fun linea(x1: Double, y1: Double, x2: Double, y2: Double, grosor: Double) {
            salida.append(PdfEscritura.numero(grosor)).append(" w ")
                .append(PdfEscritura.numero(x1)).append(' ')
                .append(PdfEscritura.numero(y1)).append(" m ")
                .append(PdfEscritura.numero(x2)).append(' ')
                .append(PdfEscritura.numero(y2)).append(" l S\n")
        }
    }


    /** El byte de [cp] en WinAnsi (la Helvetica sin incrustar), si lo tiene. */
    private fun winAnsi(cp: Int): Int? = when (cp) {
        in 0x20..0x7E, in 0xA0..0xFF -> cp
        else -> WIN_ANSI[cp]
    }

    /** Lo de 0x80 a 0x9F de WinAnsi: el euro, las comillas, la viñeta, las rayas… */
    private val WIN_ANSI: Map<Int, Int> = "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ".withIndex()
        .filter { it.value != '\u0000' }.associate { it.value.code to 0x80 + it.index }

    /** Lo que no se pinta si ninguna letra lo tiene: variantes, uniones de anchura cero, etiquetas, controles. */
    private fun invisible(cp: Int): Boolean =
        cp in 0x200B..0x200F || cp in 0x2060..0x2064 || cp in 0xFE00..0xFE0F || cp == 0xFEFF ||
            cp in 0xE0000..0xE007F || cp in 0xE0100..0xE01EF || cp < 0x20 || cp in 0x7F..0x9F

    /**
     * El ancho de un byte de WinAnsi en milésimas: los de la Helvetica (los de Liberation Sans, que
     * mide igual que ella), en negrita los suyos, y la Courier, 600 todos.
     */
    private fun anchoWinAnsi(b: Int, fuente: String): Double = when (fuente) {
        "F4" -> 600.0
        "F2" -> NEGRITA.getOrElse(b - 32) { 556 }.toDouble()
        else -> NORMAL.getOrElse(b - 32) { 556 }.toDouble()
    }

    private val NORMAL = intArrayOf(
        278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,
        1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,
        333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584,0,
        556,0,222,556,333,1000,556,556,333,1000,667,333,1000,0,611,0,0,222,222,333,333,350,556,1000,333,1000,500,333,944,0,500,667,
        278,333,556,556,556,556,260,556,333,737,370,556,584,333,737,552,400,549,333,333,333,576,537,333,333,333,365,556,834,834,834,611,
        667,667,667,667,667,667,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,
        556,556,556,556,556,556,889,500,556,556,556,556,278,278,278,278,556,556,556,556,556,556,556,549,611,556,556,556,556,500,556,500
    )
    private val NEGRITA = intArrayOf(
        278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,
        975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,
        333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,389,584,0,
        556,0,278,556,500,1000,556,556,333,1000,667,333,1000,0,611,0,0,278,278,500,500,350,556,1000,333,1000,556,333,944,0,500,667,
        278,333,556,556,556,556,280,556,333,737,370,556,584,333,737,552,400,549,333,333,333,576,556,333,333,333,365,556,834,834,834,611,
        722,722,722,722,722,722,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,
        556,556,556,556,556,556,889,556,556,556,556,556,278,278,278,278,611,611,611,611,611,611,611,549,611,611,611,611,611,556,611,556
    )

    /** El `ToUnicode` de una reserva: de cada glifo al carácter que dibuja, para copiar y buscar. */
    private fun aUnicode(glifos: Map<Int, String>): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        for (trozo in glifos.entries.chunked(100)) {
            append(trozo.size).append(" beginbfchar\n")
            for ((g, letra) in trozo) {
                append('<').append("%04X".format(g)).append("> <")
                letra.forEach { append("%04X".format(it.code)) }
                append(">\n")
            }
            append("endbfchar\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }
}
