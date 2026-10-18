package com.forge.pixpin.mini

import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.ErrorFecha
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento

/**
 * **Lo que se reconoce al escribir una tarea** (PC `bfdd89a`, `apps/pixpin/src/tareas/frase.rs`,
 * 17-oct-2026, portado tal cual con sus pruebas en `FraseTest`).
 *
 * Al apuntar «llamar al banco prioridad alta recordar a las 5 #casa» se guarda la tarea «llamar al
 * banco» con prioridad alta, la etiqueta `#casa` y su hora de recordar.
 *
 * **No hay campos nuevos**: como la fecha de creación (`➕`) y la hora de recordar (`⏰`), todo va
 * DENTRO del texto de la tarea, que es lo único que viaja:
 *
 * ```
 * - [ ] llamar al banco ⏫ #casa 💬 no abren hasta el lunes ⏰ 2026-10-17 17:00 ➕ 2026-10-17
 * ```
 *
 * - La **prioridad** son los emoticonos de Obsidian Tasks: `⏫` alta, `🔼` media, `🔽` baja.
 * - Las **etiquetas** son `#palabra`: el buscador de Tareas las encuentra escribiendo `#casa`.
 * - La **nota** de por qué no se está haciendo (la del pin de la tarea) va tras `💬`, delante de la
 *   hora y de la fecha, que siguen al final para que [Tareas.partir] y [Tareas.horaDe] las lean.
 *
 * La fecha dicha en español la entiende [Fechas] (el mismo que usa el asistente), sin reloj propio:
 * quien llama da el «ahora». Sin Android.
 */
enum class Prioridad(val marca: String) {
    BAJA("🔽"), MEDIA("🔼"), ALTA("⏫");

    companion object {
        fun deMarca(p: String): Prioridad? = entries.firstOrNull { it.marca == p }

        fun dePalabra(p: String): Prioridad? = when (p) {
            "alta", "alto", "urgente", "maxima" -> ALTA
            "media", "medio", "normal" -> MEDIA
            "baja", "bajo", "minima" -> BAJA
            else -> null
        }
    }
}

object Frase {

    /** Lo que marca la nota de una tarea. */
    const val NOTA = "💬"

    /** Una tarea recién escrita, ya entendida. */
    data class Leida(
        /** Lo que hay que hacer, sin lo reconocido. */
        val titulo: String,
        val prioridad: Prioridad?,
        /** Sin la almohadilla, en el orden escrito y sin repetir. */
        val etiquetas: List<String>,
        /** Cuándo recordarla, en hora local. */
        val hora: Momento?
    ) {
        /** El texto que se guarda: el título con sus marcas detrás. */
        fun texto(): String {
            val s = StringBuilder(titulo)
            prioridad?.let { s.append(' ').append(it.marca) }
            etiquetas.forEach { s.append(" #").append(it) }
            var t = s.toString()
            hora?.let { t = Tareas.sinHora(t).trimEnd() + " " + it.marca() }
            return t.trim()
        }
    }

    /** Los milisegundos locales de un momento. */
    fun msDe(m: Momento): Long = (m.dia * 1440 + m.minuto) * 60_000L

    /** Lo que dice que lo que sigue es cuándo recordarla. */
    private fun esAviso(p: String) = p in setOf(
        "recordar", "recuerdame", "recordarme", "recordatorio", "recuerda",
        "avisar", "avisame", "avisarme", "aviso", "alarma"
    )

    /** Una palabra para compararla: minúsculas, sin tildes ni puntuación. */
    private fun llana(p: String): String =
        Acciones.normalizar(p.trim { !it.isLetterOrDigit() && it != '!' })

    /**
     * La etiqueta de una palabra `#algo`, sin la almohadilla ni la puntuación de detrás. null si no
     * lo es (`#` suelto; `#1` sigue siendo un número).
     */
    fun etiquetaDe(p: String): String? {
        if (!p.startsWith('#')) return null
        val e = p.substring(1).trimEnd { !it.isLetterOrDigit() }
        val valida = e.isNotEmpty() && e.any { it.isLetter() } && e.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        return if (valida) e else null
    }

