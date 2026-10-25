package com.forge.pixpin.horario

import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.ErrorFecha
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import java.io.File

/**
 * **El horario de clases que llama por teléfono** (25-oct-2026, `apps/pixpin/src/horario.rs` del
 * PC, portado tal cual). Lo pidió el usuario en el PC: «en recordatorios poder ingresar de forma
 * rápida e intuitiva un horario … que aparezcan en pantalla … y un mensaje de voz si lo activo,
 * predefinido según lo que se tenía que hacer para esa clase, que grabaría durante la clase pasada;
 * y aparecería como una llamada telefónica, de forma que sí o sí me haga recordar cosas importantes
 * como evaluaciones con anticipación».
 *
 * ## Una nota de texto, una línea por clase
 *
 * El horario es `horario.md` (en `filesDir`, ver [AvisosDelHorario]) y **es el mismo formato que el
 * del PC**, así que el día que viaje no hará falta traducir nada:
 *
 * ```text
 * Lun Mie 8:00-10:00 Geometría @A-12
 * > traer el compás                      ← lo que lee la voz al contestar
 * Mar Jue 10:15-12:00 Física
 * Evaluación Geometría 23/10             ← avisa 3 días y 1 día antes
 * avisar 15 min antes                    ← la antelación (10 si no se dice)
 * Asignatura Geometría @A-12 #4F8EF7     ← la lista de asignaturas (con su color)
 * ```
 *
 * También vale **pegar una tabla** (de Excel, de la web o de Markdown): la de filas («Lunes |
 * 8:00-10:00 | Geometría | A-12») y la cuadrícula de los colegios (la primera fila son los días y
 * la primera columna las horas).
 *
 * **Por qué local y no en la carpeta que se sincroniza**: en el PC el horario es «solo de este
 * equipo y no viaja» (`horario::ruta`, junto a los datos pero fuera de lo que se sincroniza). Aquí
 * igual; se guarda con el mismo nombre y formato para que pueda viajar el día que el PC lo haga.
 *
 * Puro: lo prueban `HorarioTest` y `SemanaTest`. Lo de Android (alarmas, llamada) está en
 * [AvisosDelHorario]; la rejilla de la semana en [HorarioActivity].
 */
object Horario {

    /** Lo que va delante del id de todo aviso del horario en las alarmas. */
    const val PREFIJO = "horario:"

    /** El fichero del horario. */
    const val FICHERO = "horario.md"

    /** Los minutos de antelación si el horario no dice otra cosa. */
    const val ANTELACION = 10

    /** Los días antes de una evaluación en que se avisa. */
    val DIAS_ANTES = listOf(3, 1)

    /** Cuántos días por delante se ponen las alarmas. */
    const val DIAS_A_LA_VISTA = 8L

    const val MINUTO = 60_000L
    const val DIA = 86_400_000L

    /** Una clase de la semana. */
    data class Clase(
        /** Los días de la semana (0 lunes … 6 domingo), sin repetir y en orden. */
        val dias: List<Int>,
        /** Minuto del día en que empieza y en que acaba. */
        val inicio: Int,
        val fin: Int,
        val nombre: String,
        val aula: String?,
        /** Lo apuntado con `>` debajo: lo que lee la voz al contestar. */
        val notas: List<String> = emptyList(),
        /** Los minutos de antes en que avisa esta clase (`avisar 15`); null es la de todo el horario. */
        val antelacion: Int? = null
    )

    /** Una asignatura de la lista (`Asignatura Geometría @A-12 #4F8EF7`). */
    data class Asignatura(
        val nombre: String,
        val aula: String?,
        /** Su color, `0xRRGGBB`; null es el de su nombre. */
        val color: Int? = null
    )

    /** Una evaluación (examen, entrega…) con su fecha. */
    data class Evaluacion(
        /** La línea tal cual, sin la fecha: «Evaluación Geometría». */
        val que: String,
        /** La clase que nombra, si nombra alguna (su sitio en `clases`). */
        val clase: Int?,
        val cuando: Momento
    )

