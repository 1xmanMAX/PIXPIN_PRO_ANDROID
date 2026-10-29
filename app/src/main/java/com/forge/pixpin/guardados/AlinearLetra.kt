package com.forge.pixpin.guardados

/**
 * **Poner los tiempos a una letra pegada con lo que oyó el reconocedor** (28-oct-2026, el usuario:
 * «para la música, que también la transcriba, pero con un sistema inteligente: yo pego la letra de
 * la canción, que es el texto bueno; la transcripción da los tiempos. Como un agente, que compare
 * las dos: la letra pegada es el texto bueno y la transcripción tiene el tiempo. Que las fusione en
 * una letra con tiempos»).
 *
 * **Puerto tal cual de `pixpin_voz::alinear_letra` del PC** (29-oct-2026, «Sincronizar sola»,
 * `origin/main` 50e87978): las mismas constantes, los mismos pasos y **todas sus pruebas** en
 * `AlinearLetraTest`, para que el PC y el móvil den **los mismos minutos con lo mismo oído**. El
 * alineador propio del 28-oct (+3/+2/+1/−2, la fonética de b/v y c/s/z, las anclas «fuertes») se
 * retira: con los dos aparatos sincronizando la misma canción, dos cálculos distintos daban dos
 * letras distintas. Ver `docs/musica-con-letra.md` y `docs/ui-funciones-y-chats.md` del PC.
 *
 * ## Lo que entra y lo que sale
 *
 * - **Las líneas de la letra pegada** ([lineasDeLaLetra]: las mismas que sincronizar a toques, un
 *   renglón por línea). **El texto que se guarda es siempre el pegado**: lo oído solo da los tiempos.
 * - **Las palabras oídas** ([palabrasOidas]): las tandas del reconocedor con su principio y su fin,
 *   y dentro de cada una el tiempo repartido según lo largas que son ([Transcriptor.repartir]).
 * - Sale, por línea, un milisegundo, una confianza de 0 a 1 y si es ancla ([Alineado]); o null si
 *   ninguna línea se pudo anclar (y la pantalla lo cuenta en vez de guardar tiempos inventados).
 *
 * ## El algoritmo (puro, sin sonido ni pantallas)
 *
 * 1. **Normalizar** las dos partes ([normalizar]): minúsculas, sin tildes ni puntuación; el apóstrofo
 *    junta (`can't` → `cant`); los «x2» no son palabras. Una línea que es solo un rótulo entre
 *    corchetes (`[Coro]`) no se canta: se lleva el minuto de la siguiente.
 * 2. **Needleman–Wunsch palabra a palabra**, monótono: una pareja suma `1 + parecido` si se parecen
 *    (Levenshtein, desde [UMBRAL]; las de una o dos letras solo exactas y valen [CORTA]); una pareja
 *    distinta resta 0,9; una palabra de la letra sin pareja resta 0,6 y una oída sin pareja 0,4 (los
 *    coros de fondo y los «yeah» sobran mucho más a menudo que faltan palabras). Lo oído antes de la
 *    primera palabra y después de la última no cuesta. Un estribillo repetido casa con su vez porque
 *    el camino nunca vuelve atrás.
 * 3. **Anclas**: confianza de una línea = suma de los parecidos de sus palabras casadas / sus
 *    palabras; ancla con ≥ 0,4 y al menos dos casadas (una si la línea tiene una). Su minuto es el de
 *    su primera palabra casada menos lo que se tarda en cantar las de delante (al ritmo de la
 *    canción), nunca antes que el ancla anterior. **Una línea repetida** (el estribillo dos veces
 *    seguidas) que casó con la vez que no toca pasa el ancla a su gemela ([gemelas]).
 * 4. **El ritmo** (ms por letra): la mediana de lo medido dentro de las líneas y entre dos líneas
 *    seguidas ancladas (hasta 20 s); 70 si no hay, entre 35 y 300.
 * 5. **Las líneas sin ancla se reparten sobre lo cantado** ([Voz]: los tramos con palabras oídas,
 *    casen o no): así un solo de guitarra no estira la estrofa de antes y una estrofa mal oída se
 *    reparte por donde se cantó. Donde no se oyó nada, nunca más despacio que el ritmo (el hueco
 *    instrumental queda antes de la estrofa siguiente). Delante de la primera ancla, desde lo que se
 *    pudo cantar (lo que Whisper se invente en la intro no cuenta); detrás de la última, hasta lo
 *    último que se oye, sin pasar del final de la canción. Todo en orden.
 */
