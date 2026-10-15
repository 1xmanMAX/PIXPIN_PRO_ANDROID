package com.forge.pixpin.ia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * **Las tareas del usuario, por su nombre**, para el asistente (`tareas.rs` del PC, portado tal
 * cual).
 *
 * Las listas son las de siempre y se cambian con los pedidos de siempre (`anadir_tarea`,
 * `marcar_tarea`, `mover_tarea`, `corregir_tarea`, `quitar_tarea`, `borrar_lista`), que piden ids y
 * números. El modelo, y el usuario, hablan de «la tarea comprar pan de la lista Compras» y de
 * «mañana a las 10». Aquí se hace el cambio, **sin tocar nada**: la app da las listas con [Fuente]
 * y recibe los pedidos ya completos.
 *
 * Lo que no casa no se adivina: una tarea que no está, dos que se parecen igual, una lista de otro
 * chat o una fecha imposible son un error con el motivo y lo que sí hay ([NoCasa]).
 */
object Tareas {
    data class VistaTarea(
        /** Su número en la lista (desde 0): el `indice` de los pedidos. */
        val indice: Int,
        val texto: String,
        val hecha: Boolean,
        val vence: Momento? = null
    )

    data class VistaLista(
        val proyecto: String,
        val chat: String,
        val guardados: Boolean,
        /** El id del mensaje de la lista. */
        val codigo: String,
        val titulo: String,
        val cuando: Long,
        val tareas: List<VistaTarea>
    ) {
        val esInbox: Boolean get() = guardados && Acciones.normalizar(titulo.trim()) == "inbox"
        fun nombre(): String = if (guardados) titulo else "$titulo ($chat)"
    }

    data class Chat(val id: String, val nombre: String, val guardados: Boolean)

    /** **Lo que la app enseña al asistente**: sus listas, sus chats y la hora. */
    interface Fuente {
        fun listas(): List<VistaLista>
        fun chats(): List<Chat>
        fun ahora(): Momento
    }

    data class Fijas(val listas: List<VistaLista>, val chats: List<Chat>, val ahora: Momento) : Fuente {
        override fun listas() = listas
        override fun chats() = chats
        override fun ahora() = ahora
    }

    /** Lo que no casa con lo que pidió: un motivo para el modelo, no un fallo técnico. */
    class NoCasa(m: String) : Exception(m)

    const val NO_HICE_NADA = "No hice nada"
    const val MAXIMO_EN_CONSULTA = 30

    private val RELLENO = setOf(
        "el", "la", "los", "las", "de", "del", "a", "al", "que", "en", "y", "un", "una", "unos", "unas", "para", "por",
        "mi", "mis", "lo", "tarea", "tareas", "lista", "con"
    )

    private fun palabras(s: String) = Acciones.normalizar(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in RELLENO }

    /** **Cuánto se parece** lo que dijo el usuario a un texto: 100 igual, 85, 70, hasta 60, 0. */
    fun parecido(consulta: String, texto: String): Int {
        val q = palabras(consulta)
        val t = palabras(texto)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (q == t) return 100
        fun contiene(a: List<String>, b: List<String>) = b.size <= a.size && (0..a.size - b.size).any { a.subList(it, it + b.size) == b }
        if (contiene(t, q)) return 85
        if (contiene(q, t)) return 70
        fun igual(a: String, b: String) = a == b || (a.toByteArray().size >= 4 && b.toByteArray().size >= 4 && (a.startsWith(b) || b.startsWith(a)))
        val comunes = q.count { p -> t.any { igual(p, it) } }
        val ratio = comunes.toFloat() / q.size
        return if (comunes > 0 && ratio >= 0.6f) (40.0f + 20.0f * ratio).toInt() else 0
    }

    private fun esGuardados(nombre: String) = Acciones.normalizar(nombre.trim()) in setOf("mensajes guardados", "guardados", "mis mensajes")

