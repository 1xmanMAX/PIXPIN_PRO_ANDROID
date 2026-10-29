package com.forge.pixpin.jev

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Dictar al chat** (29-oct-2026; porte de `crates/pixpin-typesafe/src/dictado.rs` del PC y de la parte
 * `chats` de `vivo.rs`, `origin/main` `50e87978`): «anota esto en el chat de la obra» → en qué chat (Choice
 * sobre los nombres de los chats, con un Noul de «nombra alguno») y qué se anota. El texto **no se
 * genera**: el código saca los candidatos ([textos]: lo que sigue en la frase después de «:» o de «que»,
 * y la frase anterior para «esto») y Jev elige cuál.
 *
 * **Una petición por frase dictada**, con el estado `{antes, frase: {quien, dice}}` ([estado], el de
 * `vivo::estado` con solo la parte `chats`: sin `reunion`, `participantes` ni `agenda`) y las preguntas
 * `dictado`, `dictado_chat_dicho`, `dictado_chat` y, si hay dos textos, `dictado_que`
 * ([Preguntas.Vivo]).
 *
 * **La tabla** ([decidir], `dictado::decidir` del PC):
 *
 * | `dictado` | `dictado_chat_dicho` | se hace |
 * |---|---|---|
 * | ≥ SI | ≥ SI | nota en el chat elegido |
 * | ≥ SI | < SI | nota sin chat (se pregunta al usuario cuál) |
 * | ≥ SI, sin nada que anotar | — | nada (no hay texto) |
 * | entre NO y SI | — | nada, se apunta como dudoso |
 * | ≤ NO | — | nada |
 *
 * Lo prueba `DictadoYRepasoTest` con las pruebas del PC.
 */
object DictadoAlChat {

    /** **Una frase dicha** (`vivo::Frase`): el milisegundo en que empieza, quién la dice y lo que dice. */
    data class Frase(val ms: Long, val quien: String, val dice: String)

    /** Cuántas frases de antes van de contexto (`vivo::ANTES`). */
    const val ANTES = 2

    /** Lo largo de la frase y de cada una de antes. */
    private const val LARGO_FRASE = 600
    private const val LARGO_ANTES = 300

    /** Con menos palabras no se pregunta (`temas::PALABRAS_MINIMAS`: un «vale» suelto no). */
    const val PALABRAS_MINIMAS = 3

    private val BLANCOS = Regex("\\s+")

    private fun palabras(s: String): Int = s.trim().split(BLANCOS).count { it.isNotEmpty() }

    /** Si una frase trae bastante para preguntar (`vivo::vale_la_pena`). */
    fun valeLaPena(f: Frase): Boolean = palabras(f.dice) >= PALABRAS_MINIMAS

    /**
     * **La clave de una opción** (`vivo::clave`): su texto en minúsculas, sin tildes y con `_` (el
     * nombre de la opción se le manda a Jev: mejor que «c1»). 60 letras como mucho; vacía, «x».
     */
    fun clave(texto: String): String {
        val s = StringBuilder()
        for (c in texto.trim().lowercase()) {
            val l = when (c) {
                'á', 'à' -> 'a'
                'é', 'è' -> 'e'
                'í', 'ì' -> 'i'
                'ó', 'ò' -> 'o'
                'ú', 'ù', 'ü' -> 'u'
                'ñ' -> 'n'
                else -> c
            }
            if (l in 'a'..'z' || l in '0'..'9') s.append(l)
            else if (s.isNotEmpty() && !s.endsWith("_")) s.append('_')
        }
        val r = s.toString().trimEnd('_').take(60)
        return r.ifEmpty { "x" }
    }

    /** Claves únicas para estos textos, en el mismo orden (`vivo::claves`; «todos» es la de [Preguntas.Vivo.TODOS]). */
    fun claves(textos: List<String>): List<Pair<String, String>> {
        val v = mutableListOf<Pair<String, String>>()
        for (t in textos) {
            val base = clave(t)
            var k = base
            var n = 2
            while (v.any { it.first == k } || k == Preguntas.Vivo.TODOS) {
                k = "${base}_$n"
                n++
            }
            v += k to t
        }
        return v
    }

    /**
     * **Lo que se podría anotar** (`dictado::textos`): (clave de la opción, texto). Lo que sigue a «:»
     * o, si no hay, al primer « que » tras «chat» o «grupo» (o al primero, si no se nombran), con dos
     * palabras o más; y la frase anterior, para «anota esto».
     */
    fun textos(frase: Frase, antes: List<Frase>): List<Pair<String, String>> {
        val v = mutableListOf<Pair<String, String>>()
        val d = frase.dice.trim()
        val bajo = d.lowercase()
        val dosPuntos = d.indexOf(':')
        val resto: String? = if (dosPuntos >= 0) {
            d.substring(dosPuntos + 1)
        } else {
            // «… que faltan tornillos»: lo de después del primer «que» que sigue a «chat» o «grupo» (o del
            // primero, si no se nombran). `bajo` y `d` tienen el mismo largo en español.
            val desde = listOf("chat", "grupo").map { bajo.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val i = bajo.indexOf(" que ", desde)
            if (i >= 0 && i + 5 <= d.length) d.substring(i + 5) else null
        }
        val r = resto?.trim()?.trimEnd('.', '!')?.trim()
        if (r != null && palabras(r) >= 2) v += Preguntas.Vivo.LO_QUE_SIGUE to r
        val a = antes.lastOrNull()
        if (a != null && a.dice.isNotBlank()) v += Preguntas.Vivo.LA_ANTERIOR to recortar(a.dice, 300)
        return v
    }

    /** El estado (`vivo::estado` con solo la parte `chats`): las dos frases de antes y la frase. */
    fun estado(antes: List<Frase>, frase: Frase): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        val previas = antes.takeLast(ANTES).map { "${it.quien}: ${recortar(it.dice, LARGO_ANTES)}" }
        if (previas.isNotEmpty()) m["antes"] = JsonArray(previas.map(::JsonPrimitive))
        m["frase"] = JsonObject(linkedMapOf(
            "quien" to JsonPrimitive(frase.quien),
            "dice" to JsonPrimitive(recortar(frase.dice, LARGO_FRASE))
        ))
        return JsonObject(m)
    }

