package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.TreeMap

/**
 * **Jev de TypeSafe en Android** (28-oct-2026; porte de `crates/pixpin-typesafe` del PC, `e6ccca92`, y
 * de su `docs/jev.md`).
 *
 * Jev es un modelo «System One»: no escribe texto, **contesta preguntas tipadas** sobre un estado que
 * se le da (texto, JSON con campos con nombre e imágenes) con probabilidades calibradas, en 70-500 ms.
 * Cuatro tipos: **Noul** (la probabilidad de que algo sea verdad), **Choice** (una de varias opciones),
 * **Score** (un nivel de una escala descrita nivel a nivel) y **Box** (dónde está algo en una imagen).
 *
 * La regla, la del PC: **los juicios de Jev, las decisiones en código**. Jev dice «este renglón encarga
 * una tarea: 0.94»; qué se marca, qué tarjeta va a Anki, qué prioridad tiene una tarea o qué modelo
 * contesta lo decide una tabla escrita aquí ([Reunion], [Anki], [Urgencia], [Modelo]), con sus pruebas.
 * Todo el texto de las preguntas está en [Preguntas]. **Sin clave no se llama a nadie**, y un fallo es
 * un [ErrorJev], **nunca un «no»**: quien llama hace lo de siempre.
 *
 * Este fichero es lo que viaja (`api.rs`): la petición `POST /v1/systemone` y su respuesta, como las
 * describe el OpenAPI de TypeSafe (versión 0.2.0, leído el 28-oct-2026).
 */
const val MODELO_JEV = "jev-latest"

/** **Por qué no hubo respuesta** (`pixpin_typesafe::Error`). Ninguno es un «no». */
sealed class ErrorJev(mensaje: String) : Exception(mensaje) {
    /** No hay clave guardada. */
    class SinClave : ErrorJev("falta la clave de TypeSafe")
    /** No se llegó a hablar con TypeSafe (red, DNS, TLS, tiempo). */
    class Red(val detalle: String) : ErrorJev("sin conexión con TypeSafe: $detalle")
    /** TypeSafe contestó con un error (401 clave, 422 petición, 429…). */
    class Http(val codigo: Int, val detalle: String) : ErrorJev(
        when (codigo) {
            401 -> "TypeSafe no acepta la clave (401)"
            429 -> "TypeSafe pide esperar: límite de peticiones (429)"
            else -> "TypeSafe contestó $codigo: $detalle"
        }
    )
    /** Lo que llegó no se entiende, o la petición no se pudo escribir. */
    class Formato(val detalle: String) : ErrorJev("respuesta de TypeSafe que no se entiende: $detalle")
    /** Falta la respuesta a una pregunta. */
    class FaltaRespuesta(val nombre: String) : ErrorJev("TypeSafe no contestó «$nombre»")
    /** La respuesta es de otro tipo que la pregunta. */
    class OtroTipo(val nombre: String, val esperado: String, val llego: String) : ErrorJev("«$nombre»: se esperaba $esperado y llegó $llego")
}

/** Recorta un texto a [n] caracteres (con «…»), sin partir un carácter (`recortar` del PC). */
internal fun recortar(s0: String, n: Int): String {
    val s = s0.trim()
    val total = s.codePointCount(0, s.length)
    return if (total <= n) s else s.substring(0, s.offsetByCodePoints(0, n)) + "…"
}

/** **Una pregunta**, de uno de los cuatro tipos. */
sealed class Pregunta {
    abstract val instrucciones: String
    abstract val tipo: String

    /** ¿Es verdad? Contesta la probabilidad de «sí». */
    data class Noul(override val instrucciones: String, val si: String?, val no: String?) : Pregunta() {
        override val tipo get() = "noul"
    }

    /** Una de varias opciones, **en el orden escrito** (el orden pesa). */
    data class Choice(override val instrucciones: String, val opciones: List<Pair<String, String>>) : Pregunta() {
        override val tipo get() = "choice"
    }

    /** Un nivel de una escala descrita nivel a nivel (del 0 en adelante). */
    data class Score(override val instrucciones: String, val niveles: List<String>) : Pregunta() {
        override val tipo get() = "score"
    }

    /** Dónde está algo en una imagen (pide la cabecera de las imágenes). */
    data class Caja(override val instrucciones: String) : Pregunta() {
        override val tipo get() = "box"
    }

    /** Como va en el JSON: `type` primero y luego sus campos (el orden de `serde` del PC). */
    fun aJson(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["type"] = JsonPrimitive(tipo)
        m["instructions"] = JsonPrimitive(instrucciones)
        when (this) {
            is Noul -> if (si != null && no != null) m["criteria"] = JsonObject(linkedMapOf("true" to JsonPrimitive(si), "false" to JsonPrimitive(no)))
            is Choice -> m["criteria"] = JsonObject(LinkedHashMap<String, JsonElement>().apply { for ((k, d) in opciones) put(k, JsonPrimitive(d)) })
            is Score -> m["criteria"] = JsonArray(niveles.map(::JsonPrimitive))
            is Caja -> {}
        }
        return JsonObject(m)
    }