    fun buscarChat(chats: List<Chat>, nombre: String): Chat {
        if (esGuardados(nombre)) chats.firstOrNull { it.guardados }?.let { return it }
        val v = chats.map { parecido(nombre, it.nombre) to it }.filter { it.first > 0 }.sortedByDescending { it.first }
        return when {
            v.isEmpty() -> throw NoCasa("No hay ningun chat «$nombre». Chats: ${chats.joinToString(", ") { "«${it.nombre}»" }}. No hice nada.")
            v.size >= 2 && v[0].first == v[1].first -> throw NoCasa("Hay varios chats que se parecen a «$nombre» (p. ej. «${v[0].second.nombre}»). No hice nada: di cual.")
            else -> v[0].second
        }
    }

    private fun chatExiste(chats: List<Chat>, c: String) = runCatching { buscarChat(chats, c) }.isSuccess

    /** **La lista que dijo el usuario**, en el chat que dijo (si lo dijo). */
    fun buscarLista(listas: List<VistaLista>, nombre: String, chat0: String?, chats: List<Chat>): VistaLista {
        val q = Acciones.normalizar(nombre.trim())
        // Un chat que no existe no filtra (los modelos pequeños se inventan uno).
        val chat = chat0?.takeIf { it.isNotBlank() && chatExiste(chats, it) }
        val enChat = chat?.let { runCatching { buscarChat(chats, it) }.getOrNull()?.id }
        val candidatas = listas.map { l ->
            val s = if (q in setOf("inbox", "bandeja", "bandeja de entrada") && l.esInbox) 100 else parecido(nombre, l.titulo)
            s to l
        }.filter { it.first > 0 }
        val en = candidatas.filter { (_, l) -> enChat == null || l.proyecto == enChat }
        if (en.isEmpty()) {
            if (chat != null && candidatas.isNotEmpty()) {
                val otra = candidatas.first().second
                throw NoCasa("La lista «${otra.titulo}» esta en el chat «${otra.chat}», no en «$chat». No hice nada: di si la quieres alli o si creo una nueva en «$chat».")
            }
            val todas = listas.take(12).map { "«${it.nombre()}»" }
            throw NoCasa(
                "No hay ninguna lista «$nombre»${chat?.let { " en «$it»" } ?: ""}. Tus listas: ${if (todas.isEmpty()) "ninguna" else todas.joinToString(", ")}. No hice nada."
            )
        }
        val mejor = en.maxOf { it.first }
        val top = en.filter { it.first == mejor }.map { it.second }
        if (top.size > 1) throw NoCasa("Hay varias listas «$nombre»: ${top.joinToString(", ") { "«${it.nombre()}»" }}. No hice nada: di de que chat.")
        return top[0]
    }

    /** **La tarea que dijo el usuario.** [prefiereHecha]: de dos iguales, la que está en ese estado. */
    fun buscarTarea(listas: List<VistaLista>, consulta: String, lista: VistaLista?, prefiereHecha: Boolean?): Pair<VistaLista, VistaTarea> {
        val donde = lista?.let { listOf(it) } ?: listas
        var q = consulta.trim().trimStart('#')
        q = q.removePrefix("la ")
        val n = q.trim().toIntOrNull()
        if (lista != null && n != null) {
            val t = lista.tareas.getOrNull(n - 1) ?: throw NoCasa("La lista «${lista.nombre()}» no tiene tarea $n (tiene ${lista.tareas.size}). No hice nada.")
            return lista to t
        }
        val v = mutableListOf<Triple<Int, VistaLista, VistaTarea>>()
        for (l in donde) for (t in l.tareas) {
            val s = parecido(consulta, t.texto)
            if (s > 0) v += Triple(s * 2 + (if (prefiereHecha != null && prefiereHecha == t.hecha) 1 else 0), l, t)
        }
        val o = v.sortedByDescending { it.first }
        if (o.isEmpty()) {
            val pendientes = donde.flatMap { l -> l.tareas.filter { !it.hecha } }.take(6).map { "«${it.texto}»" }
            throw NoCasa(
                "No encuentro ninguna tarea «$consulta»${lista?.let { " en la lista «${it.nombre()}»" } ?: ""}. No hice nada. " +
                    if (pendientes.isEmpty()) "No hay tareas pendientes." else "Pendientes: ${pendientes.joinToString(", ")}."
            )
        }
        if (o.size >= 2 && o[0].first == o[1].first && !(o[0].second.codigo == o[1].second.codigo && o[0].third.indice == o[1].third.indice)) {
            val iguales = o.filter { it.first == o[0].first }.take(5).map { (_, l, t) -> "«${t.texto}» (${l.nombre()})" }
            throw NoCasa("Hay varias tareas que se parecen a «$consulta»: ${iguales.joinToString(", ")}. No hice nada: di cual (o de que lista).")
        }
        return o[0].second to o[0].third
    }

