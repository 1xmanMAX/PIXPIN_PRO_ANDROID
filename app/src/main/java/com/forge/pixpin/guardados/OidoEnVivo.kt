package com.forge.pixpin.guardados

/**
 * **Escuchar en vivo mientras se graba una reunión, lo puro** (29-oct-2026; porte de
 * `crates/pixpin-voz/src/en_vivo.rs` del PC, `50e87978`): lo que oye la grabación se corta en frases para
 * pasarlo a texto **frase a frase**, de fondo, sin sonido ni modelo en las pruebas.
 *
 * - [Frasero]: junta los bloques del PCM de la grabación (los de su toma, [com.forge.pixpin.audio.Grabador])
 *   y **corta una frase en cuanto se calla** quien habla ([Medida.silencioMs] sin voz después de algo
 *   dicho), o en el punto más callado de sus últimos [BUSCAR_CORTE_MS] si alguien habla sin parar. Cada
 *   frase va en mono y a 16 kHz, con su número, el milisegundo de la grabación en que empieza, el de
 *   cuando se dejó de hablar y cuánto silencio llevaba ya al cortarse.
 *   [PARA_WHISPER]: trozos de 5 s o más (Whisper cuesta casi lo mismo con 2 que con 30 s), cortados en un
 *   silencio, sin solaparse.
 * - [Cola]: **acotada, y tira lo más viejo**. Si el texto va por detrás, se pierde texto en vivo, nunca
 *   grabación.
 * - [Freno]: **si una frase se pasa a texto o se salta**. Se salta lo que no tiene voz, todo mientras el
 *   teléfono va justo (en el PC, el procesador ≥ 85 %; aquí, batería < 20 % o el aparato caliente) y, si
 *   la última tardó más del 80 % de lo que dura, la siguiente.
 */
object OidoEnVivo {

    /** Las muestras por segundo de lo que se pasa a texto. */
    const val HERCIOS = 16_000
    const val BUSCAR_CORTE_MS = 2_000L
    const val HUECO_MAXIMO_MS = 1_000L
    const val ANTES_DE_LA_VOZ_MS = 300L
    const val DESPUES_DE_LA_VOZ_MS = 200L
    const val SILENCIO_LARGO_MS = 1_500L
    /** Cuántas frases esperan como mucho a pasar a texto. */
    const val COLA = 3
    /** Si una frase tardó más que esta parte de lo que dura, se descansa una. */
    const val TARDANZA_MAXIMA = 0.8f
    /** Batería por debajo de esto (0-100): no se pasa a texto. */
    const val BATERIA_MINIMA = 20
    private const val RMS_VOZ = 300.0
    private const val PARTE_CON_VOZ = 0.1

    /** **Cuándo se corta una frase.** */
    data class Medida(val vozMinimaMs: Long, val largoMinimoMs: Long, val silencioMs: Long, val maximoMs: Long)

    /** Frases de verdad, cortadas en cuanto se calla medio segundo (el reconocedor de Windows en el PC). */
    val PARA_FRASES = Medida(500, 0, 450, 8_000)

    /** Para Whisper: trozos de 5 s o más, y si no, en cuanto se acaba el turno. */
    val PARA_WHISPER = Medida(800, 5_000, 500, 12_000)

    /** Una frase lista para pasar a texto: mono, 16 kHz. */
    class Frase(val n: Long, val ms: Long, val finVozMs: Long, val calladoAlFinalMs: Long, val muestras: ShortArray) {
        /** Lo que dura, en ms. */
        fun duracionMs(): Long = muestras.size.toLong() * 1000 / HERCIOS
    }

    private fun msDe(marcos: Long, hz: Int): Long = marcos * 1000 / hz.coerceAtLeast(1)
    private fun marcosDe(ms: Long, hz: Int): Int = (ms.coerceAtLeast(0) * hz / 1000).toInt()

    /** **Corta el PCM de la grabación en frases.** */
    class Frasero(private var medida: Medida) {
        internal var buf = ShortArray(0)
        internal var largo = 0
        private var hz = 0
        private var inicio = 0L
        private var esperado: Long? = null
        private var n = 0L
        private var mirado = 0
        private var conVoz = 0
        private var ultimaVoz: Int? = null

        fun ponerMedida(m: Medida) { medida = m }

        private fun anadir(v: ShortArray, desde: Int, cuantos: Int) {
            if (largo + cuantos > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, largo + cuantos, 1024))
            System.arraycopy(v, desde, buf, largo, cuantos)
            largo += cuantos
        }

