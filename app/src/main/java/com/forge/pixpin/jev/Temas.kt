package com.forge.pixpin.jev

import com.forge.pixpin.jev.Preguntas.SI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.forge.pixpin.jev.Preguntas.Temas as Q

/**
 * **Qué temas de la agenda ya se trataron**, mientras se graba la reunión y **en vivo** (29-oct-2026;
 * porte de `crates/pixpin-typesafe/src/temas.rs` del PC, `50e87978`; el usuario: «en cuanto termino de
 * decir un tema, que se marque»).
 *
 * Lo que se dice llega frase a frase, ya pasado a texto ([Dicho]), a un [Rollo]: los últimos
 * [VENTANA_MS] de la conversación. Cada vez que hay texto nuevo, [Ritmo] decide si ya toca preguntar
 * (una petición en vuelo como mucho, [HUECO_MS] entre una y otra; lo que llega mientras tanto se junta
 * para la siguiente). La petición es **una sola** con tres Nouls por cada tema pendiente (las preguntas,
 * en [Preguntas.Temas]). La decisión la toma el código, con esta tabla.
 *
 * **Tabla de decisión** ([Seguimiento.decidir]), por tema y por petición, con SI = 0.7 y CASI = 0.5.
 * «Recordado» es una petición anterior, de hace como mucho [VIGENCIA_MS], en que el tema quedó a medias:
 *
 * | tratado | cerrado | ahora | recordado | se marca |
 * |---|---|---|---|---|
 * | ≥ SI | — | — | — | tratado, `p` = tratado |
 * | ≥ CASI y < SI | ≥ SI | — | — | tratado (se habló y se cerró), `p` = cerrado |
 * | ≥ CASI y < SI | < SI | ≥ CASI | sí | tratado (dos peticiones a medias, la segunda con algo nuevo), `p` = la mayor |
 * | ≥ CASI y < SI | < SI | < CASI, o sin recordado | — | nada aún: se recuerda (o se mantiene lo recordado) |
 * | < CASI | — | — | — | nada (y se olvida lo recordado) |
 *
 * El milisegundo de lo marcado es el del principio de `conversacion.ahora` si lo nuevo es del tema, o
 * el del principio de la conversación si no; y si había algo recordado, el de entonces.
 *
 * Jev solo marca, nunca desmarca, y solo los temas que siguen pendientes y que nadie tocó a mano (eso
 * lo decide quien llama, [com.forge.pixpin.guardados.TemasDeLaReunion]). Sin clave o si Jev falla, no
 * se marca nada: el fallo es un error, nunca un «no se trató», y lo nuevo no se da por mirado.
 */
object Temas {

    /** Lo que se le enseña a Jev de la conversación: los últimos 40 s. */
    const val VENTANA_MS = 40_000L
    /** Lo que vale un «a medias» recordado. */
    const val VIGENCIA_MS = 60_000L
    /** Lo menos entre dos peticiones. */
    const val HUECO_MS = 1_500L
    /** Lo que se espera después de un fallo. */
    const val TRAS_FALLO_MS = 5_000L
    /** Las palabras nuevas que hacen falta para preguntar (un «vale» suelto espera). */
    const val PALABRAS_MINIMAS = 3
    /** Lo largo de `conversacion.ahora` (unos 20 s hablados). */
    private const val LARGO_AHORA = 1200
    /** Y de `conversacion.antes`. */
    private const val LARGO_ANTES = 2400
    /** Y de cada tema de la agenda. */
    private const val LARGO_TEMA = 160

    /** Un tema que aún hay que mirar. */
    data class Pendiente(val id: String, val texto: String)

    /**
     * **Una frase pasada a texto**: el milisegundo de la grabación en que empieza y en que se dejó de
     * hablar, lo que se dijo y, en el reloj de quien llama (monótono, en ms), cuándo se dejó de hablar.
     */
    data class Dicho(val ms: Long, val finMs: Long, val texto: String, val relojFin: Long)

    /** **Lo que se le enseña a Jev en una petición.** */
    data class Ventana(
        /** Lo ya mirado de los últimos [VENTANA_MS], si hay. */
        val antes: String?,
        /** Lo nuevo. */
        val ahora: String,
        /** Dónde empieza lo nuevo, en la grabación. */
        val msAhora: Long,
        /** Dónde empieza la conversación entera. */
        val msDesde: Long,
        /** Dónde acaba (el fin de la última frase). */
        val finMs: Long,
        /** Cuándo se dejó de hablar en la primera y en la última frase nueva. */
        val relojPrimera: Long,
        val relojUltima: Long,
        /** Hasta dónde se mira (para [Rollo.mirado]). */
        internal val hasta: Long = 0
    )