    /** Lo que hay que hacer de verdad para una [Acciones.AccionApp]. */
    data class Pasos(val pedidos: List<JsonObject>, val confirmar: String?, val hecho: String)

    private fun textoDe(m: JsonObject, k: String): String? =
        (m[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun fecha(texto: String, ahora: Momento): Momento = try {
        Fechas.interpretar(texto, ahora)
    } catch (e: ErrorFecha) {
        val m = e.message
        throw NoCasa(m.replaceFirstChar { it.uppercase() } + ". No hice nada.")
    }

    /** **De nombres a pedidos.** Lanza [NoCasa] con el motivo y lo que sí hay. */
    fun resolver(a: Acciones.AccionApp, f: Fuente): Pasos {
        val fila = a.fila
        val valores = a.valores().toMutableList()
        fun poner(k: String, v: String) { valores.removeAll { it.first == k }; valores += k to v }
        if (a.porResolver.isEmpty()) return Pasos(listOf(a.pedido), a.confirmar, Acciones.rellenar(fila.hecho, valores))
        val r = a.porResolver
        val listas = f.listas()
        val chats = f.chats()
        val ahora = f.ahora()
        val pedido = LinkedHashMap(a.pedido)
        val chat = textoDe(r, "chat")
        val accion = fila.accion

        var pista: String? = null
        val nombreLista = textoDe(r, "lista")
        val lista: VistaLista? = if (nombreLista != null && (fila.herramienta != "anadir_tareas" || textoDe(r, "lista_nueva") == null)) {
            try {
                buscarLista(listas, nombreLista, chat, chats)
            } catch (e: NoCasa) {
                if (textoDe(r, "tarea") != null && e.message!!.startsWith("No hay ninguna lista")) { pista = nombreLista; null } else throw e
            }
        } else null
        if (lista != null) {
            pedido["codigo"] = JsonPrimitive(lista.codigo)
            pedido["proyecto"] = JsonPrimitive(lista.proyecto)
            poner("lista", lista.nombre())
        } else if (chat != null) {
            val c = buscarChat(chats, chat)
            pedido["proyecto"] = JsonPrimitive(c.id)
            poner("chat", c.nombre)
        }

        var tarea: Pair<VistaLista, VistaTarea>? = null
        textoDe(r, "tarea")?.let { t ->
            val prefiere = if (accion == "marcar_tarea") !((pedido["hecha"] as? JsonPrimitive)?.booleanOrNull ?: true) else false
            val (l, x) = try {
                buscarTarea(listas, t, lista, prefiere)
            } catch (e: NoCasa) {
                pista?.let { p -> runCatching { buscarTarea(listas, p, null, prefiere) }.getOrNull() } ?: throw e
            }
            pedido["codigo"] = JsonPrimitive(l.codigo)
            pedido["proyecto"] = JsonPrimitive(l.proyecto)
            pedido["indice"] = JsonPrimitive(x.indice)
            poner("tarea", x.texto)
            poner("lista", l.nombre())
            tarea = l to x
            if (accion == "marcar_tarea") {
                val hecha = (pedido["hecha"] as? JsonPrimitive)?.booleanOrNull ?: true
                poner("estado", if (hecha) "hecha" else "pendiente")
            }
        }

        textoDe(r, "a_lista")?.let { n ->
            val destino = buscarLista(listas, n, null, chats)
            if (tarea != null && tarea!!.first.codigo == destino.codigo) throw NoCasa("La tarea ya esta en la lista «${destino.nombre()}». No hice nada.")
            pedido["a_codigo"] = JsonPrimitive(destino.codigo)
            pedido["a_proyecto"] = JsonPrimitive(destino.proyecto)
            poner("a_lista", destino.nombre())
        }

        val cuando = textoDe(r, "fecha")?.let { fecha(it, ahora) }
        cuando?.let { poner("fecha", it.legible(ahora)) }
        val textoPedido = (pedido["texto"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (accion == "corregir_tarea") {
            val x = tarea?.second ?: error("editar_tarea pide la tarea")
            val base = textoPedido ?: x.texto
            if (textoPedido == null && cuando == null) throw NoCasa("Para cambiar una tarea dime el texto nuevo o la fecha nueva. No hice nada.")
            val marca = (cuando ?: x.vence)?.let { " ${it.marca()}" } ?: ""
            pedido["texto"] = JsonPrimitive("${base.trim()}$marca")
            poner("texto", base.trim())
            if (cuando == null && x.vence != null) poner("fecha", x.vence.legible(ahora))
        } else if (cuando != null && textoPedido != null) {
            pedido["texto"] = JsonPrimitive("${textoPedido.trim()} ${cuando.marca()}")
        }

        val pedidos = mutableListOf(JsonObject(pedido))
        val items = r["tareas"] as? JsonArray
        if (items != null) {
            pedidos.clear()
            val base = LinkedHashMap(pedido).also { it["accion"] = JsonPrimitive("anadir_tarea") }
            var nombre = lista?.nombre() ?: "Inbox"
            val nueva = textoDe(r, "lista_nueva")
            if (nueva != null) {
                val p = LinkedHashMap<String, JsonElement>()
                p["pixpin"] = JsonPrimitive(1); p["accion"] = JsonPrimitive("lista_nueva"); p["titulo"] = JsonPrimitive(nueva)
                base["proyecto"]?.let { p["proyecto"] = it }
                pedidos += JsonObject(p)
                nombre = nueva
            }
            for (it in items) {
                val texto = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } ?: continue
                val p = LinkedHashMap(base)
                val (t, c) = try { Fechas.separar(texto, ahora) } catch (e: ErrorFecha) { texto to null }
                p["texto"] = JsonPrimitive(if (c != null) "$t ${c.marca()}" else t)
                if (nueva != null) p["__en_lista_nueva"] = JsonPrimitive(nueva)
                pedidos += JsonObject(p)
            }
            poner("n", (pedidos.size - (if (nueva != null) 1 else 0)).toString())
            poner("lista", nombre)
        }
        return Pasos(pedidos, fila.confirmar?.let { Acciones.rellenar(it, valores) }, Acciones.rellenar(fila.hecho, valores))
    }

    /** Completa un pedido que va a una lista recién creada: su código y su chat, leídos de nuevo. */
    fun enListaNueva(pedido: JsonObject, f: Fuente): JsonObject {
        val titulo = (pedido["__en_lista_nueva"] as? JsonPrimitive)?.content ?: return pedido
        val proyecto = (pedido["proyecto"] as? JsonPrimitive)?.content
        val l = f.listas().filter { it.titulo.trim() == titulo.trim() && (proyecto == null || it.proyecto == proyecto) }.maxByOrNull { it.cuando }
            ?: throw NoCasa("No encuentro la lista nueva «$titulo» recien creada.")
        val p = LinkedHashMap(pedido)
        p["codigo"] = JsonPrimitive(l.codigo)
        p["proyecto"] = JsonPrimitive(l.proyecto)
        p.remove("__en_lista_nueva")
        return JsonObject(p)
    }

    /**
     * **Hace la acción entera**: consulta, o resuelve, pregunta si hace falta y manda los pedidos
     * en orden. Devuelve la frase de hecho (o la consulta); lanza [NoCasa] si no se pudo.
     */
    fun hacer(a: Acciones.AccionApp, f: Fuente, confirmar: (String) -> Boolean, mandar: (JsonObject) -> Unit): String {
        if (a.esConsulta) return consultar(a.porResolver, f)
        val pasos = resolver(a, f)
        if (pasos.confirmar != null && !confirmar(pasos.confirmar)) throw NoCasa("el usuario dijo que no; no se hizo nada")
        for (p in pasos.pedidos) mandar(enListaNueva(p, f))
        return pasos.hecho
    }

    /** **«¿Qué tengo pendiente?»**: las tareas que pide [filtro], agrupadas por lista, con su fecha. */
    fun consultar(filtro: JsonObject, f: Fuente): String {
        val ahora = f.ahora()
        val listas = f.listas()
        val chats = f.chats()
        val estado = textoDe(filtro, "estado") ?: "pendientes"
        val periodo = textoDe(filtro, "periodo") ?: "todo"
        val chat = textoDe(filtro, "chat")
        val buscar = textoDe(filtro, "buscar")?.takeIf { b -> listOfNotNull(chat, textoDe(filtro, "lista")).all { parecido(b, it) < 70 } }
        val elegidas: List<VistaLista> = textoDe(filtro, "lista")?.let { listOf(buscarLista(listas, it, chat, chats)) }
            ?: chat?.let { c -> val id = buscarChat(chats, c).id; listas.filter { it.proyecto == id } }
            ?: listas
        val lunes = ahora.dia - Fechas.diaDeLaSemana(ahora.dia)
        val (y, m, _) = Fechas.fechaDe(ahora.dia)
        val finDeMes = (if (m == 12L) Fechas.diasDe(y + 1, 1, 1) else Fechas.diasDe(y, m + 1, 1)) - 1
        val rango: Pair<Long, Long>? = when (periodo) {
            "hoy" -> ahora.dia to ahora.dia
            "semana" -> lunes to lunes + 6
            "mes" -> Fechas.diasDe(y, m, 1) to finDeMes
            else -> null
        }
        fun vale(t: VistaTarea): Boolean {
            val porEstado = when (estado) {
                "hechas" -> t.hecha
                "vencidas" -> !t.hecha && t.vence != null && t.vence < ahora
                "todas" -> true
                else -> !t.hecha
            }
            val porFecha = rango == null || (t.vence != null && t.vence.dia >= rango.first && t.vence.dia <= rango.second)
            val porTexto = buscar == null || parecido(buscar, t.texto) > 0
            return porEstado && porFecha && porTexto
        }
        val lineas = mutableListOf<String>()
        var cuantas = 0
        var puestas = 0
        for (l in elegidas) {
            val ts = l.tareas.filter(::vale).sortedWith(compareBy<VistaTarea>({ it.vence == null }, { it.vence }, { it.indice }))
            if (ts.isEmpty()) continue
            cuantas += ts.size
            if (puestas >= MAXIMO_EN_CONSULTA) continue
            lineas += "${l.nombre()}:"
            for (t in ts) {
                if (puestas >= MAXIMO_EN_CONSULTA) break
                puestas++
                val casilla = if (t.hecha) "☑" else "☐"
                val cuando = when {
                    t.vence != null && !t.hecha && t.vence < ahora -> " — venció ${t.vence.legible(ahora)}"
                    t.vence != null -> " — ${t.vence.legible(ahora)}"
                    else -> ""
                }
                lineas += "  $casilla ${t.texto}$cuando"
            }
        }
        val que = when (estado) { "hechas" -> "hechas"; "vencidas" -> "vencidas"; "todas" -> ""; else -> "pendientes" }
        val cuando = when (periodo) { "hoy" -> " para hoy"; "semana" -> " de esta semana"; "mes" -> " de este mes"; else -> "" }
        if (cuantas == 0) return "No tienes tareas $que$cuando.".replace("  ", " ")
        val s = StringBuilder("Tareas $que$cuando ($cuantas):\n".replace("  ", " "))
        s.append(lineas.joinToString("\n"))
        if (cuantas > puestas) s.append("\n…y ${cuantas - puestas} más.")
        return s.toString()
    }
}
