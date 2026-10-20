package com.forge.pixpin.mini

import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.ErrorFecha
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento

/**
 * **Lo que se reconoce al escribir una tarea** (PC `pixpin-frase/src/tarea.rs`, 17 y 18-oct-2026,
 * portado tal cual con sus pruebas en `FraseTest`).
 *
 * El usuario: «que reconozca la prioridad y el recordatorio; ponerle tags con el hashtag» y después
 * «que sea más inteligente»: un `#palabra` en CUALQUIER sitio es una etiqueta (sale del título y se
 * enseña aparte), la urgencia tiene tres niveles (normal, urgente y muy urgente, con palabras que se
 * cambian en Ajustes) y la fecha se escribe como se dice («18/10 17:00», «el 20 a las 9», «viernes
 * 15:30», [Fechas]).
 *
 * **No hay campos nuevos**: todo va DENTRO del texto de la tarea, que es lo único que viaja:
 *
 * ```
 * - [ ] llamar al banco ⏫ #casa 💬 no abren hasta el lunes ⏰ 2026-10-17 17:00 ➕ 2026-10-17
 * ```
 *
 * - La **urgencia** usa los emoticonos de Obsidian Tasks: `⏫` muy urgente y `🔼` urgente; normal no
 *   lleva nada. El `🔽` de la «baja» de antes se lee como normal y se quita.
 * - Las **etiquetas** son `#palabra`, detrás del título; se enseñan como chapitas.
 * - La **nota** va tras `💬`, y la hora (`⏰`) al final, donde la buscan los avisos.
 *
 * Sin reloj propio: quien llama da el «ahora» local y las palabras de cada urgencia. Sin Android.
 */
enum class Urgencia(val marca: String?, val nombre: String) {
    NORMAL(null, "Normal"), URGENTE("🔼", "Urgente"), MUY_URGENTE("⏫", "Muy urgente");

    companion object {
        /** La de un emoticono guardado. `🔽` (la «baja» de antes) es normal. */
        fun deMarca(p: String): Urgencia? = when (p) {
            "⏫" -> MUY_URGENTE
            "🔼" -> URGENTE
            "🔽" -> NORMAL
            else -> null
        }
    }
}

object Frase {
    /** Lo que marca la nota de una tarea. */
    const val NOTA = "💬"
    /** Lo que marca la hora de recordar. */
    const val RELOJ = "⏰"

    val URGENTE = listOf("urgente", "prioridad alta", "importante", "!")
    val MUY_URGENTE = listOf("muy urgente", "urgentísimo", "prioridad máxima", "!!", "!!!")

    /** **Las de Ajustes ahora** (PC `tareas::frase::fijar`): las pone la app al arrancar y al cambiarlas. */
    @Volatile var actuales: Palabras = Palabras()

    /** Las palabras que hacen urgente o muy urgente una tarea (Ajustes); sin mayúsculas ni tildes. */
    data class Palabras(val urgente: List<String> = URGENTE, val muyUrgente: List<String> = MUY_URGENTE) {
        /** Cada frase partida en palabras llanas, con su nivel; las vacías fuera. */
        internal fun partidas(): List<Pair<List<String>, Urgencia>> =
            listOf(muyUrgente to Urgencia.MUY_URGENTE, urgente to Urgencia.URGENTE).flatMap { (lista, nivel) ->
                lista.mapNotNull { f -> f.split(Regex("\\s+")).map { llana(it) }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { it to nivel } }
            }

        companion object {
            /** Las de Ajustes, separadas por comas («urgente, importante, !»). Vacío: ninguna. */
            fun deTextos(urgente: String?, muyUrgente: String?) = Palabras(
                urgente?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: URGENTE,
                muyUrgente?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: MUY_URGENTE
            )
        }
    }

