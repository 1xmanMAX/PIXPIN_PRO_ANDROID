package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **Preguntar al usuario y esperar** (`preguntar.rs` del PC, 22-oct-2026; portado el 25-oct-2026).
 *
 * El fallo que más se mide en los modelos de hoy es **preguntar de menos**: eligen una lectura y
 * la hacen (CLAM, AskBench). Pero preguntar de más también cuesta, así que la herramienta es
 * estrecha, como el `AskUserQuestion` de Claude Code: UNA pregunta concreta (dos como mucho por
 * llamada), de 2 a 4 opciones con etiqueta corta y descripción, la recomendada primero, y la app
 * añade siempre «Otro…» para que escriba lo suyo. El turno ([Agente]) **se para en la llamada**:
 * el ejecutor enseña la tarjeta en la burbuja y no vuelve hasta que el usuario elige, escribe o se
 * acaba el tiempo; lo contestado vuelve al modelo como resultado de la herramienta.
 *
 * Lo que borra o envía NO pasa por aquí: eso es la confirmación Sí/No de su herramienta.
 *
 * **Mismo nombre, mismo esquema y mismos textos que el PC**: el modelo aprende de ellos, y una
 * respuesta grabada en un aparato pasa igual por el otro.
 */
object Preguntar {
    const val NOMBRE = "preguntar_usuario"

    /** Cuántas preguntas por llamada como mucho (una es lo normal). */
    const val MAX_PREGUNTAS = 2
    /** Opciones por pregunta: menos de dos no es elegir; más de cuatro no se lee de un vistazo. */
    const val MIN_OPCIONES = 2
    const val MAX_OPCIONES = 4
    /** Lo largo de cada cosa (letras): la tarjeta es estrecha. */
    const val LARGO_PREGUNTA = 200
    const val LARGO_ETIQUETA = 40
    const val LARGO_DESCRIPCION = 120

    data class Opcion(val etiqueta: String, val descripcion: String = "")

    /** Una pregunta con sus opciones (la primera es la recomendada). */
    data class Pregunta(val texto: String, val opciones: List<Opcion>)

    /** Lo que contestó el usuario. */
    sealed class Contestada {
        /** Pulsó la opción [n] (desde 0). */
        data class Opcion(val n: Int) : Contestada()
        /** Escribió lo suyo («Otro…», o contestó escribiendo en el chat). */
        data class Texto(val texto: String) : Contestada()
        /** Nadie contestó: pasó el tiempo, se paró el turno o aquí no hay a quién preguntar. */
        object SinRespuesta : Contestada() {
            override fun toString() = "SinRespuesta"
        }
    }

    private const val EJEMPLO = "{\"preguntas\":[{\"pregunta\":\"¿Traduzco todos los textos del lienzo o solo los de la figura elegida?\"," +
        "\"opciones\":[{\"etiqueta\":\"Solo la figura\",\"descripcion\":\"Los 6 textos de lo elegido\"}," +
        "{\"etiqueta\":\"Todo el lienzo\",\"descripcion\":\"Los 23 textos\"}]}]}"

