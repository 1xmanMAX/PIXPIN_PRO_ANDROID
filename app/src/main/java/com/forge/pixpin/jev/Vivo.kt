package com.forge.pixpin.jev

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.forge.pixpin.jev.Preguntas.Vivo as Q

/**
 * **Lo que se dice en vivo, frase a frase** (29-oct-2026; porte de `crates/pixpin-typesafe/src/vivo.rs`
 * del PC, `50e87978`, y de `docs/probar-ideas-sin-interfaz.md`): **una sola petición por frase** que
 * sirve a varias ideas a la vez, porque todas comparten el estado (la frase, quién la dice y las dos de
 * antes). Cada idea pone sus preguntas si se pide ([Partes]) y lee sus respuestas ([Senales]); lo que
 * se hace con ellas lo deciden las tablas de cada módulo:
 *
 * | parte | preguntas ([Preguntas.Vivo]) | decide |
 * |---|---|---|
 * | `compromiso` | `compromiso`, `quien_dicho`, `quien`, `urgente` | [Compromisos] |
 * | `plazo` (con `compromiso` o con tareas) | `plazo_dicho`, `plazo` | [Compromisos], [UrgenciaViva] |
 * | `tareas` | `sobre_<id>`, `apura_<id>` por tarea | [UrgenciaViva] |
 * | `decision` | `decision` | [esDecision] |
 * | `pregunta` y `abiertas` | `pregunta`, `respondida_<id>` por pregunta | [Pendientes] |
 * | `cita` | `cita`, `cita_cuando` | [Citas] |
 * | `tema` y `cierre` | `de_la_agenda`, `tema`, `cierre` | [TiempoDeTemas] |
 * | `chats` | `dictado`, `dictado_chat_dicho`, `dictado_chat`, `dictado_que` | [Dictado] |
 *
 * Las fechas las busca el código ([FechasDichas.buscar]) y Jev solo elige cuál es el plazo o la cita:
 * sin ninguna expresión de fecha en la frase no se pregunta por plazos ni citas (no hay nada que
 * elegir). Con una sola, no hace falta la Choice: basta el Noul. Las preguntas especulativas (quién,
 * cuál plazo, qué chat) van en la misma petición y solo se leen si su Noul dice «sí».
 *
 * `participantes` y `agenda` solo van en el estado si van las preguntas que los nombran (un campo de
 * más puede torcer otra respuesta).
 */
object Vivo {

    /** **Una frase dicha**: el milisegundo de la reunión en que empieza, quién la dice y lo que dice. */
    data class Frase(val ms: Long, val quien: String, val dice: String)

    /** Cuántas frases de antes van de contexto. */
    const val ANTES = 2
    private const val LARGO_FRASE = 600
    private const val LARGO_ANTES = 300

    /** Lo que se sabe de la reunión cuando llega una frase. */
    data class Contexto(
        val reunion: String,
        val participantes: List<String>,
        val agenda: List<String>,
        /** Las frases anteriores, de la más vieja a la más nueva (van las últimas [ANTES]). */
        val antes: List<Frase>,
        val frase: Frase,
        /** El día de la reunión (para resolver «el viernes»). */
        val hoy: FechasDichas.Fecha,
        /** El tema de la agenda de lo anterior, si el código lo sabe (solo va con la parte `tema`). */
        val temaAnterior: String? = null
    )

    /** **Qué ideas van en la petición.** */
    data class Partes(
        val compromiso: Boolean = false,
        val decision: Boolean = false,
        val pregunta: Boolean = false,
        val cita: Boolean = false,
        val cierre: Boolean = false,
        val tema: Boolean = false,
        /** Las tareas que ya existen: (id, texto). */
        val tareas: List<Pair<String, String>> = emptyList(),
        /** Las preguntas aún sin respuesta: (id, texto). */
        val abiertas: List<Pair<String, String>> = emptyList(),
        /** Los chats donde se puede anotar. */
        val chats: List<String> = emptyList()
    )