    /** Una tarea escrita, ya entendida. */
    data class Leida(
        /** Lo que hay que hacer, sin lo reconocido. */
        val titulo: String,
        val urgencia: Urgencia = Urgencia.NORMAL,
        /** Sin la almohadilla, en el orden escrito y sin repetir. */
        val etiquetas: List<String> = emptyList(),
        /** Cuándo recordarla, en hora local. */
        val hora: Momento? = null,
        /** La nota de por qué no se está haciendo (tras `💬`). */
        val nota: String? = null
    ) {
        /** **El texto que se guarda**: título, urgencia, etiquetas, nota y hora, en ese orden. */
        fun texto(): String {
            val s = StringBuilder(titulo.trim())
            fun pon(t: String) { if (s.isNotEmpty()) s.append(' '); s.append(t) }
            urgencia.marca?.let { pon(it) }
            etiquetas.forEach { pon("#$it") }
            nota?.trim()?.takeIf { it.isNotEmpty() }?.let { pon("$NOTA $it") }
            hora?.let { pon(it.marca()) }
            return s.toString()
        }

        /** **Lo que se entendió, en una línea** («Muy urgente · #casa · ⏰ mañana 17:00»). */
        fun resumen(ahora: Momento): String {
            val partes = ArrayList<String>()
            if (urgencia != Urgencia.NORMAL) partes += urgencia.nombre
            etiquetas.forEach { partes += "#$it" }
            hora?.let { partes += "$RELOJ ${it.legible(ahora)}" }
            return partes.joinToString(" · ")
        }
    }

    /** Los milisegundos locales de un momento. */
    fun msDe(m: Momento): Long = (m.dia * 1440 + m.minuto) * 60_000L

    private fun alfanum(c: Char) = c.isLetterOrDigit()

    /** Una palabra para compararla: minúsculas, sin tildes ni la puntuación de los bordes (los `!` se quedan). */
    fun llana(p: String): String = Acciones.normalizar(p.trim { !alfanum(it) && it != '!' })

    /** Si la palabra llana [t] es [w] («urgente!» también es «urgente»). */
    private fun igual(t: String, w: String) = t == w || (t.any { alfanum(it) } && t.trimEnd('!') == w)

    /** La etiqueta de una palabra `#algo`, sin la almohadilla ni la puntuación de detrás; null si no lo es. */
    fun etiquetaDe(p: String): String? {
        if (!p.startsWith("#")) return null
        val e = p.substring(1).trimEnd { !alfanum(it) }
        val valida = e.isNotEmpty() && e.any { it.isLetter() } && e.all { alfanum(it) || it == '_' || it == '-' }
        return e.takeIf { valida }
    }

    /** La marca `⏰ AAAA-MM-DD HH:MM` de un texto: dónde empieza y su momento. */
    fun marcaDeHora(texto: String): Pair<Int, Momento>? {
        val i = texto.lastIndexOf(RELOJ).takeIf { it >= 0 } ?: return null
        val resto = texto.substring(i + RELOJ.length).trimStart()
        if (resto.length < 14 || resto[4] != '-' || resto[7] != '-' || resto[13] != ':') return null
        return Momento.deMarca(texto.substring(i))?.let { i to it }
    }

    private fun esAviso(p: String) = p in setOf(
        "recordar", "recuerdame", "recordarme", "recordatorio", "recuerda", "avisar", "avisame", "avisarme", "aviso", "alarma"
    )

    private fun empiezaFecha(p: String) = p in setOf("hoy", "manana", "pasado", "el", "este", "esta", "proximo", "proxima", "dentro", "en") ||
        p in setOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo") || p.firstOrNull()?.isDigit() == true

    private fun palabrasDe(t: String) = t.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun interpretar(f: String, ahora: Momento): Result<Momento> = runCatching { Fechas.interpretar(f, ahora) }
    private fun noEntiende(r: Result<Momento>) = r.exceptionOrNull() is ErrorFecha.NoEntiendo

