package com.forge.pixpin.guardados

import java.text.Normalizer
import java.util.Locale

/**
 * **Poner los tiempos a una letra pegada con lo que oyó el reconocedor** (28-oct-2026, el usuario:
 * «para la música, que también la transcriba, pero con un sistema inteligente: yo pego la letra de
 * la canción, que es el texto bueno; la transcripción da los tiempos. Como un agente, que compare
 * las dos: la letra pegada es el texto bueno y la transcripción tiene el tiempo. Que las fusione en
 * una letra con tiempos»).
 *
 * ## Lo que entra y lo que sale
 *
 * - **La letra pegada**: el texto bueno. **No se toca ni una letra**: solo se le ponen tiempos.
 * - **Lo que oyó el reconocedor** (Whisper, Vosk…): trozos con su principio y su fin
 *   ([Transcriptor.Segmento]); las palabras de un trozo se reparten su tiempo por letras
 *   ([Transcriptor.repartir]). En una canción se equivoca mucho (la música tapa la voz), pero
 *   **cuándo** se canta cada cosa lo sabe bastante bien.
 * - Sale la letra en el formato de siempre ([LetraDeCancion.aGuardar]): `[m:ss] línea` y un renglón
 *   en blanco entre líneas, el que leen el PC y la pantalla de estudiar. Ver `docs/musica-con-letra.md`.
 *
 * ## Cómo: una alineación de secuencias, determinista y sin red
 *
 * Las dos cosas se pasan a **palabras normalizadas** ([normalizar]: minúsculas, sin tildes ni
 * signos) y se alinean con **Needleman–Wunsch semiglobal** ([alinear]): la puntuación de dos palabras
 * es +3 si son iguales, +2 si **suenan igual** ([fonetica]: b/v, c/s/z, ll/y, la h muda…), +1 si se
 * parecen (distancia de edición de un tercio o menos) y −2 si no; saltarse una palabra de cualquiera
 * de los dos lados cuesta −1. Los huecos **del principio y del final son gratis** en los dos lados:
 * una intro hablada, aplausos, o la última estrofa que el reconocedor no cogió no castigan al resto.
 *
 * Lo que eso aguanta:
 * - **Palabras mal oídas**: una sustitución entre dos aciertos cuesta poco y no mueve nada.
 * - **El estribillo repetido**: la alineación va en orden, así que el segundo estribillo de la letra
 *   va con el segundo que se cantó; si la letra lo escribe una vez y se canta dos, la copia de más del
 *   audio se queda en un hueco.
 * - **Una estrofa saltada o de más**: un hueco por el lado que sea; sus líneas se reparten luego.
 * - **Una intro sin letra**: hueco gratis al principio.
 *
 * Después, **cada línea toma el tiempo de su primera palabra alineada con fuerza** ([fuerte]: igual
 * y de tres letras o más, o que suena igual y de cuatro o más; las cortitas como «la» o «y» se cuelan
 * en cualquier sitio). Si esa no es la primera de la línea, se le restan las palabras de delante a
 * ritmo de canto, sin pasar de la línea anterior. **Las líneas sin ninguna** se reparten entre sus
 * vecinas por número de palabras ([repartirHuecos]); las de antes de la primera y después de la
 * última, al ritmo medio. Los tiempos nunca bajan.
 *
 * Todo puro y determinista (los mismos datos, la misma letra), probado en `AlinearLetraTest`. El
 * asistente en la nube no hace falta para esto: funciona sin red.
 */
object AlinearLetra {

    /** Una palabra oída, con el milisegundo en que empieza. */
    data class Palabra(val ms: Int, val texto: String)

    /** Lo que dura una palabra cantada si no se sabe más: para retroceder y para los extremos. */
    const val PALABRA_MS = 400

    /** Más celdas que esto no se alinean (una hora de audio contra un libro): sale null. */
    const val CELDAS_MAXIMAS = 8_000_000L

    private const val IGUAL = 3
    private const val SUENA_IGUAL = 2
    private const val PARECIDA = 1
    private const val DISTINTA = -2
    private const val HUECO = -1

    // --- Las palabras --------------------------------------------------------------------------