    /** El horario entero, ya leído. */
    data class Leido(
        val clases: List<Clase> = emptyList(),
        val evaluaciones: List<Evaluacion> = emptyList(),
        /** Minutos antes de cada clase. */
        val antelacion: Int = ANTELACION,
        /** Días antes de cada evaluación. */
        val diasAntes: List<Int> = DIAS_ANTES,
        /** La lista de asignaturas, en el orden escrito. */
        val asignaturas: List<Asignatura> = emptyList()
    ) {
        /**
         * Las asignaturas de la lista y, detrás, las de las clases que no están en ella (escritas a
         * mano o pegadas en una tabla), sin repetir (sin mirar mayúsculas ni tildes).
         */
        fun todasLasAsignaturas(): List<Asignatura> {
            val v = asignaturas.toMutableList()
            for (c in clases) {
                val n = norm(c.nombre)
                if (v.none { norm(it.nombre) == n }) v += Asignatura(c.nombre, c.aula, null)
            }
            return v
        }
    }

    internal fun norm(s: String) = Acciones.normalizar(s)

    /** Las palabras como `split_whitespace` de Rust. */
    internal fun palabras(s: String): List<String> = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Las líneas como `str::lines` de Rust (sin la vacía final ni el `\r`). */
    internal fun lineas(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val v = s.split('\n').toMutableList()
        if (v.last().isEmpty()) v.removeAt(v.size - 1)
        return v.map { it.removeSuffix("\r") }
    }

    /** Un número sin signo, como `parse::<u32>()` (admite `+`, no `-`). */
    private fun natural(s: String): Int? {
        if (s.isEmpty() || s.startsWith("-")) return null
        val n = s.toLongOrNull() ?: return null
        return if (n in 0..UInt.MAX_VALUE.toLong()) n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
    }

    /** `split_once` con varios separadores: lo de antes y después del primero que salga. */
    private fun partir(s: String, vararg seps: Char): Pair<String, String>? {
        val i = s.indexOfAny(seps)
        return if (i < 0) null else s.substring(0, i) to s.substring(i + 1)
    }

    // ------------------------------------------------------------- leer

    /**
     * El día de la semana de una palabra (`lunes`, `lun`, `lu`, `L`…). [corto]: si se aceptan
     * también las de una y dos letras, que solo valen al principio de la línea (`mi`, `do` o `sa`
     * son palabras de un nombre).
     */
    private fun diaDe(p: String, corto: Boolean): Int? {
        val q = norm(p.trim { !it.isLetterOrDigit() })
        val largo = when (q) {
            "lunes", "lun" -> 0
            "martes", "mar" -> 1
            "miercoles", "mie", "mier" -> 2
            "jueves", "jue" -> 3
            "viernes", "vie" -> 4
            "sabado", "sab" -> 5
            "domingo", "dom" -> 6
            else -> null
        }
        if (largo != null || !corto) return largo
        return when (q) {
            "lu", "l" -> 0
            "ma", "m" -> 1
            "mi", "x" -> 2
            "ju", "j" -> 3
            "vi", "v" -> 4
            "sa", "s" -> 5
            "do", "d" -> 6
            else -> null
        }
    }

    /** Los días de una palabra: uno, una lista (`Lun,Mie`, `L/X`) o un tramo (`Lun-Vie`, `L-V`). */
    private fun diasDe(p: String, corto: Boolean): List<Int>? {
        diaDe(p, corto)?.let { return listOf(it) }
        partir(p, '-', '–')?.let { (x, y) ->
            val a = diaDe(x, corto) ?: return null
            val b = diaDe(y, corto) ?: return null
            return if (a <= b) (a..b).toList() else listOf(a, b)
        }
        val partes = p.split(',', '/', ';').filter { it.isNotEmpty() }
        if (partes.size > 1) {
            val v = partes.map { diaDe(it, corto) ?: return null }
            return v
        }
        return null
    }

