package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lo que se pide a la biblioteca de figuras (`figuras.rs` del PC). */
sealed class PedidoFiguras {
    data class Guardar(val nombre: String) : PedidoFiguras()
    data class Poner(val nombre: String) : PedidoFiguras()
    object Lista : PedidoFiguras() {
        override fun toString() = "Lista"
    }
}

/**
 * **La biblioteca de figuras desde el asistente** (`figuras_guardadas`): «guarda esto como árbol
 * isométrico», «pon el árbol isométrico», «¿qué figuras tengo?». Es la MISMA biblioteca del menú de
 * figuras del lienzo (`pins/draw/figuras.json`, [com.forge.pixpin.motor.BibliotecaStore]). Aquí
 * solo se valida y se busca por nombre; lo hace el ejecutor del chat.
 */
object Figuras {
    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    fun validar(args: JsonObject): PedidoFiguras {
        val nombre = listOf("nombre", "figura", "como").firstNotNullOfOrNull { k -> (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
            ?.trim()?.trim('«', '»', '"', '\'')?.trim()?.let { s -> if (s.codePointCount(0, s.length) <= 80) s else s.substring(0, s.offsetByCodePoints(0, 80)) }
            ?.takeIf { it.isNotEmpty() }
        val accion = (args["accion"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Acciones::normalizar)
        return when (accion) {
            "guardar", "guarda", "salvar", "save" -> PedidoFiguras.Guardar(nombre ?: throw mal("para guardar hace falta el «nombre» de la figura"))
            "poner", "pon", "insertar", "estampar", "usar", "put", "insert" -> PedidoFiguras.Poner(nombre ?: throw mal("falta el «nombre» de la figura que poner"))
            "lista", "listar", "ver", "cuales", "list" -> PedidoFiguras.Lista
            null -> if (nombre == null) PedidoFiguras.Lista else throw mal("falta «accion»: guardar, poner o lista")
            else -> throw mal("«accion» = «$accion» no existe; usa guardar, poner o lista")
        }
    }

    private fun palabras(s: String): List<String> = Acciones.normalizar(s).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.toByteArray().size > 2 || (it.isNotEmpty() && it.all { c -> c in '0'..'9' }) }
        .map { p -> p.removeSuffix("es").takeIf { p.endsWith("es") && it.toByteArray().size > 3 } ?: p.removeSuffix("s") }
        .filter { it.isNotEmpty() }

    /** **La figura que mejor encaja con el nombre pedido** (su índice en [nombres]); null si ninguna. */
    fun buscar(nombres: List<String>, pedido: String): Int? {
        val q = Acciones.normalizar(pedido.trim())
        nombres.indexOfFirst { Acciones.normalizar(it) == q }.takeIf { it >= 0 }?.let { return it }
        val pq = palabras(pedido)
        if (pq.isEmpty()) return null
        fun comun(n: String): Int { val pf = palabras(n); return pq.count { it in pf } }
        nombres.indexOfFirst { comun(it) == pq.size }.takeIf { it >= 0 }?.let { return it }
        // Como `max_by_key` de Rust: de las que más comparten, la última.
        var mejor: Int? = null
        nombres.forEachIndexed { i, n -> val c = comun(n); if (c > 0 && (mejor == null || c >= comun(nombres[mejor!!]))) mejor = i }
        return mejor
    }

    /** La lista para el modelo (y el usuario). */
    fun lista(nombres: List<String>): String =
        if (nombres.isEmpty()) "no hay figuras guardadas todavia" else "${nombres.size} figuras guardadas: ${nombres.joinToString(", ")}"
}
