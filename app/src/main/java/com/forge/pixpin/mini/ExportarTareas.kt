package com.forge.pixpin.mini

import com.forge.pixpin.guardados.ClaseExportar.escapar
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import java.time.LocalDate

/**
 * **Las listas de tareas para sacarlas fuera** (2-nov-2026, `pindesk_proyecto::tareas_exportar` del PC,
 * commits `f59890b7`, `bc4533bd`, `1746154b`, `668fa506`). El usuario: «mejora la apariencia de mis
 * quehaceres cuando los exporto; ahora es solo una lista plana y queda un poco tonto».
 *
 * Antes una lista salía por la hoja de compartir como texto corrido (`☐ llamar al banco ⏫ #casa ⏰
 * 2026-10-17 17:00 ➕ 2026-10-17`, con los enlaces de sus fotos a la vista). Aquí, lo puro de sacarla
 * bien, igual que el PC:
 *
 * - [ListaParaExportar]: lo de cada tarea **ya entendido** con los lectores de Tareas ([Tareas.partir],
 *   [Tareas.imagenes], [TareaInteligente.marcas]): título limpio, urgencia, etiquetas, nota,
 *   recordatorio, día en que se creó y fotos. Ni un dato inventado.
 * - [html]: una página sola, sin nada de la red (la hoja de estilo es la del PC, `pagina.css`, en
 *   `assets/tareas-exportar.css`): título y fecha, el resumen con su barrita, cada lista en su tarjeta y
 *   lo hecho plegado al final. Claro y oscuro según el sistema, para el móvil y para imprimir.
 * - [markdown] (de ahí salen también el Word y el PDF) y [texto].
 *
 * Nada de esto toca el disco ni sabe de idiomas: las fotos las da quien llama y los rótulos vienen en
 * [Rotulos] (en la app, de `strings_tareas_exportar.xml`, los del PC en los 14 idiomas).
 */
object ExportarTareas {

    /** **Una tarea ya entendida.** */
    data class TareaParaExportar(
        val titulo: String = "",
        val hecha: Boolean = false,
        val urgencia: TareaInteligente.Urgencia = TareaInteligente.Urgencia.NORMAL,
        /** Sin la almohadilla. */
        val etiquetas: List<String> = emptyList(),
        /** Cuándo recordarla (`⏰`), en hora local. */
        val hora: Momento? = null,
        /** La nota de por qué no se está haciendo (`💬`). */
        val nota: String? = null,
        /** El día en que se apuntó (`➕`). */
        val creada: LocalDate? = null,
        /** Los enlaces de sus fotos (`pixpin:files/…`), en orden. */
        val imagenes: List<String> = emptyList()
    ) {
        companion object {
            /** La de un texto guardado de tarea (lo que va tras `- [ ] `). */
            fun delTexto(crudo: String, hecha: Boolean): TareaParaExportar {
                val (visible, creada) = Tareas.partir(crudo)
                val (limpio, imagenes) = Tareas.imagenes(visible)
                val f = TareaInteligente.marcas(limpio)
                // [Tareas.partir] de aquí ya quita la hora `⏰` (el `mini::partir` del PC no): se lee del texto entero.
                val hora = f.hora ?: TareaInteligente.marcaDeHora(crudo)?.second
                return TareaParaExportar(f.titulo, hecha, f.urgencia, f.etiquetas, hora, f.nota, creada, imagenes)
            }
        }
    }

    /** **Una lista de tareas** (la mini-app `tareas` de un mensaje). */
    data class ListaParaExportar(val titulo: String = "", val tareas: List<TareaParaExportar> = emptyList()) {
        val pendientes: List<TareaParaExportar> get() = tareas.filter { !it.hecha }
        val hechas: List<TareaParaExportar> get() = tareas.filter { it.hecha }
        /** (hechas, total). */
        fun cuenta(): Pair<Int, Int> = hechas.size to tareas.size

        companion object {
            /** La del documento de la mini-app; sin título propio, [titulo]. */
            fun delDocumento(documento: String, titulo: String): ListaParaExportar {
                val propio = Cabecera.titulo(documento)
                return ListaParaExportar(
                    if (propio.isBlank()) titulo.trim() else propio,
                    Tareas.leer(documento).map { TareaParaExportar.delTexto(it.texto, it.hecha) }
                )
            }
        }
    }