    /** Una hora: `8`, `08`, `8:30`, `8.30`, `8h`, `8h30`, `8:30pm`. En minutos. */
    internal fun horaDe(p: String): Int? {
        var q = norm(p.trim())
        var tarde: Boolean? = null
        if (q.endsWith("pm")) { q = q.removeSuffix("pm").trim(); tarde = true }
        else if (q.endsWith("am")) { q = q.removeSuffix("am").trim(); tarde = false }
        val corte = partir(q, ':', '.', 'h')
        val (hs, ms) = if (corte != null) corte.first to corte.second.ifEmpty { "0" } else q to "0"
        if (hs.isEmpty() || hs.toByteArray().size > 2 || ms.toByteArray().size > 2) return null
        var h = natural(hs) ?: return null
        val m = natural(ms) ?: return null
        if (h > 23 || m > 59) return null
        if (tarde == true && h < 12) h += 12
        return h * 60 + m
    }

    /** Un tramo de horas en una palabra (`8:00-10:00`, `8-10`). */
    private fun tramoDe(p: String): Pair<Int, Int>? {
        val (a, b) = partir(p, '-', '–', '—') ?: return null
        return (horaDe(a) ?: return null) to (horaDe(b) ?: return null)
    }

    /** Deja el tramo en horas del día: una clase «de 2 a 4» es por la tarde. */
    private fun arreglarTramo(t: Pair<Int, Int>): Pair<Int, Int>? {
        var (a, b) = t
        if (a < 7 * 60 && b <= 12 * 60) { a += 12 * 60; b += 12 * 60 }
        // «10-2» acaba a las 14:00; «10-8» no es nada.
        if (b <= a && b < 7 * 60) b += 12 * 60
        return if (b > a && b <= 24 * 60) a to b else null
    }

    /** Separa el aula del nombre: `@A-12`, `aula 12`, `salón 3`, `(Lab 2)`, `@(Lab de física)`. */
    internal fun nombreYAula(palabras: List<String>): Pair<String, String?> {
        val nombre = mutableListOf<String>()
        var aula: String? = null
        var i = 0
        while (i < palabras.size) {
            val p = palabras[i]
            if (p.startsWith("@(")) {
                // `@(Lab de física)`: un aula de varias palabras (así la escribe la rejilla).
                val v = mutableListOf(p.removePrefix("@("))
                while (!v.last().endsWith(")") && i + 1 < palabras.size) { i++; v += palabras[i] }
                val a = v.joinToString(" ").let { it.removeSuffix(")") }.trim()
                if (a.isNotEmpty()) aula = a
            } else if (p.startsWith("@") && p.length > 1) {
                aula = p.substring(1)
            } else if (norm(p.trimStart('(')) in setOf("aula", "salon", "sala", "lab", "laboratorio") && i + 1 < palabras.size) {
                aula = "${p.trimStart('(')} ${palabras[i + 1].trimEnd(')')}"
                i++
            } else {
                nombre += p
            }
            i++
        }
        val n = nombre.joinToString(" ").trim { it == '-' || it == '·' || it == ':' || it == '|' || it.isWhitespace() }
        return n to aula
    }

