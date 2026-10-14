package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * **Un turno del asistente** (`agente.rs` del PC): pedir al modelo, validar lo que pide, hacerlo
 * y devolverle el resultado, hasta que conteste con texto.
 *
 * ```text
 * usuario ─▶ modelo ─▶ ¿llamadas? ──no──▶ texto final
 *                         │ sí
 *                         ▼
 *                     validar ──mal──▶ error al modelo (reintento, máx. 2)
 *                         │ bien
 *                         ▼
 *                  Ejecutor (el chat) ──▶ resultado al modelo ──▶ otra vuelta
 * ```
 *
 * El bucle no sabe dibujar ni leer: eso lo hace el [Ejecutor] del chat. Así el mismo bucle sirve
 * para las pruebas (con un motor y un ejecutor de mentira) y para el teléfono. Mismos topes que el
 * PC: [VUELTAS] vueltas, [REINTENTOS] reintentos, listas acotadas por la gramática de llama.cpp
 * ([conTopes]) y respuesta que cabe en el contexto ([topeDeTokens]).
 */
object Agente {
    /** Cuántas veces se deja al modelo corregir una llamada mal hecha en un mismo turno. */
    const val REINTENTOS = 2

    /** Cuántas vueltas modelo→herramienta→modelo como mucho (un modelo pequeño puede quedarse en bucle). */
    const val VUELTAS = 4

    const val SIN_PALABRAS = "No sé qué decir sobre eso. ¿Qué quieres que haga?"
    const val HECHO = "Hecho."
    const val HECHO_EN_PARTE = "Hecho en parte: lo demas no me salio con las herramientas."
    const val NO_SUPE = "No he sabido hacerlo con las herramientas; prueba a pedirlo de otra forma o por partes."

    /** Tope de tokens de cada vuelta, y lo mínimo aunque el contexto vaya lleno. */
    const val MAX_TOKENS_CON_HERRAMIENTAS = 3072
    const val MIN_TOKENS = 512

    /** Cuántas figuras puede llevar una llamada, y tras una cortada. Las listas de números, el doble. */
    const val TOPE_LISTAS = 16L
    const val TOPE_LISTAS_REINTENTO = 10L

    /** Lo más largo que puede ser un texto suelto dentro de una llamada. */
    const val TOPE_TEXTO = 120L

    /** Lo más largo que se devuelve al modelo de un resultado de herramienta. */
    const val RESULTADO_MAXIMO = 4000

    /** Lo que pasa durante un turno, para pintarlo en vivo. */
    sealed class Evento {
        data class Texto(val texto: String) : Evento()
        data class Llamando(val nombre: String) : Evento()
        data class Herramienta(val llamada: Llamada, val avisos: List<String>) : Evento()
        data class Resultado(val nombre: String, val ok: Boolean, val texto: String) : Evento()
        data class Reintento(val motivo: String) : Evento()
    }

    /** **El punto de enganche del chat**: hace de verdad cada herramienta. */
    fun interface Ejecutor {
        /** `Result.success` con un resumen corto para el modelo; `failure` con el motivo. */
        fun ejecutar(llamada: Llamada): Result<String>
    }

    data class Final(val texto: String, val herramientasHechas: Int, val reintentos: Int, val cortado: Boolean)