    /**
     * **Entiende una tarea escrita.** Lo que no se entiende se queda en el título, nunca se adivina.
     * Vale también para un texto ya guardado: sus marcas se vuelven a leer y sale el mismo texto.
     */
    fun leer(texto: String, ahora: Momento, palabras: Palabras = actuales): Leida {
        var cuerpo = texto
        var horaGuardada: Momento? = null
        marcaDeHora(cuerpo)?.let { (i, m) -> horaGuardada = m; cuerpo = cuerpo.substring(0, i) }
        var nota: String? = null
        val iNota = cuerpo.indexOf(NOTA)
        if (iNota >= 0) {
            nota = palabrasDe(cuerpo.substring(iNota + NOTA.length)).joinToString(" ").takeIf { it.isNotEmpty() }
            cuerpo = cuerpo.substring(0, iNota)
        }
        val crudas = palabrasDe(cuerpo)
        val (urgencia, etiquetas, quedan) = urgenciaYEtiquetas(crudas, palabras)
        val resto = quedan.map { crudas[it] }.toMutableList()
        var hora: Momento? = null
        hallarHora(resto, ahora)?.let { (r, m) -> hora = m; repeat(r.last - r.first + 1) { resto.removeAt(r.first) } }
        val titulo = resto.joinToString(" ").trimEnd(',', ';', ':', ' ', '-').trim()
        return Leida(titulo, urgencia, etiquetas, hora ?: horaGuardada, nota)
    }

    private fun urgenciaYEtiquetas(crudas: List<String>, palabras: Palabras): Triple<Urgencia, List<String>, List<Int>> {
        val frases = palabras.partidas()
        val llanas = crudas.map { llana(it) }
        var urgencia = Urgencia.NORMAL
        val etiquetas = ArrayList<String>()
        val quedan = ArrayList<Int>()
        var i = 0
        while (i < crudas.size) {
            val p = crudas[i]
            val e = etiquetaDe(p)
            if (e != null) {
                if (etiquetas.none { it.lowercase() == e.lowercase() }) etiquetas += e
                i++; continue
            }
            val u = Urgencia.deMarca(p)
            if (u != null) { if (u > urgencia) urgencia = u; i++; continue }
            // La frase de urgencia más larga que empiece aquí (a igual largo, la de «muy urgente»).
            val hallada = frases.filter { (w, _) -> i + w.size <= llanas.size && w.indices.all { k -> igual(llanas[i + k], w[k]) } }
                .maxWithOrNull(compareBy({ it.first.size }, { it.second }))
            if (hallada != null) {
                if (hallada.second > urgencia) urgencia = hallada.second
                // «con prioridad alta»: el «con» también sobra.
                if (quedan.isNotEmpty() && llanas[quedan.last()] == "con") quedan.removeAt(quedan.size - 1)
                i += hallada.first.size
                continue
            }
            quedan += i
            i++
        }
        return Triple(urgencia, etiquetas, quedan)
    }

    /** **Dónde dice la hora** una lista de palabras: las que hay que quitar (índices) y el momento. */
    private fun hallarHora(p: List<String>, ahora: Momento): Pair<IntRange, Momento?>? {
        val k = p.indexOfFirst { esAviso(llana(it)) }
        if (k >= 0) {
            for (j in p.size downTo k + 2) {
                val cola = p.subList(k + 1, j).joinToString(" ")
                val r = interpretar(cola, ahora)
                if (r.isSuccess) return (k until j) to (porLaTarde(cola, ahora) ?: r.getOrThrow())
                if (!noEntiende(r)) break
            }
            if (k > 0 && k + 1 == p.size) return (k..k) to null
        }
        val frase = p.joinToString(" ")
        val separada = runCatching { Fechas.separar(frase, ahora) }.getOrNull()
        if (separada != null && separada.second != null) {
            val (cabeza, m) = separada
            val cola = frase.substring(cabeza.length).trim()
            if (cola.toULongOrNull() == null) {
                val n = palabrasDe(cabeza).size
                return (n until p.size) to (porLaTarde(cola, ahora) ?: m)
            }
        }
        if (p.size >= 2 && empiezaFecha(llana(p[0]))) {
            for (j in p.size - 1 downTo 1) {
                val cabeza = p.subList(0, j).joinToString(" ")
                if (j == 1 && cabeza.toULongOrNull() != null) continue
                val r = interpretar(cabeza, ahora)
                if (r.isSuccess) return (0 until j) to (porLaTarde(cabeza, ahora) ?: r.getOrThrow())
                if (!noEntiende(r)) break
            }
        }
        return null
    }