    /** **Una línea de clase**: días al principio, el tramo de horas y el nombre (con el aula). */
    internal fun claseDeLinea(linea: String): Clase? {
        val palabras = palabras(linea)
        val dias = mutableListOf<Int>()
        var i = 0
        // Los días, al principio («Lun Mie», «Lunes y Miércoles», «L-V»).
        while (i < palabras.size) {
            val d = diasDe(palabras[i], true)
            if (d != null) dias += d
            else if (!(norm(palabras[i]) == "y" && dias.isNotEmpty())) break
            i++
        }
        // (Con los días delante, un día de más es del nombre: «Taller del Sábado».)
        val diasDelante = dias.isNotEmpty()
        // El tramo: en una palabra, o en tres («8:00 a 10:00», «8 - 10»).
        var tramo: Pair<Int, Int>? = null
        val resto = mutableListOf<String>()
        var j = i
        while (j < palabras.size) {
            val p = palabras[j]
            if (tramo == null) {
                val t = tramoDe(p)
                if (t != null) { tramo = t; j++; continue }
                val a = horaDe(p)
                val sep = palabras.getOrNull(j + 1)
                val bb = palabras.getOrNull(j + 2)
                if (a != null && sep != null && bb != null && norm(sep) in setOf("a", "-", "–", "hasta")) {
                    val b = horaDe(bb)
                    if (b != null) { tramo = a to b; j += 3; continue }
                }
                // «de 8 a 10»: el «de» no es del nombre.
                if (norm(p) == "de" && palabras.getOrNull(j + 1)?.let { horaDe(it) } != null) { j++; continue }
            }
            // Días detrás del nombre («Geometría lunes 8-10»): solo los largos, y no si ya iban
            // delante ni detrás de «de»/«del».
            val trasDe = j > 0 && norm(palabras[j - 1]) in setOf("de", "del")
            val d = if (!diasDelante && !trasDe) diasDe(p, false) else null
            if (d != null) dias += d else resto += p
            j++
        }
        val (inicio, fin) = arreglarTramo(tramo ?: return null) ?: return null
        val antelacion = quitarAvisar(resto)
        val (nombre, aula) = nombreYAula(resto)
        val ordenados = dias.distinct().sorted()
        if (ordenados.isEmpty() || nombre.isEmpty()) return null
        return Clase(ordenados, inicio, fin, nombre, aula, emptyList(), antelacion)
    }

    /** Saca de las palabras de una clase su «avisar 15 [min] [antes]»: los minutos de antes. */
    private fun quitarAvisar(resto: MutableList<String>): Int? {
        val i = resto.indexOfFirst { norm(it) == "avisar" || norm(it) == "aviso" }.takeIf { it >= 0 } ?: return null
        val n = resto.getOrNull(i + 1)?.let { natural(it) } ?: return null
        var j = i + 2
        if (resto.getOrNull(j)?.let { norm(it) in setOf("min", "minutos") } == true) j++
        if (resto.getOrNull(j)?.let { norm(it) == "antes" } == true) j++
        repeat(j - i) { resto.removeAt(i) }
        return minOf(n, 24 * 60)
    }

    /** **Una línea de la lista de asignaturas**: `Asignatura Geometría @A-12 #4F8EF7`. */
    private fun asignaturaDeLinea(palabras: List<String>): Asignatura? {
        val primera = norm((palabras.firstOrNull() ?: return null).trimEnd(':'))
        if (primera != "asignatura" && primera != "materia") return null
        var color: Int? = null
        val resto = palabras.drop(1).filter { p -> val c = colorDe(p); if (c != null) { color = c; false } else true }
        val (nombre, aula) = nombreYAula(resto)
        return if (nombre.isNotEmpty()) Asignatura(nombre, aula, color) else null
    }

    /** Un color `#RRGGBB`. */
    private fun colorDe(p: String): Int? {
        if (!p.startsWith("#")) return null
        val h = p.substring(1)
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return h.toInt(16)
    }

    private val EVALUACIONES = setOf(
        "evaluacion", "examen", "parcial", "final", "prueba", "entrega", "exposicion", "control", "test", "quiz", "practica", "eval"
    )

    /** Lo que dice que una línea es una evaluación. */
    private fun esEvaluacion(primera: String) = norm(primera.trim { !it.isLetterOrDigit() }) in EVALUACIONES