    /** Lo que dijo Jev de un dictado (`vivo::Dictado`). */
    data class Senal(
        val p: Double,
        val chatDicho: Double,
        /** El índice del chat más probable. */
        val chat: Int,
        /** Lo que se anotaría (de [Preparada.textos]); null si no hay nada que anotar. */
        val texto: String?
    )

    /** **La petición preparada** y lo que hace falta para leer sus respuestas (`vivo::Preparada`). */
    class Preparada(
        val peticion: Peticion,
        /** (clave, nombre) de cada chat, en el orden en que se dieron. */
        val chats: List<Pair<String, String>>,
        /** Lo que se podría anotar: (clave, texto). */
        val textos: List<Pair<String, String>>
    ) {
        /** **Lee las respuestas.** Lo que falte es un error de toda la frase, nunca un «no». */
        fun leer(r: Respuestas): Senal {
            val texto = when (textos.size) {
                0 -> null
                1 -> textos[0].second
                else -> textos[elegida(r, Preguntas.Vivo.DICTADO_QUE, textos)].second
            }
            return Senal(
                p = r.noul(Preguntas.Vivo.DICTADO),
                chatDicho = r.noul(Preguntas.Vivo.DICTADO_CHAT_DICHO),
                chat = elegida(r, Preguntas.Vivo.DICTADO_CHAT, chats),
                texto = texto
            )
        }
    }

    /** La opción elegida de una Choice, por su índice entre [opciones]. Una que no se pidió es un error. */
    private fun elegida(r: Respuestas, nombre: String, opciones: List<Pair<String, String>>): Int {
        val (c, _) = r.choice(nombre)
        val i = opciones.indexOfFirst { it.first == c }
        if (i < 0) throw ErrorJev.Formato("$nombre: opción «$c» que no se pidió")
        return i
    }

    /**
     * **Prepara la petición** de una frase dictada (`vivo::preparar` con la parte `chats`). [chats]: los
     * nombres de los chats donde se puede anotar (vacío: no hay preguntas y [Peticion.cuerpo] fallaría;
     * quien llama no pregunta).
     */
    fun preparar(antes: List<Frase>, frase: Frase, chats: List<String>): Preparada {
        var p = Peticion(estado(antes, frase))
        val opciones = claves(chats)
        var textos = emptyList<Pair<String, String>>()
        if (opciones.isNotEmpty()) {
            textos = textos(frase, antes)
            p = p.con(Preguntas.Vivo.DICTADO, Preguntas.Vivo.dictado())
                .con(Preguntas.Vivo.DICTADO_CHAT_DICHO, Preguntas.Vivo.dictadoChatDicho())
                .con(Preguntas.Vivo.DICTADO_CHAT, Preguntas.Vivo.dictadoChat(opciones))
            if (textos.size > 1) p = p.con(Preguntas.Vivo.DICTADO_QUE, Preguntas.Vivo.dictadoQue(textos))
        }
        return Preparada(p, opciones, textos)
    }

    /** **Pregunta por una frase.** Bloquea; un fallo es un [ErrorJev] de la frase entera. */
    fun preguntar(cliente: Cliente, antes: List<Frase>, frase: Frase, chats: List<String>): Senal {
        val prep = preparar(antes, frase, chats)
        return prep.leer(cliente.preguntar(prep.peticion))
    }

    /** Una nota para un chat. */
    data class Nota(
        val ms: Long,
        /** null: no se dijo el chat (se pregunta). */
        val chat: String?,
        val texto: String,
        val p: Double
    )

    /** Lo que sale de la tabla. */
    sealed class Resultado {
        data class Anotar(val nota: Nota) : Resultado()
        object SinTexto : Resultado()
        data class Dudoso(val p: Double) : Resultado()
        object Nada : Resultado()
    }

    /** **La tabla** (`dictado::decidir`): ver la cabecera. */
    fun decidir(frase: Frase, d: Senal?, chats: List<String>): Resultado {
        if (d == null) return Resultado.Nada
        if (d.p <= Preguntas.NO) return Resultado.Nada
        if (d.p < Preguntas.SI) return Resultado.Dudoso(d.p)
        val texto = d.texto ?: return Resultado.SinTexto
        return Resultado.Anotar(
            Nota(
                ms = frase.ms,
                chat = if (d.chatDicho >= Preguntas.SI) chats.getOrNull(d.chat) else null,
                texto = texto,
                p = d.p
            )
        )
    }
}
