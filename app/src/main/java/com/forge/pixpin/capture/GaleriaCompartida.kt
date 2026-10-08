package com.forge.pixpin.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **La galería de capturas, la misma en todos los aparatos del grupo** (8-oct-2026). El usuario:
 * «quiero que se sincronice la galería con sus tiempos de desaparición y todo completo».
 *
 * Cada captura tiene una [Entrada] que viaja al sincronizar: cuándo se hizo, **cuándo se va**
 * (la fecha exacta, no «a los N días»: así se va a la vez en todos aunque llegara a cada uno otro
 * día), si se conserva y si se borró. Lo cambiado más tarde gana ([juntar]).
 *
 * Tres decisiones:
 *
 * - **Caducar no es borrar.** Cada aparato barre lo suyo con la misma fecha ([CaducidadDeCapturas]
 *   con `fijadas`), así que no hace falta avisar a nadie. Solo lo que el usuario quita **antes de
 *   tiempo** deja una marca de borrada que se lleva la captura de los demás (a su papelera).
 * - **Lo que no está porque no se pudo ver no cuenta.** Solo se marca como borrada una captura que
 *   este aparato llegó a tener ([Estado.tenia]) y ya no está; si Android no deja listar (null), no
 *   se toca nada.
 * - **El registro de siempre manda en la pantalla.** Lo acordado se escribe en el registro de
 *   caducidad ([aplicar]): la galería, el barrendero y el widget no necesitan saber nada de esto.
 *
 * Sin Android: se prueba en JUnit (`GaleriaCompartidaTest`, `GaleriaQueViajaTest`).
 */
object GaleriaCompartida {

    const val FICHERO = "galeria-compartida.json"

    @Serializable
    data class Entrada(
        val nombre: String,
        /** Cuándo se hizo, en ms UTC (en el aparato donde se hizo). */
        val cuando: Long,
        val bytes: Long = 0,
        val mime: String = "image/png",
        /** Cuándo se va, en ms UTC. null y no conservada: no se va (allí no caducaba nada). */
        val seVa: Long? = null,
        val conservada: Boolean = false,
        /** Quitada a mano antes de tiempo: se quita en todos. */
        val borrada: Boolean = false,
        /** Cuándo cambió por última vez algo de lo de arriba: lo más nuevo gana al juntar. */
        val cambiado: Long = 0,
        /** El aparato donde se hizo, solo para enseñarlo. */
        val de: String = ""
    ) {
        /** Ya pasó su fecha: cada aparato la barre solo, no hace falta pasarla. */
        fun caducada(ahora: Long) = !borrada && !conservada && seVa != null && seVa <= ahora
        /** Viva y con sentido tenerla: hay que traerla o mandarla si falta. */
        fun hayQueTenerla(ahora: Long) = !borrada && !caducada(ahora)
    }

    /** Lo de este aparato: las entradas (las que viajan) y lo que había aquí la última vez. */
    @Serializable
    data class Estado(
        val entradas: List<Entrada> = emptyList(),
        /** Las capturas que había en este aparato al ponerse al día: para saber qué se quitó. */
        val tenia: Set<String> = emptySet()
    ) {
        val porNombre: Map<String, Entrada> get() = entradas.associateBy { it.nombre }
    }

    /** Una captura que hay en este aparato ahora. */
    data class Local(val nombre: String, val cuando: Long, val bytes: Long, val mime: String)

    // ------------------------------------------------------------------- reglas

    /**
     * **Lo de este aparato, al día**: entradas nuevas para las capturas que aún no tenían, lo que
     * el usuario cambió aquí desde la última vez (conservar, «7 días más») y las que se quitaron a
     * mano. [locales] null = no se pudo listar: se devuelve igual.
     */
    fun alDia(e: Estado, locales: List<Local>?, r: CaducidadDeCapturas.Registro, dias: Int, ahora: Long, aparato: String): Estado {
        if (locales == null) return e
        val hay = locales.associateBy { it.nombre }
        val nuevas = LinkedHashMap(e.porNombre)
        for (c in locales) {
            val vieja = nuevas[c.nombre]
            val conservada = c.nombre in r.conservadas
            if (vieja == null) {
                nuevas[c.nombre] = Entrada(
                    c.nombre, c.cuando, c.bytes, c.mime,
                    seVa = if (conservada) null else CaducidadDeCapturas.seVaEl(r, c.nombre, c.cuando, dias),
                    conservada = conservada, cambiado = c.cuando, de = aparato
                )
                continue
            }
            if (vieja.borrada) continue   // se quitó en otro: la quitará [aTirar]
            // La fecha que rige aquí: la acordada (`fijadas`) o, si aún no se acordó, la regla con
            // sus prórrogas. Sin caducidad aquí (días ≤ 0) no se dice nada: eso es de este aparato.
            val efectiva = if (dias <= 0) vieja.seVa else CaducidadDeCapturas.seVaEl(r, c.nombre, c.cuando, dias)
            nuevas[c.nombre] = when {
                // Se conservó aquí.
                conservada && !vieja.conservada -> vieja.copy(conservada = true, seVa = null, cambiado = ahora)
                // «7 días más» aquí: la fecha se movió.
                !vieja.conservada && !conservada && efectiva != null && efectiva != vieja.seVa -> vieja.copy(seVa = efectiva, cambiado = ahora)
                else -> vieja
            }
        }
        // Las que tenía y ya no están: quitadas a mano, si no les tocaba irse.
        for (n in e.tenia) {
            if (n in hay) continue
            val v = nuevas[n] ?: continue
            if (v.borrada || v.caducada(ahora)) continue
            nuevas[n] = v.copy(borrada = true, cambiado = ahora)
        }
        return Estado(nuevas.values.toList(), hay.keys)
    }