    /** **Una palabra para comparar**: minúsculas, sin tildes ni diéresis, solo letras y cifras. */
    fun normalizar(palabra: String): String {
        val sinTildes = Normalizer.normalize(palabra.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return sinTildes.filter { it.isLetterOrDigit() }
    }

    /** Las palabras normalizadas de un texto (sin las que se quedan vacías: «—», «¡!»). */
    fun palabras(texto: String): List<String> =
        texto.split(Regex("[\\s\\-–—/]+")).map { normalizar(it) }.filter { it.isNotEmpty() }

    /**
     * **Cómo suena una palabra en español**, para que «bes» y «vez», «haya» y «allá» o «cielo» y
     * «sielo» cuenten como la misma: la h muda fuera, b/v, c/z/s ante e/i, qu/c/k, ll/y, gu ante e/i,
     * las dobles sencillas.
     */
    fun fonetica(normalizada: String): String {
        var s = normalizada
        s = s.replace("ch", "\u0001")
        s = s.replace("h", "")
        s = s.replace("\u0001", "ch")
        s = s.replace("qu", "k").replace(Regex("c(?=[ei])"), "s").replace("c", "k").replace("z", "s")
        s = s.replace("v", "b").replace("w", "u").replace("ll", "y").replace(Regex("gu(?=[ei])"), "g")
        s = s.replace(Regex("(.)\\1+"), "$1")
        return s
    }

    /** La distancia de edición (Levenshtein) entre dos palabras. */
    fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var antes = IntArray(b.length + 1) { it }
        var ahora = IntArray(b.length + 1)
        for (i in 1..a.length) {
            ahora[0] = i
            for (j in 1..b.length) {
                val coste = if (a[i - 1] == b[j - 1]) 0 else 1
                ahora[j] = minOf(antes[j] + 1, ahora[j - 1] + 1, antes[j - 1] + coste)
            }
            val t = antes; antes = ahora; ahora = t
        }
        return antes[b.length]
    }

    /** **Lo que puntúa poner juntas** dos palabras ya normalizadas. */
    fun puntos(a: String, b: String): Int = when {
        a == b -> IGUAL
        fonetica(a) == fonetica(b) -> SUENA_IGUAL
        maxOf(a.length, b.length) >= 4 && distancia(a, b) * 3 <= maxOf(a.length, b.length) -> PARECIDA
        else -> DISTINTA
    }

    /**
     * **Si un par alineado vale para fijar el tiempo de una línea**: iguales y de tres letras o más,
     * o que suenan igual y de cuatro o más. Las cortas («la», «y», «de») caen en cualquier sitio.
     */
    fun fuerte(a: String, b: String): Boolean = when {
        a == b -> a.length >= 3
        fonetica(a) == fonetica(b) -> a.length >= 4 && b.length >= 4
        else -> false
    }

    /**
     * **Las palabras oídas con su tiempo**: cada trozo se reparte por letras ([Transcriptor.repartir]).
     * Un trozo de una sola palabra (Vosk da palabra a palabra) se queda con el suyo.
     */
    fun palabrasDe(trozos: List<Transcriptor.Segmento>): List<Palabra> =
        trozos.sortedBy { it.desdeMs }.flatMap { s ->
            Transcriptor.repartir(s.desdeMs, s.hastaMs, s.texto).flatMap { p ->
                palabras(p.texto).map { Palabra(p.desdeMs, it) }
            }
        }

    // --- La alineación -------------------------------------------------------------------------