    companion object {
        fun noul(instrucciones: String, si: String, no: String): Pregunta = Noul(instrucciones, si, no)
        fun score(instrucciones: String, niveles: List<String>): Pregunta = Score(instrucciones, niveles)
        fun choice(instrucciones: String, opciones: List<Pair<String, String>>): Pregunta = Choice(instrucciones, opciones)
        fun caja(instrucciones: String): Pregunta = Caja(instrucciones)
    }
}

/** Una imagen de la petición: va en `assets` con su referencia. */
class Imagen(val formato: String, val bytes: ByteArray)

/**
 * **Una petición**: el estado y las preguntas que se le hacen. Las preguntas, por nombre (en orden de
 * nombre, como el `BTreeMap` del PC); el estado, con sus claves en orden alfabético por lo mismo.
 */
data class Peticion(
    val estado: JsonObject,
    val preguntas: TreeMap<String, Pregunta> = TreeMap(),
    val imagenes: TreeMap<String, Imagen> = TreeMap(),
    val modelo: String = MODELO_JEV
) {
    /** Añade una pregunta con su nombre (el de la respuesta). */
    fun con(nombre: String, p: Pregunta): Peticion = copy(preguntas = TreeMap(preguntas).apply { put(nombre, p) })

    fun conTodas(lista: List<Pair<String, Pregunta>>): Peticion = lista.fold(this) { p, (n, q) -> p.con(n, q) }

    fun conImagen(referencia: String, i: Imagen): Peticion = copy(imagenes = TreeMap(imagenes).apply { put(referencia, i) })

    /** Si hay que mandar `TypeSafe-Danger-Zone-Images: true`: con imágenes o con algún Box. */
    fun conImagenes(): Boolean = imagenes.isNotEmpty() || preguntas.values.any { it is Pregunta.Caja }

    private fun json(conBytes: Boolean): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["model"] = JsonPrimitive(modelo)
        m["state"] = ordenado(estado)
        m["questions"] = JsonObject(LinkedHashMap<String, JsonElement>().apply { for ((k, p) in preguntas) put(k, p.aJson()) })
        if (imagenes.isNotEmpty()) m["assets"] = JsonObject(LinkedHashMap<String, JsonElement>().apply {
            for ((k, i) in imagenes) put(k, JsonObject(linkedMapOf(
                "type" to JsonPrimitive("image"),
                "format" to JsonPrimitive(i.formato),
                "data_base64" to JsonPrimitive(if (conBytes) Base64.getEncoder().encodeToString(i.bytes) else "")
            )))
        })
        return JsonObject(m)
    }

    /** **El cuerpo exacto que se manda.** Sin preguntas, error. */
    fun cuerpo(): ByteArray {
        if (preguntas.isEmpty()) throw ErrorJev.Formato("una petición sin preguntas")
        return json(true).toString().toByteArray(Charsets.UTF_8)
    }

    /** El mismo cuerpo para el registro: las imágenes cambiadas por su tamaño. Nunca lleva la clave. */
    fun paraElRegistro(): String {
        val cuerpo = json(false).toString()
        if (imagenes.isEmpty()) return cuerpo
        return "$cuerpo [imagenes: " + imagenes.entries.joinToString(", ") { (k, i) -> "$k: ${i.bytes.size} bytes ${i.formato}" } + "]"
    }

    companion object {
        /** Un objeto con sus claves en orden alfabético, por dentro también (el `Value` del PC). */
        fun ordenado(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(sortedMapOf<String, JsonElement>().apply { for ((k, v) in e) put(k, ordenado(v)) })
            is JsonArray -> JsonArray(e.map(::ordenado))
            else -> e
        }

        fun ordenado(o: JsonObject): JsonObject = ordenado(o as JsonElement) as JsonObject
    }
}

/** Un Score leído: el valor esperado y la probabilidad de cada nivel, por su posición. */
data class Escala(val valor: Double, val confianza: Double, val niveles: List<Double>) {
    /** La probabilidad de que el nivel sea [n] o más. */
    fun alMenos(n: Int): Double = niveles.drop(n).sum()

    /** El nivel más probable. */
    fun masProbable(): Int = niveles.indices.maxByOrNull { niveles[it] } ?: 0

    companion object {
        /** Una escala con estos niveles (el valor, su esperanza): para las pruebas y lo grabado. */
        fun de(niveles: List<Double>) = Escala(niveles.withIndex().sumOf { (i, p) -> i * p }, 0.6, niveles)
    }
}

