package com.forge.pixpin.mini

import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.ErrorFecha
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import java.time.LocalDate

/**
 * **Tareas inteligentes: lo que se reconoce al escribir una tarea** (porte de
 * `crates/pixpin-frase/src/tarea.rs` del PC, 17 y 18-oct-2026; portado el 24-oct-2026).
 *
 * El usuario, en el PC: «que reconozca la prioridad y el recordatorio; ponerle tags con el
 * hashtag» y después «que sea más inteligente»: un `#palabra` en CUALQUIER sitio es una etiqueta
 * (sale del título y se enseña aparte), la urgencia tiene tres niveles (normal, urgente y muy
 * urgente) y la fecha se escribe como se dice («18/10 17:00», «el 20 a las 9», «viernes 15:30»,
 * [Fechas]).
 *
 * **No hay campos nuevos**: todo va DENTRO del texto de la casilla, que es lo único que viaja
 * (`- [ ] texto`, el Markdown de [Tareas]). Byte a byte lo mismo que escribe el PC:
 *
 * ```
 * - [ ] llamar al banco ⏫ #casa 💬 no abren hasta el lunes ⏰ 2026-10-17 17:00 ➕ 2026-10-17
 * ```
 *
 * - La **urgencia** usa los emoticonos de prioridad de Obsidian Tasks: `⏫` muy urgente y `🔼`
 *   urgente; normal no lleva nada. Lo viejo se sigue leyendo: el `🔽` de «baja» es normal y se
 *   quita.
 * - Las **etiquetas** son `#palabra`. En el fichero van detrás del título; aquí, como en el PC, se
 *   enseñan como chapitas y el título queda limpio.
 * - La **nota** de por qué no se está haciendo va tras `💬`, y la hora (`⏰`) al final, donde la
 *   buscan [AlarmasDeTareas] y [Tareas.horaDe]. La fecha de creación `➕` la pone quien apunta
 *   ([paraApuntar]); no es de esta lectura.
 *
 * **Sin reloj propio**: quien llama da el «ahora» local ([Momento.ahora]) y las palabras de cada
 * urgencia ([Palabras]); así las pruebas son las del PC, tal cual (`TareaInteligenteTest`). En el
 * teléfono no hay Ajustes → Tareas: valen **las de fábrica** ([Palabras.preestablecidas]).
 */
object TareaInteligente {

    /** Lo que marca la nota de una tarea (`tarea::NOTA` del PC). */
    const val NOTA = "💬"

    /** Lo que marca la hora de recordar: el mismo reloj que [Tareas.RELOJ]. */
    const val RELOJ = Tareas.RELOJ

    /** Las palabras de fábrica de «urgente» (en el PC, Ajustes → Tareas las cambia). */
    val URGENTE: List<String> = listOf("urgente", "prioridad alta", "importante", "!")

    /** Las palabras de fábrica de «muy urgente». */
    val MUY_URGENTE: List<String> = listOf("muy urgente", "urgentísimo", "prioridad máxima", "!!", "!!!")

    // ---- La urgencia ---------------------------------------------------------

    /** **Cuánta prisa corre una tarea.** Sin decir nada, normal. El orden importa: gana la mayor. */
    enum class Urgencia {
        NORMAL, URGENTE, MUY_URGENTE;

        /** El emoticono que la guarda en el texto (los de Obsidian Tasks); normal no lleva. */
        val marca: String?
            get() = when (this) {
                MUY_URGENTE -> "⏫"
                URGENTE -> "🔼"
                NORMAL -> null
            }

        /** Cómo se dice («Muy urgente», «Urgente», «Normal»). */
        val nombre: String
            get() = when (this) {
                MUY_URGENTE -> "Muy urgente"
                URGENTE -> "Urgente"
                NORMAL -> "Normal"
            }

        companion object {
            /**
             * La de un emoticono guardado, que ha de ser **una palabra entera** (como el
             * `(Some(c), None)` del PC). `🔽` (la «baja» de antes) es normal: se lee y se quita.
             */
            fun deMarca(p: String): Urgencia? = when (p) {
                "⏫" -> MUY_URGENTE
                "🔼" -> URGENTE
                "🔽" -> NORMAL
                else -> null
            }
        }
    }

    /**
     * Las palabras que hacen urgente o muy urgente una tarea. Cada una puede tener varias palabras
     * («prioridad alta»); se comparan sin mayúsculas ni tildes. El `de_toml` del PC no se porta:
     * aquí no hay `pixpinmax.toml`.
     */
    data class Palabras(val urgente: List<String>, val muyUrgente: List<String>) {