    private fun palabras(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** **Los últimos segundos de la conversación**, frase a frase, y hasta dónde los miró ya Jev. */
    class Rollo {
        internal val dichos = ArrayDeque<Dicho>()
        /** El número (desde el principio) de `dichos[0]`. */
        private var base = 0L
        /** Los dichos con número menor ya se miraron. */
        private var mirados = 0L

        /** Una frase más (las vacías no cuentan). */
        fun meter(d: Dicho) {
            if (d.texto.isBlank()) return
            dichos.addLast(d)
        }

        private fun nuevos(): List<Dicho> = dichos.drop((mirados - base).coerceAtLeast(0).toInt())

        /** Las palabras que aún no miró nadie. */
        fun palabrasNuevas(): Int = nuevos().sumOf { palabras(it.texto) }

        /** Si hay bastante texto nuevo para preguntar. */
        fun hayNuevo(): Boolean = palabrasNuevas() >= PALABRAS_MINIMAS

        /** **La ventana de ahora**: lo nuevo y lo de antes que cabe en los últimos [VENTANA_MS]. null sin nada nuevo. */
        fun ventana(): Ventana? {
            val ultimo = dichos.lastOrNull() ?: return null
            val primeroNuevo = maxOf(mirados, base)
            if (primeroNuevo >= base + dichos.size) return null
            // Lo nuevo, de atrás hacia delante, mientras quepa (siempre la última frase).
            var desde = dichos.size
            var largo = 0
            while (desde > (primeroNuevo - base).toInt()) {
                val n = dichos[desde - 1].texto.length + 1
                if (desde < dichos.size && largo + n > LARGO_AHORA) break
                largo += n
                desde--
            }
            val ahora = (desde until dichos.size).map { dichos[it].texto.trim() }
            val primera = dichos[desde]
            // Lo de antes: lo que acaba dentro de la ventana, hasta lo nuevo.
            val corte = ultimo.finMs - VENTANA_MS
            val antes = (0 until desde).map { dichos[it] }.filter { it.finMs >= corte }
            val msDesde = antes.firstOrNull()?.ms ?: primera.ms
            val textoAntes = cola(antes.map { it.texto.trim() }, LARGO_ANTES)
            return Ventana(
                antes = textoAntes.takeIf { it.isNotEmpty() },
                ahora = recortar(ahora.joinToString(" "), LARGO_AHORA),
                msAhora = primera.ms,
                msDesde = msDesde,
                finMs = ultimo.finMs,
                relojPrimera = primera.relojFin,
                relojUltima = ultimo.relojFin,
                hasta = base + dichos.size
            )
        }

        /** **Jev ya miró esta ventana**: lo suyo deja de ser nuevo, y lo que ya no cabe en la ventana se tira. */
        fun mirado(v: Ventana) {
            mirados = maxOf(mirados, v.hasta)
            val fin = dichos.lastOrNull()?.finMs ?: return
            while (true) {
                val d = dichos.firstOrNull() ?: break
                if (base < mirados && d.finMs < fin - VENTANA_MS) {
                    dichos.removeFirst()
                    base++
                } else break
            }
        }
    }

    /** Las frases juntas, quedándose con el final si no caben (lo más cerca de lo nuevo), cortando por una palabra. */
    private fun cola(frases: List<String>, n: Int): String {
        val todo = frases.joinToString(" ")
        if (todo.length <= n) return todo
        val resto = todo.substring(todo.length - n)
        val i = resto.indexOf(' ')
        return "…" + (if (i >= 0) resto.substring(i + 1) else resto)
    }

    /** Qué hacer ahora con Jev. */
    sealed class Cuando {
        /** Nada: sin texto nuevo, o con una petición en vuelo (lo nuevo se junta para la siguiente). */
        data object Nada : Cuando()
        /** Preguntar ya. */
        data object Ya : Cuando()
        /** Preguntar dentro de tantos ms. */
        data class Esperar(val ms: Long) : Cuando()
    }

    /** **El ritmo de las peticiones**, con un reloj de ms que da quien llama (en las pruebas, uno de mentira). */
    class Ritmo {
        private var enVuelo = false
        private var ultima: Long? = null
        private var noAntesDe = 0L

        /** Si toca preguntar a las [ahora], con o sin texto nuevo. */
        fun cuando(ahora: Long, hayNuevo: Boolean): Cuando {
            if (enVuelo || !hayNuevo) return Cuando.Nada
            val listo = maxOf(ultima?.let { it + HUECO_MS } ?: 0L, noAntesDe)
            return if (ahora >= listo) Cuando.Ya else Cuando.Esperar(listo - ahora)
        }

        /** Sale una petición. */
        fun sale(ahora: Long) {
            enVuelo = true
            ultima = ahora
        }

        /** Volvió (bien o con un fallo). */
        fun vuelve(ahora: Long, bien: Boolean) {
            enVuelo = false
            if (!bien) noAntesDe = ahora + TRAS_FALLO_MS
        }
    }

    /** Lo que Jev dijo de un tema en una petición. */
    data class Senal(val tratado: Double, val cerrado: Double, val ahora: Double)

    /** Un tema que ya se trató: su `id`, el milisegundo en que empezó a tratarse y la probabilidad. */
    data class Tratado(val id: String, val ms: Long, val p: Double)

    /** **El estado de una petición**: el título (si lo hay), la agenda entera y la conversación. */
    fun estado(reunion: String, agenda: List<String>, v: Ventana): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (reunion.isNotBlank()) m["reunion"] = JsonPrimitive(recortar(reunion, 120))
        val a = agenda.filter { it.isNotBlank() }.map { JsonPrimitive(recortar(it, LARGO_TEMA)) }
        if (a.isNotEmpty()) m["agenda"] = JsonArray(a)
        val c = LinkedHashMap<String, JsonElement>()
        v.antes?.takeIf { it.isNotBlank() }?.let { c["antes"] = JsonPrimitive(it) }
        c["ahora"] = JsonPrimitive(v.ahora)
        m["conversacion"] = JsonObject(c)
        return JsonObject(m)
    }