    /** (hechas, total) de varias listas juntas. */
    fun cuenta(listas: List<ListaParaExportar>): Pair<Int, Int> =
        listas.fold(0 to 0) { (h, t), l -> val (a, b) = l.cuenta(); (h + a) to (t + b) }

    /** Lo hecho en tanto por ciento, redondeado (0 sin tareas). */
    fun porCiento(hechas: Int, total: Int): Int = if (total == 0) 0 else Math.round(hechas.toDouble() / total * 100).toInt()

    // --- Los rótulos -------------------------------------------------------------------------------

    /** **Lo que dicen las páginas**, en el idioma de la app. Los patrones llevan sus huecos entre llaves. */
    data class Rotulos(
        val idioma: String = "es",
        val tareas: String = "Tareas",
        val exportado: String = "Exportado el {fecha}",
        val pendientes: String = "Pendientes",
        val hechas: String = "Hechas",
        val completado: String = "Completado",
        val pendiente: String = "Pendiente",
        val hecha: String = "Hecha",
        val todoHecho: String = "Todo hecho",
        val vacia: String = "Sin tareas",
        val urgente: String = "Urgente",
        val muyUrgente: String = "Muy urgente",
        val recordatorio: String = "Recordatorio",
        val pasada: String = "Pasada",
        val creada: String = "Creada el {fecha}",
        val hoy: String = "Hoy",
        val manana: String = "Mañana",
        val ayer: String = "Ayer",
        val de: String = "{hechas} de {total}",
        val foto: String = "Foto",
        /** lun … dom. */
        val dias: List<String> = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
        /** enero … diciembre, como van dentro de una fecha (en ruso, genitivo). */
        val meses: List<String> = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"),
        val fecha: String = "{dia} de {mes}",
        val fechaConAno: String = "{dia} de {mes} de {ano}"
    ) {
        /** Lo que dice la chapita de una urgencia; normal no lleva. */
        fun urgencia(u: TareaInteligente.Urgencia): String? = when (u) {
            TareaInteligente.Urgencia.MUY_URGENTE -> muyUrgente
            TareaInteligente.Urgencia.URGENTE -> urgente
            TareaInteligente.Urgencia.NORMAL -> null
        }

        /** Un día del calendario: sin el año si es el de [ahora]. */
        fun dia(dia: Long, ahora: Momento): String = fechaDe(dia, Fechas.fechaDe(dia).first != Fechas.fechaDe(ahora.dia).first)

        private fun fechaDe(dia: Long, conAno: Boolean): String {
            val (y, m, d) = Fechas.fechaDe(dia)
            val mes = meses[(m - 1).toInt().coerceIn(0, 11)]
            return (if (conAno) fechaConAno else fecha).replace("{dia}", d.toString()).replace("{mes}", mes).replace("{ano}", y.toString())
        }

        /** El día de la semana corto («vie»). */
        fun semana(dia: Long): String = dias[Fechas.diaDeLaSemana(dia).toInt().coerceIn(0, 6)]

        /** «{hechas} de {total}». */
        fun de(hechas: Int, total: Int): String = de.replace("{hechas}", hechas.toString()).replace("{total}", total.toString())

        /** «Exportado el 1 de noviembre de 2026 · 09:12». */
        fun exportado(ahora: Momento): String = exportado.replace("{fecha}", "${fechaDe(ahora.dia, true)} · ${horaDe(ahora.minuto)}")

        /** «Creada el 28 de octubre». */
        fun creada(f: LocalDate, ahora: Momento): String =
            creada.replace("{fecha}", dia(Fechas.diasDe(f.year.toLong(), f.monthValue.toLong(), f.dayOfMonth.toLong()), ahora))
    }

    /** `09:05`. */
    fun horaDe(minuto: Int): String = "%02d:%02d".format(minuto / 60, minuto % 60)

    /**
     * **Lo que dice el recordatorio**, de cerca y de lejos. Hoy, mañana y ayer van por su nombre y llevan
     * la fecha aparte («Mañana 17:00», «2 de noviembre»); lo demás, con su día de la semana («vie 6 de
     * noviembre 09:00») y sin nada aparte.
     */
    fun recordatorio(h: Momento, ahora: Momento, r: Rotulos): Pair<String, String?> {
        val hora = horaDe(h.minuto)
        val cerca = when (h.dia - ahora.dia) { 0L -> r.hoy; 1L -> r.manana; -1L -> r.ayer; else -> null }
        return if (cerca != null) "$cerca $hora" to r.dia(h.dia, ahora)
        else "${r.semana(h.dia)} ${r.dia(h.dia, ahora)} $hora" to null
    }