    /** Las celdas de una fila de tabla (`| a | b |` o separada por tabuladores). */
    private fun celdas(linea: String): List<String>? {
        val t = linea.trim()
        return when {
            t.startsWith("|") -> t.trim('|').split('|').map { it.trim() }
            t.contains('\t') -> t.split('\t').map { it.trim() }
            else -> null
        }
    }

    /** Si una fila es la raya de debajo de la cabecera (`|---|:--:|`). */
    private fun esRaya(v: List<String>) = v.all { c -> c.all { it == '-' || it == ':' || it == ' ' } }

    /**
     * **Una tabla pegada.** Si la cabecera tiene dos o más días, es la cuadrícula de los colegios
     * (las columnas son los días y la primera columna las horas); si no, cada fila es una clase como
     * una línea.
     */
    private fun clasesDeTabla(todas: List<List<String>>): List<Clase> {
        val filas = todas.filter { !esRaya(it) }
        val cabecera = filas.firstOrNull() ?: return emptyList()
        val columnas = cabecera.mapIndexedNotNull { i, c -> diaDe(c, false)?.let { i to it } }
        val clases = mutableListOf<Clase>()
        if (columnas.size >= 2) {
            for (fila in filas.drop(1)) {
                val tramo = fila.firstOrNull()?.let { tramoDe(it.replace(" ", "")) }?.let { arreglarTramo(it) } ?: continue
                for ((i, d) in columnas) {
                    val celda = fila.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
                    val (nombre, aula) = nombreYAula(palabras(celda))
                    if (nombre.isEmpty()) continue
                    val k = clases.indexOfFirst { it.nombre == nombre && it.inicio == tramo.first && it.fin == tramo.second }
                    if (k >= 0) {
                        if (d !in clases[k].dias) clases[k] = clases[k].copy(dias = (clases[k].dias + d).sorted())
                    } else {
                        clases += Clase(listOf(d), tramo.first, tramo.second, nombre, aula)
                    }
                }
            }
            return clases
        }
        for (f in filas) {
            // Lo corto con cifras detrás del nombre es el aula («A-12»).
            val fila = f.filter { it.isNotEmpty() }.toMutableList()
            val aula = if (fila.size >= 4) fila.last().takeIf { c ->
                c.toByteArray().size <= 10 && c.any { it in '0'..'9' } && tramoDe(c) == null
            } else null
            if (aula != null) fila.removeAt(fila.size - 1)
            val c = claseDeLinea(fila.joinToString(" ")) ?: continue
            clases += if (aula != null) c.copy(aula = aula) else c
        }
        return clases
    }

    /**
     * **Lee el horario** escrito. [ahora]: el momento local (para las fechas de las evaluaciones,
     * que se dicen como en las tareas: «23/10», «el jueves»). Lo que no se entiende se salta: es una
     * nota, puede llevar títulos y comentarios.
     */
    fun leer(texto: String, ahora: Momento): Leido = leerConSitios(texto, ahora).first

    /** Los dos títulos que pone la rejilla encima de lo que escribe (se quitan y se vuelven a poner). */
    const val TITULO_ASIGNATURAS = "## Asignaturas"
    const val TITULO_CLASES = "## Clases"