    private fun palabras(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Si una frase trae bastante para preguntar (un «vale» suelto no). */
    fun valeLaPena(f: Frase): Boolean = palabras(f.dice) >= Temas.PALABRAS_MINIMAS

    /**
     * **Parte en oraciones** lo que da la escucha en vivo: un trozo de Whisper (5-12 s de voz) suele
     * traer dos o tres turnos («¿Cuándo se publican? El viernes, en cuanto…»). Las preguntas de aquí
     * miran una frase dicha por alguien: se le da una por oración.
     *
     * Corta después de `.`, `?` o `!` seguidos de un espacio (no en «2.400») y antes de `¿` o `¡`. Un
     * trozo de menos de [Temas.PALABRAS_MINIMAS] palabras («Vale.», «Buenos días.») va con el siguiente
     * (o con el anterior si es el último). El milisegundo de cada trozo se reparte por letras entre
     * `f.ms` y [finMs] (donde se dejó de hablar): `(frase, fin)` de cada uno.
     */
    fun partir(f: Frase, finMs: Long): List<Pair<Frase, Long>> {
        val texto = f.dice.trim()
        val cortes = mutableListOf(0)
        for (i in texto.indices) {
            val c = texto[i]
            val siguiente = texto.getOrNull(i + 1)
            if ((c == '.' || c == '?' || c == '!') && siguiente != null && siguiente.isWhitespace()) {
                cortes += i + 1
            } else if (c == '¿' || c == '¡') {
                // Delante, si va tras un espacio y lo de antes no acaba en coma («Oye, ¿empezamos?» va junto).
                val desde = cortes.last()
                val antes = texto.substring(desde, i).trimEnd()
                if (antes.isNotBlank() && antes.length < i - desde && !antes.endsWith(',') && !antes.endsWith(';') && !antes.endsWith(':')) {
                    cortes += i
                }
            }
        }
        cortes += texto.length
        val unicos = cortes.fold(mutableListOf<Int>()) { v, x -> if (v.lastOrNull() != x) v += x; v }
        val trozos = unicos.zipWithNext().filter { (a, b) -> texto.substring(a, b).isNotBlank() }.toMutableList()
        fun pal(t: Pair<Int, Int>) = palabras(texto.substring(t.first, t.second).trim())
        var i = 0
        while (trozos.size > 1 && i < trozos.size) {
            if (pal(trozos[i]) < Temas.PALABRAS_MINIMAS) {
                if (i + 1 < trozos.size) trozos[i + 1] = trozos[i].first to trozos[i + 1].second
                else trozos[i - 1] = trozos[i - 1].first to trozos[i].second
                trozos.removeAt(i)
            } else i++
        }
        val total = texto.length.coerceAtLeast(1).toLong()
        val dura = (finMs - f.ms).coerceAtLeast(0)
        fun en(b: Int) = f.ms + dura * b / total
        return trozos.map { (a, b) -> Frase(en(a), f.quien, texto.substring(a, b).trim()) to en(b) }
    }

    /**
     * La clave de una opción: su texto en minúsculas, sin tildes y con `_` (el nombre de la opción se
     * le manda a Jev: mejor que «c1»).
     */
    fun clave(texto: String): String {
        val s = StringBuilder()
        for (c0 in texto.trim().lowercase()) {
            val c = FechasDichas.sinTilde(c0)
            if ((c in 'a'..'z') || (c in '0'..'9')) s.append(c)
            else if (s.isNotEmpty() && !s.endsWith("_")) s.append('_')
        }
        val r = s.toString().trimEnd('_').take(60)
        return r.ifEmpty { "x" }
    }

    /** Claves únicas para estos textos, en el mismo orden («todos» es la opción de todos). */
    internal fun claves(textos: List<String>): List<Pair<String, String>> {
        val v = ArrayList<Pair<String, String>>()
        for (t in textos) {
            val base = clave(t)
            var k = base
            var n = 2
            while (v.any { it.first == k } || k == Q.TODOS) {
                k = "${base}_$n"
                n++
            }
            v += k to t
        }
        return v
    }

    /** El estado (ver [Preguntas.Vivo]). */
    fun estado(c: Contexto, partes: Partes): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (c.reunion.isNotBlank()) m["reunion"] = JsonPrimitive(recortar(c.reunion, 120))
        if (partes.compromiso && c.participantes.isNotEmpty()) m["participantes"] = JsonArray(c.participantes.map(::JsonPrimitive))
        if (partes.tema && c.agenda.isNotEmpty()) {
            m["agenda"] = JsonArray(c.agenda.map { JsonPrimitive(recortar(it, 160)) })
            // Lo que el código ya sabe: de qué tema iba lo anterior.
            c.temaAnterior?.takeIf { it.isNotBlank() }?.let { m["tema_anterior"] = JsonPrimitive(recortar(it, 160)) }
        }
        val antes = c.antes.takeLast(ANTES).map { JsonPrimitive("${it.quien}: ${recortar(it.dice, LARGO_ANTES)}") }
        if (antes.isNotEmpty()) m["antes"] = JsonArray(antes)
        m["frase"] = JsonObject(linkedMapOf("quien" to JsonPrimitive(c.frase.quien), "dice" to JsonPrimitive(recortar(c.frase.dice, LARGO_FRASE))))
        return JsonObject(m)
    }

