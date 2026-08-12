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
 * El texto va en Latin-1, que es lo que entiende `WinAnsiEncoding`. Cubre los
 * acentos y las eñes; lo que se salga —un emoji, un símbolo raro— se cambia por
 * un interrogante en vez de romper el archivo.
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
    fun aniadir(original: ByteArray, texto: String): ByteArray? = runCatching {
        val archivo = leerPdf(original) ?: return null
        if (archivo.cifrado) return null

        val paginas = Paginado.paginas(Markdown.parse(texto))
            .filter { it.isNotEmpty() }
        if (paginas.isEmpty()) return null

        var bytes = original
        paginas.forEach { bloques ->
            val actual = leerPdf(bytes) ?: return@forEach
            val hoja = Hoja(StringBuilder())
            bloques.forEach { hoja.bloque(it) }
            bytes = PdfAnotado.aniadirPagina(
                actual,
                hoja.salida.toString().toByteArray(Charsets.ISO_8859_1),
                A4_ANCHO,
                A4_ALTO,
                recursos = recursos()
            ) ?: bytes
        }
        bytes
    }.getOrNull()

    /** Las catorce de siempre, sin incrustar nada. */
    private fun recursos(): PdfValor.Dicc = PdfValor.Dicc(
        linkedMapOf(
            "Font" to PdfValor.Dicc(
                linkedMapOf(
                    "F1" to fuente("Helvetica"),
                    "F2" to fuente("Helvetica-Bold"),
                    "F3" to fuente("Helvetica-Oblique"),
                    "F4" to fuente("Courier")
                )
            )
        )
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
    private class Hoja(val salida: StringBuilder) {
        var y = A4_ALTO - MARGEN

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

                // Un medio no se puede meter aquí sin cargar el archivo; queda
                // su nombre, que al menos dice qué falta.
                is MarkdownBlock.Medio -> {
                    escribir("[${b.alt.ifBlank { b.ruta.substringAfterLast('/') }}]",
                        MARGEN, CUERPO, "F3")
                    y -= CUERPO * RENGLON
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

        /** Un texto en su sitio, con su fuente y su tamaño. */
        private fun escribir(
            texto: String,
            x: Double,
            tam: Double,
            fuente: String,
            base: Double = y
        ) {
            if (texto.isEmpty()) return
            salida.append("BT /").append(fuente).append(' ')
                .append(PdfEscritura.numero(tam)).append(" Tf ")
                .append(PdfEscritura.numero(x)).append(' ')
                .append(PdfEscritura.numero(base)).append(" Td ")
                .append(cadena(texto)).append(" Tj ET\n")
        }

        private fun linea(x1: Double, y1: Double, x2: Double, y2: Double, grosor: Double) {
            salida.append(PdfEscritura.numero(grosor)).append(" w ")
                .append(PdfEscritura.numero(x1)).append(' ')
                .append(PdfEscritura.numero(y1)).append(" m ")
                .append(PdfEscritura.numero(x2)).append(' ')
                .append(PdfEscritura.numero(y2)).append(" l S\n")
        }
    }

    /**
     * Cuánto mide un texto, más o menos.
     *
     * Las anchuras de verdad de la Helvetica están en unas tablas que habría que
     * traerse enteras; aquí basta con una media razonable para decidir dónde
     * doblar una línea. Si se pasa por poco, la línea sale algo corta, que es el
     * fallo que no se nota.
     */
    private fun anchoDe(texto: String, tam: Double): Double = texto.length * tam * 0.5

    /**
     * Una cadena de PDF, escapada y en Latin-1.
     *
     * Los paréntesis y la barra invertida se escapan porque delimitan la cadena;
     * lo que no cabe en Latin-1 se cambia por un interrogante, que es mejor que
     * escribir un byte que el lector no sepa leer.
     */
    private fun cadena(texto: String): String {
        val salida = StringBuilder("(")
        texto.forEach { c ->
            when {
                c == '(' || c == ')' || c == '\\' -> salida.append('\\').append(c)
                c.code in 32..255 -> salida.append(c)
                else -> salida.append('?')
            }
        }
        return salida.append(')').toString()
    }
}