    /**
     * Como [leer], y además qué líneas del texto son de clases o de asignaturas (las que reescribe
     * la rejilla, [Semana.reescribir]): las líneas de clase, sus `>`, las tablas que dieron clases,
     * las de `Asignatura …` y los dos títulos de la rejilla. Lo demás no se toca.
     */
    fun leerConSitios(texto: String, ahora: Momento): Pair<Leido, BooleanArray> {
        val lineas = lineas(texto)
        val suyas = BooleanArray(lineas.size)
        val clases = mutableListOf<Clase>()
        val asignaturas = mutableListOf<Asignatura>()
        var antelacion = ANTELACION
        var diasAntes = DIAS_ANTES
        val tabla = mutableListOf<List<String>>()
        val filasTabla = mutableListOf<Int>()
        var enComentario = false
        val sueltas = mutableListOf<String>()
        fun cerrarTabla() {
            if (tabla.isEmpty()) return
            val de = clasesDeTabla(tabla)
            if (de.isNotEmpty()) filasTabla.forEach { suyas[it] = true }
            clases += de
            tabla.clear(); filasTabla.clear()
        }
        for ((n, linea) in lineas.withIndex()) {
            val t0 = linea.trim()
            // Los comentarios de HTML (`<!-- … -->`): ahí va el ejemplo.
            if (enComentario) { if (t0.contains("-->")) enComentario = false; continue }
            if (t0.startsWith("<!--")) { enComentario = !t0.contains("-->"); continue }
            val c = celdas(linea)
            if (c != null) { tabla += c; filasTabla += n; continue }
            cerrarTabla()
            if (t0 == TITULO_ASIGNATURAS || t0 == TITULO_CLASES) { suyas[n] = true; continue }
            if (t0.isEmpty() || t0.startsWith("#")) continue
            // Lo que lee la voz, para la clase de encima.
            if (t0.startsWith(">")) {
                val nota = t0.substring(1).trim()
                if (clases.isNotEmpty()) {
                    if (nota.isNotEmpty()) clases[clases.size - 1] = clases.last().let { it.copy(notas = it.notas + nota) }
                    suyas[n] = true
                }
                continue
            }
            val t = t0.trimStart('-', '*', '•').trim()
            val ps = palabras(t)
            val primera = norm(ps.firstOrNull() ?: "")
            // «avisar 15 min antes», «antelación 20», «avisar evaluaciones 5 y 2 días antes».
            if (primera in setOf("avisar", "aviso", "avisame", "antelacion", "anticipacion")) {
                val numeros = ps.mapNotNull { p -> natural(p.trim { !(it in '0'..'9') }) }
                val q = norm(t)
                if (q.contains("evaluacion") || q.contains("examen") || q.contains("dia")) {
                    if (numeros.isNotEmpty()) diasAntes = numeros
                } else if (numeros.isNotEmpty()) {
                    val x = numeros.first()
                    antelacion = minOf(if (q.contains("hora")) x * 60 else x, 24 * 60)
                }
                continue
            }
            val a = asignaturaDeLinea(ps)
            if (a != null) {
                suyas[n] = true
                if (asignaturas.none { norm(it.nombre) == norm(a.nombre) }) asignaturas += a
                continue
            }
            if (ps.firstOrNull()?.let { esEvaluacion(it) } == true) { sueltas += t; continue }
            val clase = claseDeLinea(t)
            if (clase != null) { suyas[n] = true; clases += clase }
        }
        cerrarTabla()
        // Las evaluaciones al final: nombran clases que pueden ir después.
        val evaluaciones = mutableListOf<Evaluacion>()
        for (t in sueltas) {
            val (que, cuando) = try { Fechas.separar(t, ahora) } catch (e: ErrorFecha) { continue }
            if (cuando == null) continue
            val q = norm(que)
            val clase = clases.indexOfFirst { q.contains(norm(it.nombre)) }.takeIf { it >= 0 }
            evaluaciones += Evaluacion(que, clase, cuando)
        }
        return Leido(clases, evaluaciones, antelacion, diasAntes, asignaturas) to suyas
    }

    // ------------------------------------------------------------- avisos

    /** Qué avisa un aviso. */
    sealed class Que {
        /** La clase [i], la sesión que empieza en [inicio] (ms locales). */
        data class DeClase(val i: Int, val inicio: Long) : Que()
        /** La evaluación [j], [dias] días antes. */
        data class DeEvaluacion(val j: Int, val dias: Int) : Que()
    }

    /** Un aviso con su hora (ms locales) y su id en las alarmas. */
    data class Aviso(val id: String, val cuando: Long, val que: Que)

