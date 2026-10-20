package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * **El contrato con el motor** (`motor.rs` del PC): lo único que el resto de PixPin ve del
 * modelo. La conversación sigue la forma de la API de chat de OpenAI —la que habla
 * `llama-server`—, así que cambiar de motor no toca ni el bucle del agente ni el chat.
 */
enum class Rol(val nombre: String) { SISTEMA("system"), USUARIO("user"), ASISTENTE("assistant"), HERRAMIENTA("tool") }

/** Un trozo de un mensaje. */
sealed class Parte {
    data class Texto(val texto: String) : Parte()

    /** Una imagen ya codificada (JPEG o PNG). El chat la reduce antes a ~768 px. */
    class Imagen(val bytes: ByteArray, val mime: String) : Parte()
}

/** Una llamada a herramienta tal como la escribió el modelo, sin validar. */
data class LlamadaCruda(val id: String, val nombre: String, val argumentos: String)

/** Un mensaje de la conversación con el modelo (no confundir con el del chat de PixPin). */
data class MensajeIa(
    val rol: Rol,
    val partes: List<Parte>,
    /** Solo del asistente: las herramientas que pidió. */
    val llamadas: List<LlamadaCruda> = emptyList(),
    /** Solo de herramienta: a qué llamada responde. */
    val idLlamada: String? = null
) {
    val textoEntero: String get() = partes.filterIsInstance<Parte.Texto>().joinToString("") { it.texto }
    val tieneImagen: Boolean get() = partes.any { it is Parte.Imagen }

    /** En la forma de la API de chat de OpenAI. */
    fun aJson(): JsonObject = buildJsonObject {
        put("role", rol.nombre)
        if (tieneImagen) {
            put("content", buildJsonArray {
                for (p in partes) add(
                    when (p) {
                        is Parte.Texto -> buildJsonObject { put("type", "text"); put("text", p.texto) }
                        is Parte.Imagen -> buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:${p.mime};base64," + Base64.getEncoder().encodeToString(p.bytes))
                            })
                        }
                    }
                )
            })
        } else put("content", textoEntero)
        if (llamadas.isNotEmpty()) put("tool_calls", JsonArray(llamadas.map { l ->
            buildJsonObject {
                put("id", l.id)
                put("type", "function")
                put("function", buildJsonObject { put("name", l.nombre); put("arguments", l.argumentos) })
            }
        }))
        idLlamada?.let { put("tool_call_id", it) }
    }

    companion object {
        fun texto(rol: Rol, t: String) = MensajeIa(rol, listOf(Parte.Texto(t)))
        fun sistema(t: String) = texto(Rol.SISTEMA, t)
        fun usuario(t: String) = texto(Rol.USUARIO, t)

        /** La imagen va **antes** del texto (lo recomienda Qwen). */
        fun usuarioConImagen(t: String, bytes: ByteArray, mime: String) =
            MensajeIa(Rol.USUARIO, listOf(Parte.Imagen(bytes, mime), Parte.Texto(t)))

        fun resultado(idLlamada: String, t: String) = MensajeIa(Rol.HERRAMIENTA, listOf(Parte.Texto(t)), idLlamada = idLlamada)
    }
}

/** Lo que se le pide al motor en una vuelta. */
data class Peticion(
    val mensajes: List<MensajeIa>,
    val herramientas: List<Herramienta> = emptyList(),
    val maxTokens: Int = 1024,
    val temperatura: Double = 0.7,
    /** Qwen3.5 piensa en voz alta si se le deja; el 0.8B entra en bucles. Siempre apagado. */
    val pensar: Boolean = false
) {
    /** El cuerpo de `/v1/chat/completions`, con lo que recomienda Qwen3.5 sin pensar. */
    fun aJson(enVivo: Boolean): JsonObject = buildJsonObject {
        put("messages", JsonArray(mensajes.map { it.aJson() }))
        put("max_tokens", maxTokens)
        put("temperature", temperatura)
        put("top_p", 0.8)
        put("top_k", 20)
        put("presence_penalty", 1.5)
        put("stream", enVivo)
        // Que la ranura guarde lo leído: la vuelta siguiente solo lee lo nuevo.
        put("cache_prompt", true)
        put("chat_template_kwargs", buildJsonObject { put("enable_thinking", pensar) })
        if (enVivo) put("stream_options", buildJsonObject { put("include_usage", true) })
        if (herramientas.isNotEmpty()) put("tools", JsonArray(herramientas.map { it.aJson() }))
    }
}

