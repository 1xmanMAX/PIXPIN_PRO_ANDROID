package com.forge.pixpin.atajos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * **Lo reciente de «Buscar en PixPin»** (5-oct-2026, porte de `buscar_todo/recientes.rs` del PC):
 * las últimas búsquedas —las fichas de arriba, con su ✕ y «Borrar todas»— y lo último que se abrió
 * desde aquí («Abierto hace poco»).
 *
 * Vive en `buscar-recientes.json`, **solo en este aparato**: no se sincroniza (es como el historial
 * de un navegador). Los topes y las reglas son los del PC: 8 búsquedas, 6 abiertos, una letra sola
 * no se apunta, sin repetir sin mirar mayúsculas, y lo que crea algo («Nueva tarea», «Grabar») no
 * se recuerda como abierto porque no es algo a lo que se vuelva.
 *
 * Lo abierto se guarda por su **clave** («archivo/<id>», «proyecto/<id>»…) y no entero: al volver
 * a enseñarlo se busca entre lo que hay ahora, y lo que ya no está (un mensaje borrado) no sale.
 *
 * Sin Android: se prueba en `BuscarV2Test`.
 */
data class RecientesDelBuscador(
    val busquedas: List<String> = emptyList(),
    val abiertos: List<Abierto> = emptyList()
) {
    /** Algo abierto, con lo justo para enseñarlo aunque ya no se encuentre. */
    data class Abierto(val clave: String, val titulo: String, val detalle: String = "", val emoji: String = "")

    /** Apunta una búsqueda, la primera: limpia de blancos de más y una sola vez sin mirar mayúsculas. */
    fun conBusqueda(consulta: String): RecientesDelBuscador {
        val c = consulta.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        // Una letra sola no es una búsqueda que valga la pena recordar.
        if (c.length < 2) return this
        val baja = c.lowercase()
        return copy(busquedas = (listOf(c) + busquedas.filter { it.lowercase() != baja }).take(BUSQUEDAS))
    }

    fun sinBusqueda(i: Int): RecientesDelBuscador =
        if (i in busquedas.indices) copy(busquedas = busquedas.filterIndexed { j, _ -> j != i }) else this

    fun sinBusquedas(): RecientesDelBuscador = copy(busquedas = emptyList())

    /** Apunta lo abierto, lo primero. Lo que crea algo no se apunta (ver [valeComoAbierto]). */
    fun conAbierto(e: BuscarEnTodo.Elemento): RecientesDelBuscador {
        if (!valeComoAbierto(e)) return this
        val a = Abierto(claveDe(e), e.titulo, e.detalle, e.emoji)
        return copy(abiertos = (listOf(a) + abiertos.filter { it.clave != a.clave }).take(ABIERTOS))
    }

    /** Lo abierto que sigue existiendo, ya como elementos que se pueden abrir, en su orden. */
    fun abiertosEn(todo: List<BuscarEnTodo.Elemento>): List<BuscarEnTodo.Elemento> {
        if (abiertos.isEmpty()) return emptyList()
        val porClave = HashMap<String, BuscarEnTodo.Elemento>(todo.size * 2)
        for (e in todo) porClave.putIfAbsent(claveDe(e), e)
        return abiertos.mapNotNull { porClave[it.clave] }
    }

    fun aJson(): String = buildJsonObject {
        put("busquedas", JsonArray(busquedas.map { JsonPrimitive(it) }))
        put("abiertos", JsonArray(abiertos.map { a ->
            buildJsonObject {
                put("clave", a.clave); put("titulo", a.titulo); put("subtitulo", a.detalle); put("glifo", a.emoji)
            }
        }))
    }.toString()

    companion object {
        /** Cuántas búsquedas se recuerdan (como en el PC). */
        const val BUSQUEDAS = 8

        /** Cuántas cosas abiertas se recuerdan (como en el PC). */
        const val ABIERTOS = 6

        const val NOMBRE = "buscar-recientes.json"

        /** Las funciones que crean algo con lo de ahora: no son algo que se vuelva a abrir. */
        private val QUE_CREAN = setOf(Atajos.GRABAR, Atajos.TAREA, Atajos.LECCION, Atajos.CAPTURAR, Atajos.NOTA, Atajos.SOLTAR)

        fun valeComoAbierto(e: BuscarEnTodo.Elemento): Boolean =
            !(e.tipo == BuscarEnTodo.Tipo.FUNCION && e.accion in QUE_CREAN)

        /** Lo que identifica a un elemento entre arranques: su tipo y su id, mensaje o acción. */
        fun claveDe(e: BuscarEnTodo.Elemento): String {
            val tipo = e.tipo.name.lowercase()
            val id = when (e.tipo) {
                BuscarEnTodo.Tipo.FUNCION -> e.accion
                BuscarEnTodo.Tipo.PROYECTO, BuscarEnTodo.Tipo.LECCION -> e.id
                else -> e.mensaje?.id
            } ?: e.titulo
            return "$tipo/$id"
        }

        /** Un archivo roto o que no está es una lista vacía, nunca un error. */
        fun leer(texto: String?): RecientesDelBuscador {
            if (texto.isNullOrBlank()) return RecientesDelBuscador()
            return runCatching {
                val o = Json.parseToJsonElement(texto).jsonObject
                val busquedas = (o["busquedas"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                val abiertos = (o["abiertos"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val a = el as? JsonObject ?: return@mapNotNull null
                    fun txt(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val clave = txt("clave").ifBlank { return@mapNotNull null }
                    Abierto(clave, txt("titulo"), txt("subtitulo"), txt("glifo"))
                }
                RecientesDelBuscador(busquedas.take(BUSQUEDAS), abiertos.take(ABIERTOS))
            }.getOrDefault(RecientesDelBuscador())
        }
    }
}
