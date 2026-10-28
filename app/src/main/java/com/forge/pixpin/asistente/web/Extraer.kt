package com.forge.pixpin.asistente.web

/**
 * **El texto legible de una página** (28-oct-2026, `extraer.rs` del PC), a lo «modo lectura»: lo
 * que el modelo necesita para contestar, sin menús ni anuncios ni código.
 *
 * - Fuera: `<script>`, `<style>`, `<noscript>`, `<template>`, `<svg>`, `<iframe>`, formularios
 *   sueltos (`<select>`, `<button>`), la navegación (`<nav>`, `role=navigation`), `<aside>`,
 *   `<footer>`, la cabecera del sitio (`<header>` fuera del artículo) y lo oculto (`hidden`,
 *   `aria-hidden=true`, `display:none`).
 * - Si hay `<main>` o `<article>` con texto de verdad (200 letras o más), solo eso.
 * - Se quedan: los títulos (`# `, `## `…), las listas (`- `), las **tablas** (una fila por línea,
 *   `| a | b |`: un horario es una tabla), el texto preformateado y los enlaces, marcados `[n]` y
 *   listados al final con su dirección entera (para abrir el siguiente o citarlo).
 */
object Extraer {
    /** Cuántos enlaces se listan como mucho. */
    const val MAX_ENLACES = 80

    data class Enlace(val texto: String, val url: String)

    data class Pagina(val titulo: String = "", val texto: String = "", val enlaces: List<Enlace> = emptyList())

    /** Lo que nunca se lee. */
    private val FUERA = setOf(
        "script", "style", "noscript", "template", "svg", "math", "iframe", "object", "canvas",
        "select", "button", "textarea", "nav", "aside", "footer", "dialog", "head"
    )

    /** Lo que es un bloque: salto de línea antes y después. */
    private val BLOQUES = setOf(
        "div", "section", "article", "main", "ul", "ol", "dl", "dt", "dd", "figure", "figcaption", "address",
        "details", "summary", "fieldset", "legend", "caption", "form", "center", "header", "body", "label", "option"
    )

    /** Lo que es un párrafo: línea en blanco antes y después. */
    private val PARRAFOS = setOf("p", "blockquote", "table", "pre")