        /** Cada frase partida en palabras llanas, con su nivel; las vacías fuera. */
        internal fun partidas(): List<Pair<List<String>, Urgencia>> {
            val v = ArrayList<Pair<List<String>, Urgencia>>()
            for ((lista, nivel) in listOf(muyUrgente to Urgencia.MUY_URGENTE, urgente to Urgencia.URGENTE)) {
                for (f in lista) {
                    val w = palabrasDe(f).map(::llana).filter { it.isNotEmpty() }
                    if (w.isNotEmpty()) v += w to nivel
                }
            }
            return v
        }

        companion object {
            /** Las de fábrica. */
            fun preestablecidas(): Palabras = Palabras(URGENTE, MUY_URGENTE)
        }
    }

    // ---- Una tarea entendida -------------------------------------------------

    /** Una tarea escrita, ya entendida (`tarea::Frase` del PC). */
    data class Frase(
        /** Lo que hay que hacer, sin lo reconocido. */
        val titulo: String = "",
        val urgencia: Urgencia = Urgencia.NORMAL,
        /** Sin la almohadilla, en el orden escrito y sin repetir. */
        val etiquetas: List<String> = emptyList(),
        /** Cuándo recordarla, en hora local. */
        val hora: Momento? = null,
        /** La nota de por qué no se está haciendo (tras `💬`), tal cual. */
        val nota: String? = null
    ) {
        /**
         * **El texto que se guarda**: el título, la urgencia, las etiquetas, la nota y la hora,
         * en ese orden (el que leen el PC, el vigía y las alarmas de aquí).
         */
        fun texto(): String {
            val s = StringBuilder(titulo.trim())
            fun pon(t: String) {
                if (s.isNotEmpty()) s.append(' ')
                s.append(t)
            }
            urgencia.marca?.let(::pon)
            for (e in etiquetas) pon("#$e")
            nota?.takeIf { it.isNotBlank() }?.let { pon("$NOTA ${it.trim()}") }
            hora?.let { pon(it.marca()) }
            return s.toString()
        }

        /**
         * **Lo que se entendió, en una línea** («Muy urgente · #casa · ⏰ mañana 17:00»). Vacío si
         * no se reconoció nada.
         */
        fun resumen(ahora: Momento): String {
            val partes = ArrayList<String>()
            if (urgencia != Urgencia.NORMAL) partes += urgencia.nombre
            etiquetas.mapTo(partes) { "#$it" }
            hora?.let { partes += "$RELOJ ${it.legible(ahora)}" }
            return partes.joinToString(" · ")
        }
    }

    // ---- Las palabras ----------------------------------------------------------

    /** El `split_whitespace` del PC: trozos entre blancos (de cualquier tipo), sin vacíos. */
    internal fun palabrasDe(s: String): List<String> {
        val v = ArrayList<String>()
        var desde = -1
        for (i in s.indices) {
            if (s[i].isWhitespace()) {
                if (desde >= 0) { v += s.substring(desde, i); desde = -1 }
            } else if (desde < 0) desde = i
        }
        if (desde >= 0) v += s.substring(desde)
        return v
    }

    /**
     * Una palabra para compararla: minúsculas, sin tildes ni la puntuación de los bordes (los `!`
     * se quedan: son palabras de urgencia).
     */
    private fun llana(p: String): String = Acciones.normalizar(p.trim { !it.isLetterOrDigit() && it != '!' })

    /** Si la palabra llana [t] es [w] («urgente!» también es «urgente»). */
    private fun igual(t: String, w: String): Boolean =
        t == w || (t.any { it.isLetterOrDigit() } && t.trimEnd('!') == w)

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

    /** La marca `⏰ AAAA-MM-DD HH:MM` de un texto: dónde empieza y su momento. */
    fun marcaDeHora(texto: String): Pair<Int, Momento>? {
        val i = texto.lastIndexOf(RELOJ).takeIf { it >= 0 } ?: return null
        val resto = texto.substring(i + 1).trimStart()
        if (resto.getOrNull(4) != '-' || resto.getOrNull(7) != '-' || resto.getOrNull(13) != ':') return null
        return Momento.deMarca(texto.substring(i))?.let { i to it }
    }

    /** Lo que dice que lo que sigue es cuándo recordarla. */
    private fun esAviso(p: String): Boolean = p in setOf(
        "recordar", "recuerdame", "recordarme", "recordatorio", "recuerda", "avisar", "avisame", "avisarme", "aviso", "alarma"
    )

