package com.forge.pixpin.sincro

/**
 * **El reloj de una grabación** (28-oct-2026, `Reloj` de `crates/pixpin-sincro/src/camara.rs` del
 * PC, copiado número a número): cuándo empezó y sus pausas, en el reloj de quien lo lleva. Pasa una
 * hora a un milisegundo de la grabación ([msEn]): lo pausado no cuenta; antes de empezar, 0; tras
 * acabar, lo que duró.
 *
 * En el teléfono lo lleva [RelojDelMovil] con `SystemClock.elapsedRealtime()`, que no cambia si
 * el usuario toca la hora. Lógica pura: lo prueba `RelojDeGrabacionTest` con los mismos números
 * que las pruebas del PC.
 */
class RelojDeGrabacion(var desde: Long = 0) {

    /** Una pausa: [hasta] `null` mientras sigue. */
    data class Pausa(val desde: Long, var hasta: Long? = null)

    val pausas = ArrayList<Pausa>()
    /** Cuándo acabó. */
    var fin: Long? = null
        private set

    val enPausa: Boolean get() = pausas.isNotEmpty() && pausas.last().hasta == null
    val acabado: Boolean get() = fin != null

    fun pausar(ahora: Long) {
        if (!enPausa && !acabado) pausas += Pausa(ahora)
    }

    fun seguir(ahora: Long) {
        val p = pausas.lastOrNull() ?: return
        if (p.hasta == null) p.hasta = maxOf(ahora, p.desde)
    }

    fun acabar(ahora: Long) {
        if (fin != null) return
        seguir(ahora)
        fin = maxOf(ahora, desde)
    }

    /** Lo que estuvo en pausa antes de [t]. */
    private fun pausadoHasta(t: Long): Long = pausas.sumOf { p -> (minOf(p.hasta ?: t, t) - p.desde).coerceAtLeast(0) }

    /** **El milisegundo de la grabación en la hora [t]**: lo que corrió menos lo pausado. */
    fun msEn(t: Long): Long {
        val hasta = fin?.let { minOf(t, it) } ?: t
        if (hasta <= desde) return 0
        return (hasta - desde - pausadoHasta(hasta)).coerceAtLeast(0)
    }

    /** Lo que lleva (o lo que duró). */
    fun duracion(ahora: Long): Long = msEn(ahora)

    /**
     * **Lo pone de acuerdo con quien manda** (el anuncio del PC): en [ahora] va por [ms] y está o
     * no en pausa. Una diferencia de menos de [tolerancia] se deja.
     */
    fun seguirA(ahora: Long, ms: Long, pausa: Boolean, tolerancia: Long) {
        if (acabado) return
        if (pausa) pausar(ahora) else seguir(ahora)
        val mio = msEn(ahora)
        if (kotlin.math.abs(mio - ms) > tolerancia) desde += mio - ms.coerceAtLeast(0)
    }

    /** Las pausas como viajan en el anuncio: `[desde, hasta]`, `hasta` 0 si sigue. */
    fun pausasAlCable(): List<List<Long>> = pausas.map { listOf(it.desde, it.hasta ?: 0L) }

    companion object {
        /** Empieza a contar en [ahora], que ya va por [msYa]. */
        fun empezar(ahora: Long, msYa: Long): RelojDeGrabacion = RelojDeGrabacion(ahora - msYa.coerceAtLeast(0))
    }
}

/**
 * **El reloj de la grabación del PC, llevado en el móvil** (§3 de la guía del PC, `RelojDelMovil`
 * de `tests/movil_como_camara.rs`): lo que dijo el último anuncio (en qué minuto iba y si estaba en
 * pausa) y lo que pasó desde entonces en el reloj de aquí. En pausa, congelado; al acabar, lo que
 * duró. Cada foto lleva el [ms] del momento del disparo; un clip, el de cuando empezó.
 *
 * [ahora] es un reloj que solo avanza (`SystemClock.elapsedRealtime()` en el teléfono; en las
 * pruebas, el que se quiera). [ida]: la mitad de lo que tardó la pregunta en volver; el anuncio se
 * escribió entonces, así que, corriendo, ya va por [Anuncio.msActual] + [ida].
 */
class RelojDelMovil private constructor(val grabacion: String, private val reloj: RelojDeGrabacion) {

    val enPausa: Boolean get() = reloj.enPausa
    val acabado: Boolean get() = reloj.acabado

    /** El milisegundo de la grabación ahora. */
    fun ms(ahora: Long): Long = reloj.msEn(ahora)

    /**
     * **Al día con otro anuncio** de la misma grabación. Si es de otra, o ya acabó, un reloj nuevo
     * ([de]); si no, se corrige sin saltos pequeños ([CamaraRemota.TOLERANCIA_MS]).
     */
    fun alDia(a: CamaraRemota.Anuncio, ahora: Long, ida: Long = 0): RelojDelMovil {
        if (a.grabacion != grabacion || !a.activa || acabado) return de(a, ahora, ida)
        reloj.seguirA(ahora, msDe(a, ida), a.enPausa, CamaraRemota.TOLERANCIA_MS)
        return this
    }

    companion object {
        private fun msDe(a: CamaraRemota.Anuncio, ida: Long): Long =
            a.msActual + if (a.activa && !a.enPausa) ida.coerceIn(0, 5_000) else 0

        /** El reloj de lo que dice [a], recibido en [ahora]. */
        fun de(a: CamaraRemota.Anuncio, ahora: Long, ida: Long = 0): RelojDelMovil {
            if (!a.activa) {
                // Ya acabó: congelado en lo que duró.
                val r = RelojDeGrabacion.empezar(ahora, if (a.duracion > 0) a.duracion else a.msActual)
                r.acabar(ahora)
                return RelojDelMovil(a.grabacion, r)
            }
            val r = RelojDeGrabacion.empezar(ahora, msDe(a, ida))
            if (a.enPausa) r.pausar(ahora)
            return RelojDelMovil(a.grabacion, r)
        }

        /** Lo que enseña la pantalla de cámara arriba: «● 12:34 grabando en el PC». */
        fun rotulo(ms: Long, enPausa: Boolean, acabado: Boolean): String = when {
            acabado -> "■ ${CamaraRemota.minuto(ms)} · la grabación del PC terminó"
            enPausa -> "‖ ${CamaraRemota.minuto(ms)} en pausa"
            else -> "● ${CamaraRemota.minuto(ms)} grabando en el PC"
        }
    }
}