    /** **La petición preparada**: la petición y lo que hace falta para leer sus respuestas. */
    class Preparada internal constructor(
        val peticion: Peticion,
        /** Las expresiones de fecha de la frase, en orden. */
        val fechas: List<FechasDichas.Expresion>,
        private val quien: List<Pair<String, String>>,
        private val tema: List<Pair<String, String>>,
        private val chats: List<Pair<String, String>>,
        /** Lo que se podría anotar: (clave, texto). */
        val textosDictado: List<Pair<String, String>>,
        private val compromiso: Boolean,
        private val plazo: Boolean,
        private val decision: Boolean,
        private val pregunta: Boolean,
        private val cita: Boolean,
        private val cierre: Boolean,
        private val tareas: List<String>,
        private val abiertas: List<String>
    ) {
        /** **Lee las respuestas.** Lo que falte es un error de toda la frase. */
        fun leer(r: Respuestas): Senales {
            var compromisoL: Compromiso? = null
            if (compromiso) {
                val quienL = if (quien.isEmpty()) null else {
                    val conTodos = quien + (Q.TODOS to "todos")
                    val (i, p) = elegida(r, Q.QUIEN, conTodos)
                    conTodos[i].second to p
                }
                compromisoL = Compromiso(
                    p = r.noul(Q.COMPROMISO),
                    urgente = r.noul(Q.URGENTE),
                    quienDicho = if (quien.isEmpty()) null else r.noul(Q.QUIEN_DICHO),
                    quien = quienL
                )
            }
            val opcionesFecha = { claves(fechas.map { it.texto }) }
            val plazoL = if (plazo) Plazo(r.noul(Q.PLAZO_DICHO), if (fechas.size > 1) elegida(r, Q.PLAZO, opcionesFecha()).first else 0) else null
            val decisionL = if (decision) r.noul(Q.DECISION) else null
            val preguntaL = if (pregunta) r.noul(Q.PREGUNTA) else null
            val respondidas = abiertas.map { it to r.noul(Q.respondida(it)) }
            val citaL = if (cita) Cita(r.noul(Q.CITA), if (fechas.size > 1) elegida(r, Q.CITA_CUANDO, opcionesFecha()).first else 0) else null
            val cierreL = if (cierre) r.noul(Q.CIERRE) else null
            val temaL = if (tema.isNotEmpty()) {
                val (cual, pCual) = elegida(r, Q.TEMA, tema)
                Tema(r.noul(Q.DE_LA_AGENDA), cual, pCual)
            } else null
            val tareasL = tareas.map { it to SenalTarea(r.noul(Q.sobre(it)), r.noul(Q.apura(it))) }
            val dictadoL = if (chats.isNotEmpty()) {
                val texto = when (textosDictado.size) {
                    0 -> null
                    1 -> textosDictado[0].second
                    else -> textosDictado[elegida(r, Q.DICTADO_QUE, textosDictado).first].second
                }
                Dictado(r.noul(Q.DICTADO), r.noul(Q.DICTADO_CHAT_DICHO), elegida(r, Q.DICTADO_CHAT, chats).first, texto)
            } else null
            return Senales(compromisoL, plazoL, decisionL, preguntaL, citaL, cierreL, temaL, tareasL, respondidas, dictadoL)
        }
    }