    /**
     * **Entiende una tarea recién escrita.** [ahora]: el momento local.
     *
     * Lo que no se entiende se queda en el título, nunca se adivina: una fecha que no existe o que ya
     * pasó («recordar el 31 de febrero») no pone hora y se lee tal cual.
     */
    fun leer(texto: String, ahora: Momento): Leida {
        var prioridad: Prioridad? = null
        val etiquetas = ArrayList<String>()
        // 1. Las etiquetas y la prioridad, estén donde estén.
        val crudas = texto.split(BLANCOS).filter { it.isNotEmpty() }
        val quedan = ArrayList<String>(crudas.size)
        var i = 0
        while (i < crudas.size) {
            val p = crudas[i]
            val e = etiquetaDe(p)
            if (e != null) {
                if (etiquetas.none { it.equals(e, ignoreCase = true) }) etiquetas += e
                i++; continue
            }
            val l = llana(p)
            val sig = crudas.getOrNull(i + 1)?.let { llana(it) }
            // «prioridad alta», «prio alta», «con prioridad alta».
            if (l in setOf("prioridad", "prio", "prioritaria")) {
                val pr = sig?.let { Prioridad.dePalabra(it) }
                if (pr != null) {
                    prioridad = pr
                    if (quedan.lastOrNull()?.let { llana(it) } == "con") quedan.removeAt(quedan.size - 1)
                    i += 2; continue
                }
            }
            // «alta prioridad».
            val prSuelta = Prioridad.dePalabra(l)
            if (prSuelta != null && sig == "prioridad") {
                prioridad = prSuelta
                if (quedan.lastOrNull()?.let { llana(it) } == "con") quedan.removeAt(quedan.size - 1)
                i += 2; continue
            }
            // «!alta», «!!!» y «urgente» sueltos.
            val prExcl = if (l.startsWith('!')) Prioridad.dePalabra(l.substring(1)) else null
            if (prExcl != null) { prioridad = prExcl; i++; continue }
            if (p == "!!!" || l == "urgente") { prioridad = Prioridad.ALTA; i++; continue }
            val prMarca = Prioridad.deMarca(p)
            if (prMarca != null) { prioridad = prMarca; i++; continue }
            quedan += p
            i++
        }
        // 2. La hora: tras «recordar» / «avísame»…, o una fecha al final.
        var hora: Momento? = null
        var palabras: MutableList<String> = quedan.toMutableList()
        val k = palabras.indexOfFirst { esAviso(llana(it)) }
        if (k >= 0) {
            // La cola más larga tras la palabra que sea una fecha entera; lo que sobre detrás vuelve
            // al título («recordar mañana comprar pan»).
            var hallada: Pair<Int, Momento>? = null
            for (j in palabras.size downTo k + 2) {
                val m = fechaDe(palabras.subList(k + 1, j).joinToString(" "), ahora)
                if (m != null) { hallada = j to m; break }
            }
            if (hallada != null) {
                hora = hallada.second
                palabras.subList(k, hallada.first).clear()
            } else if (k > 0 && k + 1 == palabras.size) {
                // «recordar» suelto al final: no dice cuándo; se quita igual.
                palabras.removeAt(k)
            }
        }
        if (hora == null) {
            val frase = palabras.joinToString(" ")
            val separada = try { Fechas.separar(frase, ahora) } catch (e: ErrorFecha) { null }
            val m = separada?.second
            if (separada != null && m != null) {
                val cola = frase.substring(separada.first.length)
                // Un número suelto al final («leer el capítulo 5») no es una hora: sin «recordar»
                // delante, la hora tiene que decirse como hora.
                if (cola.trim().toULongOrNull() == null) {
                    hora = porLaTarde(cola, ahora) ?: m
                    palabras = separada.first.split(BLANCOS).filter { it.isNotEmpty() }.toMutableList()
                }
            }
        }
        val titulo = palabras.joinToString(" ").trimEnd(',', ';', ':', ' ', '-').trim()
        return Leida(titulo, prioridad, etiquetas, hora)
    }

