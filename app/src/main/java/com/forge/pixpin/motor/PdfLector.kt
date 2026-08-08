package com.forge.pixpin.motor

/**
 * El analizador de la sintaxis de un PDF.
 *
 * Un PDF es, por dentro, un lenguaje diminuto: números, nombres (`/Type`),
 * cadenas, listas, diccionarios (`<< >>`), referencias a otros objetos (`12 0
 * R`) y flujos de datos. Esto lo lee y nada más — no interpreta qué significa
 * ninguno de ellos, de eso va [PdfArchivo].
 *
 * Va sobre un `ByteArray` y no sobre texto **a propósito**. Un PDF no es un
 * archivo de texto: entre sus objetos hay imágenes, fuentes y flujos
 * comprimidos, y pasarlo por un decodificador de caracteres estropearía esos
 * bytes en silencio. Todo lo que se lee aquí son bytes hasta el final.
 */
internal class PdfLector(private val bytes: ByteArray, var pos: Int = 0) {

    fun siguienteByte(): Byte? = if (pos < bytes.size) bytes[pos++] else null

    fun saltarBlancos() {
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            when {
                esBlanco(b) -> pos++
                // Un comentario llega hasta el final de la línea, y puede
                // aparecer entre dos entradas de un diccionario.
                b == '%'.code -> {
                    while (pos < bytes.size &&
                        bytes[pos].toInt() != '\n'.code && bytes[pos].toInt() != '\r'.code
                    ) pos++
                }
                else -> return
            }
        }
    }

    /** Consume [texto] si está justo aquí, saltando blancos antes. */
    fun palabra(texto: String): Boolean {
        saltarBlancos()
        val que = texto.toByteArray()
        if (!coincide(bytes, pos, que)) return false
        pos += que.size
        return true
    }

    fun leerEntero(): Int? {
        saltarBlancos()
        val inicio = pos
        if (pos < bytes.size && (bytes[pos] == '+'.code.toByte() || bytes[pos] == '-'.code.toByte())) {
            pos++
        }
        var digitos = 0
        while (pos < bytes.size && bytes[pos] >= '0'.code.toByte() && bytes[pos] <= '9'.code.toByte()) {
            pos++
            digitos++
        }
        if (digitos == 0) {
            pos = inicio
            return null
        }
        return String(bytes, inicio, pos - inicio, Charsets.US_ASCII).toIntOrNull()
    }

    /**
     * Lee un valor completo.
     *
     * [archivo] hace falta **solo para los flujos**: su longitud puede venir
     * como una referencia a otro objeto, y para resolverla hay que poder ir a
     * buscarlo. Con null se busca el final del flujo a ojo, que es lo que toca
     * mientras se está leyendo el propio índice y todavía no hay archivo.
     */
    fun leerValor(archivo: PdfArchivo?): PdfValor? {
        saltarBlancos()
        if (pos >= bytes.size) return null
        val b = bytes[pos].toInt() and 0xFF

        return when {
            b == '<'.code && pos + 1 < bytes.size && bytes[pos + 1].toInt() == '<'.code ->
                leerDiccOFlujo(archivo)
            b == '<'.code -> leerCadenaHex()
            b == '('.code -> leerCadena()
            b == '['.code -> leerLista(archivo)
            b == '/'.code -> leerNombre()
            b == ']'.code || b == '>'.code || b == '}'.code -> null
            palabra("true") -> PdfValor.Booleano(true)
            palabra("false") -> PdfValor.Booleano(false)
            palabra("null") -> PdfValor.Nulo
            else -> leerNumeroOReferencia()
        }
    }

    private fun leerNombre(): PdfValor.Nombre {
        pos++ // la barra
        val sb = StringBuilder()
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            if (esBlanco(b) || esDelimitador(b)) break
            pos++
            // `#41` es una «A»: así se escriben en un nombre los caracteres que
            // de otro modo cortarían la palabra.
            if (b == '#'.code && pos + 1 < bytes.size) {
                val hex = String(bytes, pos, 2, Charsets.US_ASCII).toIntOrNull(16)
                if (hex != null) {
                    sb.append(hex.toChar())
                    pos += 2
                    continue
                }
            }
            sb.append(b.toChar())
        }
        return PdfValor.Nombre(sb.toString())
    }

    private fun leerNumeroOReferencia(): PdfValor? {
        val inicio = pos
        val entero = leerEnteroCrudo()
        if (entero == null) {
            // No es un número: se salta el token para no quedarse en bucle.
            pos = inicio
            saltarToken()
            return if (pos > inicio) PdfValor.Nulo else null
        }
        // `12 0 R` es una referencia; `12 0` a secas son dos números.
        val guardado = pos
        val generacion = leerEntero()
        if (generacion != null && palabra("R")) {
            return PdfValor.Ref(entero.toInt(), generacion)
        }
        pos = guardado
        return PdfValor.Numero(entero)
    }

    private fun leerEnteroCrudo(): Double? {
        saltarBlancos()
        val inicio = pos
        if (pos < bytes.size && (bytes[pos] == '+'.code.toByte() || bytes[pos] == '-'.code.toByte())) {
            pos++
        }
        var digitos = 0
        var punto = false
        while (pos < bytes.size) {
            val b = bytes[pos]
            when {
                b >= '0'.code.toByte() && b <= '9'.code.toByte() -> { pos++; digitos++ }
                b == '.'.code.toByte() && !punto -> { pos++; punto = true }
                else -> break
            }
        }
        if (digitos == 0) {
            pos = inicio
            return null
        }
        return String(bytes, inicio, pos - inicio, Charsets.US_ASCII).toDoubleOrNull()
    }

    private fun saltarToken() {
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            if (esBlanco(b) || esDelimitador(b)) break
            pos++
        }
        if (pos < bytes.size && esDelimitador(bytes[pos].toInt() and 0xFF)) pos++
    }

    private fun leerLista(archivo: PdfArchivo?): PdfValor.Lista {
        pos++ // el corchete
        val out = mutableListOf<PdfValor>()
        while (pos < bytes.size) {
            saltarBlancos()
            if (pos < bytes.size && bytes[pos].toInt() == ']'.code) { pos++; break }
            out += leerValor(archivo) ?: break
            if (out.size > MAX_ELEMENTOS) break
        }
        return PdfValor.Lista(out)
    }

    private fun leerDiccOFlujo(archivo: PdfArchivo?): PdfValor? {
        pos += 2 // los dos menores
        val entradas = LinkedHashMap<String, PdfValor>()
        while (pos < bytes.size) {
            saltarBlancos()
            if (coincide(bytes, pos, ">>".toByteArray())) { pos += 2; break }
            val clave = (leerValor(archivo) as? PdfValor.Nombre)?.valor ?: break
            val valor = leerValor(archivo) ?: break
            entradas[clave] = valor
            if (entradas.size > MAX_ELEMENTOS) break
        }
        val dicc = PdfValor.Dicc(entradas)

        val guardado = pos
        if (!palabra("stream")) {
            pos = guardado
            return dicc
        }
        // Tras `stream` va un salto de línea —o un retorno y un salto— y a
        // partir del byte siguiente empiezan los datos, sin margen.
        if (pos < bytes.size && bytes[pos].toInt() == '\r'.code) pos++
        if (pos < bytes.size && bytes[pos].toInt() == '\n'.code) pos++
        val inicio = pos

        val declarado = when (val l = dicc.entradas["Length"]) {
            is PdfValor.Numero -> l.valor.toInt()
            is PdfValor.Ref -> (archivo?.objeto(l.numero) as? PdfValor.Numero)?.valor?.toInt()
            else -> null
        }
        // **La longitud declarada no se cree a ciegas.** Hay archivos remendados
        // donde miente, y leer de más o de menos aquí estropea el flujo entero.
        // Se comprueba que detrás esté `endstream`; si no, se busca.
        val fin = if (declarado != null && inicio + declarado <= bytes.size &&
            hayEndstreamTras(inicio + declarado)
        ) {
            inicio + declarado
        } else {
            buscar(bytes, "endstream".toByteArray(), inicio).also { if (it < 0) return dicc }
        }
        pos = fin
        palabra("endstream")
        return PdfValor.Flujo(dicc, bytes.copyOfRange(inicio, fin.coerceAtLeast(inicio)))
    }

    private fun hayEndstreamTras(desde: Int): Boolean {
        var i = desde
        var margen = 0
        while (i < bytes.size && margen < 4 && esBlanco(bytes[i].toInt() and 0xFF)) { i++; margen++ }
        return coincide(bytes, i, "endstream".toByteArray())
    }

    private fun leerCadena(): PdfValor.Cadena {
        pos++ // el paréntesis
        val out = java.io.ByteArrayOutputStream()
        var nivel = 1
        while (pos < bytes.size) {
            val b = bytes[pos++]
            when {
                b == '\\'.code.toByte() -> {
                    if (pos >= bytes.size) break
                    val e = bytes[pos++]
                    when (e.toInt().toChar()) {
                        'n' -> out.write('\n'.code)
                        'r' -> out.write('\r'.code)
                        't' -> out.write('\t'.code)
                        'b' -> out.write('\b'.code)
                        'f' -> out.write(12)
                        '\n' -> Unit // una línea partida no aporta nada
                        else -> out.write(e.toInt())
                    }
                }
                b == '('.code.toByte() -> { nivel++; out.write(b.toInt()) }
                b == ')'.code.toByte() -> {
                    nivel--
                    if (nivel == 0) break
                    out.write(b.toInt())
                }
                else -> out.write(b.toInt())
            }
        }
        return PdfValor.Cadena(out.toByteArray())
    }

    private fun leerCadenaHex(): PdfValor.Cadena {
        pos++ // el menor
        val out = java.io.ByteArrayOutputStream()
        var alto = -1
        while (pos < bytes.size) {
            val c = bytes[pos++].toInt().toChar()
            if (c == '>') break
            val d = Character.digit(c, 16)
            if (d < 0) continue
            if (alto < 0) alto = d else { out.write(alto * 16 + d); alto = -1 }
        }
        // Un dígito suelto al final se completa con cero, que es lo que dice el
        // formato: `<A>` es el byte 0xA0.
        if (alto >= 0) out.write(alto * 16)
        return PdfValor.Cadena(out.toByteArray())
    }

    private companion object {
        /** Tope de entradas: un diccionario más largo es un archivo roto. */
        const val MAX_ELEMENTOS = 200_000
    }
}

internal fun esBlanco(b: Int): Boolean =
    b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32

internal fun esDelimitador(b: Int): Boolean =
    b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code ||
        b == '['.code || b == ']'.code || b == '{'.code || b == '}'.code ||
        b == '/'.code || b == '%'.code

internal fun buscar(bytes: ByteArray, que: ByteArray, desde: Int): Int {
    var i = maxOf(0, desde)
    while (i + que.size <= bytes.size) {
        if (coincide(bytes, i, que)) return i
        i++
    }
    return -1
}
