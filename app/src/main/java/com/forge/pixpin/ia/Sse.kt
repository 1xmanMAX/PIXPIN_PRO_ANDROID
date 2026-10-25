package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/**
 * **Leer la respuesta en vivo** (Server-Sent Events al estilo OpenAI; `sse.rs` del PC).
 *
 * DeepSeek (y `llama-server` en el PC) manda líneas `data: {json}` con trocitos (`delta`) de texto, de razonamiento y
 * de llamadas a herramientas; las llamadas llegan por pedazos (`index` + nombre una vez +
 * argumentos poco a poco) y hay que juntarlas. Al final, `data: [DONE]`. No sabe nada de
 * sockets: se le dan bytes y devuelve trozos, para probarla sin red.
 */
class Acumulador {
    private val pendiente = ByteArrayOutputStream()
    private val texto = StringBuilder()
    private val pensamiento = StringBuilder()
    private val llamadas = mutableListOf<Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, nombre, argumentos
    private var fin: Fin? = null
    private var medidas = Medidas()

    var acabado = false
        private set

    /** Mete bytes y devuelve los trozos nuevos que salen de ellos. */
    fun meter(bytes: ByteArray, desde: Int = 0, cuantos: Int = bytes.size): List<Trozo> {
        pendiente.write(bytes, desde, cuantos)
        val trozos = mutableListOf<Trozo>()
        var todo = pendiente.toByteArray()
        var inicio = 0
        while (true) {
            var fin = -1
            for (i in inicio until todo.size) if (todo[i] == '\n'.code.toByte()) { fin = i; break }
            if (fin < 0) break
            // Se corta por bytes y se decodifica la línea entera: una tilde partida entre dos
            // paquetes no se rompe.
            val linea = String(todo, inicio, fin - inicio, Charsets.UTF_8).trimEnd('\r', '\n')
            inicio = fin + 1
            if (linea.startsWith("data:")) evento(linea.removePrefix("data:").trim(), trozos)
            // Las líneas vacías separan eventos; `:` son latidos. Se ignoran.
        }
        pendiente.reset()
        if (inicio < todo.size) pendiente.write(todo, inicio, todo.size - inicio)
        todo = ByteArray(0)
        return trozos
    }

    private fun evento(datos: String, trozos: MutableList<Trozo>) {
        if (datos == "[DONE]") { acabado = true; return }
        val v = runCatching { Json.parseToJsonElement(datos).jsonObject }
            .getOrElse { throw ErrorIa.Protocolo("evento ilegible: ${datos.take(120)}") }
        (v["error"] as? JsonObject)?.let { throw ErrorIa.Protocolo(it.texto("message") ?: "error del motor") }
        (v["usage"] as? JsonObject)?.let { u ->
            medidas = medidas.copy(tokensEntrada = num(u, "prompt_tokens").toInt(), tokensSalida = num(u, "completion_tokens").toInt())
        }
        (v["timings"] as? JsonObject)?.let { t ->
            medidas = medidas.copy(
                tokensPorSegundoLectura = num(t, "prompt_per_second"),
                tokensPorSegundoEscritura = num(t, "predicted_per_second")
            )
        }
        val eleccion = (v["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val delta = (eleccion["delta"] ?: eleccion["message"]) as? JsonObject
        if (delta != null) {
            delta.texto("content")?.takeIf { it.isNotEmpty() }?.let { texto.append(it); trozos += Trozo.Texto(it) }
            delta.texto("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { pensamiento.append(it); trozos += Trozo.Pensando(it) }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, e ->
                val l = e as? JsonObject ?: return@forEachIndexed
                val indice = (l["index"] as? JsonPrimitive)?.intOrNull ?: pos
                if (indice > 64 || indice < 0) throw ErrorIa.Protocolo("demasiadas llamadas a la vez")
                while (llamadas.size <= indice) llamadas += Triple(StringBuilder(), StringBuilder(), StringBuilder())
                val (id, nombre, args) = llamadas[indice]
                l.texto("id")?.let { id.setLength(0); id.append(it) }
                (l["function"] as? JsonObject)?.let { f ->
                    f.texto("name")?.takeIf { it.isNotEmpty() }?.let {
                        val empezaba = nombre.isEmpty()
                        nombre.append(it)
                        if (empezaba) trozos += Trozo.Llamando(nombre.toString())
                    }
                    f.texto("arguments")?.let { args.append(it) }
                }
            }
        }
        eleccion.texto("finish_reason")?.let {
            fin = when (it) { "tool_calls" -> Fin.HERRAMIENTAS; "length" -> Fin.LONGITUD; else -> Fin.NATURAL }
        }
    }

    /** La respuesta entera. [cortado]: el usuario paró a medias. */
    fun respuesta(cortado: Boolean): Respuesta {
        val ls = llamadas.mapIndexed { i, (id, n, a) -> LlamadaCruda(id.toString().ifEmpty { "llamada-$i" }, n.toString(), a.toString()) }
            .filter { it.nombre.isNotEmpty() }
        val f = when {
            cortado -> Fin.CORTADO
            ls.isNotEmpty() -> Fin.HERRAMIENTAS
            else -> fin ?: Fin.NATURAL
        }
        return Respuesta(texto.toString(), pensamiento.toString(), ls, f, medidas)
    }

    private fun num(o: JsonObject, campo: String): Double = when (val e: JsonElement? = o[campo]) {
        is JsonPrimitive -> e.doubleOrNull ?: 0.0
        JsonNull, null -> 0.0
        else -> 0.0
    }
}