    /** Si el recordatorio ya pasó y la tarea sigue sin hacer. */
    fun pasada(t: TareaParaExportar, ahora: Momento): Boolean = !t.hecha && t.hora?.let { it < ahora } == true

    private fun iso(h: Momento): String {
        val (y, m, d) = Fechas.fechaDe(h.dia)
        return "%04d-%02d-%02dT%s".format(y, m, d, horaDe(h.minuto))
    }

    // --- HTML --------------------------------------------------------------------------------------

    private const val MARCA_SVG = "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\"><path d=\"M3.6 8.4l2.9 2.9 5.9-6.6\"/></svg>"
    private const val RELOJ_SVG = "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\"><circle cx=\"8\" cy=\"8.6\" r=\"5.6\"/><path d=\"M8 5.6v3.2l2 1.3M3.2 2.6 1.8 4M12.8 2.6 14.2 4\"/></svg>"

    private fun filaHtml(t: TareaParaExportar, r: Rotulos, ahora: Momento, foto: (String) -> String?): String {
        val clases = mutableListOf("tarea")
        if (t.hecha) clases += "hecha"
        when (t.urgencia) {
            TareaInteligente.Urgencia.MUY_URGENTE -> clases += "u-muy"
            TareaInteligente.Urgencia.URGENTE -> clases += "u-alta"
            TareaInteligente.Urgencia.NORMAL -> {}
        }
        val h = StringBuilder()
        h.append("<li class=\"${clases.joinToString(" ")}\"><span class=\"casilla\" role=\"img\" aria-label=\"${escapar(if (t.hecha) r.hecha else r.pendiente)}\">${if (t.hecha) MARCA_SVG else ""}</span><div class=\"que\">")
        val titulo = if (t.titulo.isBlank()) "—" else escapar(t.titulo.trim())
        h.append("<p class=\"titulo\">$titulo</p>")
        val chips = mutableListOf<String>()
        val u = r.urgencia(t.urgencia)
        if (!t.hecha && u != null) {
            val c = if (t.urgencia == TareaInteligente.Urgencia.MUY_URGENTE) "muy" else "alta"
            chips += "<span class=\"chip prio $c\"><i aria-hidden=\"true\"></i>${escapar(u)}</span>"
        }
        t.hora?.let { hora ->
            val (cerca, lejos) = recordatorio(hora, ahora, r)
            val pas = pasada(t, ahora)
            val c = StringBuilder("<span class=\"chip hora${if (pas) " pasada" else ""}\" title=\"${escapar(r.recordatorio)}\">$RELOJ_SVG<time datetime=\"${iso(hora)}\">${escapar(cerca)}</time>")
            lejos?.let { c.append("<span class=\"lejos\">${escapar(it)}</span>") }
            if (pas) c.append("<b>${escapar(r.pasada)}</b>")
            c.append("</span>")
            chips += c.toString()
        }
        for (e in t.etiquetas) chips += "<span class=\"chip tag\">#${escapar(e)}</span>"
        t.creada?.let { chips += "<span class=\"creada\">${escapar(r.creada(it, ahora))}</span>" }
        if (chips.isNotEmpty()) h.append("<div class=\"chips\">${chips.joinToString("")}</div>")
        t.nota?.takeIf { it.isNotBlank() }?.let { h.append("<p class=\"nota\">${escapar(it.trim())}</p>") }
        val fotos = t.imagenes.mapNotNull(foto)
        if (fotos.isNotEmpty()) {
            h.append("<div class=\"fotos\">")
            fotos.forEachIndexed { i, src -> h.append("<img src=\"${escapar(src)}\" alt=\"${escapar(r.foto)} ${i + 1}\" loading=\"lazy\">") }
            h.append("</div>")
        }
        h.append("</div></li>")
        return h.toString()
    }