    /** Una fecha dicha, con la hora de la tarde si es lo que se quería decir. */
    private fun fechaDe(frase: String, ahora: Momento): Momento? {
        val m = try { Fechas.interpretar(frase, ahora) } catch (e: ErrorFecha) { return null }
        return porLaTarde(frase, ahora) ?: m
    }

    /**
     * **«A las 5» es por la tarde.** Nadie apunta una tarea para las cinco de la madrugada sin
     * decirlo: una hora suelta de la 1 a las 7, sin «de la mañana», «am» ni minutos, se lee como de
     * la tarde. null si la frase no es de esas o ya dice la franja.
     */
    private fun porLaTarde(frase: String, ahora: Momento): Momento? {
        val q = Acciones.normalizar(frase)
        val palabras = q.split(BLANCOS).filter { it.isNotEmpty() }
        val franja = listOf("de la manana", "por la manana", "madrugada", "tarde", "noche", "mediodia").any { q.contains(it) } ||
            palabras.any { it == "am" || it == "pm" }
        if (franja) return null
        val suelta = palabras.zipWithNext().any { (a, b) -> (a == "las" || a == "la") && b.toIntOrNull()?.let { it in 1..7 } == true }
        if (!suelta) return null
        return try { Fechas.interpretar("$frase de la tarde", ahora) } catch (e: ErrorFecha) { null }
    }

    // ---- Leer lo guardado ------------------------------------------------

    /** Lo que lleva una tarea ya guardada, para enseñarla. */
    data class Marcas(
        /** Lo que se lee: sin prioridad, etiquetas, nota ni hora. */
        val titulo: String = "",
        val prioridad: Prioridad? = null,
        val etiquetas: List<String> = emptyList(),
        val nota: String? = null,
        /** La hora de recordar, en ms locales. */
        val hora: Long? = null
    )

    /** Las marcas de un texto de tarea (el crudo entero: la fecha y las imágenes no estorban). */
    fun marcas(texto: String): Marcas {
        val hora = Tareas.horaDe(texto)?.let { AlarmasDeTareas.msLocales(it) }
        var base = Tareas.imagenes(Tareas.partir(texto).first).first
        var nota: String? = null
        val i = base.indexOf(NOTA)
        if (i >= 0) {
            nota = base.substring(i + NOTA.length).trim().ifEmpty { null }
            base = base.substring(0, i)
        }
        var prioridad: Prioridad? = null
        val etiquetas = ArrayList<String>()
        val quedan = ArrayList<String>()
        for (p in base.split(BLANCOS)) {
            if (p.isEmpty()) continue
            val pr = Prioridad.deMarca(p)
            if (pr != null) { prioridad = pr; continue }
            val e = etiquetaDe(p)
            if (e != null) { if (e !in etiquetas) etiquetas += e; continue }
            quedan += p
        }
        return Marcas(quedan.joinToString(" "), prioridad, etiquetas, nota, hora)
    }

    /**
     * **Lo que enseña la tarjeta** de una tarea: el título con sus `#etiquetas` detrás y, si tiene,
     * la hora de recordar legible («⏰ hoy 17:00») y la nota; y su prioridad aparte, para la chapita.
     * (El PC saca además los emoticonos como chapitas; aquí se quedan en el texto.)
     */
    fun aLaVista(texto: String, ahora: Momento?): Pair<String, Prioridad?> {
        val m = marcas(texto)
        val s = StringBuilder(m.titulo)
        m.etiquetas.forEach { s.append(" #").append(it) }
        m.hora?.let { h ->
            val mm = Momento.deMsLocales(h)
            val cuando = if (ahora != null) mm.legible(ahora) else {
                val (y, mo, d) = Fechas.fechaDe(mm.dia)
                "$d/$mo/$y %02d:%02d".format(mm.minuto / 60, mm.minuto % 60)
            }
            s.append(" · ⏰ ").append(cuando)
        }
        m.nota?.let { s.append(" · ").append(NOTA).append(' ').append(it) }
        return s.toString().trim() to m.prioridad
    }

