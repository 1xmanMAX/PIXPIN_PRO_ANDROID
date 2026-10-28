package com.forge.pixpin.guardados

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * **Un solo reloj de pared** (28-oct-2026, «Grabar sin perder nada»; PC `4fec53d3` y
 * `docs/movil-como-camara.md` §12.1): el reloj del diario de la grabación
 * ([GrabacionAMedias.Reloj], con sus pausas y **sus huecos**) y el que va en la nota ([RelojDePared],
 * `resto.reloj`) son el mismo. Lo lleva la grabación (el diario es de [GrabacionAMedias] y
 * [DiarioEnMarcha]); aquí está el paso de uno al otro y la cuenta, para quien lo necesite:
 *
 * - [delDiario]: `RelojDeGrabacion::del_diario` del PC (lo que se pone en la nota al guardar y al
 *   recuperar una grabación cortada).
 * - [msHasta]: `RelojDeGrabacion::ms_hasta` (lo corrido sin las pausas ni los huecos); es también
 *   [GrabacionAMedias.Diario.msEn].
 * - [enJson]: como lo escribe el PC en el `resto` (con `serde_json::to_value`, un `BTreeMap`): **las
 *   claves en orden alfabético** y sin lo que vale lo de por omisión.
 * - [enCurso]: el de la grabación en marcha en este teléfono, al día en cada latido.
 */
object RelojDeLaGrabacion {

    /**
     * **El reloj de la grabación en marcha en este teléfono**, o null si no se graba. Lo pone al día el
     * diario en cada latido ([DiarioEnMarcha]); lo puede leer quien necesite pasar una hora a un minuto
     * mientras se graba (una foto que llega con su hora).
     */
    @Volatile
    var enCurso: RelojDePared? = null

    /**
     * **El del diario de la grabación**, de [aparato], visto por última vez en [visto] (el último
     * latido). Sin acabar, la ventana se corta en [visto] (`vistoEn`); acabada, en `fin`.
     */
    fun delDiario(r: GrabacionAMedias.Reloj, aparato: String, visto: Long, grabacion: String = ""): RelojDePared {
        fun tramos(v: List<GrabacionAMedias.Tramo>) = v.map { listOf(it.desde, it.hasta ?: 0L) }
        return RelojDePared(
            aparato = aparato,
            inicio = r.inicio,
            fin = r.fin ?: 0L,
            pausas = tramos(r.pausas),
            vistoEn = if (r.fin != null) 0L else visto,
            huecos = tramos(r.huecos),
            grabacion = grabacion
        )
    }

    /** Donde acaba la ventana: el fin, o lo último visto si se cortó. Null: sigue abierta. */
    fun final(r: RelojDePared): Long? = when {
        r.fin > 0 -> maxOf(r.fin, r.inicio)
        r.vistoEn > 0 -> maxOf(r.vistoEn, r.inicio)
        else -> null
    }

    /** **Lo corrido hasta [t] sin lo pausado ni los huecos** (sin mirar si está dentro). Nunca negativo. */
    fun msHasta(r: RelojDePared, t: Long): Long {
        val tope = final(r)?.let { minOf(t, it) } ?: t
        if (tope <= r.inicio) return 0
        val pausado = (r.pausas + r.huecos).sumOf { p ->
            val d = p.getOrElse(0) { 0L }
            val h0 = p.getOrElse(1) { 0L }
            val h = if (h0 == 0L) tope else minOf(h0, tope)
            (h - maxOf(d, r.inicio)).coerceAtLeast(0)
        }
        return (tope - r.inicio - pausado).coerceAtLeast(0)
    }

    /** **Como va en la nota**: las claves en orden alfabético (el `resto` del PC) y sin lo vacío. */
    fun enJson(r: RelojDePared): JsonObject = buildJsonObject {
        fun tramos(v: List<List<Long>>): JsonArray = buildJsonArray {
            v.forEach { t -> add(buildJsonArray { add(JsonPrimitive(t.getOrElse(0) { 0L })); add(JsonPrimitive(t.getOrElse(1) { 0L })) }) }
        }
        put("aparato", JsonPrimitive(r.aparato))
        if (r.fin != 0L) put("fin", JsonPrimitive(r.fin))
        if (r.grabacion.isNotEmpty()) put("grabacion", JsonPrimitive(r.grabacion))
        if (r.huecos.isNotEmpty()) put("huecos", tramos(r.huecos))
        put("inicio", JsonPrimitive(r.inicio))
        if (r.pausas.isNotEmpty()) put("pausas", tramos(r.pausas))
        if (r.vistoEn != 0L) put("vistoEn", JsonPrimitive(r.vistoEn))
    }
}