    private fun barraHtml(hechas: Int, total: Int, r: Rotulos, clase: String): String =
        "<div class=\"$clase\" role=\"progressbar\" aria-valuemin=\"0\" aria-valuemax=\"$total\" aria-valuenow=\"$hechas\" aria-label=\"${escapar(r.de(hechas, total))}\"><span style=\"width:${porCiento(hechas, total)}%\"></span></div>"

    /**
     * **La página de las listas**, entera en un fichero. [titulo]: el de lo compartido; [ahora]: la hora
     * local de quien exporta; [foto] da el `src` de cada foto de una tarea; [css], la hoja del PC.
     */
    fun html(listas: List<ListaParaExportar>, titulo0: String, r: Rotulos, ahora: Momento, css: String, foto: (String) -> String?): String {
        val titulo = titulo0.trim().ifEmpty { r.tareas }
        val (hechas, total) = cuenta(listas)
        val pendientes = total - hechas
        val varias = listas.size > 1
        val h = StringBuilder(16 * 1024)
        h.append("<!DOCTYPE html>\n<html lang=\"${escapar(r.idioma)}\">\n<head>\n<meta charset=\"utf-8\">\n")
        h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<meta name=\"color-scheme\" content=\"light dark\">\n")
        h.append("<meta name=\"generator\" content=\"PinDesk\">\n<title>${escapar(titulo)}</title>\n<style>\n$css</style>\n</head>\n<body>\n<main class=\"pagina\">\n")
        h.append("<header class=\"cab\">\n<p class=\"sello\">${escapar(r.exportado(ahora))}</p>\n<h1>${escapar(titulo)}</h1>\n")
        h.append(
            "<div class=\"resumen\"><div class=\"cifra\"><b>$pendientes</b><span>${escapar(r.pendientes)}</span></div>" +
                "<div class=\"cifra\"><b>$hechas</b><span>${escapar(r.hechas)}</span></div>" +
                "<div class=\"cifra avance-cifra\"><b>${porCiento(hechas, total)}<small>%</small></b><span>${escapar(r.completado)}</span></div>" +
                "${barraHtml(hechas, total, r, "avance")}</div>\n</header>\n"
        )
        for (l in listas) {
            val (lh, lt) = l.cuenta()
            h.append("<section class=\"lista\">\n")
            if (varias) h.append(
                "<header class=\"lista-cab\"><h2>${escapar(l.titulo.trim().ifEmpty { r.tareas })}</h2><span class=\"cuenta\">${escapar(r.de(lh, lt))}</span>${barraHtml(lh, lt, r, "avance fina")}</header>\n"
            )
            val pend = l.pendientes
            if (pend.isEmpty()) h.append("<p class=\"vacio\">${escapar(if (lt == 0) r.vacia else r.todoHecho)}</p>\n")
            else {
                h.append("<ul class=\"tareas\">\n")
                for (t in pend) h.append(filaHtml(t, r, ahora, foto)).append('\n')
                h.append("</ul>\n")
            }
            if (lh > 0) {
                h.append("<details class=\"hechas\"><summary><span>${escapar(r.hechas)}</span><span class=\"n\">$lh</span></summary>\n<ul class=\"tareas\">\n")
                for (t in l.hechas) h.append(filaHtml(t, r, ahora, foto)).append('\n')
                h.append("</ul>\n</details>\n")
            }
            h.append("</section>\n")
        }
        // Al imprimir, lo plegado se abre: en papel no hay donde pulsar.
        h.append(
            "</main>\n<footer class=\"pie\">PinDesk</footer>\n<script>" +
                "addEventListener('beforeprint',function(){document.querySelectorAll('details.hechas').forEach(function(d){d.dataset.cerrada=d.open?'':'1';d.open=true;});});" +
                "addEventListener('afterprint',function(){document.querySelectorAll('details.hechas').forEach(function(d){if(d.dataset.cerrada)d.open=false;});});" +
                "</script>\n</body>\n</html>\n"
        )
        return h.toString()
    }

    // --- Markdown y texto --------------------------------------------------------------------------

    /** Lo que cambiaría la forma de un renglón de Markdown, escapado. */
    fun md(s: String): String = buildString {
        for (c in s.trim()) {
            if (c in "\\`*_[]<>~|") append('\\')
            append(if (c == '\n' || c == '\r') ' ' else c)
        }
    }

    /** Lo de detrás del título: urgencia, recordatorio y etiquetas, en el mismo orden en todos los formatos. */
    private fun sufijo(t: TareaParaExportar, r: Rotulos, ahora: Momento, negrita: Boolean): List<String> {
        val v = mutableListOf<String>()
        val u = r.urgencia(t.urgencia)
        if (!t.hecha && u != null) v += if (negrita) "**${md(u)}**" else u
        t.hora?.let { h ->
            val (cerca, lejos) = recordatorio(h, ahora, r)
            var s = "⏰ $cerca"
            lejos?.let { s += " ($it)" }
            if (pasada(t, ahora)) s += " · ${r.pasada}"
            v += if (negrita) md(s) else s
        }
        for (e in t.etiquetas) v += "#$e"
        return v
    }

    private fun lineaMd(t: TareaParaExportar, r: Rotulos, ahora: Momento): String {
        val s = StringBuilder("- [${if (t.hecha) "x" else " "}] ${md(t.titulo)}")
        for (p in sufijo(t, r, ahora, true)) s.append(" · ").append(p)
        t.nota?.takeIf { it.isNotBlank() }?.let { s.append(" — *${md(it)}*") }
        return s.append('\n').toString()
    }

    /**
     * **Las listas en Markdown**: las casillas de siempre con la urgencia, el recordatorio y las etiquetas
     * detrás. Con [foto] (para el PDF y el Word), cada foto de una tarea va debajo como imagen
     * (`![Foto 1](clave)`, la clave que da [foto]); sin ella, como el PC: sin fotos.
     */
    fun markdown(listas: List<ListaParaExportar>, titulo0: String, r: Rotulos, ahora: Momento, foto: ((String) -> String?)? = null): String {
        val titulo = titulo0.trim().ifEmpty { r.tareas }
        val (hechas, total) = cuenta(listas)
        val v = StringBuilder("# ${md(titulo)}\n\n")
        v.append("*${md(r.exportado(ahora))} · ${md(r.pendientes)}: ${total - hechas} · ${md(r.hechas)}: $hechas · ${porCiento(hechas, total)} %*\n")
        val varias = listas.size > 1
        for (l in listas) {
            val (lh, lt) = l.cuenta()
            if (varias) v.append("\n## ${md(l.titulo.trim().ifEmpty { r.tareas })} — ${md(r.de(lh, lt))}\n")
            v.append('\n')
            val pend = l.pendientes
            if (pend.isEmpty()) v.append("*${md(if (lt == 0) r.vacia else r.todoHecho)}*\n")
            fun conFotos(t: TareaParaExportar) {
                v.append(lineaMd(t, r, ahora))
                if (foto != null) t.imagenes.mapNotNull(foto).forEachIndexed { i, k -> v.append("\n![${md(r.foto)} ${i + 1}]($k)\n\n") }
            }
            for (t in pend) conFotos(t)
            if (lh > 0) {
                v.append("\n${if (varias) "###" else "##"} ${md(r.hechas)} ($lh)\n\n")
                for (t in l.hechas) conFotos(t)
            }
        }
        return v.toString()
    }

    /** **Las listas en texto llano**, con casillas `☐`/`☑`. */
    fun texto(listas: List<ListaParaExportar>, titulo0: String, r: Rotulos, ahora: Momento): String {
        val titulo = titulo0.trim().ifEmpty { r.tareas }
        val (hechas, total) = cuenta(listas)
        val v = StringBuilder("$titulo\n${r.exportado(ahora)} · ${r.pendientes}: ${total - hechas} · ${r.hechas}: $hechas\n")
        val varias = listas.size > 1
        fun linea(t: TareaParaExportar): String {
            val s = StringBuilder("${if (t.hecha) '☑' else '☐'} ${t.titulo.trim()}")
            for (p in sufijo(t, r, ahora, false)) s.append(" · ").append(p)
            t.nota?.takeIf { it.isNotBlank() }?.let { s.append("\n   💬 ${it.trim()}") }
            return s.append('\n').toString()
        }
        for (l in listas) {
            val (lh, lt) = l.cuenta()
            if (varias) v.append("\n${l.titulo.trim()} (${r.de(lh, lt)})\n")
            v.append('\n')
            for (t in l.pendientes) v.append(linea(t))
            if (lh > 0) {
                v.append("\n${r.hechas} ($lh)\n")
                for (t in l.hechas) v.append(linea(t))
            }
        }
        return v.toString()
    }
}