    /** Las palabras de un texto, separadas por cualquier blanco (también el de no separar). */
    internal fun palabras(t: String): List<String> {
        val v = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            while (i < t.length && t[i].isWhitespace()) i++
            val ini = i
            while (i < t.length && !t[i].isWhitespace()) i++
            if (i > ini) v += t.substring(ini, i)
        }
        return v
    }

    /** Los espacios juntados en uno. */
    internal fun junta(t: String) = palabras(t).joinToString(" ")

    /** El texto que se va escribiendo, con los espacios ya juntados. */
    private class Salida {
        val s = StringBuilder()

        fun texto(t: String, pre: Boolean) {
            if (pre) { s.append(t); return }
            val p = palabras(t)
            for ((i, palabra) in p.withIndex()) {
                if ((i > 0 || (t.isNotEmpty() && t[0].isWhitespace())) && cabeEspacio()) s.append(' ')
                s.append(palabra)
            }
            if (t.isNotEmpty() && t.last().isWhitespace() && cabeEspacio()) s.append(' ')
        }

        fun cabeEspacio() = s.isNotEmpty() && s.last() != ' ' && s.last() != '\n'

        fun crudo(t: String) { s.append(t) }

        fun sinEspaciosAlFinal() { while (s.isNotEmpty() && s.last() == ' ') s.setLength(s.length - 1) }

        fun salto() {
            sinEspaciosAlFinal()
            if (s.isNotEmpty() && s.last() != '\n') s.append('\n')
        }

        fun parrafo() {
            salto()
            if (s.isNotEmpty() && !s.endsWith("\n\n")) s.append('\n')
        }
    }

    /** Las dos salidas: la página entera y lo de `<main>`/`<article>`. */
    private class Doble {
        val todo = Salida()
        val principal = Salida()
        var dentro = 0
        fun con(f: (Salida) -> Unit) {
            f(todo)
            if (dentro > 0) f(principal)
        }
    }

    private fun oculto(p: Html.Pieza): Boolean {
        if (p.atributo("hidden") != null || p.atributo("aria-hidden") == "true") return true
        val r = p.atributo("role")
        if (r != null && r in setOf("navigation", "banner", "contentinfo", "search", "menu", "menubar")) return true
        val s = p.atributo("style")?.filterNot { it.isWhitespace() }?.lowercase() ?: return false
        return "display:none" in s || "visibility:hidden" in s
    }

    /** **Lee una página HTML.** [base]: su dirección (para los enlaces). */
    fun deHtml(html: String, base: Direccion.Url): Pagina {
        val d = Doble()
        val titulo = StringBuilder()
        var enTitulo = false
        // Lo que no se lee, con su nombre (para saber cuándo acaba).
        val fuera = ArrayList<String>()
        var pre = 0
        var celdas = 0
        val enlaces = ArrayList<Enlace>()
        // El enlace abierto: su dirección y su texto.
        var enlace: Pair<String, StringBuilder>? = null
        for (p in Html.piezas(html)) {
            when {
                p is Html.Pieza.Abre && p.nombre == "title" && fuera.all { it == "head" } -> enTitulo = true
                p is Html.Pieza.Cierra && p.nombre == "title" -> enTitulo = false
                p is Html.Pieza.Texto && enTitulo -> titulo.append(p.t)
                p is Html.Pieza.Abre && fuera.isNotEmpty() -> {
                    if (p.nombre !in Html.VACIAS && p.nombre in FUERA) fuera += p.nombre
                }
                p is Html.Pieza.Cierra && fuera.isNotEmpty() -> {
                    val i = fuera.lastIndexOf(p.nombre)
                    if (i >= 0) while (fuera.size > i) fuera.removeAt(fuera.lastIndex)
                    if (p.nombre == "head") fuera.removeAll { it == "head" }
                }
                p is Html.Pieza.Texto && fuera.isNotEmpty() -> {}
                p is Html.Pieza.Abre -> {
                    val n = p.nombre
                    val vacia = n in Html.VACIAS
                    if (!vacia && (n in FUERA || (n == "header" && d.dentro == 0) || oculto(p))) {
                        fuera += n
                        continue
                    }
                    when {
                        n == "main" || n == "article" -> { d.dentro++; d.con { it.parrafo() } }
                        n == "br" && celdas > 0 -> d.con { it.texto(" ", false) }
                        n == "br" -> d.con { it.salto() }
                        n == "hr" -> d.con { it.parrafo(); it.crudo("---"); it.parrafo() }
                        n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> {
                            val nivel = n[1] - '0'
                            d.con { it.parrafo(); it.crudo("#".repeat(nivel)); it.crudo(" ") }
                        }
                        n == "li" -> d.con { it.salto(); it.crudo("- ") }
                        n == "tr" -> d.con { it.salto(); it.crudo("|") }
                        n == "td" || n == "th" -> { celdas++; d.con { it.crudo(" ") } }
                        n == "pre" -> { pre++; d.con { it.parrafo() } }
                        n == "a" -> {
                            val u = p.atributo("href")?.let { Direccion.resolver(base, it) }
                            if (u != null) enlace = u to StringBuilder()
                        }
                        n == "img" -> {
                            // Una imagen que es el único texto de un enlace.
                            val e = enlace
                            val alt = p.atributo("alt")?.trim()?.takeIf { it.isNotEmpty() }
                            if (e != null && e.second.isBlank() && alt != null) {
                                e.second.append(alt)
                                d.con { it.texto(alt, false) }
                            }
                        }
                        celdas > 0 && (n in BLOQUES || n in PARRAFOS) -> d.con { it.texto(" ", false) }
                        n in PARRAFOS -> d.con { it.parrafo() }
                        n in BLOQUES -> d.con { it.salto() }
                    }
                }
                p is Html.Pieza.Cierra -> {
                    val x = p.nombre
                    when {
                        x == "main" || x == "article" -> { d.con { it.parrafo() }; d.dentro = maxOf(0, d.dentro - 1) }
                        x.length == 2 && x[0] == 'h' && x[1] in '1'..'6' -> d.con { it.parrafo() }
                        x == "td" || x == "th" -> {
                            celdas = maxOf(0, celdas - 1)
                            d.con { it.sinEspaciosAlFinal(); it.crudo(" |") }
                        }
                        x == "tr" -> d.con { it.salto() }
                        x == "pre" -> { pre = maxOf(0, pre - 1); d.con { it.parrafo() } }
                        x == "a" -> {
                            val e = enlace
                            enlace = null
                            if (e != null) {
                                val texto = junta(e.second.toString())
                                if (texto.isNotEmpty()) {
                                    val i = enlaces.indexOfFirst { it.url == e.first }
                                    val num = when {
                                        i >= 0 -> i + 1
                                        enlaces.size < MAX_ENLACES -> { enlaces += Enlace(texto, e.first); enlaces.size }
                                        else -> null
                                    }
                                    if (num != null) d.con { it.sinEspaciosAlFinal(); it.crudo(" [$num]") }
                                }
                            }
                        }
                        celdas > 0 && (x in BLOQUES || x in PARRAFOS) -> d.con { it.texto(" ", false) }
                        x in PARRAFOS -> d.con { it.parrafo() }
                        x in BLOQUES || x == "li" -> d.con { it.salto() }
                    }
                }
                p is Html.Pieza.Texto -> {
                    val t = Html.entidades(p.t)
                    enlace?.second?.append(t)?.append(' ')
                    val enPre = pre > 0
                    d.con { it.texto(t, enPre) }
                }
            }
        }
        val principal = limpiar(d.principal.s.toString())
        val texto = if (principal.codePointCount(0, principal.length) >= 200) principal else limpiar(d.todo.s.toString())
        return Pagina(junta(Html.entidades(titulo.toString())), texto, enlaces)
    }

    /** Quita lo que sobra: espacios al final, filas de tabla vacías, líneas en blanco de más. */
    fun limpiar(s: String): String {
        val o = StringBuilder(s.length)
        var blancas = 0
        for (linea in s.lines()) {
            val l = linea.trimEnd()
            val vacia = l.isBlank() || l.trim() == "-" || l.all { it == '|' || it.isWhitespace() }
            if (vacia) { blancas++; continue }
            if (o.isNotEmpty()) {
                o.append('\n')
                if (blancas > 0) o.append('\n')
            }
            blancas = 0
            o.append(l)
        }
        return o.toString()
    }
}