    /** Por dónde puede empezar una fecha escrita al principio de la tarea. */
    private fun empiezaFecha(p: String): Boolean =
        p in setOf("hoy", "manana", "pasado", "el", "este", "esta", "proximo", "proxima", "dentro", "en") ||
            p in setOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo") ||
            p.firstOrNull()?.let { it in '0'..'9' } == true

    // ---- Entender lo escrito -----------------------------------------------------

    /**
     * **Entiende una tarea escrita** (`tarea::leer` del PC). [ahora]: el momento local; [palabras]:
     * las de cada urgencia.
     *
     * Lo que no se entiende se queda en el título, **nunca se adivina**: una fecha que no existe o
     * que ya pasó («recordar el 31 de febrero») no pone hora y se lee tal cual. Vale también para un
     * texto ya guardado (lo que sale al corregir una tarea): sus marcas `⏫ #casa 💬 … ⏰ …` se
     * vuelven a leer y el resultado es el mismo texto.
     */
    fun leer(texto: String, ahora: Momento, palabras: Palabras = Palabras.preestablecidas()): Frase {
        // 0. La hora ya marcada y la nota, que van detrás de todo.
        var horaGuardada: Momento? = null
        var cuerpo = texto
        marcaDeHora(cuerpo)?.let { (i, m) -> horaGuardada = m; cuerpo = cuerpo.substring(0, i) }
        var nota: String? = null
        val n = cuerpo.indexOf(NOTA)
        if (n >= 0) {
            nota = palabrasDe(cuerpo.substring(n + NOTA.length)).joinToString(" ").ifEmpty { null }
            cuerpo = cuerpo.substring(0, n)
        }
        // 1. Las etiquetas y la urgencia, estén donde estén.
        val crudas = palabrasDe(cuerpo)
        val (urgencia, etiquetas, quedan) = urgenciaYEtiquetas(crudas, palabras)
        // 2. La hora: tras «recordar» / «avísame»…, o una fecha al final o al principio.
        val p = quedan.map { crudas[it] }.toMutableList()
        var hora: Momento? = null
        hallarHora(p, ahora)?.let { (r, m) ->
            hora = m
            p.subList(r.first, r.last + 1).clear()
        }
        val titulo = p.joinToString(" ").trimEnd(',', ';', ':', ' ', '-').trim()
        return Frase(titulo, urgencia, etiquetas, hora ?: horaGuardada, nota)
    }

    /** La urgencia, las etiquetas y qué palabras quedan (sus posiciones en [crudas]) al quitarlas. */
    private fun urgenciaYEtiquetas(crudas: List<String>, palabras: Palabras): Triple<Urgencia, List<String>, List<Int>> {
        val frases = palabras.partidas()
        val llanas = crudas.map(::llana)
        var urgencia = Urgencia.NORMAL
        val etiquetas = ArrayList<String>()
        val quedan = ArrayList<Int>(crudas.size)
        var i = 0
        while (i < crudas.size) {
            val p = crudas[i]
            val e = etiquetaDe(p)
            if (e != null) {
                if (etiquetas.none { it.lowercase() == e.lowercase() }) etiquetas += e
                i++
                continue
            }
            val u = Urgencia.deMarca(p)
            if (u != null) {
                urgencia = maxOf(urgencia, u)
                i++
                continue
            }
            // La frase de urgencia más larga que empiece aquí (a igual largo, la de «muy urgente»).
            val hallada = frases
                .filter { (w, _) -> i + w.size <= llanas.size && w.indices.all { k -> igual(llanas[i + k], w[k]) } }
                .maxWithOrNull(compareBy<Pair<List<String>, Urgencia>>({ it.first.size }, { it.second }))
            if (hallada != null) {
                urgencia = maxOf(urgencia, hallada.second)
                // «con prioridad alta»: el «con» también sobra.
                if (quedan.isNotEmpty() && llanas[quedan.last()] == "con") quedan.removeAt(quedan.lastIndex)
                i += hallada.first.size
                continue
            }
            quedan += i
            i++
        }
        return Triple(urgencia, etiquetas, quedan)
    }

    /** `interpretar` sin excepción: el momento, o el error (para seguir o parar como el PC). */
    private fun interpretar(frase: String, ahora: Momento): Pair<Momento?, ErrorFecha?> =
        try { Fechas.interpretar(frase, ahora) to null } catch (e: ErrorFecha) { null to e }