    /** **La petición**: el estado y tres preguntas por cada tema pendiente (como mucho [Preguntas.Temas.POR_PETICION]). */
    fun peticion(reunion: String, agenda: List<String>, v: Ventana, pendientes: List<Pendiente>): Peticion {
        val temas = pendientes.take(Q.POR_PETICION).map { it.id to recortar(it.texto, LARGO_TEMA) }
        return Peticion(estado(reunion, agenda, v)).conTodas(Q.preguntas(temas))
    }

    /**
     * **Pregunta por una ventana**: la señal de cada tema que fue en la petición y el modelo que contestó.
     * Un fallo es un [ErrorJev] de la petición entera.
     */
    fun preguntar(cliente: Cliente, reunion: String, agenda: List<String>, v: Ventana, pendientes: List<Pendiente>): Pair<List<Pair<String, Senal>>, String> {
        val r = cliente.preguntar(peticion(reunion, agenda, v, pendientes))
        val s = pendientes.take(Q.POR_PETICION).map { p ->
            p.id to Senal(r.noul(Q.tratado(p.id)), r.noul(Q.cerrado(p.id)), r.noul(Q.ahora(p.id)))
        }
        return s to r.modelo
    }

    private data class AMedias(val finMs: Long, val ms: Long, val p: Double)

    /** **Lo que se recuerda entre peticiones** para la fila de «dos a medias». */
    class Seguimiento {
        private val aMedias = HashMap<String, AMedias>()

        /** **La tabla** (ver la cabecera): lo que se trató según lo que dijo Jev de la ventana [v]. */
        fun decidir(v: Ventana, senales: List<Pair<String, Senal>>): List<Tratado> {
            val hechos = ArrayList<Tratado>()
            for ((id, s) in senales) {
                val antes = aMedias.remove(id)?.takeIf { v.finMs - it.finMs <= VIGENCIA_MS }
                val aqui = if (s.ahora >= Q.CASI) v.msAhora else v.msDesde
                val ms = antes?.ms ?: aqui
                if (s.tratado >= SI) {
                    hechos += Tratado(id, ms, s.tratado)
                } else if (s.tratado >= Q.CASI) {
                    if (s.cerrado >= SI) {
                        hechos += Tratado(id, ms, s.cerrado)
                    } else if (antes != null && s.ahora >= Q.CASI) {
                        hechos += Tratado(id, ms, maxOf(antes.p, s.tratado))
                    } else {
                        aMedias[id] = AMedias(v.finMs, ms, antes?.let { maxOf(it.p, s.tratado) } ?: s.tratado)
                    }
                }
            }
            return hechos
        }

        /** Olvida un tema (se marcó a mano, se quitó). */
        fun olvidar(id: String) {
            aMedias.remove(id)
        }
    }
}
