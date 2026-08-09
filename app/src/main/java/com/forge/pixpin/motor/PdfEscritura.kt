package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Escribir dentro de un PDF ajeno **sin tocar lo que ya tenía**.
 *
 * Es la otra mitad de [PdfLectura], y la que hace que anotar un PDF sea otra
 * cosa que sacarle una foto: al terminar, el archivo **sigue siendo el
 * original** —su texto se busca y se selecciona, sus enlaces funcionan, sus
 * fuentes son las suyas— y encima lleva lo que hayas dibujado, en vectores.
 *
 * ## La actualización incremental
 *
 * Un PDF admite crecer por el final. No se reescribe: se **añaden** los objetos
 * nuevos detrás de los viejos, se escribe un índice nuevo que dice dónde están,
 * y ese índice apunta al anterior con `/Prev`. Un lector empieza por el final,
 * encuentra el índice nuevo, y para todo lo que no esté ahí sigue la flecha
 * hacia atrás.
 *
 * ```
 *  ┌───────────────── el archivo original, byte por byte ─────────────────┐
 *  │ texto · imágenes · fuentes · páginas · índice viejo                  │
 *  └──────────────────────────────────────────────────────────────────────┘
 *  ┌── lo añadido ────────────────────────────────────────────────────────┐
 *  │ tu dibujo (m · l · c) · la página, otra vez · índice nuevo ──/Prev──►│
 *  └──────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * Un objeto que ya existía se **reemplaza** escribiéndolo otra vez con su mismo
 * número: el índice nuevo manda, así que la versión de antes queda ahí pero ya
 * no la mira nadie. Es como se firma digitalmente un PDF, y por eso funciona en
 * todas partes.
 *
 * ## Lo que hay que respetar
 *
 * - **Ni un byte del original se mueve.** Todo desplazamiento que había escrito
 *   por ahí dentro sigue apuntando donde apuntaba.
 * - **El índice nuevo va del mismo tipo que el viejo**: tabla clásica sobre
 *   tabla clásica, flujo comprimido sobre flujo comprimido. Ver
 *   [PdfArchivo.indiceEsFlujo].
 * - **Los cifrados no se tocan.** Habría que cifrar lo añadido con su misma
 *   clave, y equivocarse ahí no da un archivo raro: da un archivo que no abre.
 */

/** Un objeto que se añade o que reemplaza a otro con su mismo número. */
data class ObjetoPdf(val numero: Int, val valor: PdfValor)

object PdfEscritura {

    /**
     * El archivo con la revisión nueva pegada al final, o null si no se puede.
     *
     * Devuelve **el archivo entero**, no solo lo añadido: quien llame lo guarda
     * tal cual y ya está. Copiar unos megas de más es irrelevante al lado de la
     * posibilidad de dejar a alguien con medio PDF escrito.
     */
    fun incremental(archivo: PdfArchivo, nuevos: List<ObjetoPdf>): ByteArray? {
        if (archivo.cifrado) return null
        if (nuevos.isEmpty()) return null
        if (nuevos.any { it.numero <= 0 }) return null

        val salida = ByteArrayOutputStream(archivo.bytes.size + 64 * 1024)
        salida.write(archivo.bytes)
        // Si el original no acaba en salto de línea, el `2 0 obj` que viene
        // detrás se pegaría al `%%EOF` y ya no sería una palabra suelta.
        if (archivo.bytes.isNotEmpty() && archivo.bytes.last() != '\n'.code.toByte()) {
            salida.write('\n'.code)
        }

        val donde = LinkedHashMap<Int, Long>()
        for (o in nuevos.sortedBy { it.numero }) {
            donde[o.numero] = salida.size().toLong()
            salida.write("${o.numero} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            salida.write(serializar(o.valor))
            salida.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }

        val previo = ultimoStartxref(archivo.bytes) ?: return null
        val raiz = archivo.trailer.entradas["Root"] ?: return null
        val info = archivo.trailer.entradas["ID"]

        return runCatching {
            if (archivo.indiceEsFlujo) {
                escribirIndiceComprimido(salida, donde, previo, raiz, info, archivo)
            } else {
                escribirTablaClasica(salida, donde, previo, raiz, info, archivo)
            }
            salida.toByteArray()
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Los dos tipos de índice
    // ---------------------------------------------------------------------

    /** La tabla de toda la vida: `xref`, veinte bytes por entrada, y `trailer`. */
    private fun escribirTablaClasica(
        salida: ByteArrayOutputStream,
        donde: Map<Int, Long>,
        previo: Long,
        raiz: PdfValor,
        id: PdfValor?,
        archivo: PdfArchivo
    ) {
        val inicioIndice = salida.size().toLong()
        val texto = StringBuilder("xref\n")
        // Las entradas van por tramos de números consecutivos. Un objeto 5 y un
        // objeto 40 son dos tramos, no uno de treinta y seis con basura en medio.
        for ((primero, numeros) in tramos(donde.keys)) {
            texto.append("$primero ${numeros.size}\n")
            for (n in numeros) {
                // Veinte bytes clavados por entrada: diez de posición, cinco de
                // generación, la letra y dos de final. El formato lo exige y hay
                // lectores que se saltan la línea entera si sobra un carácter.
                texto.append(
                    String.format(Locale.ROOT, "%010d %05d n \n", donde.getValue(n), 0)
                )
            }
        }
        texto.append("trailer\n")
        salida.write(texto.toString().toByteArray(Charsets.ISO_8859_1))
        salida.write(
            serializar(
                PdfValor.Dicc(
                    buildMap {
                        put("Size", PdfValor.Numero(nuevoTamano(archivo, donde).toDouble()))
                        put("Root", raiz)
                        put("Prev", PdfValor.Numero(previo.toDouble()))
                        if (id != null) put("ID", id)
                        archivo.trailer.entradas["Info"]?.let { put("Info", it) }
                    }
                )
            )
        )
        salida.write("\nstartxref\n$inicioIndice\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * El índice comprimido de los PDF modernos: un objeto más, con las entradas
     * en binario.
     *
     * El propio índice es un objeto y tiene que constar en sí mismo, así que se
     * reserva un número para él y se apunta antes de escribirlo. Los anchos van
     * fijos a `[1 4 2]` —tipo, posición y generación— en vez de calcular el
     * mínimo: ahorra unos bytes en un archivo que ya pesa megas y se lee mucho
     * peor.
     */
    private fun escribirIndiceComprimido(
        salida: ByteArrayOutputStream,
        donde: MutableMap<Int, Long>,
        previo: Long,
        raiz: PdfValor,
        id: PdfValor?,
        archivo: PdfArchivo
    ) {
        val numeroDelIndice = (donde.keys.maxOrNull() ?: 0)
            .coerceAtLeast(nuevoTamano(archivo, donde) - 1) + 1
        val inicioIndice = salida.size().toLong()
        donde[numeroDelIndice] = inicioIndice

        val ordenados = donde.keys.sorted()
        val datos = ByteArrayOutputStream(ordenados.size * 7)
        val indices = mutableListOf<PdfValor>()
        for ((primero, numeros) in tramos(ordenados)) {
            indices.add(PdfValor.Numero(primero.toDouble()))
            indices.add(PdfValor.Numero(numeros.size.toDouble()))
            for (n in numeros) {
                val off = donde.getValue(n)
                datos.write(1)                                   // tipo: objeto suelto
                datos.write(((off ushr 24) and 0xFF).toInt())
                datos.write(((off ushr 16) and 0xFF).toInt())
                datos.write(((off ushr 8) and 0xFF).toInt())
                datos.write((off and 0xFF).toInt())
                datos.write(0)                                   // generación, dos bytes
                datos.write(0)
            }
        }

        val comprimido = desinflar(datos.toByteArray())
        val dicc = PdfValor.Dicc(
            buildMap {
                put("Type", PdfValor.Nombre("XRef"))
                put("Size", PdfValor.Numero((numeroDelIndice + 1).toDouble()))
                put("Index", PdfValor.Lista(indices))
                put(
                    "W",
                    PdfValor.Lista(
                        listOf(
                            PdfValor.Numero(1.0), PdfValor.Numero(4.0), PdfValor.Numero(2.0)
                        )
                    )
                )
                put("Root", raiz)
                put("Prev", PdfValor.Numero(previo.toDouble()))
                put("Filter", PdfValor.Nombre("FlateDecode"))
                if (id != null) put("ID", id)
                archivo.trailer.entradas["Info"]?.let { put("Info", it) }
            }
        )

        salida.write("$numeroDelIndice 0 obj\n".toByteArray(Charsets.ISO_8859_1))
        salida.write(serializar(PdfValor.Flujo(dicc, comprimido)))
        salida.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        salida.write("startxref\n$inicioIndice\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
    }

    /** El `/Size` nuevo: uno más que el número más alto que exista ya. */
    private fun nuevoTamano(archivo: PdfArchivo, donde: Map<Int, Long>): Int {
        val declarado = archivo.trailer.entero("Size") ?: 0
        val masAlto = maxOf(
            archivo.indice.keys.maxOrNull() ?: 0,
            donde.keys.maxOrNull() ?: 0
        )
        return maxOf(declarado, masAlto + 1)
    }

    /** El primer número libre para un objeto nuevo. */
    fun primerNumeroLibre(archivo: PdfArchivo): Int = maxOf(
        archivo.trailer.entero("Size") ?: 1,
        (archivo.indice.keys.maxOrNull() ?: 0) + 1
    )

    /** Agrupa números consecutivos: `[3,4,5,40]` da `3→[3,4,5]` y `40→[40]`. */
    private fun tramos(numeros: Collection<Int>): List<Pair<Int, List<Int>>> {
        val orden = numeros.sorted()
        val out = mutableListOf<Pair<Int, List<Int>>>()
        var actual = mutableListOf<Int>()
        for (n in orden) {
            if (actual.isEmpty() || n == actual.last() + 1) {
                actual.add(n)
            } else {
                out.add(actual.first() to actual.toList())
                actual = mutableListOf(n)
            }
        }
        if (actual.isNotEmpty()) out.add(actual.first() to actual.toList())
        return out
    }

    // ---------------------------------------------------------------------
    // De valor a bytes
    // ---------------------------------------------------------------------

    /** Un valor tal como se escribe dentro de un PDF. */
    fun serializar(valor: PdfValor): ByteArray {
        val salida = ByteArrayOutputStream(256)
        escribir(valor, salida)
        return salida.toByteArray()
    }

    private fun escribir(valor: PdfValor, salida: ByteArrayOutputStream) {
        when (valor) {
            is PdfValor.Numero -> salida.ascii(numero(valor.valor))
            is PdfValor.Nombre -> salida.ascii(nombre(valor.valor))
            is PdfValor.Booleano -> salida.ascii(if (valor.valor) "true" else "false")
            PdfValor.Nulo -> salida.ascii("null")
            is PdfValor.Ref -> salida.ascii("${valor.numero} ${valor.generacion} R")
            // En hexadecimal siempre: una cadena con paréntesis sin equilibrar o
            // con un byte suelto rompe el archivo, y en hexadecimal no hay nada
            // que escapar.
            is PdfValor.Cadena -> {
                salida.ascii("<")
                for (b in valor.bytes) salida.ascii("%02x".format(b.toInt() and 0xFF))
                salida.ascii(">")
            }
            is PdfValor.Lista -> {
                salida.ascii("[")
                for ((i, v) in valor.valores.withIndex()) {
                    if (i > 0) salida.ascii(" ")
                    escribir(v, salida)
                }
                salida.ascii("]")
            }
            is PdfValor.Dicc -> {
                salida.ascii("<<")
                for ((k, v) in valor.entradas) {
                    salida.ascii(nombre(k))
                    salida.ascii(" ")
                    escribir(v, salida)
                    salida.ascii(" ")
                }
                salida.ascii(">>")
            }
            is PdfValor.Flujo -> {
                // El `/Length` se recalcula siempre: el del diccionario que
                // venía puede ser el de antes de tocar los datos, y un `/Length`
                // que no cuadra es un archivo que no abre.
                val dicc = PdfValor.Dicc(
                    valor.dicc.entradas + ("Length" to PdfValor.Numero(valor.datos.size.toDouble()))
                )
                escribir(dicc, salida)
                salida.ascii("\nstream\n")
                salida.write(valor.datos)
                salida.ascii("\nendstream")
            }
        }
    }

    /**
     * Un número tal como lo acepta un PDF.
     *
     * Sin notación científica, que es el fallo silencioso de escribir números
     * pequeños: un `1.0E-5` es sintaxis válida de Java y **no** es un número de
     * PDF, así que el lector se pierde a mitad del archivo. Y con punto decimal
     * pase lo que pase con el idioma del móvil.
     */
    fun numero(v: Double): String {
        if (!v.isFinite()) return "0"
        val redondeado = (v * 1000.0).roundToLong() / 1000.0
        if (redondeado == floor(redondeado) && abs(redondeado) < 1e15) {
            return redondeado.toLong().toString()
        }
        return String.format(Locale.ROOT, "%.3f", redondeado).trimEnd('0').trimEnd('.')
    }

    /**
     * Un nombre: `/Type`, `/MediaBox`…
     *
     * Lo que no sea una letra o una cifra va escapado como `#xx`. Un `/` o un
     * espacio dentro de un nombre parte el diccionario en dos.
     */
    fun nombre(texto: String): String = buildString {
        append('/')
        for (c in texto) {
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '+') append(c)
            else append("#%02x".format(c.code and 0xFF))
        }
    }

    /** Comprime como el formato espera: `FlateDecode` es zlib del de siempre. */
    internal fun desinflar(datos: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(datos)
        deflater.finish()
        val salida = ByteArrayOutputStream(maxOf(64, datos.size / 2))
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n <= 0) break
            salida.write(buffer, 0, n)
        }
        deflater.end()
        return salida.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(texto: String) =
        write(texto.toByteArray(Charsets.ISO_8859_1))
}