    /**
     * **Dónde dice la hora** una lista de palabras: las que hay que quitar y el momento (null con un
     * «recordar» suelto al final, que se quita sin decir cuándo). Tras «recordar» / «avísame»…, o
     * una fecha al final o al principio.
     */
    private fun hallarHora(p: List<String>, ahora: Momento): Pair<IntRange, Momento?>? {
        val k = p.indexOfFirst { esAviso(llana(it)) }
        if (k >= 0) {
            // La cola más larga tras la palabra que sea una fecha entera; lo que sobre detrás vuelve
            // al título («recordar mañana comprar pan»). Una fecha que no existe o ya pasó para ahí:
            // «el 31 de febrero» no es «el 31» con «de febrero» de título.
            for (j in (k + 2..p.size).reversed()) {
                val cola = p.subList(k + 1, j).joinToString(" ")
                val (m, e) = interpretar(cola, ahora)
                if (m != null) return (k until j) to (porLaTarde(cola, ahora) ?: m)
                if (e !is ErrorFecha.NoEntiendo) break
            }
            // «recordar» suelto al final: no dice cuándo; se quita igual.
            if (k > 0 && k + 1 == p.size) return (k until k + 1) to null
        }
        val frase = p.joinToString(" ")
        val separada = try { Fechas.separar(frase, ahora) } catch (e: ErrorFecha) { null }
        if (separada != null) {
            val (cabeza, m) = separada
            val cola = frase.substring(cabeza.length)
            // Un número suelto al final («leer el capítulo 5») no es una hora: sin «recordar»
            // delante, la hora tiene que decirse como hora.
            if (m != null && cola.trim().toULongOrNull() == null) {
                return (palabrasDe(cabeza).size until p.size) to (porLaTarde(cola, ahora) ?: m)
            }
        }
        if (p.size >= 2 && empiezaFecha(llana(p[0]))) {
            // «18/10 17:00 llamar al banco», «mañana 7 gimnasio»: lo más largo del principio que sea
            // una fecha entera, dejando algo de título. Un número solo («5 manzanas») no es una hora.
            for (j in (1 until p.size).reversed()) {
                val cabeza = p.subList(0, j).joinToString(" ")
                if (j == 1 && cabeza.toULongOrNull() != null) continue
                val (m, e) = interpretar(cabeza, ahora)
                if (m != null) return (0 until j) to (porLaTarde(cabeza, ahora) ?: m)
                if (e !is ErrorFecha.NoEntiendo) break
            }
        }
        return null
    }

    /**
     * **Lo escrito sin la fecha dicha con palabras** («pan mañana a las 10 urgente» → «pan
     * urgente»), con lo demás en su sitio y sin reconocer: lo que hace el selector de día y hora
     * antes de poner la marca elegida.
     */
    fun sinFechaDicha(texto: String, ahora: Momento, palabras: Palabras = Palabras.preestablecidas()): String {
        val crudas = palabrasDe(texto)
        val quedan = urgenciaYEtiquetas(crudas, palabras).third
        val otras = quedan.map { crudas[it] }
        val (r, m) = hallarHora(otras, ahora) ?: return texto.trim()
        if (m == null) return texto.trim()
        val fuera = quedan.subList(r.first, r.last + 1).toSet()
        return crudas.filterIndexed { k, _ -> k !in fuera }.joinToString(" ")
    }

    /**
     * **«A las 5» es por la tarde.** Nadie apunta una tarea para las cinco de la madrugada sin
     * decirlo: una hora suelta de la 1 a las 7 tras «las», sin «de la mañana», «am» ni minutos, se
     * lee como de la tarde (las 17:00). null si la frase no es de esas o ya dice la franja.
     */
    private fun porLaTarde(frase: String, ahora: Momento): Momento? {
        val q = Acciones.normalizar(frase)
        val palabras = palabrasDe(q)
        val franja = listOf("de la manana", "por la manana", "madrugada", "tarde", "noche", "mediodia").any { q.contains(it) } ||
            palabras.any { it.endsWith("am") || it.endsWith("pm") }
        if (franja) return null
        val suelta = palabras.zipWithNext().any { (a, b) -> (a == "las" || a == "la") && (b.toUIntOrNull()?.toInt() ?: 0) in 1..7 }
        if (!suelta) return null
        return interpretar("$frase de la tarde", ahora).first
    }

    // ---- Leer lo guardado ----------------------------------------------------------