    /** **La herramienta como la ve el modelo** (el `herramienta()` del PC, letra a letra). */
    fun herramienta(): Herramienta = Herramienta(
        NOMBRE,
        "Hace al usuario una pregunta con opciones y espera su respuesta, que te llega como resultado. " +
            "Úsala solo cuando el pedido admite lecturas que dan resultados distintos, equivocarse cuesta (un trabajo largo, sobrescribir lo que hay, gastar) " +
            "y ni el lienzo, ni la conversación, ni los adjuntos lo aclaran. " +
            "No la uses para lo que se deshace fácil (decide y di lo que asumiste), si el usuario dijo «tú decide», ni para confirmar algo que borra o envía: eso lo pregunta su propia herramienta. " +
            "Una pregunta concreta, de 2 a 4 opciones cortas con la recomendada primero; la app añade «Otro…» para que escriba lo suyo. " +
            "Ejemplo: $EJEMPLO",
        ordenado(buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("preguntas")) })
            put("properties", buildJsonObject {
                put("preguntas", buildJsonObject {
                    put("type", "array"); put("minItems", 1); put("maxItems", MAX_PREGUNTAS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", buildJsonArray { add(JsonPrimitive("pregunta")); add(JsonPrimitive("opciones")) })
                        put("properties", buildJsonObject {
                            put("pregunta", buildJsonObject { put("type", "string"); put("maxLength", LARGO_PREGUNTA); put("description", "una sola cosa, concreta, terminada en ?") })
                            put("opciones", buildJsonObject {
                                put("type", "array"); put("minItems", MIN_OPCIONES); put("maxItems", MAX_OPCIONES)
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("required", buildJsonArray { add(JsonPrimitive("etiqueta")) })
                                    put("properties", buildJsonObject {
                                        put("etiqueta", buildJsonObject { put("type", "string"); put("maxLength", LARGO_ETIQUETA); put("description", "de 1 a 5 palabras") })
                                        put("descripcion", buildJsonObject { put("type", "string"); put("maxLength", LARGO_DESCRIPCION); put("description", "qué pasa si la elige") })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }) as JsonObject
    )

    private fun cortar(s0: String, max: Int): String {
        val s = s0.trim()
        if (s.codePointCount(0, s.length) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, maxOf(max - 1, 0))) + "…"
    }

    private fun cadena(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Una opción: el objeto de la forma buena o, por si un modelo la simplifica, una cadena sola. */
    private fun opcion(v: JsonElement): Opcion? {
        val (etiqueta, descripcion) = when (v) {
            is JsonPrimitive -> if (v.isString) v.content to "" else return null
            is JsonObject -> (cadena(v["etiqueta"]) ?: cadena(v["label"]) ?: "") to (cadena(v["descripcion"]) ?: cadena(v["description"]) ?: "")
            else -> return null
        }
        val e = cortar(etiqueta, LARGO_ETIQUETA)
        return if (e.isEmpty()) null else Opcion(e, cortar(descripcion, LARGO_DESCRIPCION))
    }

    private fun pregunta(v: JsonElement): Pregunta {
        val o = v as? JsonObject
        val texto = (cadena(o?.get("pregunta")) ?: cadena(o?.get("question")))?.let { cortar(it, LARGO_PREGUNTA) }?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("falta «pregunta»: una sola pregunta concreta")
        var opciones = ((o?.get("opciones") ?: o?.get("options")) as? JsonArray)?.mapNotNull(::opcion).orEmpty()
        // «Otro» lo pone la app: si el modelo lo añade, sobra.
        opciones = opciones.filter { Acciones.normalizar(it.etiqueta).trimEnd('.', '…') !in setOf("otro", "otra", "otra cosa") }
        val vistas = HashSet<String>()
        opciones = opciones.filter { vistas.add(Acciones.normalizar(it.etiqueta)) }
        if (opciones.size < MIN_OPCIONES) throw IllegalArgumentException(
            "«$texto» necesita de $MIN_OPCIONES a $MAX_OPCIONES opciones distintas con su «etiqueta» (sin «Otro»: lo pone la app). Si no hay opciones claras, no preguntes: decide lo más razonable y dilo."
        )
        if (opciones.size > MAX_OPCIONES) throw IllegalArgumentException(
            "«$texto» tiene ${opciones.size} opciones: deja las $MAX_OPCIONES que más cambian el resultado, la recomendada primero"
        )
        return Pregunta(texto, opciones)
    }

    /**
     * **Lee los argumentos de la llamada.** Acepta también una pregunta sola sin la lista (lo que
     * escribe un modelo con prisa). El fallo lleva el motivo, para que el modelo lo corrija.
     */
    fun leer(argumentos: String): Result<List<Pregunta>> = runCatching {
        val v = Herramientas.repararJson(argumentos) as? JsonObject
            ?: throw IllegalArgumentException("los argumentos de preguntar_usuario no son JSON")
        val lista: List<JsonElement> = when (val p = v["preguntas"] ?: v["questions"]) {
            is JsonArray -> p
            is JsonObject -> listOf(p)
            else -> if (v["pregunta"] != null || v["question"] != null) listOf(v)
            else throw IllegalArgumentException("falta «preguntas»: [{\"pregunta\": \"…?\", \"opciones\": [{\"etiqueta\": \"…\"}, …]}]")
        }
        if (lista.isEmpty()) throw IllegalArgumentException("«preguntas» esta vacia: pon una pregunta")
        if (lista.size > MAX_PREGUNTAS) throw IllegalArgumentException(
            "como mucho $MAX_PREGUNTAS preguntas por llamada; mejor una: la que más cambia el resultado"
        )
        lista.map(::pregunta)
    }

    private fun recomendada(p: Pregunta) = p.opciones.firstOrNull()?.etiqueta ?: "lo más razonable"

    /** **Lo que vuelve al modelo** de una pregunta contestada. */
    fun paraElModelo(p: Pregunta, c: Contestada): String = when (c) {
        is Contestada.Opcion -> when (val o = p.opciones.getOrNull(c.n)) {
            null -> paraElModelo(p, Contestada.SinRespuesta)
            else -> if (o.descripcion.isEmpty()) "«${p.texto}» → el usuario eligió «${o.etiqueta}». Sigue con eso."
            else "«${p.texto}» → el usuario eligió «${o.etiqueta}» (${o.descripcion}). Sigue con eso."
        }
        is Contestada.Texto -> "«${p.texto}» → el usuario contestó con sus palabras: «${c.texto.trim()}». Sigue con eso."
        Contestada.SinRespuesta -> "«${p.texto}» → el usuario no contestó. No vuelvas a preguntarlo: sigue con la recomendada («${recomendada(p)}») y dilo en una línea al final."
    }

    /** Lo que queda escrito en la burbuja tras contestar: «❓ ¿…? → ✅ Solo la figura». */
    fun lineaContestada(p: Pregunta, c: Contestada): String {
        val fin = when (c) {
            is Contestada.Opcion -> p.opciones.getOrNull(c.n)?.let { "✅ ${it.etiqueta}" } ?: "⌛ sin respuesta"
            is Contestada.Texto -> "✍️ ${c.texto.trim()}"
            Contestada.SinRespuesta -> "⌛ sin respuesta: sigo con «${recomendada(p)}»"
        }
        return "❓ ${p.texto} → $fin"
    }
}
