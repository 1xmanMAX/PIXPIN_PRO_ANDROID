package com.forge.pixpin.mini

import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.ErrorFecha
import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import java.io.File

/**
 * **El horario de clases que llama por teléfono** (PC `bfdd89a`, `apps/pixpin/src/horario.rs`,
 * 17-oct-2026), portado tal cual con sus pruebas en `HorarioTest`. Sin Android.
 *
 * El usuario: «en recordatorios poder ingresar de forma rápida e intuitiva un horario […] el nombre
 * de la asignatura y su horario, y un mensaje de voz si lo activo, que grabaría durante la clase
 * pasada; y aparecería como una llamada telefónica».
 *
 * ## Meterlo: una nota de texto, una línea por clase
 *
 * ```
 * Lun Mie 8:00-10:00 Geometría @A-12
 * > traer el compás                      ← lo que lee la voz al contestar
 * Mar Jue 10:15-12:00 Física
 * Evaluación Geometría 23/10             ← avisa 3 días y 1 día antes
 * avisar 15 min antes                    ← la antelación (10 si no se dice)
 * ```
 *
 * También vale **pegar una tabla** (de Excel, de la web o de Markdown): la de filas («Lunes |
 * 8:00-10:00 | Geometría | A-12») y la cuadrícula de los colegios (la primera fila son los días y la
 * primera columna las horas).
 *
 * ## El aviso: la llamada secreta
 *
 * Antes de cada clase suena la llamada secreta (`pin/LlamadaSecretaActivity`) con la asignatura
 * como quien llama, la hora y el aula debajo. **Contestar** pone la nota de voz de la clase
 * (grabada para «la próxima clase») y después la voz del teléfono lee lo apuntado con `>`.
 *
 * Como en el PC, el horario es **solo de este aparato y no viaja**: `files/horario.md` (el mismo
 * formato que el `horario.md` del PC, así que se puede copiar de uno a otro) y las notas de voz en
 * `files/horario/voz/`. Quien pone las alarmas es `pin/RecordatoriosDelHorario`.
 */
object Horario {

    /** Lo que va delante del id de todo aviso del horario. */
    const val PREFIJO = "horario:"

    /** El fichero del horario. */
    const val FICHERO = "horario.md"

    /** Los minutos de antelación si el horario no dice otra cosa. */
    const val ANTELACION = 10

    /** Los días antes de una evaluación en que se avisa. */
    val DIAS_ANTES = listOf(3, 1)

    /** Cuántos días por delante se ponen las alarmas. */
    const val DIAS_A_LA_VISTA = 8L