    /**
     * **Lo que lleva una tarea ya guardada, para enseñarla** (`tarea::marcas` del PC). [texto]: lo
     * que se ve, sin la fecha de creación `➕` ni las imágenes (las quita [Tareas.partir] y
     * [Tareas.imagenes]; ver [analizar]). No interpreta fechas dichas: solo las marcas.
     */
    fun marcas(texto: String): Frase {
        var base = texto
        var hora: Momento? = null
        marcaDeHora(base)?.let { (i, m) -> hora = m; base = base.substring(0, i) }
        var nota: String? = null
        val n = base.indexOf(NOTA)
        if (n >= 0) {
            nota = base.substring(n + NOTA.length).trim().ifEmpty { null }
            base = base.substring(0, n)
        }
        var urgencia = Urgencia.NORMAL
        val etiquetas = ArrayList<String>()
        val quedan = ArrayList<String>()
        for (p in palabrasDe(base)) {
            val u = Urgencia.deMarca(p)
            if (u != null) { urgencia = maxOf(urgencia, u); continue }
            val e = etiquetaDe(p)
            if (e != null) { if (e !in etiquetas) etiquetas += e; continue }
            quedan += p
        }
        return Frase(quedan.joinToString(" "), urgencia, etiquetas, hora, nota)
    }

    // ---- Lo del teléfono (no está en `tarea.rs`) ---------------------------------------

    /** **Una casilla guardada, por partes**: lo que pinta una fila de tareas. */
    data class Partes(
        val titulo: String,
        val urgencia: Urgencia,
        val etiquetas: List<String>,
        val nota: String?,
        /** La hora de recordar (`⏰`), en hora local. */
        val recordatorio: Momento?,
        /** El día en que se creó (`➕`). */
        val creada: LocalDate?,
        /** Los enlaces de sus imágenes, en orden. */
        val imagenes: List<String>
    )

    /**
     * **Analiza una casilla tal como está guardada** (el texto crudo, con su `➕`, su `⏰` y sus
     * imágenes, en cualquiera de los dos órdenes que escriben el PC y el teléfono).
     */
    fun analizar(crudo: String): Partes {
        val (visible, creada) = Tareas.partir(crudo)
        val (texto, imagenes) = Tareas.imagenes(visible)
        val f = marcas(texto)
        return Partes(f.titulo, f.urgencia, f.etiquetas, f.nota, marcaDeHora(crudo)?.second, creada, imagenes)
    }

    /**
     * **El título limpio de una casilla**: sin fecha, imágenes, urgencia, etiquetas, nota ni hora.
     * Para los avisos, las alarmas y los diálogos («Mover "llamar al banco" a…»).
     */
    fun tituloDe(crudo: String): String = analizar(crudo).titulo

    /**
     * **El texto que se guarda de lo escrito en una caja** (`frase::a_guardar` del PC): lo
     * entendido o, si no queda título ni hay imágenes (solo «#casa»), lo escrito tal cual.
     *
     * Las imágenes ya puestas (`![img 01](…)`, lo que sale de [CampoConImagenes.componer]) no se
     * leen como palabras: se apartan, se entiende lo demás y vuelven **detrás del título**, delante
     * de las marcas; si fueran detrás de la `⏰`, [Tareas.sinHora] las cortaría con ella.
     */
    fun aGuardar(escrito: String, ahora: Momento = Momento.ahora(), palabras: Palabras = Palabras.preestablecidas()): String {
        val (texto, imagenes) = Tareas.imagenes(escrito)
        val f = leer(texto, ahora, palabras)
        if (f.titulo.isEmpty() && imagenes.isEmpty()) return escrito.trim()
        return f.copy(titulo = Tareas.conImagenes(f.titulo, imagenes)).texto()
    }

    /**
     * **Lo escrito, listo para [Tareas.anadir]**: entendido ([aGuardar]) y con su fecha de creación
     * **detrás de todo**, como `mini::anadir_el` del PC (`… ⏰ 2026-10-25 09:00 ➕ 2026-10-24`), que
     * así lo escribe igual byte a byte. [Tareas.partir] y [Tareas.sinHora] leen ese orden y el de
     * siempre del teléfono (`➕` antes de `⏰`). Lo que ya traía su `➕` (pegado) se deja tal cual,
     * como hacía [Tareas.anadir]. Vacío si no hay nada que apuntar.
     */
    fun paraApuntar(
        escrito: String,
        hoy: LocalDate = LocalDate.now(),
        ahora: Momento = Momento.ahora(),
        palabras: Palabras = Palabras.preestablecidas()
    ): String {
        val limpio = Tareas.saneado(escrito)
        if (limpio.isEmpty()) return ""
        if (Tareas.partir(limpio).second != null) return limpio
        val t = aGuardar(limpio, ahora, palabras)
        return if (t.isEmpty()) "" else "$t ➕ $hoy"
    }
}