/** Un trozo de la respuesta mientras llega. */
sealed class Trozo {
    data class Texto(val texto: String) : Trozo()
    data class Pensando(val texto: String) : Trozo()

    /** Empezó a escribir una llamada (para enseñar «dibujando…»). */
    data class Llamando(val nombre: String) : Trozo()
}

/** Si seguir escuchando o cortar (el usuario pulsó «Parar»). */
enum class Seguir { SI, PARAR }

/** Por qué acabó la respuesta. */
enum class Fin { NATURAL, HERRAMIENTAS, LONGITUD, CORTADO }

data class Medidas(
    val tokensEntrada: Int = 0,
    val tokensSalida: Int = 0,
    val tokensPorSegundoLectura: Double = 0.0,
    val tokensPorSegundoEscritura: Double = 0.0
)

data class Respuesta(
    val texto: String,
    val pensamiento: String = "",
    val llamadas: List<LlamadaCruda> = emptyList(),
    val fin: Fin = Fin.NATURAL,
    val medidas: Medidas = Medidas()
) {
    companion object {
        fun deTexto(t: String) = Respuesta(t)
        fun deLlamada(nombre: String, argumentos: String) =
            Respuesta("", llamadas = listOf(LlamadaCruda("llamada-$nombre", nombre, argumentos)), fin = Fin.HERRAMIENTAS)
    }
}

/**
 * **El motor.** Bloquea hasta acabar: se llama desde un hilo de fondo (una corrutina en
 * `Dispatchers.IO`), y [alTrozo] recibe la respuesta a medida que sale.
 */
interface Motor {
    fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta

    /** Soltar la memoria ya (cerrar el modelo). Se vuelve a cargar en la siguiente petición. */
    fun soltar() {}

    /** Dejarlo listo sin esperar a la primera pregunta. Bloquea. */
    fun precalentar() {}

    /**
     * Si se le manda el catálogo entero en cada turno (la nube: un prefijo estable que se cachea)
     * en vez de las pocas que elige [Seleccion] (el modelo local).
     */
    val catalogoCompleto: Boolean get() = false

    /** **Si ve imágenes** (PC `ve_imagenes`): la nube sí; el local, por ahora no (el usuario, 15-oct). */
    val veImagenes: Boolean get() = false
}

/**
 * **Un motor de mentira** que contesta lo que se le dice, en orden (`MotorGuionado` del PC). Para
 * las pruebas del agente y del chat sin bajar nada.
 */
class MotorGuionado(guion: List<Respuesta>, private val vision: Boolean = false) : Motor {
    override val veImagenes: Boolean get() = vision
    private val guion = ArrayDeque(guion)
    val recibido = mutableListOf<Peticion>()

    @Synchronized
    override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta {
        recibido += peticion
        val r = guion.removeFirstOrNull() ?: throw ErrorIa.Protocolo("el guion se acabo")
        for (palabra in trozosDe(r.pensamiento)) if (alTrozo(Trozo.Pensando(palabra)) == Seguir.PARAR) return r.copy(fin = Fin.CORTADO)
        for (palabra in trozosDe(r.texto)) if (alTrozo(Trozo.Texto(palabra)) == Seguir.PARAR) return r.copy(fin = Fin.CORTADO)
        for (l in r.llamadas) alTrozo(Trozo.Llamando(l.nombre))
        return r
    }

    /** «Hola que tal» → «Hola », «que », «tal», como `split_inclusive(' ')`. */
    private fun trozosDe(t: String): List<String> {
        if (t.isEmpty()) return emptyList()
        val salida = mutableListOf<String>()
        var desde = 0
        for (i in t.indices) if (t[i] == ' ') { salida += t.substring(desde, i + 1); desde = i + 1 }
        if (desde < t.length) salida += t.substring(desde)
        return salida
    }
}

internal fun JsonObject.texto(campo: String): String? = (this[campo] as? JsonPrimitive)?.takeIf { it.isString }?.content