    /**
     * **Junta lo de dos aparatos**, igual en los dos lados: por cada captura, la entrada cambiada
     * más tarde; a la misma hora, borrada antes que viva, conservada antes que no, y la fecha más
     * lejana. `cuando` y `de` salen de la que los tenga (son de quien la hizo).
     */
    fun juntar(a: Collection<Entrada>, b: Collection<Entrada>): List<Entrada> {
        val todas = (a.map { it.nombre } + b.map { it.nombre }).distinct()
        val pa = a.associateBy { it.nombre }
        val pb = b.associateBy { it.nombre }
        return todas.sorted().map { n ->
            val x = pa[n]; val y = pb[n]
            if (x == null || y == null) (x ?: y)!! else ganadora(x, y)
        }
    }

    private val orden = compareBy<Entrada>({ it.cambiado }, { it.borrada }, { it.conservada }, { it.seVa ?: Long.MAX_VALUE }, { it.bytes })

    private fun ganadora(x: Entrada, y: Entrada): Entrada = if (orden.compare(x, y) >= 0) x else y

    /**
     * **Lo acordado, en el registro de caducidad**: conservadas y fechas fijadas. Las borradas se
     * olvidan (su nombre queda libre).
     */
    fun aplicar(r: CaducidadDeCapturas.Registro, entradas: Collection<Entrada>): CaducidadDeCapturas.Registro {
        val borradas = entradas.filter { it.borrada }.map { it.nombre }.toSet()
        val conservadas = entradas.filter { !it.borrada && it.conservada }.map { it.nombre }
        val fijadas = entradas.filter { !it.borrada && !it.conservada && it.seVa != null }.associate { it.nombre to it.seVa!! }
        return r.copy(
            conservadas = r.conservadas - borradas + conservadas,
            prorrogadas = r.prorrogadas - borradas,
            fijadas = r.fijadas - borradas + fijadas
        )
    }

    /** Las capturas de aquí que se quitaron en otro aparato: a la papelera. */
    fun aTirar(entradas: Collection<Entrada>, aqui: Set<String>): List<String> =
        entradas.filter { it.borrada && it.nombre in aqui }.map { it.nombre }

    /** Las que me faltan y el otro tiene: hay que traerlas. */
    fun queTraer(entradas: Collection<Entrada>, mias: Set<String>, suyas: Set<String>, ahora: Long): List<Entrada> =
        entradas.filter { it.hayQueTenerla(ahora) && it.nombre !in mias && it.nombre in suyas }

    /** Las que tengo y al otro le faltan: hay que mandárselas. */
    fun queMandar(entradas: Collection<Entrada>, mias: Set<String>, suyas: Set<String>, ahora: Long): List<Entrada> =
        entradas.filter { it.hayQueTenerla(ahora) && it.nombre in mias && it.nombre !in suyas }

    // ------------------------------------------------------------------- fichero

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Las entradas como viajan. */
    fun entradasATexto(l: List<Entrada>): String = JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(Entrada.serializer()), l)
    fun entradasDeTexto(t: String): List<Entrada> = JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Entrada.serializer()), t)

    private val cerrojo = Any()

    fun leer(raiz: File): Estado = synchronized(cerrojo) {
        val f = File(raiz, FICHERO)
        if (!f.exists()) return Estado()
        runCatching { JSON.decodeFromString(Estado.serializer(), f.readText()) }.getOrElse {
            // No se entiende: se aparta y se empieza de nuevo (lo vivo vuelve a apuntarse solo).
            runCatching { f.renameTo(File(raiz, "$FICHERO.roto-${System.currentTimeMillis()}")) }
            Estado()
        }
    }

    /** Cambia el estado con el cerrojo cogido y lo escribe. */
    fun cambiar(raiz: File, f: (Estado) -> Estado): Estado = synchronized(cerrojo) {
        val nuevo = f(leer(raiz))
        raiz.mkdirs()
        val tmp = File(raiz, "$FICHERO.tmp")
        tmp.writeText(JSON.encodeToString(Estado.serializer(), nuevo))
        val ruta = File(raiz, FICHERO)
        if (!tmp.renameTo(ruta)) { ruta.delete(); check(tmp.renameTo(ruta)) { "no se pudo escribir $FICHERO" } }
        nuevo
    }
}