    private const val MINUTO = 60_000L
    private const val DIA = 86_400_000L

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
        /** Los minutos de antes en que avisa esta clase (`avisar 15` en su línea); null: la del horario. */
        val antelacion: Int? = null
    )

    /** Una asignatura de la lista (`Asignatura Geometría @A-12 #4F8EF7`, PC 18-oct). [color]: `0xRRGGBB`. */
    data class Asignatura(val nombre: String, val aula: String?, val color: Int?)

    /** Los dos títulos que pone la pantalla semanal encima de lo que escribe. */
    const val TITULO_ASIGNATURAS = "## Asignaturas"
    const val TITULO_CLASES = "## Clases"

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
        /** Las de la lista y, detrás, las de las clases que no están en ella, sin repetir. */
        fun todasLasAsignaturas(): List<Asignatura> {
            val v = asignaturas.toMutableList()
            for (c in clases) if (v.none { Acciones.normalizar(it.nombre) == Acciones.normalizar(c.nombre) }) v += Asignatura(c.nombre, c.aula, null)
            return v
        }
    }

    // ---- Leer ------------------------------------------------------------

    private fun n(s: String) = Acciones.normalizar(s)

    /**
     * El día de la semana de una palabra (`lunes`, `lun`, `lu`, `L`…). [corto]: si se aceptan también
     * las de una y dos letras, que solo valen al principio de la línea (`mi`, `do` o `sa` son palabras
     * de un nombre).
     */
    private fun diaDe(p: String, corto: Boolean): Int? {
        val q = n(p.trim { !it.isLetterOrDigit() })
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

    /** `split_once` con varios separadores: corta por el primero que salga. */
    private fun partirEnDos(p: String, seps: CharArray): Pair<String, String>? {
        val i = p.indexOfAny(seps)
        return if (i < 0) null else p.substring(0, i) to p.substring(i + 1)
    }

    /** Los días de una palabra: uno, una lista (`Lun,Mie`, `L/X`) o un tramo (`Lun-Vie`, `L-V`). */
    private fun diasDe(p: String, corto: Boolean): List<Int>? {
        diaDe(p, corto)?.let { return listOf(it) }
        partirEnDos(p, charArrayOf('-', '–'))?.let { (a0, b0) ->
            val a = diaDe(a0, corto) ?: return null
            val b = diaDe(b0, corto) ?: return null
            return if (a <= b) (a..b).toList() else listOf(a, b)
        }
        val partes = p.split(',', '/', ';').filter { it.isNotEmpty() }
        if (partes.size > 1) {
            val v = partes.map { diaDe(it, corto) ?: return null }
            return v
        }
        return null
    }

    private fun soloCifras(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    /** Una hora: `8`, `08`, `8:30`, `8.30`, `8h`, `8h30`, `8:30pm`. En minutos. */
    private fun horaDe(p: String): Int? {
        var q = n(p.trim())
        var tarde: Boolean? = null
        if (q.endsWith("pm")) { q = q.dropLast(2).trim(); tarde = true }
        else if (q.endsWith("am")) { q = q.dropLast(2).trim(); tarde = false }
        val (h0, m0) = partirEnDos(q, charArrayOf(':', '.', 'h'))?.let { (h, m) -> h to m.ifEmpty { "0" } } ?: (q to "0")
        if (h0.isEmpty() || h0.length > 2 || m0.length > 2) return null
        if (!soloCifras(h0) || !soloCifras(m0)) return null
        var h = h0.toInt()
        val m = m0.toInt()
        if (h > 23 || m > 59) return null
        if (tarde == true && h < 12) h += 12
        return h * 60 + m
    }

    /** Un tramo de horas en una palabra (`8:00-10:00`, `8-10`). */
    private fun tramoDe(p: String): Pair<Int, Int>? {
        val (a, b) = partirEnDos(p, charArrayOf('-', '–', '—')) ?: return null
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

    /** Separa el aula del nombre: `@A-12`, `aula 12`, `salón 3`, `(Lab 2)`. */
    fun nombreYAula(palabras: List<String>): Pair<String, String?> {
        val nombre = ArrayList<String>()
        var aula: String? = null
        var i = 0
        while (i < palabras.size) {
            val p = palabras[i]
            val sig = palabras.getOrNull(i + 1)
            if (p.startsWith("@(")) {
                // `@(Lab de física)`: un aula de varias palabras (así la escribe la pantalla semanal).
                val v = arrayListOf(p.substring(2))
                while (!v.last().endsWith(')') && i + 1 < palabras.size) { i++; v += palabras[i] }
                v.joinToString(" ").removeSuffix(")").trim().takeIf { it.isNotEmpty() }?.let { aula = it }
            } else if (p.startsWith('@') && p.length > 1) {
                aula = p.substring(1)
            } else if (n(p.trimStart('(')) in setOf("aula", "salon", "sala", "lab", "laboratorio") && sig != null) {
                aula = "${p.trimStart('(')} ${sig.trimEnd(')')}"
                i++
            } else nombre += p
            i++
        }
        val limpio = nombre.joinToString(" ").trim { it == '-' || it == '·' || it == ':' || it == '|' || it.isWhitespace() }
        return limpio to aula
    }

    private fun palabrasDe(s: String) = s.split(BLANCOS).filter { it.isNotEmpty() }

    /** **Una línea de clase**: días al principio, el tramo de horas y el nombre (con el aula). */
    private fun claseDeLinea(linea: String): Clase? {
        val palabras = palabrasDe(linea)
        val dias = ArrayList<Int>()
        var i = 0
        // Los días, al principio («Lun Mie», «Lunes y Miércoles», «L-V»).
        while (i < palabras.size) {
            val d = diasDe(palabras[i], true)
            if (d != null) dias += d
            else if (!(n(palabras[i]) == "y" && dias.isNotEmpty())) break
            i++
        }
        val diasDelante = dias.isNotEmpty()
        // El tramo: en una palabra, o en tres («8:00 a 10:00», «8 - 10»).
        var tramo: Pair<Int, Int>? = null
        val resto = ArrayList<String>()
        var j = i
        while (j < palabras.size) {
            val p = palabras[j]
            if (tramo == null) {
                val t = tramoDe(p)
                if (t != null) { tramo = t; j++; continue }
                val a = horaDe(p)
                val sep = palabras.getOrNull(j + 1)
                val b = palabras.getOrNull(j + 2)?.let { horaDe(it) }
                if (a != null && sep != null && b != null && n(sep) in setOf("a", "-", "–", "hasta")) {
                    tramo = a to b; j += 3; continue
                }
                // «de 8 a 10»: el «de» no es del nombre.
                if (n(p) == "de" && palabras.getOrNull(j + 1)?.let { horaDe(it) } != null) { j++; continue }
            }
            // Días detrás del nombre («Geometría lunes 8-10»): solo los largos, y no si ya iban delante
            // ni detrás de «de»/«del» («Taller del Sábado»).
            val trasDe = j > 0 && n(palabras[j - 1]) in setOf("de", "del")
            val d = if (!diasDelante && !trasDe) diasDe(p, false) else null
            if (d != null) dias += d else resto += p
            j++
        }
        val (inicio, fin) = arreglarTramo(tramo ?: return null) ?: return null
        val antelacion = quitarAvisar(resto)
        val (nombre, aula) = nombreYAula(resto)
        val unicos = dias.distinct().sorted()
        if (unicos.isEmpty() || nombre.isEmpty()) return null
        return Clase(unicos, inicio, fin, nombre, aula, antelacion = antelacion)
    }

    /** Saca de las palabras de una clase su «avisar 15 [min] [antes]»: los minutos de antes de esa clase. */
    private fun quitarAvisar(resto: MutableList<String>): Int? {
        val i = resto.indexOfFirst { n(it) in setOf("avisar", "aviso") }.takeIf { it >= 0 } ?: return null
        val m = resto.getOrNull(i + 1)?.toIntOrNull() ?: return null
        var j = i + 2
        if (resto.getOrNull(j)?.let { n(it) in setOf("min", "minutos") } == true) j++
        if (resto.getOrNull(j)?.let { n(it) == "antes" } == true) j++
        repeat(j - i) { resto.removeAt(i) }
        return m.coerceAtMost(24 * 60)
    }

    /** **Una línea de la lista de asignaturas**: `Asignatura Geometría @A-12 #4F8EF7`. */
    private fun asignaturaDeLinea(palabras: List<String>): Asignatura? {
        val primera = n(palabras.firstOrNull()?.trimEnd(':') ?: return null)
        if (primera != "asignatura" && primera != "materia") return null
        var color: Int? = null
        val resto = palabras.drop(1).filter { p ->
            val c = p.takeIf { it.startsWith('#') && it.length == 7 }?.substring(1)?.toIntOrNull(16)
            if (c != null) { color = c; false } else true
        }
        val (nombre, aula) = nombreYAula(resto)
        return if (nombre.isEmpty()) null else Asignatura(nombre, aula, color)
    }

    /** Lo que dice que una línea es una evaluación. */
    private fun esEvaluacion(primera: String) = n(primera.trim { !it.isLetterOrDigit() }) in setOf(
        "evaluacion", "examen", "parcial", "final", "prueba", "entrega", "exposicion", "control", "test", "quiz", "practica", "eval"
    )

    /** Las celdas de una fila de tabla (`| a | b |` o separada por tabuladores). */
    private fun celdas(linea: String): List<String>? {
        val t = linea.trim()
        return when {
            t.startsWith('|') -> t.trim('|').split('|').map { it.trim() }
            '\t' in t -> t.split('\t').map { it.trim() }
            else -> null
        }
    }

    /** Si una fila es la raya de debajo de la cabecera (`|---|:--:|`). */
    private fun esRaya(v: List<String>) = v.all { c -> c.all { it == '-' || it == ':' || it == ' ' } }

    /**
     * **Una tabla pegada.** Si la cabecera tiene dos o más días, es la cuadrícula de los colegios (las
     * columnas son los días y la primera columna las horas); si no, cada fila es una clase.
     */
    private fun clasesDeTabla(filas0: List<List<String>>): List<Clase> {
        val filas = filas0.filter { !esRaya(it) }
        val cabecera = filas.firstOrNull() ?: return emptyList()
        val columnas = cabecera.mapIndexedNotNull { i, c -> diaDe(c, false)?.let { i to it } }
        val clases = ArrayList<Clase>()
        if (columnas.size >= 2) {
            for (fila in filas.drop(1)) {
                val tramo = fila.firstOrNull()?.let { tramoDe(it.replace(" ", "")) }?.let { arreglarTramo(it) } ?: continue
                for ((i, d) in columnas) {
                    val celda = fila.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
                    val (nombre, aula) = nombreYAula(palabrasDe(celda))
                    if (nombre.isEmpty()) continue
                    val k = clases.indexOfFirst { it.nombre == nombre && it.inicio == tramo.first && it.fin == tramo.second }
                    if (k >= 0) {
                        val c = clases[k]
                        if (d !in c.dias) clases[k] = c.copy(dias = (c.dias + d).sorted())
                    } else clases += Clase(listOf(d), tramo.first, tramo.second, nombre, aula)
                }
            }
            return clases
        }
        for (f in filas) {
            // Lo corto con cifras detrás del nombre es el aula («A-12»).
            val fila = f.filter { it.isNotEmpty() }.toMutableList()
            val aula = fila.lastOrNull()?.takeIf {
                fila.size >= 4 && it.toByteArray(Charsets.UTF_8).size <= 10 && it.any { ch -> ch in '0'..'9' } && tramoDe(it) == null
            }
            if (aula != null) fila.removeAt(fila.size - 1)
            claseDeLinea(fila.joinToString(" "))?.let { c -> clases += if (aula != null) c.copy(aula = aula) else c }
        }
        return clases
    }

    /**
     * **Lee el horario** escrito. [ahora]: el momento local (para las fechas de las evaluaciones, que
     * se dicen como en las tareas: «23/10», «el jueves»). Lo que no se entiende se salta: es una nota,
     * puede llevar títulos y comentarios.
     */
    fun leer(texto: String, ahora: Momento): Leido = leerConSitios(texto, ahora).first

    /**
     * Como [leer], y además qué líneas son de clases o de asignaturas (las que reescribe la pantalla
     * semanal, [Semana.reescribir]): las de clase, sus `>`, las tablas que dieron clases, las de
     * `Asignatura …` y los dos títulos. Lo demás no se toca.
     */
    fun leerConSitios(texto: String, ahora: Momento): Pair<Leido, BooleanArray> {
        val clases = ArrayList<Clase>()
        val asignaturas = ArrayList<Asignatura>()
        var antelacion = ANTELACION
        var diasAntes = DIAS_ANTES
        val lineas = texto.lines()
        val suyas = BooleanArray(lineas.size)
        val tabla = ArrayList<List<String>>()
        val filasDeTabla = ArrayList<Int>()
        var enComentario = false
        val sueltas = ArrayList<String>()
        fun cerrarTabla() {
            if (tabla.isNotEmpty()) {
                val de = clasesDeTabla(tabla)
                if (de.isNotEmpty()) filasDeTabla.forEach { suyas[it] = true }
                clases += de
                tabla.clear(); filasDeTabla.clear()
            }
        }
        for ((k, linea) in lineas.withIndex()) {
            val t0 = linea.trim()
            // Los comentarios de HTML (`<!-- … -->`): ahí va el ejemplo.
            if (enComentario) {
                if ("-->" in t0) enComentario = false
                continue
            }
            if (t0.startsWith("<!--")) { enComentario = "-->" !in t0; continue }
            val c = celdas(linea)
            if (c != null) { tabla += c; filasDeTabla += k; continue }
            cerrarTabla()
            if (t0 == TITULO_ASIGNATURAS || t0 == TITULO_CLASES) { suyas[k] = true; continue }
            if (t0.isEmpty() || t0.startsWith('#')) continue
            // Lo que lee la voz, para la clase de encima.
            if (t0.startsWith('>')) {
                val nota = t0.substring(1).trim()
                if (clases.isNotEmpty()) {
                    if (nota.isNotEmpty()) { val ultima = clases.last(); clases[clases.size - 1] = ultima.copy(notas = ultima.notas + nota) }
                    suyas[k] = true
                }
                continue
            }
            val t = t0.trimStart('-', '*', '•').trim()
            val palabras = palabrasDe(t)
            val primera = n(palabras.firstOrNull().orEmpty())
            // «avisar 15 min antes», «antelación 20», «avisar evaluaciones 5 y 2 días antes».
            if (primera in setOf("avisar", "aviso", "avisame", "antelacion", "anticipacion")) {
                val numeros = palabras.mapNotNull { p -> p.trim { it !in '0'..'9' }.takeIf { soloCifras(it) && it.length <= 9 }?.toInt() }
                val q = n(t)
                if ("evaluacion" in q || "examen" in q || "dia" in q) {
                    if (numeros.isNotEmpty()) diasAntes = numeros
                } else numeros.firstOrNull()?.let { x -> antelacion = (if ("hora" in q) x * 60 else x).coerceAtMost(24 * 60) }
                continue
            }
            val asig = asignaturaDeLinea(palabras)
            if (asig != null) {
                suyas[k] = true
                if (asignaturas.none { n(it.nombre) == n(asig.nombre) }) asignaturas += asig
                continue
            }
            if (palabras.firstOrNull()?.let { esEvaluacion(it) } == true) { sueltas += t; continue }
            claseDeLinea(t)?.let { clases += it; suyas[k] = true }
        }
        cerrarTabla()
        // Las evaluaciones al final: nombran clases que pueden ir después.
        val evaluaciones = ArrayList<Evaluacion>()
        for (t in sueltas) {
            val (que, cuando) = try { Fechas.separar(t, ahora) } catch (e: ErrorFecha) { continue }
            if (cuando == null) continue
            val q = n(que)
            val clase = clases.indexOfFirst { q.contains(n(it.nombre)) }.takeIf { it >= 0 }
            evaluaciones += Evaluacion(que, clase, cuando)
        }
        return Leido(clases, evaluaciones, antelacion, diasAntes, asignaturas) to suyas
    }

    // ---- Avisos ----------------------------------------------------------

    /** Qué avisa un aviso. */
    sealed class Que {
        /** La clase [i], la sesión que empieza en [inicio] (ms locales). */
        data class DeClase(val i: Int, val inicio: Long) : Que()
        /** La evaluación [j], [dias] días antes. */
        data class DeEvaluacion(val j: Int, val dias: Int) : Que()
    }

    /** Un aviso con su hora (ms locales) y su id. */
    data class Aviso(val id: String, val cuando: Long, val que: Que)

    private fun msDe(m: Momento): Long = (m.dia * 1440 + m.minuto) * MINUTO

    /**
     * **Cuándo toca cada aviso** desde [desde] (ms locales, sin incluirlo) durante [dias] días: cada
     * sesión de cada clase `antelacion` minutos antes de empezar, y cada evaluación los `diasAntes`
     * días antes a su hora. Del más próximo al más lejano.
     */
    fun avisos(h: Leido, desde: Long, dias: Long): List<Aviso> {
        val hasta = desde + dias * DIA
        val hoy = Math.floorDiv(desde, DIA)
        val v = ArrayList<Aviso>()
        h.clases.forEachIndexed { i, c ->
            // Desde ayer: una clase de las 00:05 avisa la víspera.
            for (d in hoy - 1..hoy + dias) {
                if (Fechas.diaDeLaSemana(d).toInt() !in c.dias) continue
                val inicio = d * DIA + c.inicio * MINUTO
                val cuando = inicio - (c.antelacion ?: h.antelacion) * MINUTO
                if (cuando > desde && cuando <= hasta) v += Aviso("${PREFIJO}clase:$inicio:${c.nombre}", cuando, Que.DeClase(i, inicio))
            }
        }
        h.evaluaciones.forEachIndexed { j, e ->
            val aLas = msDe(e.cuando)
            for (n in h.diasAntes) {
                val cuando = aLas - n * DIA
                if (cuando > desde && cuando <= hasta) v += Aviso("${PREFIJO}eval:$n:$aLas:${e.que}", cuando, Que.DeEvaluacion(j, n))
            }
        }
        return v.sortedWith(compareBy<Aviso> { it.cuando }.thenBy { it.id })
    }

    /** El aviso de un id (el de un pospuesto lleva `|<ms>` detrás). */
    fun avisoDe(h: Leido, id: String, desde: Long): Aviso? {
        val base = id.substringBefore('|')
        // Se busca desde una semana antes: el aviso ya venció (y uno pospuesto puede llevar un rato).
        return avisos(h, desde - 7 * DIA, 15).firstOrNull { it.id == base }
    }

    /** Cuándo empezó la sesión anterior de una clase a la que empieza en [inicio] (ms locales). */
    fun sesionAnterior(c: Clase, inicio: Long): Long? {
        val dia = Math.floorDiv(inicio, DIA)
        return (1..7).map { dia - it }.firstOrNull { Fechas.diaDeLaSemana(it).toInt() in c.dias }?.let { it * DIA + c.inicio * MINUTO }
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
        val semana = Fechas.diaDeLaSemana(hoy).toInt()
        h.clases.indexOfFirst { semana in it.dias && it.inicio <= minuto && minuto < it.fin }.takeIf { it >= 0 }?.let { return it }
        val reciente = h.clases.withIndex()
            .filter { (_, c) -> semana in c.dias && c.fin <= minuto && minuto - c.fin <= 180 }
            .maxByOrNull { it.value.fin }?.index
        if (reciente != null) return reciente
        return avisos(h.copy(antelacion = 0), ahora, 7).firstNotNullOfOrNull { (it.que as? Que.DeClase)?.i }
    }

    /** «08:00–10:00». */
    private fun tramoLegible(c: Clase) = "%02d:%02d–%02d:%02d".format(c.inicio / 60, c.inicio % 60, c.fin / 60, c.fin % 60)

    /**
     * Lo que se dice de una clase: el detalle de la pantalla y lo que lee la voz al contestar. Las
     * evaluaciones de esa clase en la próxima semana van en los dos: que «sí o sí» se recuerden.
     */
    fun detalleYTexto(h: Leido, i: Int, inicio: Long): Pair<String, String?> {
        val c = h.clases[i]
        val detalle = StringBuilder(tramoLegible(c))
        c.aula?.let { detalle.append(" · ").append(it) }
        val ahora = Momento.deMsLocales(inicio)
        val leer = c.notas.toMutableList()
        h.evaluaciones.filter { it.clase == i }.forEach { e ->
            val falta = msDe(e.cuando) - inicio
            if (falta in -DIA..7 * DIA) {
                val cuando = e.cuando.legible(ahora)
                detalle.append("\n📝 ${e.que} · $cuando")
                leer += "${e.que}, $cuando"
            }
        }
        return detalle.toString() to (if (leer.isEmpty()) null else leer.joinToString(". "))
    }

    /** El nombre de fichero de la nota de voz de una clase. */
    fun ficheroDeVoz(nombre: String): String {
        var s = n(nombre).map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
        while ("--" in s) s = s.replace("--", "-")
        return s.trim('-') + ".m4a"
    }

    /** Lo que sale en la llamada (`llamada::Llamada` del PC). */
    data class Llamada(
        /** La nota de voz de la clase, si es para esta sesión. */
        val audio: File?,
        /** Quien «llama»: la asignatura o «📝 la evaluación». */
        val nombre: String,
        /** El id de su aviso (`horario:…`): para «Volver a llamar en». */
        val mensaje: String,
        /** La hora y el aula (y las evaluaciones cercanas), debajo del nombre. */
        val detalle: String,
        /** Lo que lee la voz del teléfono al contestar, después de la nota. */
        val texto: String?
    ) {
        val esDeHorario: Boolean get() = mensaje.startsWith(PREFIJO)
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

    /** Lo que se enseña de un aviso cuando ya no se encuentra (el horario cambió). */
    fun textoDe(h: Leido, a: Aviso): String = when (val q = a.que) {
        is Que.DeClase -> h.clases[q.i].nombre
        is Que.DeEvaluacion -> h.evaluaciones[q.j].que
    }

    /** Lo que se enseña de una clase en la lista: «Lun Mié · 08:00–10:00 · A-12». */
    fun resumenDe(c: Clase): String {
        val dias = c.dias.joinToString(" ") { listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")[it] }
        return listOfNotNull(dias, tramoLegible(c), c.aula).joinToString(" · ")
    }

    /** Con lo que nace el horario: el ejemplo va comentado, para que no llame. Igual que el PC. */
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

    /** Donde vive el horario en este aparato. */
    fun ruta(files: File) = File(files, FICHERO)

    /** Donde van las notas de voz de las clases. */
    fun carpetaDeVoz(files: File) = File(File(files, "horario"), "voz")

    /** Lee el horario del disco (vacío si no hay). */
    fun deDisco(files: File, ahora: Momento): Leido =
        runCatching { ruta(files).takeIf { it.isFile }?.readText() }.getOrNull()?.let { leer(it, ahora) } ?: Leido()

    private val BLANCOS = Regex("""\s+""")
}