    /**
     * **Needleman–Wunsch semiglobal**: para cada palabra de [a], el índice de la de [b] con que va, o
     * -1 si va con un hueco. Los huecos de los extremos no cuentan. Null si son demasiadas
     * ([CELDAS_MAXIMAS]). A igualdad se prefiere emparejar, luego saltar de [a], luego de [b]: el
     * mismo resultado siempre.
     */
    fun alinear(a: List<String>, b: List<String>): IntArray? {
        val n = a.size
        val m = b.size
        val emparejadas = IntArray(n) { -1 }
        if (n == 0 || m == 0) return emparejadas
        if (n.toLong() * m.toLong() > CELDAS_MAXIMAS) return null
        val ancho = m + 1
        // 1 diagonal, 2 arriba (hueco en b), 3 izquierda (hueco en a). 0 en los bordes.
        val camino = ByteArray((n + 1) * ancho)
        var antes = IntArray(m + 1) // fila 0: huecos del principio de b, gratis
        var ahora = IntArray(m + 1)
        var mejor = Int.MIN_VALUE
        var mejorI = 0
        var mejorJ = 0
        for (i in 1..n) {
            ahora[0] = 0 // huecos del principio de a, gratis
            for (j in 1..m) {
                val diag = antes[j - 1] + puntos(a[i - 1], b[j - 1])
                val arriba = antes[j] + HUECO
                val izquierda = ahora[j - 1] + HUECO
                val (v, d) = when {
                    diag >= arriba && diag >= izquierda -> diag to 1
                    arriba >= izquierda -> arriba to 2
                    else -> izquierda to 3
                }
                ahora[j] = v
                camino[i * ancho + j] = d.toByte()
            }
            // Los huecos del final de b son gratis: se puede acabar en cualquier punto de la última columna.
            if (ahora[m] > mejor) { mejor = ahora[m]; mejorI = i; mejorJ = m }
            val t = antes; antes = ahora; ahora = t
        }
        // Y los del final de a: cualquier punto de la última fila.
        for (j in 1..m) if (antes[j] > mejor) { mejor = antes[j]; mejorI = n; mejorJ = j }
        var i = mejorI
        var j = mejorJ
        while (i > 0 && j > 0) {
            when (camino[i * ancho + j].toInt()) {
                1 -> { emparejadas[i - 1] = j - 1; i--; j-- }
                2 -> i--
                3 -> j--
                else -> break
            }
        }
        return emparejadas
    }

    // --- De palabras a líneas ------------------------------------------------------------------

    /**
     * **El tiempo de cada línea de la letra** (ms), o null si no se pudo (sin nada que se parezca, o
     * demasiado largo). [lineas]: las líneas de la letra tal cual; [oidas]: lo que se oyó, en orden;
     * [duracionMs]: lo que dura el audio (para no pasarse al repartir el final), si se sabe.
     */
    fun tiempos(lineas: List<String>, oidas: List<Palabra>, duracionMs: Int? = null): List<Int>? {
        if (lineas.isEmpty() || oidas.isEmpty()) return null
        val deCadaLinea = lineas.map { palabras(it) }
        val todas = deCadaLinea.flatten()
        val pares = alinear(todas, oidas.map { it.texto }) ?: return null
        val ritmo = ritmo(oidas)
        // La primera palabra fuerte de cada línea, y cuántas lleva delante.
        val anclas = arrayOfNulls<Int>(lineas.size)
        var k = 0
        var ultima = 0
        for ((l, ps) in deCadaLinea.withIndex()) {
            var t: Int? = null
            for ((dentro, p) in ps.withIndex()) {
                val j = pares[k + dentro]
                if (j >= 0 && (fuerte(p, oidas[j].texto) || (ps.size <= 2 && p == oidas[j].texto))) {
                    t = (oidas[j].ms - dentro * ritmo).coerceAtLeast(ultima).coerceAtMost(oidas[j].ms)
                    break
                }
            }
            if (t != null) { anclas[l] = t; ultima = t }
            k += ps.size
        }
        if (anclas.all { it == null }) return null
        return repartirHuecos(anclas.toList(), deCadaLinea.map { it.size.coerceAtLeast(1) }, ritmo, duracionMs)
    }

    /**
     * **El ritmo de canto**: la mediana de lo que va de una palabra oída a la siguiente (las pausas
     * entre estrofas no cuentan: son la cola larga), entre 150 y 800 ms. Sin datos, [PALABRA_MS].
     */
    fun ritmo(oidas: List<Palabra>): Int {
        val pasos = oidas.zipWithNext { a, b -> b.ms - a.ms }.filter { it > 0 }.sorted()
        if (pasos.isEmpty()) return PALABRA_MS
        return pasos[pasos.size / 2].coerceIn(150, 800)
    }

