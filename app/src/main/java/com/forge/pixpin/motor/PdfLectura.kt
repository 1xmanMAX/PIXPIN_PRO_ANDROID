package com.forge.pixpin.motor

import java.util.zip.Inflater

/**
 * Lo justo de un PDF para **poder pegarle algo encima sin romperlo**.
 *
 * No es un lector de PDF ni pretende serlo: no dibuja, no descomprime imágenes,
 * no sabe de fuentes ni de colores. Lo único que necesita saber es dónde vive
 * cada objeto del archivo y qué dice el diccionario de una página, que es lo
 * que hace falta para escribir una **actualización incremental** — el mecanismo
 * con el que un PDF admite que le añadas cosas al final sin tocar un byte de lo
 * anterior. Ver [PdfEscritura].
 *
 * Por qué a mano y no con una librería: la más ligera que se mantiene pesa 7 MB
 * y hay que pelearse con sus tripas igual para que guarde una anotación en modo
 * incremental. Aquí lo que se necesita cabe en un archivo, no pesa nada y se
 * puede comprobar entero sin dispositivo — que es la diferencia entre confiar
 * en esto y cruzar los dedos.
 *
 * ## Lo que sí entiende
 *
 * - La **tabla de referencias cruzadas** de toda la vida (`xref` + `trailer`).
 * - El **índice comprimido** de los PDF modernos (`/Type /XRef`), con su
 *   predictor PNG, que es lo que traen casi todos los archivos de hoy.
 * - Los **objetos empaquetados** (`/Type /ObjStm`): en un PDF moderno el
 *   diccionario de una página casi nunca está suelto, viene dentro de un
 *   paquete comprimido.
 * - Los **archivos con varias revisiones**, siguiendo `/Prev` hasta el fondo.
 *
 * ## Lo que no
 *
 * Cifrado. Un PDF protegido se detecta y se dice; no se intenta abrir. Y los
 * archivos rotos: si algo no cuadra, esto devuelve null en vez de inventarse
 * una lectura, porque escribir sobre una lectura inventada es la única forma de
 * corromper el archivo de alguien.
 */

// -------------------------------------------------------------------------
// Los valores de un PDF
// -------------------------------------------------------------------------

