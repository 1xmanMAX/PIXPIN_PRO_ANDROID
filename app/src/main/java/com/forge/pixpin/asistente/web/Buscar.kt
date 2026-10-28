package com.forge.pixpin.asistente.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Los buscadores** (28-oct-2026, `buscar.rs` del PC): dónde se pregunta y cómo se leen sus
 * resultados.
 *
 * - **DuckDuckGo** (por omisión, sin clave ni cuenta): su página HTML sin JavaScript
 *   (`html.duckduckgo.com/html/`), la que hace para los navegadores de texto. Gratis y sin
 *   registro; a cambio, si se le pregunta mucho seguido puede pedir comprobar que no es un robot
 *   ([ErrorBusqueda.BLOQUEADO]).
 * - **Brave Search** (con la clave del usuario, que en el teléfono se pega en Ajustes → Asistente
 *   en vez de la variable `PIXPIN_CLAVE_BRAVE` del PC): su API oficial en JSON. Más estable.
 *
 * Lo que llega se trata como datos: título, dirección y resumen, nada más.
 */
object Buscar {
    enum class Buscador(val nombre: String) {
        DUCKDUCKGO("DuckDuckGo"), BRAVE("Brave Search");

        /** La dirección de la búsqueda. */
        fun url(consulta: String, maximo: Int): String {
            val q = Direccion.codificar(consulta.trim())
            return when (this) {
                DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=$q"
                BRAVE -> "https://api.search.brave.com/res/v1/web/search?q=$q&count=${maximo.coerceIn(1, 20)}"
            }
        }
    }

    data class Resultado(val titulo: String, val url: String, val resumen: String)

    enum class ErrorBusqueda { BLOQUEADO, RARO }

    class Fallo(val error: ErrorBusqueda) : Exception(error.name)

    /** La dirección de verdad de un enlace de DuckDuckGo (`//duckduckgo.com/l/?uddg=<codificada>&rut=…`). */
    private fun destinoDdg(href: String): String? {
        val h0 = href.trim()
        val h = when {
            h0.startsWith("//") -> "https:$h0"
            h0.startsWith("/") -> "https://duckduckgo.com$h0"
            else -> h0
        }
        val i = h.indexOf("uddg=")
        val u = if (i >= 0) Direccion.decodificar(h.substring(i + 5).substringBefore('&'), false) else h
        val leida = Direccion.leerONull(u) ?: return null
        // Los anuncios (`duckduckgo.com/y.js`) y lo de la propia casa, fuera.
        if (leida.dominio() == "duckduckgo.com" || leida.servidor.endsWith(".duckduckgo.com")) return null
        return u
    }

    private fun tieneClase(p: Html.Pieza, clase: String) = p.atributo("class")?.let { c -> Extraer.palabras(c).any { it == clase } } == true

    /** **Lee la página de resultados de DuckDuckGo.** Lanza [Fallo] si pide comprobar que no es un robot. */
    fun leerDuckDuckGo(html: String, maximo: Int): List<Resultado> {
        if ("anomaly-modal" in html || "challenge-form" in html) throw Fallo(ErrorBusqueda.BLOQUEADO)
        class Leyendo(val titulo: Boolean, val etiqueta: String, var dentro: Int)
        class Actual(val url: String, val titulo: StringBuilder = StringBuilder(), val resumen: StringBuilder = StringBuilder())
        val v = ArrayList<Resultado>()
        var leyendo: Leyendo? = null
        var anuncio = false
        var actual: Actual? = null
        fun cerrar() { actual?.let { v += Resultado(it.titulo.toString(), it.url, it.resumen.toString()) }; actual = null }
        for (p in Html.piezas(html)) {
            when (p) {
                is Html.Pieza.Abre -> {
                    val l = leyendo
                    if (l != null) {
                        if (l.etiqueta == p.nombre) l.dentro++
                        continue
                    }
                    if (tieneClase(p, "result") || tieneClase(p, "web-result")) anuncio = tieneClase(p, "result--ad")
                    if (tieneClase(p, "result__a")) {
                        cerrar()
                        val url = p.atributo("href")?.let(::destinoDdg)
                        if (url != null && !anuncio) {
                            actual = Actual(url)
                            leyendo = Leyendo(true, p.nombre, 0)
                        }
                    } else if (tieneClase(p, "result__snippet") && actual != null) leyendo = Leyendo(false, p.nombre, 0)
                }
                is Html.Pieza.Cierra -> {
                    val l = leyendo
                    if (l != null && l.etiqueta == p.nombre) {
                        if (l.dentro == 0) leyendo = null else l.dentro--
                    }
                }
                is Html.Pieza.Texto -> {
                    val l = leyendo
                    val a = actual
                    if (l != null && a != null) (if (l.titulo) a.titulo else a.resumen).append(Html.entidades(p.t))
                }
            }
        }
        cerrar()
        return pulir(v, maximo)
    }

    private fun sinEtiquetas(s: String) = Html.piezas(s).filterIsInstance<Html.Pieza.Texto>().joinToString("") { Html.entidades(it.t) }

    /** **Lee la respuesta de Brave Search** (`web.results[]`). Lanza [Fallo] si no se entiende. */
    fun leerBrave(json: String, maximo: Int): List<Resultado> {
        val v = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: throw Fallo(ErrorBusqueda.RARO)
        val lista = ((v["web"] as? JsonObject)?.get("results") as? JsonArray)
            // Sin resultados de la web (una consulta rara): lista vacía.
            ?: return if (v["type"] != null) emptyList() else throw Fallo(ErrorBusqueda.RARO)
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val r = lista.mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val url = s(o, "url") ?: return@mapNotNull null
            Direccion.leerONull(url) ?: return@mapNotNull null
            Resultado(sinEtiquetas(s(o, "title").orEmpty()), url, sinEtiquetas(s(o, "description").orEmpty()))
        }
        return pulir(r, maximo)
    }

    /** Espacios juntados, sin repetidos ni vacíos, y no más de [maximo]. */
    private fun pulir(v: List<Resultado>, maximo: Int): List<Resultado> {
        val o = ArrayList<Resultado>()
        for (r0 in v) {
            val titulo = Extraer.junta(r0.titulo)
            val r = Resultado(titulo.ifEmpty { r0.url }, r0.url, Extraer.junta(r0.resumen))
            if (o.none { it.url == r.url }) o += r
            if (o.size >= maximo) break
        }
        return o
    }
}