    fun msDe(m: Momento): Long = (m.dia * 1440 + m.minuto) * MINUTO

    private fun diaDeLaSemana(d: Long) = Fechas.diaDeLaSemana(d).toInt()

    /**
     * **Cuándo toca cada aviso** desde [desde] (ms locales, sin incluirlo) durante [dias] días: cada
     * sesión de cada clase su antelación antes de empezar, y cada evaluación los `diasAntes` días
     * antes a su hora. Del más próximo al más lejano.
     */
    fun avisos(h: Leido, desde: Long, dias: Long): List<Aviso> {
        val hasta = desde + dias * DIA
        val hoy = Math.floorDiv(desde, DIA)
        val v = mutableListOf<Aviso>()
        for ((i, c) in h.clases.withIndex()) {
            // Desde ayer: una clase de las 00:05 avisa la víspera.
            for (d in hoy - 1..hoy + dias) {
                if (diaDeLaSemana(d) !in c.dias) continue
                val inicio = d * DIA + c.inicio * MINUTO
                val cuando = inicio - (c.antelacion ?: h.antelacion) * MINUTO
                if (cuando > desde && cuando <= hasta) v += Aviso("${PREFIJO}clase:$inicio:${c.nombre}", cuando, Que.DeClase(i, inicio))
            }
        }
        for ((j, e) in h.evaluaciones.withIndex()) {
            val aLas = msDe(e.cuando)
            for (n in h.diasAntes) {
                val cuando = aLas - n * DIA
                if (cuando > desde && cuando <= hasta) v += Aviso("${PREFIJO}eval:$n:$aLas:${e.que}", cuando, Que.DeEvaluacion(j, n))
            }
        }
        return v.sortedWith(compareBy<Aviso> { it.cuando }.thenBy { it.id })
    }

    /** El aviso de un id de las alarmas (el de un pospuesto lleva `|<ms>` detrás). */
    fun avisoDe(h: Leido, id: String, desde: Long): Aviso? {
        val base = id.substringBefore('|')
        // Se busca desde una semana antes: el aviso ya venció (y uno pospuesto puede llevar un rato).
        return avisos(h, desde - 7 * DIA, 15).firstOrNull { it.id == base }
    }

    /**
     * Cuándo empezó la sesión anterior de una clase a la que empieza en [inicio] (ms locales): la
     * nota de voz grabada antes de esa ya no es de esta clase.
     */
    fun sesionAnterior(c: Clase, inicio: Long): Long? {
        val dia = Math.floorDiv(inicio, DIA)
        return (1..7).map { dia - it }.firstOrNull { diaDeLaSemana(it) in c.dias }?.let { it * DIA + c.inicio * MINUTO }
    }

    /** Si una nota de voz guardada a [guardada] (ms locales) es para la sesión que empieza en [inicio]. */
    fun notaVigente(c: Clase, inicio: Long, guardada: Long): Boolean =
        sesionAnterior(c, inicio)?.let { guardada >= it } ?: true

    /**
     * **La clase a la que va una nota de voz grabada ahora**: la que está en marcha, o la última que
     * acabó hoy (hace menos de 3 horas); si no, la próxima.
     */
    fun claseParaGrabar(h: Leido, ahora: Long): Int? {
        val hoy = Math.floorDiv(ahora, DIA)
        val minuto = (Math.floorMod(ahora, DIA) / MINUTO).toInt()
        val semana = diaDeLaSemana(hoy)
        h.clases.indexOfFirst { semana in it.dias && it.inicio <= minuto && minuto < it.fin }.takeIf { it >= 0 }?.let { return it }
        val reciente = h.clases.withIndex()
            .filter { (_, c) -> semana in c.dias && c.fin <= minuto && minuto - c.fin <= 180 }
            .maxByOrNull { it.value.fin }?.index
        if (reciente != null) return reciente
        val sin = h.copy(antelacion = 0, clases = h.clases.map { it.copy(antelacion = null) })
        return avisos(sin, ahora, 7).firstNotNullOfOrNull { (it.que as? Que.DeClase)?.i }
    }