    /**
     * **Lo que se guarda de lo escrito** en la caja de apuntar: lo reconocido como marcas detrás del
     * título; si no queda título (solo «#casa», por ejemplo), lo escrito tal cual, como el PC.
     */
    fun paraGuardar(texto: String, ahora: Momento): String {
        val limpio = Tareas.saneado(texto)
        val f = leer(limpio, ahora)
        return if (f.titulo.isEmpty()) limpio else f.texto()
    }

    /**
     * **El pin de una tarea** (`pines/tarea.rs` del PC): solo su título y, debajo, la hora de recordar
     * con sus etiquetas, y la nota de por qué no se está haciendo.
     */
    fun textoDelPin(crudo: String, ahora: Momento): String {
        val m = marcas(crudo)
        val linea = buildList {
            m.hora?.let { add("⏰ " + Momento.deMsLocales(it).legible(ahora)) }
            m.etiquetas.forEach { add("#$it") }
        }.joinToString(" ")
        return listOfNotNull(
            "☑ " + m.titulo.ifBlank { "Tarea" } + (m.prioridad?.let { " ${it.marca}" } ?: ""),
            linea.ifBlank { null },
            m.nota?.let { "$NOTA $it" }
        ).joinToString("\n")
    }

    /** Una nota en un renglón y sin las marcas que confundirían al leerla. */
    private fun notaLimpia(nota: String): String =
        nota.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Tareas.RELOJ.toString(), "").replace("➕", "").replace(NOTA, "")
            .split(BLANCOS).filter { it.isNotEmpty() }.joinToString(" ")

    private val CREADA_AL_FINAL = Regex("""(?:^|\s)➕\s*(\d{4}-\d{2}-\d{2})\s*$""")

    /**
     * **El texto crudo de una tarea con otra nota** (vacía: sin nota). La nota va tras `💬`, delante
     * de las imágenes, la hora y la fecha, que se quedan como estaban y en el orden en que estaban
     * (el PC pone `⏰ … ➕ …`; Android, desde el 8-oct, `➕ … ⏰ …`: las dos se leen en los dos).
     */
    fun conNota(crudo: String, nota: String): String {
        // La fecha al final (orden del PC) o la hora al final (orden de Android).
        var resto = crudo
        var creadaFinal: String? = null
        CREADA_AL_FINAL.find(resto)?.let { creadaFinal = it.value.trim(); resto = resto.substring(0, it.range.first) }
        val hora = Tareas.horaDe(resto)
        if (hora != null) resto = Tareas.sinHora(resto)
        var creadaAntes: String? = null
        if (creadaFinal == null) CREADA_AL_FINAL.find(resto)?.let { creadaAntes = it.value.trim(); resto = resto.substring(0, it.range.first) }
        val (texto, imagenes) = Tareas.imagenes(resto)
        val i = texto.indexOf(NOTA)
        val sinNota = if (i >= 0) texto.substring(0, i).trimEnd() else texto
        val limpia = notaLimpia(nota)
        var s = if (limpia.isNotEmpty()) "${sinNota.trimEnd()} $NOTA $limpia" else sinNota
        s = Tareas.conImagenes(s, imagenes)
        creadaAntes?.let { s = "${s.trimEnd()} $it" }
        hora?.let { s = "${s.trimEnd()} ${Tareas.marcaDeHora(it)}" }
        creadaFinal?.let { s = "${s.trimEnd()} $it" }
        return s.trim()
    }

    private val BLANCOS = Regex("""\s+""")
}