        private fun ceros(cuantos: Int) {
            if (largo + cuantos > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, largo + cuantos, 1024))
            java.util.Arrays.fill(buf, largo, largo + cuantos, 0)
            largo += cuantos
        }

        /** **Mete un bloque** ([posicion] en marcos, mono). Devuelve las frases que se cerraron. */
        fun meter(posicion: Long, muestreo: Int, muestras: ShortArray, cuantas: Int = muestras.size): List<Frase> {
            val salida = ArrayList<Frase>()
            if (muestreo <= 0 || cuantas <= 0) return salida
            if (hz != muestreo) {
                cerrar()?.let { salida += it }
                hz = muestreo
                vaciar()
                esperado = null
            }
            var pos = posicion
            var desde = 0
            val e = esperado
            if (e == null) {
                vaciar()
                inicio = pos
            } else if (pos > e) {
                val hueco = pos - e
                if (msDe(hueco, hz) <= HUECO_MAXIMO_MS) ceros(hueco.toInt())
                else {
                    cerrar()?.let { salida += it }
                    vaciar()
                    inicio = pos
                }
            } else if (pos < e) {
                // Un bloque que se solapa con lo ya visto: lo nuevo, si algo.
                val saltar = (e - pos).toInt()
                if (saltar >= cuantas) return salida
                desde = saltar
                pos = e
            }
            anadir(muestras, desde, cuantas - desde)
            esperado = pos + (cuantas - desde)
            mirar(salida)
            return salida
        }

        private fun mirar(salida: MutableList<Frase>) {
            val paso = (hz / 50).coerceAtLeast(1)
            val m = medida
            while (mirado + paso <= largo) {
                if (rms(buf, mirado, mirado + paso) >= RMS_VOZ) {
                    if (ultimaVoz == null && conVoz == 0) {
                        // Empieza a hablar: de lo callado de antes, solo un poco.
                        tirar((mirado - marcosDe(ANTES_DE_LA_VOZ_MS, hz)).coerceAtLeast(0))
                    }
                    conVoz += paso
                    ultimaVoz = mirado + paso
                }
                mirado += paso
                val fin = ultimaVoz
                if (fin == null) {
                    // Aún nadie habla: solo se guarda un poco de silencio.
                    val guardar = marcosDe(ANTES_DE_LA_VOZ_MS, hz)
                    if (mirado > guardar + marcosDe(1_000, hz)) tirar(mirado - guardar)
                    continue
                }
                val callado = msDe((mirado - fin).toLong(), hz)
                val voz = msDe(conVoz.toLong(), hz)
                val dura = msDe(mirado.toLong(), hz)
                if (voz >= m.vozMinimaMs && callado >= m.silencioMs && (dura >= m.largoMinimoMs || callado >= SILENCIO_LARGO_MS)) {
                    // Se calló: la frase, con un poco de cola.
                    val corte = minOf(fin + marcosDe(DESPUES_DE_LA_VOZ_MS, hz), mirado)
                    salida += sacar(corte, fin)
                } else if (voz < m.vozMinimaMs && callado >= SILENCIO_LARGO_MS) {
                    // Una tos y silencio: nada que mandar.
                    ultimaVoz = null
                    conVoz = 0
                } else if (dura >= m.maximoMs) {
                    // No para de hablar: en lo más callado del final.
                    val desde = (mirado - marcosDe(BUSCAR_CORTE_MS, hz)).coerceAtLeast(0)
                    val corte = maxOf(puntoCallado(buf, desde, mirado, hz), paso)
                    salida += sacar(corte, corte)
                }
            }
        }

        private fun quitarDelPrincipio(marcos0: Int) {
            val marcos = minOf(marcos0, largo)
            System.arraycopy(buf, marcos, buf, 0, largo - marcos)
            largo -= marcos
        }

        /** Tira los primeros [marcos0] (silencio). */
        private fun tirar(marcos0: Int) {
            val marcos = minOf(marcos0, largo)
            quitarDelPrincipio(marcos)
            inicio += marcos
            mirado = (mirado - marcos).coerceAtLeast(0)
            ultimaVoz = ultimaVoz?.let { (it - marcos).coerceAtLeast(0) }
        }

        private fun vaciar() {
            largo = 0
            mirado = 0
            conVoz = 0
            ultimaVoz = null
        }

        private fun sacar(marcos0: Int, fin0: Int): Frase {
            val marcos = minOf(marcos0, largo)
            val fin = minOf(fin0, largo)
            val f = Frase(
                n = n, ms = msDe(inicio, hz), finVozMs = msDe(inicio + fin, hz),
                calladoAlFinalMs = msDe((largo - fin).toLong(), hz), muestras = a16k(buf, 0, marcos, hz)
            )
            n++
            quitarDelPrincipio(marcos)
            inicio += marcos
            mirado = 0
            conVoz = 0
            ultimaVoz = null
            return f
        }

        private fun cerrar(): Frase? {
            if (hz == 0 || msDe(conVoz.toLong(), hz) < medida.vozMinimaMs) {
                vaciar()
                return null
            }
            return sacar(largo, ultimaVoz ?: largo)
        }

        /** **Al acabar la grabación**: lo último, si tiene voz bastante. */
        fun acabar(): Frase? {
            val f = cerrar()
            esperado = null
            return f
        }
    }

    /** **El punto más callado** de `pcm[desde, hasta)`, medido cada 20 ms. Sin sitio, [hasta]. */
    fun puntoCallado(pcm: ShortArray, desde: Int, hasta0: Int, hz: Int): Int {
        val hasta = minOf(hasta0, pcm.size)
        val paso = (hz / 50).coerceAtLeast(1)
        var mejor = hasta
        var energia = Double.MAX_VALUE
        var i = desde
        while (i + paso <= hasta) {
            val e = rms(pcm, i, i + paso)
            if (e < energia) { mejor = i; energia = e }
            i += paso
        }
        return mejor
    }

    private fun rms(v: ShortArray, a: Int, b: Int): Double {
        if (b <= a) return 0.0
        var s = 0.0
        for (i in a until b) { val x = v[i].toDouble(); s += x * x }
        return Math.sqrt(s / (b - a))
    }

    /** **De [hz] a 16 kHz**, mono: cada muestra de salida es la media de las de entrada que le tocan. */
    fun a16k(pcm: ShortArray, desde: Int = 0, hasta: Int = pcm.size, hz: Int): ShortArray {
        val n0 = hasta - desde
        if (hz == HERCIOS || hz == 0) return pcm.copyOfRange(desde, hasta)
        if (n0 <= 0) return ShortArray(0)
        val paso = hz.toDouble() / HERCIOS
        val n = Math.floor(n0 / paso).toInt()
        val salida = ShortArray(n)
        for (k in 0 until n) {
            val a = Math.floor(k * paso).toInt()
            val b = Math.floor((k + 1) * paso).toInt().coerceIn(a + 1, n0)
            val a2 = minOf(a, n0 - 1)
            var s = 0L
            for (i in a2 until b) s += pcm[desde + i]
            salida[k] = (s / (b - a2).coerceAtLeast(1)).toInt().toShort()
        }
        return salida
    }

    /** Si un audio (16 kHz) tiene voz: al menos un 10 % de trozos de 20 ms por encima de un umbral fijo. */
    fun tieneVoz(pcm: ShortArray): Boolean {
        val paso = HERCIOS / 50
        val trozos = pcm.size / paso
        if (trozos == 0) return false
        var conVoz = 0
        for (t in 0 until trozos) if (rms(pcm, t * paso, (t + 1) * paso) >= RMS_VOZ) conVoz++
        return conVoz >= PARTE_CON_VOZ * trozos
    }

    /** **Una cola acotada que tira lo más viejo.** */
    class Cola<T>(capacidad: Int) {
        private val capacidad = capacidad.coerceAtLeast(1)
        private val v = ArrayDeque<T>()
        var tirados = 0L
            private set

        /** Mete al final; si ya estaba llena, tira el primero y lo devuelve. */
        @Synchronized
        fun meter(x: T): T? {
            val tirado = if (v.size >= capacidad) { tirados++; v.removeFirst() } else null
            v.addLast(x)
            return tirado
        }

        @Synchronized
        fun sacar(): T? = v.removeFirstOrNull()

        @get:Synchronized
        val tamano: Int get() = v.size
    }

    /** Qué se hace con una frase. */
    enum class Paso { PASAR, SALTAR_SILENCIO, SALTAR_APARATO, SALTAR_RETRASO }

    /** **Si una frase se pasa a texto.** [justo]: el teléfono va justo (batería baja o caliente). */
    class Freno {
        private var descansar = false

        fun decidir(muestras: ShortArray, justo: Boolean): Paso {
            if (!tieneVoz(muestras)) return Paso.SALTAR_SILENCIO
            if (justo) return Paso.SALTAR_APARATO
            if (descansar) { descansar = false; return Paso.SALTAR_RETRASO }
            return Paso.PASAR
        }

        /** Lo que tardó la última frase que se pasó a texto. */
        fun tardo(fraseMs: Long, tardanzaMs: Long) {
            descansar = tardanzaMs > TARDANZA_MAXIMA * fraseMs
        }
    }
}