object AlinearLetra {

    /** Una palabra de lo oído, ya normalizada, y en qué milisegundo empieza (`PalabraOida`). */
    data class PalabraOida(val texto: String, val ms: Long)

    /** El minuto que le toca a una línea y cuánto fiarse de él (`LineaAlineada`). */
    data class LineaAlineada(
        val ms: Long,
        /** De 0 a 1: lo que se parecen sus palabras a lo oído. Una línea repartida lleva 0. */
        val confianza: Float,
        /** Si el minuto sale de palabras oídas y no de repartir. */
        val anclada: Boolean
    )

    /** El resultado de alinear: una entrada por línea, en el mismo orden (`Alineado`). */
    data class Alineado(val lineas: List<LineaAlineada>) {
        /** Cuántas líneas salen de lo oído. */
        val ancladas: Int get() = lineas.count { it.anclada }

        /** **La letra con sus minutos**, con el texto pegado tal cual (nunca lo oído). */
        fun letra(textos: List<String>): LetraDeCancion.Letra =
            LetraDeCancion.Letra(textos.zip(lineas).map { (t, l) -> LetraDeCancion.Linea(l.ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), t.trim()) })

        /** Si va **a ojo**: menos de un tercio de las líneas se oyeron ([vaAOjo]). */
        val aOjo: Boolean get() = vaAOjo(ancladas, lineas.size)
    }

    /**
     * **«Va a ojo»** (29-oct-2026, como el aviso del PC `musica-sola-hecha-poca`): con menos de un
     * tercio de las líneas oídas, los minutos son sobre todo repartidos y el aviso lo dice.
     */
    fun vaAOjo(ancladas: Int, total: Int): Boolean = ancladas * 3 < total

    /** Lo que tiene que parecerse una palabra a otra para casar (de 0 a 1). */
    const val UMBRAL = 0.65f
    /** Lo que cuesta una palabra de la letra que no se oyó. */
    const val SUELTA_EN_LA_LETRA = 0.6f
    /** Lo que cuesta una palabra oída que no está en la letra (coros, «yeah»). */
    const val SUELTA_EN_LO_OIDO = 0.4f
    /** Una pareja que no se parece: un poco menos que dos sueltas. */
    const val DISTINTAS = -0.9f
    /** Lo que vale una pareja de palabras cortas («a», «de», «la»): no pueden tirar del camino. */
    const val CORTA = 0.5f
    /** La confianza que hace falta para ser ancla. */
    const val CONFIANZA_DE_ANCLA = 0.4f
    /** Los milisegundos por letra si la canción no deja medirlos, y sus topes. */
    const val RITMO_POR_OMISION = 70.0
    const val RITMO_MIN = 35.0
    const val RITMO_MAX = 300.0
    /** Más que esto entre dos líneas seguidas ya no es ritmo: es un hueco. */
    const val HUECO_MS = 20_000L
    /** Delante de la primera ancla, lo más despacio que se puede cantar: estas veces el ritmo. */
    const val LENTO = 1.5
    /** Lo que se supone que dura una palabra oída (solo se sabe dónde empieza). */
    const val PALABRA_MS = 600L
    /** Dos palabras oídas a menos de esto son el mismo tramo cantado. */
    const val JUNTAR_MS = 2_500L
    /** Un tope de celdas para la tabla (n×m): 2 500 palabras por lado. */
    const val CELDAS_MAX = 6_250_000L
    /** Lo que tiene que acercarse una gemela a donde toca, frente a la que casó (y al menos esto). */
    const val GEMELA_MEJORA_MS = 3_000.0

    // --- Cuándo se oye cantar -----------------------------------------------------------------

    /**
     * **Cuándo se oye cantar** (`Voz` del PC): los tramos con palabras oídas (casen o no con la
     * letra), cada palabra de [PALABRA_MS] y juntas si las separa menos de [JUNTAR_MS]. Repartir las
     * líneas sin ancla sobre estos tramos, y no sobre el reloj, salta los solos.
     */
    class Voz private constructor(val tramos: List<Pair<Long, Long>>) {

        /** Lo que se canta entre [a] y [b]. */
        fun dentro(a: Long, b: Long): Long = tramos.sumOf { (x, y) -> (minOf(y, b) - maxOf(x, a)).coerceAtLeast(0) }

        /** Dónde se llega desde [a] tras [d] ms cantados (null si no hay tanto canto detrás de [a]). */
        fun avanzar(a: Long, d: Long): Long? {
            var falta = d.coerceAtLeast(0)
            for ((x0, y) in tramos) {
                val x = maxOf(x0, a)
                if (y <= x) continue
                if (y - x >= falta) return x + falta
                falta -= y - x
            }
            return null
        }

        /** Dónde se llega desde [b] hacia atrás tras [d] ms cantados. */
        fun retroceder(b: Long, d: Long): Long? {
            var falta = d.coerceAtLeast(0)
            for ((x, y0) in tramos.asReversed()) {
                val y = minOf(y0, b)
                if (y <= x) continue
                if (y - x >= falta) return y - falta
                falta -= y - x
            }
            return null
        }

        /** Dónde acaba lo último que se oye cantar. */
        fun fin(): Long? = tramos.lastOrNull()?.second

        companion object {
            fun de(oidas: List<PalabraOida>): Voz {
                val tramos = ArrayList<Pair<Long, Long>>()
                for (t in oidas.map { it.ms }.sorted()) {
                    val u = tramos.lastOrNull()
                    if (u != null && t - u.second <= JUNTAR_MS) tramos[tramos.size - 1] = u.first to maxOf(u.second, t + PALABRA_MS)
                    else tramos += t to t + PALABRA_MS
                }
                return Voz(tramos)
            }
        }
    }

    // --- Las palabras --------------------------------------------------------------------------

    /** Una letra sin tilde: lo que se compara es cómo suena, no cómo se escribe (`sin_tilde`). */
    private fun sinTilde(c: Int): Int = when (c.toChar()) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'.code
        'é', 'è', 'ê', 'ë' -> 'e'.code
        'í', 'ì', 'î', 'ï' -> 'i'.code
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'.code
        'ú', 'ù', 'û', 'ü' -> 'u'.code
        'ñ' -> 'n'.code
        'ç' -> 'c'.code
        'ý', 'ÿ' -> 'y'.code
        else -> c
    }.let { if (c > 0xFFFF) c else it }

    /** `char::is_alphanumeric` de Rust: la propiedad Alphabetic de Unicode o un número (Nd, Nl, No). */
    private fun alfanumerico(c: Int): Boolean {
        if (Character.isAlphabetic(c)) return true
        val t = Character.getType(c)
        return t == Character.DECIMAL_DIGIT_NUMBER.toInt() || t == Character.LETTER_NUMBER.toInt() || t == Character.OTHER_NUMBER.toInt()
    }

    /**
     * **Las palabras de un texto, para comparar** (`normalizar`): en minúsculas, sin tildes ni
     * puntuación. El apóstrofo junta (`can't` → `cant`); lo demás que no es letra ni cifra separa.
     * Los «x2» de repetir no son palabras.
     */
    fun normalizar(texto: String): List<String> {
        val palabras = ArrayList<String>()
        val actual = StringBuilder()
        fun cerrar() {
            if (actual.isNotEmpty() && !esRepetir(actual.toString())) palabras += actual.toString()
            actual.setLength(0)
        }
        var k = 0
        while (k < texto.length) {
            val cp = texto.codePointAt(k)
            k += Character.charCount(cp)
            val c = sinTilde(Character.toLowerCase(cp))
            if (alfanumerico(c)) actual.appendCodePoint(c)
            else if (c != '\''.code && c != '’'.code && c != '´'.code && c != '`'.code) cerrar()
        }
        cerrar()
        return palabras
    }

    /** `x2`, `x3`, `2x`: «esto se repite», no se canta. */
    private fun esRepetir(p: String): Boolean {
        fun cifras(t: String) = t.isNotEmpty() && t.all { it in '0'..'9' }
        return (p.startsWith('x') && cifras(p.substring(1))) || (p.endsWith('x') && cifras(p.dropLast(1)))
    }

    /** Si una línea es solo un rótulo (`[Coro]`, `[Verse 2: Ana]`): no se canta. */
    private fun esRotulo(linea: String): Boolean {
        val t = linea.trim()
        return t.startsWith('[') && t.endsWith(']')
    }

    private fun largo(s: String): Int = s.codePointCount(0, s.length)

    /** **Lo que se parecen dos palabras**, de 0 a 1: uno menos Levenshtein sobre la más larga. */
    fun parecido(a: String, b: String): Float {
        if (a == b) return 1f
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        val mayor = maxOf(x.size, y.size)
        if (mayor == 0) return 1f
        val fila = IntArray(y.size + 1) { it }
        for (i in x.indices) {
            var diagonal = fila[0]
            fila[0] = i + 1
            for (j in y.indices) {
                val antes = fila[j + 1]
                fila[j + 1] = minOf(diagonal + (if (x[i] != y[j]) 1 else 0), fila[j] + 1, antes + 1)
                diagonal = antes
            }
        }
        return 1f - fila[y.size].toFloat() / mayor.toFloat()
    }

    /** Lo que suma casar [a] (de la letra) con [b] (oída), y lo que se parecen si casan. */
    private fun puntos(a: String, b: String): Pair<Float, Float> {
        val corta = largo(a) <= 2 || largo(b) <= 2
        val p = if (corta) (if (a == b) 1f else 0f) else parecido(a, b)
        return when {
            p < UMBRAL -> DISTINTAS to 0f
            corta -> CORTA to p
            else -> (1f + p) to p
        }
    }

    /**
     * **Las palabras oídas de las tandas del reconocedor**, con su tiempo (`palabras_oidas`): dentro
     * de cada tanda se reparte según lo largas que son ([Transcriptor.repartir]).
     */
    fun palabrasOidas(segmentos: List<Transcriptor.Segmento>): List<PalabraOida> =
        segmentos.flatMap { s ->
            Transcriptor.repartir(s.desdeMs, s.hastaMs, s.texto).flatMap { p -> normalizar(p.texto).map { PalabraOida(it, p.desdeMs.toLong()) } }
        }

    // --- El idioma de la letra -----------------------------------------------------------------

    /** Las palabras más corrientes de cada idioma (`CORRIENTES` del PC), en el mismo orden. */
    private val CORRIENTES: List<Pair<String, Set<String>>> = listOf(
        "es" to setOf(
            "que", "el", "los", "las", "del", "con", "por", "para", "pero", "como", "mas", "yo",
            "mi", "tu", "esta", "estoy", "eres", "soy", "una", "porque", "corazon", "quiero",
            "nada", "cuando", "donde", "nunca", "siempre", "noche", "amor", "y"
        ),
        "en" to setOf(
            "the", "and", "you", "your", "is", "are", "my", "me", "it", "that", "with", "for",
            "this", "love", "baby", "dont", "cant", "im", "what", "when", "never", "know", "just",
            "all", "be", "we", "oh", "will", "hope"
        ),
        "pt" to setOf(
            "nao", "voce", "meu", "minha", "com", "uma", "isso", "eu", "ela", "ele", "coracao",
            "saudade", "tudo", "agora", "muito", "quero", "sou", "estou", "nos", "do", "da"
        ),
        "fr" to setOf(
            "le", "les", "et", "je", "tu", "est", "pas", "moi", "toi", "mon", "ma", "avec", "pour",
            "qui", "dans", "cest", "amour", "nous", "vous", "suis", "jamais", "toujours"
        ),
        "it" to setOf(
            "il", "che", "non", "sono", "di", "per", "una", "mio", "mia", "con", "ti", "amore",
            "cuore", "sei", "come", "tutto", "questo", "anche", "sempre", "perche"
        ),
        "de" to setOf(
            "und", "ich", "du", "der", "die", "das", "nicht", "ist", "mein", "dich", "mich", "ein",
            "eine", "wir", "auf", "liebe", "herz", "immer", "nie", "bin"
        )
    )

    /**
     * **En qué idioma está una letra** (`idioma_de_la_letra`, 29-oct-2026), por sus palabras
     * corrientes: el código de Whisper (`es`, `en`…) o null si no está claro (menos de cuatro, o
     * dos idiomas igualados: el primero necesita una vez y media las del segundo). Así una canción
     * en inglés se oye en inglés aunque PixPin esté en castellano.
     */
    fun idiomaDeLaLetra(lineas: List<String>): String? {
        val palabras = lineas.flatMap { normalizar(it) }
        val cuentas = CORRIENTES.map { (codigo, lista) -> codigo to palabras.count { it in lista } }.sortedByDescending { it.second }
        val (codigo, n) = cuentas[0]
        val segundo = cuentas.getOrNull(1)?.second ?: 0
        return codigo.takeIf { n >= 4 && n * 2 >= segundo * 3 }
    }

    /**
     * **Con qué idiomas se oye una canción**: el de su letra si se nota ([idiomaDeLaLetra]), solo; si
     * no, el de Ajustes con su segundo idioma. Devuelve (primero, segundo o vacío).
     */
    fun idiomasParaOir(lineas: List<String>, deAjustes: String, segundoDeAjustes: String): Pair<String, String> =
        idiomaDeLaLetra(lineas)?.let { it to "" } ?: (deAjustes to segundoDeAjustes)

    // --- La alineación -------------------------------------------------------------------------

    /** Una palabra de la letra: de qué línea es y cuántas letras de su línea van delante. */
    private class DeLaLetra(val texto: String, val linea: Int, val letrasAntes: Int)

    /**
     * **Alinea las líneas de una letra con lo oído** (`alinear`). [duracionMs], si se sabe, es el
     * tope de las líneas del final. Null si ninguna línea se pudo anclar (no se entendió nada de la
     * letra, o era otra canción).
     */
    fun alinear(lineas: List<String>, oidas: List<PalabraOida>, duracionMs: Long? = null): Alineado? {
        if (lineas.isEmpty() || oidas.isEmpty()) return null
        val n = lineas.size
        val letra = ArrayList<DeLaLetra>()
        val letras = IntArray(n)
        val palabrasDe = IntArray(n)
        for ((i, l) in lineas.withIndex()) {
            val palabras = if (esRotulo(l)) emptyList() else normalizar(l)
            var antes = 0
            palabrasDe[i] = palabras.size
            for (p in palabras) {
                letra += DeLaLetra(p, i, antes)
                antes += largo(p) + 1
            }
            letras[i] = antes
        }
        if (letra.isEmpty() || letra.size.toLong() * oidas.size.toLong() > CELDAS_MAX) return null
        val parejas = needlemanWunsch(letra, oidas)

        // Cada línea: lo que se parecen sus palabras casadas y la primera.
        val suma = FloatArray(n)
        val casadas = IntArray(n)
        val primera = arrayOfNulls<Pair<Int, Long>>(n)
        val ultima = arrayOfNulls<Pair<Int, Long>>(n)
        for ((i, j, p) in parejas) {
            val w = letra[i]
            suma[w.linea] += p
            casadas[w.linea] += 1
            val aqui = w.letrasAntes to oidas[j].ms
            if (primera[w.linea] == null) primera[w.linea] = aqui
            ultima[w.linea] = aqui
        }
        val confianza = FloatArray(n) { i -> if (palabrasDe[i] == 0) 0f else (suma[i] / palabrasDe[i].toFloat()).coerceIn(0f, 1f) }
        val confianzaDeAncla = confianza.copyOf()
        fun esAncla(i: Int): Boolean {
            val necesita = minOf(palabrasDe[i], 2)
            return palabrasDe[i] > 0 && casadas[i] >= necesita && confianzaDeAncla[i] >= CONFIANZA_DE_ANCLA
        }

        // El ritmo de esta canción (ms por letra): entre palabras casadas de una misma línea y entre
        // dos líneas seguidas que empiezan casando (eso cuenta también el respiro entre líneas).
        val medidas = ArrayList<Double>()
        for (i in 0 until n) {
            val a = primera[i] ?: continue
            val b = ultima[i] ?: continue
            if (b.first > a.first && b.second > a.second) medidas += (b.second - a.second).toDouble() / (b.first - a.first).toDouble()
        }
        for (i in 1 until n) {
            val a = primera[i - 1] ?: continue
            val b = primera[i] ?: continue
            if (a.first != 0 || b.first != 0) continue
            val dura = b.second - a.second
            if (esAncla(i - 1) && esAncla(i) && dura > 0 && dura < HUECO_MS && letras[i - 1] > 0) medidas += dura.toDouble() / letras[i - 1].toDouble()
        }
        val ritmo = (mediana(medidas) ?: RITMO_POR_OMISION).coerceIn(RITMO_MIN, RITMO_MAX)

        // Las anclas: el minuto de su primera palabra casada, corrido atrás lo de las de delante.
        val tiempos = arrayOfNulls<Long>(n)
        for (i in 0 until n) {
            val p = primera[i] ?: continue
            if (esAncla(i)) tiempos[i] = p.second - (p.first.toDouble() * ritmo).toLong()
        }
        // Una línea repetida casa con cualquiera de sus dos veces: se queda con la que toca.
        val palabrasDeCada: List<List<String>> = (0 until n).map { i -> letra.filter { it.linea == i }.map { it.texto } }
        gemelas(tiempos, confianza, palabrasDeCada, letras, ritmo)
        // Nunca antes que el ancla de antes.
        var anterior = 0L
        for (i in 0 until n) {
            val t = tiempos[i] ?: continue
            val v = maxOf(t, anterior).coerceAtLeast(0)
            tiempos[i] = v
            anterior = v
        }
        val anclas = (0 until n).filter { tiempos[it] != null }
        val primerAncla = anclas.firstOrNull() ?: return null
        val ultimaAncla = anclas.last()

        // Lo que «pesa» cada línea al repartir: sus letras, y al menos una. Los rótulos no pesan.
        val peso = DoubleArray(n) { i -> if (palabrasDe[i] == 0) 0.0 else maxOf(letras[i], 1).toDouble() }
        val ms = LongArray(n) { tiempos[it] ?: 0L }
        val voz = Voz.de(oidas)
        // Antes de la primera ancla: sobre lo cantado antes de ella; si no se oyó nada, hacia atrás
        // al ritmo, sin bajar de lo repartido desde el principio de la canción.
        run {
            val tb = ms[primerAncla].toDouble()
            var total = 0.0
            for (k in 0 until primerAncla) total += peso[k]
            // Solo lo cantado desde donde podrían empezar estas líneas cantadas muy despacio.
            val desde = maxOf(tb - total * ritmo * LENTO, 0.0).toLong()
            val cantado = voz.dentro(desde, ms[primerAncla]).toDouble()
            var detras = 0.0
            for (j in primerAncla - 1 downTo 0) {
                detras += peso[j]
                val atras = tb - detras * ritmo
                val repartido = if (total > 0.0) tb * (total - detras) / total else tb
                val sobreLaVoz = if (cantado > 0.0 && total > 0.0) voz.retroceder(ms[primerAncla], (cantado * detras / total).toLong()) else null
                val v = if (sobreLaVoz != null) maxOf(sobreLaVoz, desde).toDouble() else maxOf(atras, repartido)
                ms[j] = maxOf(v, 0.0).toLong()
            }
        }
        // Entre dos anclas: por letras sobre lo cantado entre ellas; si no se oyó nada, sin ir más
        // despacio que el ritmo (el hueco instrumental se queda al final, antes del ancla).
        for (k in 0 until anclas.size - 1) {
            val a = anclas[k]
            val b = anclas[k + 1]
            if (b == a + 1) continue
            val ta = ms[a].toDouble()
            val tb = ms[b].toDouble()
            var total = 0.0
            for (x in a until b) total += peso[x]
            val cantado = voz.dentro(ms[a], ms[b]).toDouble()
            var hecho = 0.0
            for (j in a + 1 until b) {
                hecho += peso[j - 1]
                val repartido = if (total > 0.0) ta + (tb - ta) * hecho / total else ta
                val sobreLaVoz = if (cantado > 0.0 && total > 0.0) voz.avanzar(ms[a], (cantado * hecho / total).toLong()) else null
                ms[j] = (if (sobreLaVoz != null) minOf(sobreLaVoz.toDouble(), tb) else minOf(repartido, ta + hecho * ritmo)).toLong()
            }
        }
        // Detrás de la última: sobre lo cantado hasta lo último que se oye; si no, al ritmo. Nunca
        // pasado el final de la canción.
        run {
            val ta = ms[ultimaAncla].toDouble()
            var total = 0.0
            for (x in ultimaAncla until n) total += peso[x]
            val fin = duracionMs?.toDouble()?.takeIf { it > ta }
            val vozFin = voz.fin()
            val hastaMs = when {
                vozFin != null && duracionMs != null -> minOf(vozFin, duracionMs)
                vozFin != null -> vozFin
                else -> ms[ultimaAncla]
            }
            val cantado = voz.dentro(ms[ultimaAncla], hastaMs).toDouble()
            var hecho = 0.0
            for (j in ultimaAncla + 1 until n) {
                hecho += peso[j - 1]
                val alRitmo = ta + hecho * ritmo
                val sobreLaVoz = if (cantado > 0.0 && total > 0.0) voz.avanzar(ms[ultimaAncla], (cantado * hecho / total).toLong()) else null
                val t = sobreLaVoz?.toDouble() ?: alRitmo
                ms[j] = (if (fin != null && total > 0.0) minOf(t, ta + (fin - ta) * hecho / total) else t).toLong()
            }
        }
        // Un rótulo va con la línea que lo sigue (o con la de antes si es el último).
        for (i in n - 1 downTo 0) {
            if (palabrasDe[i] == 0) ms[i] = when {
                i + 1 < n -> ms[i + 1]
                i > 0 -> ms[i - 1]
                else -> 0L
            }
        }
        // Y en orden, por si acaso.
        for (i in 1 until n) ms[i] = maxOf(ms[i], ms[i - 1])
        return Alineado((0 until n).map { i ->
            LineaAlineada(ms[i], if (tiempos[i] != null) confianza[i] else 0f, tiempos[i] != null)
        })
    }

    /**
     * **Las anclas de una línea repetida, en la vez que toca** (`gemelas`). Para cada ancla con
     * gemelas sin ancla (las mismas palabras) entre las anclas de antes y de después, dónde «tocaría»
     * cada una al ritmo desde esas anclas; si una gemela cae mucho más cerca del minuto oído que la
     * línea que casó, el ancla pasa a la gemela.
     */
    private fun gemelas(tiempos: Array<Long?>, confianza: FloatArray, palabras: List<List<String>>, letras: IntArray, ritmo: Double) {
        val n = tiempos.size
        for (i in 0 until n) {
            val t = tiempos[i] ?: continue
            if (palabras[i].isEmpty()) continue
            val antes = (i - 1 downTo 0).firstOrNull { tiempos[it] != null }
            val despues = (i + 1 until n).firstOrNull { tiempos[it] != null }
            val desde = antes?.let { it + 1 } ?: 0
            val hasta = despues ?: n
            fun suma(de: Int, a: Int): Int { var s = 0; for (k in de until a) s += letras[k]; return s }
            fun toca(c: Int): Double? {
                val porAntes = antes?.let { a -> (tiempos[a] ?: 0L).toDouble() + suma(a, c).toDouble() * ritmo }
                val porDespues = despues?.let { d -> (tiempos[d] ?: 0L).toDouble() - suma(c, d).toDouble() * ritmo }
                return if (porAntes != null && porDespues != null) (porAntes + porDespues) / 2.0 else porAntes ?: porDespues
            }
            val propio = toca(i)?.let { Math.abs(it - t.toDouble()) } ?: continue
            var mejor: Pair<Int, Double>? = null
            for (c in desde until hasta) {
                if (c == i || tiempos[c] != null || palabras[c] != palabras[i]) continue
                val e = toca(c) ?: continue
                val error = Math.abs(e - t.toDouble())
                if (mejor == null || error < mejor.second) mejor = c to error
            }
            val m = mejor
            if (m != null && m.second < propio / 2.0 && propio - m.second >= GEMELA_MEJORA_MS) {
                tiempos[m.first] = tiempos[i]
                tiempos[i] = null
                confianza[m.first] = confianza[i]
                confianza[i] = 0f
            }
        }
    }

    private fun mediana(v: MutableList<Double>): Double? {
        if (v.isEmpty()) return null
        v.sort()
        return v[v.size / 2]
    }

    /**
     * El camino de Needleman–Wunsch entre la letra y lo oído: las parejas `(palabra de la letra,
     * palabra oída, parecido)` que casan, en orden. Lo oído antes de la primera pareja y después de
     * la última no cuesta. Los mismos empates que el PC: diagonal, luego arriba, luego izquierda.
     */
    private fun needlemanWunsch(letra: List<DeLaLetra>, oidas: List<PalabraOida>): List<Triple<Int, Int, Float>> {
        val diagonalD: Byte = 0
        val arribaD: Byte = 1 // palabra de la letra suelta
        val izquierdaD: Byte = 2 // palabra oída suelta
        val n = letra.size
        val m = oidas.size
        val ancho = m + 1
        val nota = FloatArray((n + 1) * ancho)
        val desde = ByteArray((n + 1) * ancho) { izquierdaD }
        for (i in 1..n) {
            nota[i * ancho] = -(i.toFloat()) * SUELTA_EN_LA_LETRA
            desde[i * ancho] = arribaD
        }
        for (i in 1..n) {
            val a = letra[i - 1].texto
            for (j in 1..m) {
                val p = puntos(a, oidas[j - 1].texto).first
                val diagonal = nota[(i - 1) * ancho + j - 1] + p
                val arriba = nota[(i - 1) * ancho + j] - SUELTA_EN_LA_LETRA
                // Al final de la letra, lo oído que sobra no cuesta.
                val izquierda = nota[i * ancho + j - 1] - if (i == n) 0f else SUELTA_EN_LO_OIDO
                if (diagonal >= arriba && diagonal >= izquierda) { nota[i * ancho + j] = diagonal; desde[i * ancho + j] = diagonalD }
                else if (arriba >= izquierda) { nota[i * ancho + j] = arriba; desde[i * ancho + j] = arribaD }
                else { nota[i * ancho + j] = izquierda; desde[i * ancho + j] = izquierdaD }
            }
        }
        val parejas = ArrayList<Triple<Int, Int, Float>>()
        var i = n
        var j = m
        while (i > 0) {
            when (if (j == 0) arribaD else desde[i * ancho + j]) {
                diagonalD -> {
                    val p = puntos(letra[i - 1].texto, oidas[j - 1].texto).second
                    if (p > 0f) parejas += Triple(i - 1, j - 1, p)
                    i--; j--
                }
                arribaD -> i--
                else -> j--
            }
        }
        parejas.reverse()
        return parejas
    }

    // --- Lo que se guarda ----------------------------------------------------------------------

    /**
     * **Las líneas de una letra pegada**, las mismas que sincronizar a toques
     * ([SincronizadorDeLetra.de]): un renglón por línea, sin los tiempos que ya trajera (se ponen
     * nuevos) ni los renglones en blanco (no se cantan).
     */
    fun lineasDeLaLetra(pegada: String): List<String> = SincronizadorDeLetra.de(LetraDeCancion.leer(pegada)).textos

    /** **La letra pegada, con sus minutos y cuántas líneas se oyeron**, o null si no se pudo. */
    class ConTiempos(val texto: String, val alineado: Alineado)

    /**
     * **La letra pegada con tiempos**, lista para [Mensaje.transcripcion]: `[m:ss] línea` y un renglón
     * en blanco entre líneas, con el texto pegado intacto. Null si no se pudo alinear.
     */
    fun conTiempos(pegada: String, trozos: List<Transcriptor.Segmento>, duracionMs: Long? = null): ConTiempos? {
        val lineas = lineasDeLaLetra(pegada)
        if (lineas.isEmpty()) return null
        val a = alinear(lineas, palabrasOidas(trozos), duracionMs) ?: return null
        return ConTiempos(LetraDeCancion.aGuardar(a.letra(lineas)), a)
    }

    /** Lo mismo, solo el texto. */
    fun letraConTiempos(pegada: String, trozos: List<Transcriptor.Segmento>, duracionMs: Int? = null): String? =
        conTiempos(pegada, trozos, duracionMs?.toLong())?.texto

    /** Lo largo de una línea de letra hecha de lo oído, sin letra pegada: lo que se canta de una vez. */
    const val LINEA_OIDA_MS = 6_000
    private const val LINEA_OIDA_MINIMA_MS = 2_000

    /**
     * **Sin letra pegada, lo oído como letra con tiempos** (solo Android): frases de hasta
     * [LINEA_OIDA_MS], partidas donde mejor se lea ([Transcriptor.agrupar]). Null si no se oyó nada.
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