    /**
     * **Un turno.** [historial] trae el mensaje nuevo del usuario al final; al acabar trae también
     * lo que dijo el asistente (con sus llamadas y resultados), listo para la vuelta siguiente.
     * [contexto]: los tokens con los que se arrancó el modelo.
     */
    fun turno(
        motor: Motor,
        historial: MutableList<MensajeIa>,
        herramientas: List<Herramienta>,
        ejecutor: Ejecutor,
        contexto: Int = ServidorLlama.CONTEXTO,
        alEvento: (Evento) -> Seguir
    ): Final {
        var hechas = 0
        var reintentos = 0
        var cortado = false
        // Con la gramática de llama.cpp el modelo NO PUEDE pasarse de estas listas; bajan si una
        // llamada salió cortada.
        var tope = TOPE_LISTAS
        repeat(VUELTAS + REINTENTOS) {
            val ofrecidas = conTopes(herramientas, tope)
            var peticion = Peticion(historial.toList(), ofrecidas)
            peticion = peticion.copy(maxTokens = topeDeTokens(peticion, contexto))
            val respuesta = try {
                motor.completar(peticion) { t ->
                    when (t) {
                        is Trozo.Texto -> alEvento(Evento.Texto(t.texto))
                        is Trozo.Llamando -> alEvento(Evento.Llamando(t.nombre))
                        is Trozo.Pensando -> Seguir.SI
                    }
                }
            } catch (e: ErrorIa.Protocolo) {
                // `llama-server` no deja pasar una llamada cuyo JSON no cierra (HTTP 500): es una
                // llamada cortada por el tope; se pide MÁS CORTA y la gramática se lo impone.
                if (!llamadaRota(e.detalle)) throw e
                reintentos++
                if (reintentos > REINTENTOS) return final(hechas, reintentos, cortado)
                tope = TOPE_LISTAS_REINTENTO
                val motivo = pedirMasCorto()
                if (alEvento(Evento.Reintento(motivo)) == Seguir.PARAR) return final(hechas, reintentos, true)
                historial += MensajeIa.usuario(motivo)
                return@repeat
            }
            val texto = quitarPensado(respuesta.texto)
            historial += MensajeIa(Rol.ASISTENTE, listOf(Parte.Texto(texto)), respuesta.llamadas)
            if (respuesta.fin == Fin.CORTADO) return final(hechas, reintentos, true)
            if (respuesta.llamadas.isEmpty()) {
                val dicho = when {
                    texto.isNotBlank() -> texto
                    hechas > 0 -> HECHO
                    else -> SIN_PALABRAS
                }
                return Final(dicho, hechas, reintentos, false)
            }
            var algunaMal = false
            // Lo que pidió el usuario, para los colores que el modelo olvide.
            val pedido = historial.lastOrNull { it.rol == Rol.USUARIO }?.textoEntero.orEmpty()
            for (cruda in respuesta.llamadas) {
                val validada = try {
                    Herramientas.validar(sanear(cruda), herramientas)
                } catch (e: ErrorHerramienta) {
                    algunaMal = true
                    // La siguiente, corta: una llamada larga que sale mal es la que más tarda en repetirse.
                    tope = TOPE_LISTAS_REINTENTO
                    val motivo = if (e is ErrorHerramienta.JsonRoto || respuesta.fin == Fin.LONGITUD) pedirMasCorto()
                    else e.paraElModelo(herramientas)
                    if (alEvento(Evento.Reintento(motivo)) == Seguir.PARAR) cortado = true
                    historial += MensajeIa.resultado(cruda.id, motivo)
                    continue
                }
                val llamada = when (val l = validada.llamada) {
                    is Llamada.Dibujar -> l.copy(figuras = Herramientas.coloresDelPedido(l.figuras, pedido))
                    else -> l
                }
                alEvento(Evento.Herramienta(llamada, validada.avisos))
                val r = runCatching { ejecutor.ejecutar(llamada) }.getOrElse { Result.failure(it) }
                val ok = r.isSuccess
                val dicho = r.getOrElse { "ERROR: ${it.message ?: "no se pudo"}" }
                if (ok) hechas++
                alEvento(Evento.Resultado(llamada.nombre, ok, dicho))
                var paraElModelo = recortarResultado(dicho)
                if (validada.avisos.isNotEmpty()) paraElModelo += " (avisos: ${validada.avisos.joinToString("; ")})"
                historial += MensajeIa.resultado(cruda.id, paraElModelo)
            }
            if (cortado) return final(hechas, reintentos, true)
            if (algunaMal) {
                reintentos++
                if (reintentos > REINTENTOS) return final(hechas, reintentos, false)
            }
        }
        return final(hechas, reintentos, cortado)
    }

    /** Se acabaron las vueltas o los reintentos (o se cortó): se dice claro en vez de una burbuja vacía. */
    private fun final(hechas: Int, reintentos: Int, cortado: Boolean): Final {
        val texto = when {
            cortado -> ""
            hechas > 0 -> HECHO_EN_PARTE
            else -> NO_SUPE
        }
        return Final(texto, hechas, reintentos, cortado)
    }

    /** Qwen a veces escribe su `<think>…</think>` aunque no se le pida pensar: fuera. */
    internal fun quitarPensado(t: String): String {
        val cierre = t.lastIndexOf("</think>")
        return if (cierre >= 0) t.substring(cierre + "</think>".length).trimStart() else t
    }

    fun recortarResultado(texto: String): String {
        if (texto.codePointCount(0, texto.length) <= RESULTADO_MAXIMO) return texto
        val cps = texto.codePoints().limit(RESULTADO_MAXIMO.toLong()).toArray()
        return String(cps, 0, cps.size) + "\n[...recortado: el usuario tiene el resultado entero]"
    }