    /** **Prepara la petición** de una frase con las partes pedidas. */
    fun preparar(c: Contexto, partes: Partes): Preparada {
        val fechas = FechasDichas.buscar(c.frase.dice, c.hoy)
        val opcionesFecha = claves(fechas.map { it.texto })
        var p = Peticion(estado(c, partes))
        val plazo = fechas.isNotEmpty() && (partes.compromiso || partes.tareas.isNotEmpty())
        val cita = partes.cita && fechas.isNotEmpty()
        val quien = claves(c.participantes)
        if (partes.compromiso) {
            p = p.con(Q.COMPROMISO, Q.compromiso()).con(Q.URGENTE, Q.urgente())
            if (quien.isNotEmpty()) p = p.con(Q.QUIEN_DICHO, Q.quienDicho()).con(Q.QUIEN, Q.quien(quien, c.frase.quien))
        }
        if (plazo) {
            p = p.con(Q.PLAZO_DICHO, Q.plazoDicho())
            if (fechas.size > 1) p = p.con(Q.PLAZO, Q.plazo(opcionesFecha))
        }
        if (partes.decision) p = p.con(Q.DECISION, Q.decision())
        if (partes.pregunta) p = p.con(Q.PREGUNTA, Q.pregunta())
        // Las más recientes primero (las viejas que no caben esperan).
        val abiertas = ArrayList<String>()
        for ((id, texto) in partes.abiertas.asReversed().take(Q.POR_PETICION)) {
            p = p.con(Q.respondida(id), Q.contesta(recortar(texto, 200)))
            abiertas += id
        }
        if (cita) {
            p = p.con(Q.CITA, Q.cita())
            if (fechas.size > 1) p = p.con(Q.CITA_CUANDO, Q.citaCuando(opcionesFecha))
        }
        if (partes.cierre) p = p.con(Q.CIERRE, Q.cierre())
        val tema = claves(c.agenda)
        val conTema = partes.tema && tema.isNotEmpty()
        if (conTema) p = p.con(Q.DE_LA_AGENDA, Q.deLaAgenda()).con(Q.TEMA, Q.tema(tema, c.temaAnterior != null))
        val tareas = ArrayList<String>()
        for ((id, texto) in partes.tareas.take(Q.POR_PETICION)) {
            val t = recortar(texto, 200)
            p = p.con(Q.sobre(id), Q.esDeLaTarea(t)).con(Q.apura(id), Q.laApura(t))
            tareas += id
        }
        val chats = claves(partes.chats)
        var textosDictado = emptyList<Pair<String, String>>()
        if (chats.isNotEmpty()) {
            textosDictado = com.forge.pixpin.jev.Dictado.textos(c.frase, c.antes)
            p = p.con(Q.DICTADO, Q.dictado()).con(Q.DICTADO_CHAT_DICHO, Q.dictadoChatDicho()).con(Q.DICTADO_CHAT, Q.dictadoChat(chats))
            if (textosDictado.size > 1) p = p.con(Q.DICTADO_QUE, Q.dictadoQue(textosDictado))
        }
        return Preparada(
            p, fechas, quien, if (conTema) tema else emptyList(), chats, textosDictado,
            partes.compromiso, plazo, partes.decision, partes.pregunta, cita, partes.cierre, tareas, abiertas
        )
    }

    /** Lo que dijo Jev de un compromiso. */
    data class Compromiso(
        val p: Double,
        val urgente: Double,
        /** null sin participantes (no se preguntó). */
        val quienDicho: Double?,
        /** El nombre más probable y su probabilidad. */
        val quien: Pair<String, Double>?
    )

    /** Lo que dijo del plazo: si hay uno y cuál de [Preparada.fechas]. */
    data class Plazo(val p: Double, val cual: Int)

    /** Lo que dijo de una cita. */
    data class Cita(val p: Double, val cual: Int)

    /** Lo que dijo del tema: si es de la agenda, el índice del más probable y su probabilidad. */
    data class Tema(val deLaAgenda: Double, val cual: Int, val pCual: Double)

    /** Lo que dijo de una tarea. */
    data class SenalTarea(val sobre: Double, val apura: Double)

    /** Lo que dijo de un dictado: el chat más probable y lo que se anotaría (null si no hay nada). */
    data class Dictado(val p: Double, val chatDicho: Double, val chat: Int, val texto: String?)

    /** **Todo lo que dijo Jev de una frase**, por partes (null: no se preguntó). */
    data class Senales(
        val compromiso: Compromiso? = null,
        val plazo: Plazo? = null,
        val decision: Double? = null,
        val pregunta: Double? = null,
        val cita: Cita? = null,
        val cierre: Double? = null,
        val tema: Tema? = null,
        val tareas: List<Pair<String, SenalTarea>> = emptyList(),
        val respondidas: List<Pair<String, Double>> = emptyList(),
        val dictado: Dictado? = null
    )