    /**
     * **Las líneas sin tiempo, repartidas**: entre dos con tiempo, en proporción a las palabras que
     * llevan; antes de la primera, hacia atrás al [ritmo] (sin bajar de 0); después de la última,
     * hacia delante al [ritmo], sin pasar de [duracionMs]. Nunca bajan.
     */
    fun repartirHuecos(anclas: List<Int?>, pesos: List<Int>, ritmo: Int, duracionMs: Int?): List<Int> {
        val n = anclas.size
        val salida = IntArray(n)
        val fijas = anclas.indices.filter { anclas[it] != null }
        if (fijas.isEmpty()) return List(n) { 0 }
        val primera = fijas.first()
        val ultima = fijas.last()
        // Antes de la primera: hacia atrás, por sus palabras.
        var t = anclas[primera]!!
        for (i in primera - 1 downTo 0) {
            t = (t - pesos[i] * ritmo).coerceAtLeast(0)
            salida[i] = t
        }
        // Entre dos fijas: en proporción.
        for ((x, y) in fijas.zipWithNext()) {
            val ta = anclas[x]!!
            val tb = maxOf(anclas[y]!!, ta)
            salida[x] = ta
            val total = (x until y).sumOf { pesos[it] }.coerceAtLeast(1)
            var hechas = 0
            for (i in x + 1 until y) {
                hechas += pesos[i - 1]
                salida[i] = ta + ((tb - ta).toLong() * hechas / total).toInt()
            }
        }
        salida[ultima] = maxOf(anclas[ultima]!!, if (ultima > 0) salida[ultima - 1] else 0)
        // Después de la última: hacia delante, sin pasar del final.
        val fin = duracionMs?.takeIf { it > 0 }
        t = salida[ultima]
        for (i in ultima + 1 until n) {
            t += pesos[i - 1] * ritmo
            if (fin != null) t = minOf(t, fin)
            salida[i] = t
        }
        for (i in 1 until n) if (salida[i] < salida[i - 1]) salida[i] = salida[i - 1]
        return salida.toList()
    }

    // --- Lo que se guarda ----------------------------------------------------------------------

    /**
     * **Las líneas de una letra pegada**, tal cual: sin los tiempos que ya trajera (se ponen nuevos)
     * ni las líneas en blanco (no se cantan). Ver [LetraDeCancion.leer].
     */
    fun lineasDeLaLetra(pegada: String): List<String> =
        LetraDeCancion.leer(pegada).lineas.map { it.texto.trimEnd() }.filter { it.isNotBlank() }

    /**
     * **La letra pegada con tiempos**, lista para [Mensaje.transcripcion]: `[m:ss] línea` y un renglón
     * en blanco entre líneas. Null si no se pudo alinear (no se parecen en nada, o no hay letra).
     */
    fun letraConTiempos(pegada: String, trozos: List<Transcriptor.Segmento>, duracionMs: Int? = null): String? {
        val lineas = lineasDeLaLetra(pegada)
        if (lineas.isEmpty()) return null
        val t = tiempos(lineas, palabrasDe(trozos), duracionMs) ?: return null
        return LetraDeCancion.aGuardar(LetraDeCancion.Letra(lineas.mapIndexed { i, l -> LetraDeCancion.Linea(t[i], l) }))
    }

    /** Lo largo de una línea de letra hecha de lo oído, sin letra pegada: lo que se canta de una vez. */
    const val LINEA_OIDA_MS = 6_000
    private const val LINEA_OIDA_MINIMA_MS = 2_000

    /**
     * **Sin letra pegada, lo oído como letra con tiempos**: frases de hasta [LINEA_OIDA_MS] (las de
     * una transcripción son de 15 s: largas para cantar), partidas donde mejor se lea
     * ([Transcriptor.agrupar]). Null si no se oyó nada.
     */
    fun letraDeLoOido(trozos: List<Transcriptor.Segmento>): String? {
        val palabras = trozos.sortedBy { it.desdeMs }.flatMap { Transcriptor.repartir(it.desdeMs, it.hastaMs, it.texto) }
        if (palabras.isEmpty()) return null
        val lineas = Transcriptor.agrupar(palabras, LINEA_OIDA_MS, LINEA_OIDA_MINIMA_MS)
        if (lineas.isEmpty()) return null
        return LetraDeCancion.aGuardar(LetraDeCancion.Letra(lineas.map { LetraDeCancion.Linea(it.desdeMs, it.texto) }))
    }

    /**
     * **Lo que se propone a partir de lo oído**: con letra pegada, la letra con tiempos (o null si no
     * se pudo); sin letra, lo oído como letra.
     */
    fun propuesta(pegada: String?, trozos: List<Transcriptor.Segmento>, duracionMs: Int? = null): String? =
        if (pegada.isNullOrBlank() || lineasDeLaLetra(pegada).isEmpty()) letraDeLoOido(trozos)
        else letraConTiempos(pegada, trozos, duracionMs)
}