/** Un valor cualquiera del archivo. Es el modelo mínimo del formato. */
sealed interface PdfValor {
    data class Numero(val valor: Double) : PdfValor
    data class Nombre(val valor: String) : PdfValor
    data class Cadena(val bytes: ByteArray) : PdfValor {
        override fun equals(other: Any?) = other is Cadena && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class Booleano(val valor: Boolean) : PdfValor
    data object Nulo : PdfValor
    data class Lista(val valores: List<PdfValor>) : PdfValor
    data class Dicc(val entradas: Map<String, PdfValor>) : PdfValor
    /** Una referencia a otro objeto: `12 0 R`. */
    data class Ref(val numero: Int, val generacion: Int) : PdfValor
    /** Un diccionario con datos detrás. [datos] va **tal cual**, sin descomprimir. */
    data class Flujo(val dicc: Dicc, val datos: ByteArray) : PdfValor {
        override fun equals(other: Any?) =
            other is Flujo && dicc == other.dicc && datos.contentEquals(other.datos)
        override fun hashCode() = 31 * dicc.hashCode() + datos.contentHashCode()
    }
}

/** Atajos para leer un diccionario sin escribir tres líneas cada vez. */
fun PdfValor.Dicc.dicc(clave: String): PdfValor.Dicc? = entradas[clave] as? PdfValor.Dicc
fun PdfValor.Dicc.nombre(clave: String): String? = (entradas[clave] as? PdfValor.Nombre)?.valor
fun PdfValor.Dicc.numero(clave: String): Double? = (entradas[clave] as? PdfValor.Numero)?.valor
fun PdfValor.Dicc.entero(clave: String): Int? = numero(clave)?.toInt()
fun PdfValor.Dicc.lista(clave: String): List<PdfValor>? =
    (entradas[clave] as? PdfValor.Lista)?.valores
fun PdfValor.Dicc.ref(clave: String): PdfValor.Ref? = entradas[clave] as? PdfValor.Ref

/** Dónde vive un objeto dentro del archivo. */
sealed interface Sitio {
    /** Escrito tal cual en el archivo, en ese desplazamiento. */
    data class Suelto(val desplazamiento: Long) : Sitio
    /** Empaquetado dentro de otro objeto comprimido, en esa posición. */
    data class Empaquetado(val paquete: Int, val indice: Int) : Sitio
}

// -------------------------------------------------------------------------
// El archivo
// -------------------------------------------------------------------------

/**
 * Un PDF ya indexado: se sabe dónde está cada objeto y se puede pedir.
 *
 * Se construye con [leer], que devuelve null si el archivo no se entiende. Los
 * bytes se guardan enteros y **no se tocan nunca**: son los que se copiarán tal
 * cual al escribir la actualización.
 */
class PdfArchivo internal constructor(
    val bytes: ByteArray,
    /** Dónde está cada objeto, por su número. */
    val indice: Map<Int, Sitio>,
    /** El diccionario final, con `/Root` y `/Size`. */
    val trailer: PdfValor.Dicc,
    /**
     * Si el índice más reciente era **comprimido** (`/Type /XRef`).
     *
     * Lo necesita [PdfEscritura], y no es un detalle: una actualización
     * incremental tiene que escribir su índice **del mismo tipo** que el que
     * había. Colgar una tabla clásica de un índice comprimido produce un
     * archivo que unos lectores abren y otros no, que es la peor de las dos
     * opciones posibles porque el fallo aparece en el ordenador de otro.
     */
    val indiceEsFlujo: Boolean = false
) {

    /** Objetos ya leídos: un `/Pages` se pide una vez por página. */
    private val cache = HashMap<Int, PdfValor?>()

    /** ¿Está cifrado? Entonces no se toca. */
    val cifrado: Boolean get() = trailer.entradas.containsKey("Encrypt")

    /** El objeto [numero], leído y montado, o null si no se puede. */
    fun objeto(numero: Int): PdfValor? = cache.getOrPut(numero) { leerObjeto(numero) }

    /** Lo mismo siguiendo referencias: `/Root` da el catálogo y no un `12 0 R`. */
    fun resolver(valor: PdfValor?): PdfValor? = when (valor) {
        is PdfValor.Ref -> objeto(valor.numero)
        else -> valor
    }

    fun diccDe(valor: PdfValor?): PdfValor.Dicc? = when (val v = resolver(valor)) {
        is PdfValor.Dicc -> v
        is PdfValor.Flujo -> v.dicc
        else -> null
    }

    /**
     * Las páginas, **en su orden**, con el número de objeto de cada una.
     *
     * Se recorre el árbol de `/Pages` en vez de fiarse del orden de los objetos:
     * en un PDF los objetos pueden estar escritos en cualquier orden, y la
     * página 1 puede ser el objeto 47.
     */
    fun paginas(): List<Int> {
        val raiz = diccDe(trailer.entradas["Root"]) ?: return emptyList()
        val arbol = raiz.ref("Pages") ?: return emptyList()
        val out = mutableListOf<Int>()
        recorrer(arbol, out, HashSet())
        return out
    }

    private fun recorrer(nodo: PdfValor.Ref, out: MutableList<Int>, vistos: MutableSet<Int>) {
        // El árbol de páginas de un PDF corrupto puede tener ciclos, y un ciclo
        // aquí es un cuelgue con el archivo de alguien delante.
        if (!vistos.add(nodo.numero) || out.size > MAX_PAGINAS) return
        val d = diccDe(nodo) ?: return
        when (d.nombre("Type")) {
            "Page" -> out += nodo.numero
            else -> {
                val kids = d.lista("Kids") ?: run {
                    // Sin /Type pero con /Contents es una página: hay archivos
                    // que se saltan el tipo y siguen abriéndose en todas partes.
                    if (d.entradas.containsKey("Contents")) out += nodo.numero
                    return
                }
                for (k in kids) if (k is PdfValor.Ref) recorrer(k, out, vistos)
            }
        }
    }

    /** El diccionario de la página [indicePagina], contando desde cero. */
    fun pagina(indicePagina: Int): PdfValor.Dicc? =
        paginas().getOrNull(indicePagina)?.let { diccDe(PdfValor.Ref(it, 0)) }

    /**
     * El tamaño de la página en puntos, mirando `/MediaBox`.
     *
     * Si la página no lo trae, se hereda del padre: es lo que hace el formato, y
     * un PDF donde solo la raíz declara el tamaño es de lo más corriente.
     */
    fun tamanoDePagina(indicePagina: Int): Pair<Double, Double>? {
        var d = pagina(indicePagina) ?: return null
        var saltos = 0
        while (saltos++ < 32) {
            val caja = (resolver(d.entradas["MediaBox"]) as? PdfValor.Lista)?.valores
            if (caja != null && caja.size >= 4) {
                val n = caja.mapNotNull { (resolver(it) as? PdfValor.Numero)?.valor }
                if (n.size >= 4) {
                    return kotlin.math.abs(n[2] - n[0]) to kotlin.math.abs(n[3] - n[1])
                }
            }
            d = diccDe(d.entradas["Parent"]) ?: return null
        }
        return null
    }

    // ---- Lectura de un objeto suelto o empaquetado ----

    private fun leerObjeto(numero: Int): PdfValor? = when (val sitio = indice[numero]) {
        null -> null
        is Sitio.Suelto -> leerSuelto(sitio.desplazamiento.toInt(), numero)
        is Sitio.Empaquetado -> leerDelPaquete(sitio)
    }

    private fun leerSuelto(desde: Int, numeroEsperado: Int): PdfValor? {
        if (desde < 0 || desde >= bytes.size) return null
        val lector = PdfLector(bytes, desde)
        val n = lector.leerEntero() ?: return null
        lector.leerEntero() ?: return null
        if (!lector.palabra("obj")) return null
        // El índice puede mentir en un archivo remendado: si ahí no está el
        // objeto que se pedía, mejor no devolver nada que devolver otro.
        if (n != numeroEsperado) return null
        return lector.leerValor(this)
    }

    private fun leerDelPaquete(sitio: Sitio.Empaquetado): PdfValor? {
        val paquete = objeto(sitio.paquete) as? PdfValor.Flujo ?: return null
        val datos = descomprimir(paquete) ?: return null
        val cuantos = paquete.dicc.entero("N") ?: return null
        val primero = paquete.dicc.entero("First") ?: return null

        // La cabecera del paquete son pares «número, desplazamiento».
        val cabecera = PdfLector(datos, 0)
        var desplazamiento: Int? = null
        for (i in 0 until cuantos) {
            cabecera.leerEntero() ?: return null
            val off = cabecera.leerEntero() ?: return null
            if (i == sitio.indice) desplazamiento = off
        }
        val inicio = primero + (desplazamiento ?: return null)
        if (inicio < 0 || inicio >= datos.size) return null
        return PdfLector(datos, inicio).leerValor(this)
    }

    /**
     * Los datos de un flujo, descomprimidos si hacen falta.
     *
     * Solo se entiende `FlateDecode`, que es con lo que se comprime el 99% de
     * lo que aquí importa —los índices y los paquetes de objetos—. Otro filtro
     * devuelve null, y quien llame decide qué hacer con eso.
     */
    fun descomprimir(flujo: PdfValor.Flujo): ByteArray? {
        val filtro = when (val f = resolver(flujo.dicc.entradas["Filter"])) {
            null -> null
            is PdfValor.Nombre -> f.valor
            is PdfValor.Lista -> (f.valores.firstOrNull() as? PdfValor.Nombre)?.valor
            else -> return null
        }
        val crudo = when (filtro) {
            null -> flujo.datos
            "FlateDecode", "Fl" -> inflar(flujo.datos) ?: return null
            else -> return null
        }
        val parms = diccDe(flujo.dicc.entradas["DecodeParms"]) ?: return crudo
        return deshacerPredictor(crudo, parms)
    }

    private fun deshacerPredictor(datos: ByteArray, parms: PdfValor.Dicc): ByteArray? {
        val predictor = parms.entero("Predictor") ?: 1
        if (predictor < 2) return datos
        if (predictor < 10) return datos // TIFF: no se usa en índices
        val columnas = parms.entero("Columns") ?: 1
        val colores = parms.entero("Colors") ?: 1
        val bits = parms.entero("BitsPerComponent") ?: 8
        return deshacerPredictorPng(datos, columnas, colores, bits)
    }

    private companion object {
        /** Tope de páginas: un archivo que diga tener más está roto o miente. */
        const val MAX_PAGINAS = 20_000
    }
}

// -------------------------------------------------------------------------
// Abrir el archivo
// -------------------------------------------------------------------------

/**
 * Lee el índice de un PDF, o null si no se entiende.
 *
 * El camino es el que manda el formato y no el sentido común: **se empieza por
 * el final**. Los últimos bytes dicen dónde está el índice, el índice dice
 * dónde está cada objeto, y si el archivo se ha guardado varias veces, cada
 * índice apunta al anterior con `/Prev`. Leer un PDF de principio a fin no
 * funciona: los objetos pueden estar en cualquier orden.
 */
fun leerPdf(bytes: ByteArray): PdfArchivo? = runCatching {
    val inicio = ultimoStartxref(bytes) ?: return null

    val indice = HashMap<Int, Sitio>()
    var trailer: PdfValor.Dicc? = null
    var siguiente: Long? = inicio
    val vistos = HashSet<Long>()
    var indiceEsFlujo = false
    var primera = true

    // Se van encadenando revisiones hacia atrás. **La primera que se lee manda**:
    // es la más reciente, y una revisión vieja no puede pisar a una nueva.
    while (siguiente != null && vistos.add(siguiente)) {
        val seccion = leerSeccion(bytes, siguiente.toInt()) ?: break
        if (primera) { indiceEsFlujo = seccion.esFlujo; primera = false }
        for ((numero, sitio) in seccion.entradas) indice.putIfAbsent(numero, sitio)
        if (trailer == null) trailer = seccion.trailer
        else {
            // Un archivo híbrido puede traer el /Root solo en una revisión vieja.
            val faltan = seccion.trailer.entradas.filterKeys { it !in trailer!!.entradas }
            if (faltan.isNotEmpty()) {
                trailer = PdfValor.Dicc(trailer!!.entradas + faltan)
            }
        }
        siguiente = seccion.previa
    }

    val t = trailer ?: return null
    if (indice.isEmpty()) return null
    PdfArchivo(bytes, indice, t, indiceEsFlujo)
}.getOrNull()

/** Una sección del índice: sus entradas, su cola y a qué revisión apunta. */
private class Seccion(
    val entradas: Map<Int, Sitio>,
    val trailer: PdfValor.Dicc,
    val previa: Long?,
    /** Si esta sección venía como flujo comprimido y no como tabla clásica. */
    val esFlujo: Boolean
)

/** El `startxref` de más al final, que es el del índice bueno. */
internal fun ultimoStartxref(bytes: ByteArray): Long? {
    val marca = "startxref".toByteArray()
    // Se busca en la cola: la especificación dice que está en el último kilobyte
    // y ampliarlo un poco cubre a los que escriben basura detrás.
    val desde = maxOf(0, bytes.size - 4096)
    var pos = -1
    var i = bytes.size - marca.size
    while (i >= desde) {
        if (coincide(bytes, i, marca)) { pos = i; break }
        i--
    }
    if (pos < 0) return null
    val lector = PdfLector(bytes, pos + marca.size)
    return lector.leerEntero()?.toLong()
}

private fun leerSeccion(bytes: ByteArray, desde: Int): Seccion? {
    if (desde < 0 || desde >= bytes.size) return null
    val lector = PdfLector(bytes, desde)
    lector.saltarBlancos()
    return if (lector.palabra("xref")) tablaClasica(bytes, lector)
    else indiceComprimido(bytes, desde)
}

/**
 * La tabla de toda la vida: `xref`, subsecciones de veinte bytes por entrada y
 * un `trailer` detrás.
 */
private fun tablaClasica(bytes: ByteArray, lector: PdfLector): Seccion? {
    val entradas = HashMap<Int, Sitio>()
    while (true) {
        lector.saltarBlancos()
        if (lector.palabra("trailer")) break
        val primero = lector.leerEntero() ?: return null
        val cuantos = lector.leerEntero() ?: return null
        if (cuantos < 0 || cuantos > 1_000_000) return null
        for (i in 0 until cuantos) {
            lector.saltarBlancos()
            val off = lector.leerEntero() ?: return null
            lector.leerEntero() ?: return null
            lector.saltarBlancos()
            val tipo = lector.siguienteByte() ?: return null
            // 'n' es un objeto vivo; 'f' es un hueco de uno borrado.
            if (tipo.toInt().toChar() == 'n') {
                entradas.putIfAbsent(primero + i, Sitio.Suelto(off.toLong()))
            }
        }
    }
    val trailer = lector.leerValor(null) as? PdfValor.Dicc ?: return null
    // Un archivo híbrido apunta además a un índice comprimido con lo que la
    // tabla clásica no sabe expresar; se lee también, y lo que ya hay manda.
    val extra = trailer.numero("XRefStm")?.toInt()
    if (extra != null) {
        indiceComprimido(bytes, extra)?.entradas?.forEach { (n, s) -> entradas.putIfAbsent(n, s) }
    }
    return Seccion(entradas, trailer, trailer.numero("Prev")?.toLong(), esFlujo = false)
}

/**
 * El índice comprimido de los PDF modernos: un objeto `/Type /XRef` cuyo flujo
 * son las entradas en binario.
 *
 * Cada entrada son tres campos cuyos anchos vienen en `/W`, y casi siempre
 * llegan pasados por el predictor PNG — el mismo truco que usan las imágenes
 * para comprimir mejor restando cada fila de la anterior.
 */
private fun indiceComprimido(bytes: ByteArray, desde: Int): Seccion? {
    val lector = PdfLector(bytes, desde)
    lector.leerEntero() ?: return null
    lector.leerEntero() ?: return null
    if (!lector.palabra("obj")) return null
    val flujo = lector.leerValor(null) as? PdfValor.Flujo ?: return null
    val dicc = flujo.dicc

    val anchos = dicc.lista("W")?.mapNotNull { (it as? PdfValor.Numero)?.valor?.toInt() }
        ?: return null
    if (anchos.size < 3) return null

    val datos = inflarConPredictor(flujo, dicc) ?: return null
    val fila = anchos.sum()
    if (fila <= 0) return null

    // `/Index` dice qué números cubre; sin él, de cero a `/Size`.
    val tramos = dicc.lista("Index")?.mapNotNull { (it as? PdfValor.Numero)?.valor?.toInt() }
        ?: listOf(0, dicc.entero("Size") ?: (datos.size / fila))

    val entradas = HashMap<Int, Sitio>()
    var pos = 0
    var t = 0
    while (t + 1 < tramos.size) {
        val primero = tramos[t]
        val cuantos = tramos[t + 1]
        for (i in 0 until cuantos) {
            if (pos + fila > datos.size) break
            var campo = 0
            val valores = IntArray(3)
            var largo = 0L
            for (c in 0 until 3) {
                var v = 0L
                repeat(anchos[c]) {
                    v = (v shl 8) or (datos[pos++].toLong() and 0xFF)
                }
                // Un ancho de cero significa «el de por defecto», que para el
                // tipo es 1: un objeto suelto.
                valores[campo] = if (anchos[c] == 0 && c == 0) 1 else v.toInt()
                if (c == 1) largo = v
                campo++
            }
            val numero = primero + i
            when (valores[0]) {
                1 -> entradas.putIfAbsent(numero, Sitio.Suelto(largo))
                2 -> entradas.putIfAbsent(numero, Sitio.Empaquetado(largo.toInt(), valores[2]))
                // 0 es un hueco: el objeto ya no existe.
            }
        }
        t += 2
    }
    return Seccion(entradas, dicc, dicc.numero("Prev")?.toLong(), esFlujo = true)
}

/** El flujo del índice, descomprimido y sin predictor. */
private fun inflarConPredictor(flujo: PdfValor.Flujo, dicc: PdfValor.Dicc): ByteArray? {
    val filtro = (dicc.entradas["Filter"] as? PdfValor.Nombre)?.valor
        ?: ((dicc.entradas["Filter"] as? PdfValor.Lista)
            ?.valores?.firstOrNull() as? PdfValor.Nombre)?.valor
    val crudo = when (filtro) {
        null -> flujo.datos
        "FlateDecode", "Fl" -> inflar(flujo.datos) ?: return null
        else -> return null
    }
    val parms = (dicc.entradas["DecodeParms"] as? PdfValor.Dicc) ?: return crudo
    val predictor = parms.entero("Predictor") ?: 1
    if (predictor < 10) return crudo
    return deshacerPredictorPng(
        crudo,
        parms.entero("Columns") ?: 1,
        parms.entero("Colors") ?: 1,
        parms.entero("BitsPerComponent") ?: 8
    )
}

// -------------------------------------------------------------------------
// Utilidades de bytes
// -------------------------------------------------------------------------

internal fun inflar(datos: ByteArray): ByteArray? = runCatching {
    val salida = java.io.ByteArrayOutputStream(maxOf(64, datos.size * 4))
    val inflater = Inflater()
    inflater.setInput(datos)
    val buffer = ByteArray(16 * 1024)
    while (!inflater.finished()) {
        val n = inflater.inflate(buffer)
        if (n == 0) {
            if (inflater.needsInput() || inflater.needsDictionary()) break
        } else {
            salida.write(buffer, 0, n)
        }
    }
    inflater.end()
    salida.toByteArray().takeIf { it.isNotEmpty() }
}.getOrNull()

/**
 * Deshace el predictor PNG.
 *
 * Cada fila viene con un byte delante que dice cómo se codificó respecto de la
 * anterior. Es el mismo esquema de los PNG, y en los índices de PDF es casi
 * siempre el tipo 2 —«arriba»—, pero están los cinco porque implementarlos
 * cuesta diez líneas y encontrarse el que falta cuesta una tarde.
 */
internal fun deshacerPredictorPng(
    datos: ByteArray, columnas: Int, colores: Int, bits: Int
): ByteArray? {
    val muestra = maxOf(1, colores * bits / 8)
    val ancho = maxOf(1, columnas * colores * bits / 8)
    if (ancho + 1 > datos.size) return null

    val filas = datos.size / (ancho + 1)
    val salida = ByteArray(filas * ancho)
    val previa = ByteArray(ancho)
    var entrada = 0
    var destino = 0

    repeat(filas) {
        val tipo = datos[entrada++].toInt() and 0xFF
        val fila = ByteArray(ancho)
        for (i in 0 until ancho) {
            val cruda = datos[entrada++].toInt() and 0xFF
            val izquierda = if (i >= muestra) fila[i - muestra].toInt() and 0xFF else 0
            val arriba = previa[i].toInt() and 0xFF
            val esquina = if (i >= muestra) previa[i - muestra].toInt() and 0xFF else 0
            val valor = when (tipo) {
                0 -> cruda
                1 -> cruda + izquierda
                2 -> cruda + arriba
                3 -> cruda + (izquierda + arriba) / 2
                4 -> cruda + paeth(izquierda, arriba, esquina)
                else -> return null
            }
            fila[i] = (valor and 0xFF).toByte()
        }
        fila.copyInto(salida, destino)
        fila.copyInto(previa)
        destino += ancho
    }
    return salida
}

private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
}

internal fun coincide(bytes: ByteArray, desde: Int, que: ByteArray): Boolean {
    if (desde < 0 || desde + que.size > bytes.size) return false
    for (i in que.indices) if (bytes[desde + i] != que[i]) return false
    return true
}