    /** La opción elegida de una Choice y su índice entre [opciones]. */
    private fun elegida(r: Respuestas, nombre: String, opciones: List<Pair<String, String>>): Pair<Int, Double> {
        val (c, probs) = r.choice(nombre)
        val i = opciones.indexOfFirst { it.first == c }
        if (i < 0) throw ErrorJev.Formato("$nombre: opción «$c» que no se pidió")
        return i to (probs[c] ?: 0.0)
    }

    /** **Pregunta por una frase**: las señales y las fechas de la frase. Un fallo es un error de la frase entera (nunca un «no»). */
    fun preguntar(cliente: Cliente, c: Contexto, partes: Partes): Pair<Senales, List<FechasDichas.Expresion>> {
        val prep = preparar(c, partes)
        val r = cliente.preguntar(prep.peticion)
        return prep.leer(r) to prep.fechas
    }

    /** **¿Se decidió algo?** `decision` ≥ SI: sí; entre NO y SI: no se apunta (una decisión de más en el acta es peor que una de menos). */
    fun esDecision(s: Senales): Boolean = s.decision?.let { it >= Preguntas.SI } == true
}

/**
 * **Dictar al chat** (29-oct-2026; porte de `dictado.rs` del PC): «anota esto en el chat de la obra» →
 * en qué chat (Choice sobre los nombres de los chats, con un Noul de «nombra alguno») y qué se anota. El
 * texto **no se genera**: el código saca los candidatos ([textos]) y Jev elige cuál.
 *
 * | `dictado` | `dictado_chat_dicho` | se hace |
 * |---|---|---|
 * | ≥ SI | ≥ SI | nota en el chat elegido |
 * | ≥ SI | < SI | nota sin chat (se pregunta al usuario cuál) |
 * | ≥ SI, sin nada que anotar | — | nada (no hay texto) |
 * | entre NO y SI | — | nada, se apunta como dudoso |
 * | ≤ NO | — | nada |
 */
object Dictado {

    /** Lo que se podría anotar: (clave de la opción, texto). */
    fun textos(frase: Vivo.Frase, antes: List<Vivo.Frase>): List<Pair<String, String>> {
        val v = ArrayList<Pair<String, String>>()
        val d = frase.dice.trim()
        val bajo = d.lowercase()
        val resto: String? = if (':' in d) d.substring(d.indexOf(':') + 1) else {
            // «… que faltan tornillos»: lo de después del primer «que» que sigue a «chat» o «grupo» (o del primero, si no se nombran).
            val desde = listOf("chat", "grupo").map { bajo.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val i = bajo.indexOf(" que ", desde)
            if (i >= 0 && i + 5 <= d.length) d.substring(i + 5) else null
        }
        resto?.trim()?.trimEnd('.', '!')?.trim()?.takeIf { r -> r.split(Regex("\\s+")).count { it.isNotEmpty() } >= 2 }?.let {
            v += Preguntas.Vivo.LO_QUE_SIGUE to it
        }
        antes.lastOrNull()?.takeIf { it.dice.isNotBlank() }?.let { v += Preguntas.Vivo.LA_ANTERIOR to recortar(it.dice, 300) }
        return v
    }

    /** Una nota para un chat. [chat] null: no se dijo el chat (se pregunta). */
    data class Nota(val ms: Long, val chat: String?, val texto: String, val p: Double)

    sealed class Resultado {
        data class Anotar(val nota: Nota) : Resultado()
        data object SinTexto : Resultado()
        data class Dudoso(val p: Double) : Resultado()
        data object Nada : Resultado()
    }

    /** **La tabla.** */
    fun decidir(frase: Vivo.Frase, d: Vivo.Dictado?, chats: List<String>): Resultado {
        if (d == null || d.p <= Preguntas.NO) return Resultado.Nada
        if (d.p < Preguntas.SI) return Resultado.Dudoso(d.p)
        val texto = d.texto ?: return Resultado.SinTexto
        return Resultado.Anotar(Nota(frase.ms, if (d.chatDicho >= Preguntas.SI) chats.getOrNull(d.chat) else null, texto, d.p))
    }
}