    /** «08:00–10:00». */
    fun tramoLegible(c: Clase) = "%02d:%02d–%02d:%02d".format(c.inicio / 60, c.inicio % 60, c.fin / 60, c.fin % 60)

    /**
     * Lo que se dice de una clase: el detalle de la llamada y lo que lee la voz al contestar. Las
     * evaluaciones de esa clase en la próxima semana van en los dos: que «sí o sí» se recuerden.
     */
    fun detalleYTexto(h: Leido, i: Int, inicio: Long): Pair<String, String?> {
        val c = h.clases[i]
        var detalle = tramoLegible(c)
        c.aula?.let { detalle += " · $it" }
        val ahora = Momento.deMsLocales(inicio)
        val leer = c.notas.toMutableList()
        for (e in h.evaluaciones.filter { it.clase == i }) {
            val falta = msDe(e.cuando) - inicio
            if (falta in -DIA..7 * DIA) {
                val cuando = e.cuando.legible(ahora)
                detalle += "\n📝 ${e.que} · $cuando"
                leer += "${e.que}, $cuando"
            }
        }
        return detalle to (if (leer.isNotEmpty()) leer.joinToString(". ") else null)
    }

    /** El nombre de fichero de la nota de voz de una clase. */
    fun ficheroDeVoz(nombre: String): String {
        var s = norm(nombre).map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '-' }.joinToString("")
        while (s.contains("--")) s = s.replace("--", "-")
        return s.trim('-') + ".m4a"
    }

    /**
     * Una llamada del horario (`llamada::Llamada` del PC): la nota de voz si hay, quién «llama», el
     * id de su aviso (para volver a llamar), el detalle bajo el nombre y lo que lee la voz.
     */
    data class Llamada(val audio: File?, val nombre: String, val mensaje: String, val detalle: String, val texto: String?) {
        /** La de una clase. */
        fun esDeHorario() = mensaje.startsWith(PREFIJO)
    }

    /**
     * **La llamada de un aviso.** [voz] es la carpeta de las notas de voz de las clases y [guardada]
     * dice cuándo se grabó (ms locales) cada una: la nota solo suena si es para esta sesión.
     */
    fun llamadaDe(h: Leido, a: Aviso, voz: File, guardada: (File) -> Long?): Llamada = when (val q = a.que) {
        is Que.DeClase -> {
            val c = h.clases[q.i]
            val (detalle, texto) = detalleYTexto(h, q.i, q.inicio)
            val ruta = File(voz, ficheroDeVoz(c.nombre))
            val audio = guardada(ruta)?.takeIf { notaVigente(c, q.inicio, it) }?.let { ruta }
            Llamada(audio, c.nombre, a.id, detalle, texto)
        }
        is Que.DeEvaluacion -> {
            val e = h.evaluaciones[q.j]
            val cuando = e.cuando.legible(Momento.deMsLocales(a.cuando))
            val en = if (q.dias == 1) "mañana" else "en ${q.dias} días"
            Llamada(null, "📝 ${e.que}", a.id, "$en · $cuando", "Recuerda: ${e.que}, $en, $cuando")
        }
    }

    /** Con lo que nace el horario: el ejemplo va comentado, para que no llame. */
    const val PLANTILLA = """# Horario de clases

Una línea por clase: los días, la hora y la asignatura (el aula con @).
Debajo de una clase, lo que empieza por > lo lee la voz al contestar.
También puedes pegar una tabla (de Excel o de la web).

<!-- Ejemplo (quita estas dos líneas de comentario para usarlo):
Lun Mie 8:00-10:00 Geometría @A-12
> traer el compás
Mar Jue 10:15-12:00 Física
Evaluación Geometría 23/10
-->

avisar 10 min antes
"""
}