/** Una caja en una imagen: sus bordes en fracciones del alto y del ancho. */
data class CajaEnImagen(val arriba: Double, val abajo: Double, val izquierda: Double, val derecha: Double)

/** **Lo que contestó una petición entera** (`Respuestas` del PC). */
class Respuestas(val modelo: String, private val respuestas: Map<String, JsonObject>, val tokensDeEntrada: Long) {

    fun tiene(nombre: String) = nombre in respuestas

    private fun una(nombre: String): JsonObject = respuestas[nombre] ?: throw ErrorJev.FaltaRespuesta(nombre)

    private fun tipoDe(o: JsonObject) = (o["type"] as? JsonPrimitive)?.contentOrNull ?: "?"

    private fun numero(o: JsonObject, k: String, nombre: String): Double =
        (o[k] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull ?: throw ErrorJev.Formato("$nombre: falta «$k»")

    /** Una probabilidad que tiene sentido (0..1). Fuera de ahí es una respuesta rota, no un «no». */
    private fun probabilidad(nombre: String, p: Double): Double =
        if (p.isFinite() && p in 0.0..1.0) p else throw ErrorJev.Formato("$nombre: probabilidad fuera de rango ($p)")

    private fun esperar(o: JsonObject, nombre: String, tipo: String) {
        val t = tipoDe(o)
        if (t != tipo) throw ErrorJev.OtroTipo(nombre, tipo, t)
    }

    /** La probabilidad de «sí» del Noul [nombre]. Falta o es de otro tipo: error, nunca un «no» callado. */
    fun noul(nombre: String): Double {
        val o = una(nombre)
        esperar(o, nombre, "noul")
        return probabilidad(nombre, numero(o, "noul", nombre))
    }

    /** La opción elegida de la Choice [nombre] y la probabilidad de cada una. */
    fun choice(nombre: String): Pair<String, Map<String, Double>> {
        val o = una(nombre)
        esperar(o, nombre, "choice")
        val elegida = (o["choice"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ErrorJev.Formato("$nombre: falta «choice»")
        val probs = (o["probabilities"] as? JsonObject)?.mapValues { (k, v) ->
            probabilidad(nombre, (v as? JsonPrimitive)?.doubleOrNull ?: throw ErrorJev.Formato("$nombre: «$k»"))
        } ?: emptyMap()
        return elegida to probs
    }

    /** El Score [nombre], con [n] niveles (los que se pidieron). Un nivel que no llega cuenta 0; una clave que no es un nivel, error. */
    fun score(nombre: String, n: Int): Escala {
        val o = una(nombre)
        esperar(o, nombre, "score")
        val niveles = DoubleArray(n)
        val probs = o["probabilities"] as? JsonObject ?: throw ErrorJev.Formato("$nombre: faltan las probabilidades")
        for ((k, v) in probs) {
            val i = k.toIntOrNull()?.takeIf { it in 0 until n } ?: throw ErrorJev.Formato("$nombre: nivel «$k»")
            niveles[i] = probabilidad(nombre, (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw ErrorJev.Formato("$nombre: nivel «$k»"))
        }
        val valor = numero(o, "score", nombre)
        if (!valor.isFinite()) throw ErrorJev.Formato("$nombre: score no es un número")
        val confianza = (o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return Escala(valor, confianza, niveles.toList())
    }

    /** La caja del Box [nombre]. */
    fun caja(nombre: String): CajaEnImagen {
        val o = una(nombre)
        esperar(o, nombre, "box")
        val b = o["box"] as? JsonObject ?: throw ErrorJev.Formato("$nombre: falta «box»")
        return CajaEnImagen(numero(b, "top", nombre), numero(b, "bottom", nombre), numero(b, "left", nombre), numero(b, "right", nombre))
    }

    companion object {
        /** Lee el JSON de la respuesta. Lo que no se entiende es un [ErrorJev.Formato]. */
        fun deJson(bytes: ByteArray): Respuestas {
            val v = runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) }.getOrNull() as? JsonObject
                ?: throw ErrorJev.Formato("no es un objeto JSON")
            val modelo = (v["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ErrorJev.Formato("falta «model»")
            val answers = v["answers"] as? JsonObject ?: throw ErrorJev.Formato("falta «answers»")
            val mapa = answers.mapValues { (k, a) -> a as? JsonObject ?: throw ErrorJev.Formato("«$k» no es un objeto") }
            val tokens = ((v["usage"] as? JsonObject)?.get("input_tokens") as? JsonPrimitive)?.longOrNull ?: 0
            return Respuestas(modelo, mapa, tokens)
        }
    }
}