    /** **Lo escrito sin la fecha dicha con palabras**, con lo demás en su sitio: lo que hace el selector de día y hora. */
    fun sinFechaDicha(texto: String, ahora: Momento, palabras: Palabras = actuales): String {
        val crudas = palabrasDe(texto)
        val (_, _, quedan) = urgenciaYEtiquetas(crudas, palabras)
        val otras = quedan.map { crudas[it] }
        val (r, m) = hallarHora(otras, ahora) ?: return texto.trim()
        if (m == null) return texto.trim()
        val fuera = r.map { quedan[it] }.toSet()
        return crudas.filterIndexed { k, _ -> k !in fuera }.joinToString(" ")
    }

    /** **«A las 5» es por la tarde**: una hora suelta de la 1 a las 7 tras «las», sin franja ni minutos. */
    private fun porLaTarde(frase: String, ahora: Momento): Momento? {
        val q = Acciones.normalizar(frase)
        val ps = palabrasDe(q)
        val franja = listOf("de la manana", "por la manana", "madrugada", "tarde", "noche", "mediodia").any { q.contains(it) } ||
            ps.any { it.endsWith("am") || it.endsWith("pm") }
        if (franja) return null
        val suelta = ps.zipWithNext().any { (a, b) -> a in setOf("las", "la") && b.toIntOrNull()?.let { it in 1..7 } == true }
        if (!suelta) return null
        return interpretar("$frase de la tarde", ahora).getOrNull()
    }

    /** Lo que lleva una tarea ya guardada (`marcas` del PC). [texto]: lo que se ve (sin `➕` ni imágenes). */
    fun marcasDe(texto: String): Leida {
        var base = texto
        var hora: Momento? = null
        marcaDeHora(base)?.let { (i, m) -> hora = m; base = base.substring(0, i) }
        var nota: String? = null
        val iNota = base.indexOf(NOTA)
        if (iNota >= 0) {
            nota = base.substring(iNota + NOTA.length).trim().takeIf { it.isNotEmpty() }
            base = base.substring(0, iNota)
        }
        var urgencia = Urgencia.NORMAL
        val etiquetas = ArrayList<String>()
        val quedan = ArrayList<String>()
        for (p in palabrasDe(base)) {
            val u = Urgencia.deMarca(p)
            if (u != null) { if (u > urgencia) urgencia = u; continue }
            val e = etiquetaDe(p)
            if (e != null) { if (e !in etiquetas) etiquetas += e; continue }
            quedan += p
        }
        return Leida(quedan.joinToString(" "), urgencia, etiquetas, hora, nota)
    }

    /** Lo que lleva una tarea ya guardada, para enseñarla. */
    data class Marcas(
        /** Lo que se lee: sin urgencia, etiquetas, nota ni hora. */
        val titulo: String = "",
        val urgencia: Urgencia = Urgencia.NORMAL,
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
        var urgencia = Urgencia.NORMAL
        val etiquetas = ArrayList<String>()
        val quedan = ArrayList<String>()
        for (p in base.split(BLANCOS)) {
            if (p.isEmpty()) continue
            val u = Urgencia.deMarca(p)
            if (u != null) { if (u > urgencia) urgencia = u; continue }
            val e = etiquetaDe(p)
            if (e != null) { if (e !in etiquetas) etiquetas += e; continue }
            quedan += p
        }
        return Marcas(quedan.joinToString(" "), urgencia, etiquetas, nota, hora)
    }

    /**
     * **Lo que se guarda de lo escrito** en la caja de apuntar: lo reconocido como marcas detrás del
     * título; si no queda título (solo «#casa», por ejemplo), lo escrito tal cual, como el PC.
     */
    fun paraGuardar(texto: String, ahora: Momento, palabras: Palabras = actuales): String {
        val limpio = Tareas.saneado(texto)
        val f = leer(limpio, ahora, palabras)
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
            "☑ " + m.titulo.ifBlank { "Tarea" } + (m.urgencia.marca?.let { " $it" } ?: ""),
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