    /**
     * **Cuánta respuesta se pide**: [MAX_TOKENS_CON_HERRAMIENTAS], o lo que quepa en el contexto con
     * lo que ya lleva la conversación (por lo alto: 3 letras por token y ~1000 por foto).
     */
    fun topeDeTokens(p: Peticion, contexto: Int): Int {
        val letras = p.mensajes.sumOf { m -> m.textoEntero.length + m.llamadas.sumOf { it.argumentos.length } } +
            p.herramientas.sumOf { it.aJson().toString().length }
        val fotos = p.mensajes.count { it.tieneImagen }
        val entrada = letras / 3 + 1000 * fotos + 200
        return (contexto - entrada).coerceAtLeast(0).coerceIn(MIN_TOKENS, MAX_TOKENS_CON_HERRAMIENTAS)
    }

    /**
     * **Las herramientas con sus listas y textos acotados** (`con_topes` del PC). `llama-server`
     * convierte el esquema en una gramática: con `maxItems` una llamada no puede crecer hasta
     * cortarse a medias. Solo se recorta lo de la petición; la validación usa los límites de verdad.
     */
    fun conTopes(herramientas: List<Herramienta>, tope: Long): List<Herramienta> =
        herramientas.map { it.copy(parametros = acotar(it.parametros, tope) as JsonObject) }

    private fun acotar(v: JsonElement, tope: Long): JsonElement {
        if (v is JsonArray) return JsonArray(v.map { acotar(it, tope) })
        if (v !is JsonObject) return v
        val o = LinkedHashMap<String, JsonElement>()
        for ((k, x) in v) o[k] = if (x is JsonObject || x is JsonArray) acotar(x, tope) else x
        when ((v["type"] as? JsonPrimitive)?.content) {
            "array" -> {
                val deObjetos = ((v["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "object"
                val t = if (deObjetos) tope else 2 * tope
                val max = (v["maxItems"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { minOf(it, t) } ?: t
                o["maxItems"] = JsonPrimitive(max)
            }
            // Un texto sin tope propio es una etiqueta o un título.
            "string" -> if ("enum" !in v && "maxLength" !in v) o["maxLength"] = JsonPrimitive(TOPE_TEXTO)
        }
        return JsonObject(o)
    }

    /**
     * **Lleva al borde las coordenadas que se salen un poco**, antes de validar (`sanear` del PC):
     * el 2B escribe a veces `x: 2010` en la figura 12 de 16 y se perdía la llamada entera.
     */
    fun sanear(cruda: LlamadaCruda): LlamadaCruda {
        val v = runCatching { Json.parseToJsonElement(cruda.argumentos) }.getOrNull() ?: return cruda
        var cambios = 0
        fun llevar(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { (k, x) ->
                val rango = when (k) {
                    "x", "y", "x2", "y2" -> Herramientas.COORDENADA_MINIMA to Herramientas.COORDENADA_MAXIMA
                    "ancho", "alto" -> -Herramientas.COORDENADA_MAXIMA to Herramientas.COORDENADA_MAXIMA
                    else -> null
                }
                val n = (x as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (rango != null && n != null && n.isFinite() && (n < rango.first || n > rango.second)) {
                    cambios++
                    JsonPrimitive(n.coerceIn(rango.first, rango.second))
                } else llevar(x)
            })
            is JsonArray -> JsonArray(e.map { llevar(it) })
            else -> e
        }
        val nuevo = llevar(v)
        return if (cambios == 0) cruda else cruda.copy(argumentos = nuevo.toString())
    }

    private fun pedirMasCorto() =
        "ERROR: tu llamada a la herramienta salio cortada porque era demasiado larga. " +
            "Vuelve a llamarla con $TOPE_LISTAS_REINTENTO elementos como mucho, solo las formas principales; " +
            "si hace falta mas, haz varias llamadas seguidas."

    /** Si el error del motor es una llamada con el JSON roto (lo que contesta `llama-server`). */
    internal fun llamadaRota(m: String): Boolean {
        val t = m.lowercase()
        return "tool call" in t && ("json" in t || "parse" in t)
    }

    /**
     * **Las instrucciones de sistema** (`agente::instrucciones` del PC, con solo lo que el teléfono
     * sabe hacer hoy). Cortas: cada token cuenta en cada vuelta, y los modelos pequeños siguen
     * mejor pocas reglas claras.
     */
    fun instrucciones(): String =
        "Eres el asistente de PixPin, una app de capturas, notas y lienzo. " +
            "Contesta en el idioma del usuario, breve y claro. " +
            "Si pide dibujar algo, usa dibujar_en_lienzo, poniendo el color de cada figura. " +
            "Si da datos para graficar, usa grafica. " +
            "Si quiere el texto de un documento escaneado o de una foto, usa ocr. " +
            "Despues de usar una herramienta, di en una frase lo que hiciste. " +
            "Estas en un telefono Android, sin internet: no inventes enlaces ni funciones que no tienes."
}
