package com.forge.pixpin.mini

import com.forge.pixpin.ia.Acciones

/**
 * **La semana del horario** (PC `horario/semana.rs`, 18-oct-2026): lo puro de la pantalla semanal,
 * sin pintar nada, para probarlo sin aparato.
 *
 * - [Semana]: las asignaturas de la lista y los **bloques** de la rejilla, uno por clase y día. En el
 *   texto una clase puede ser de varios días («Lun Mie 8:00-10:00 Geometría»); en la rejilla cada día
 *   es un bloque que se mueve y se alarga solo. Al escribir, los bloques iguales vuelven a una línea.
 * - [reescribir]: el `horario.md` con lo de la pantalla, cambiando SOLO las líneas de clases y de
 *   asignaturas: los títulos, los comentarios, las evaluaciones y el «avisar» se quedan.
 */
object Semana {
    const val DESDE = 7 * 60
    const val HASTA = 22 * 60
    const val PASO = 15
    const val DURACION = 60
    const val FRANJAS = (HASTA - DESDE) / PASO

    val DIAS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

    /** Los colores de las asignaturas: vivos, para leer letra blanca encima. */
    val PALETA = listOf(0x0A84FF, 0x30D158, 0xFF9F0A, 0xFF453A, 0xBF5AF2, 0x64D2FF, 0xFF375F, 0xAC8E68, 0x5E5CE6, 0x66D4CF)

    /** Una clase en un día: un bloque de la rejilla. */
    data class Bloque(
        val dia: Int, val inicio: Int, val fin: Int, val nombre: String, val aula: String?,
        val notas: List<String> = emptyList(), val antelacion: Int? = null
    )

    data class Estado(val asignaturas: List<Horario.Asignatura> = emptyList(), val bloques: List<Bloque> = emptyList()) {
        /** Las clases para escribir: los bloques iguales en una sola, con todos sus días. */
        fun clases(): List<Horario.Clase> {
            val ordenados = bloques.sortedWith(compareBy({ it.dia }, { it.inicio }, { it.nombre }))
            val claves = ArrayList<List<Any?>>()
            val clases = ArrayList<Horario.Clase>()
            for (b in ordenados) {
                val k = listOf(b.nombre, b.inicio, b.fin, b.aula, b.notas, b.antelacion)
                val i = claves.indexOf(k)
                if (i >= 0) {
                    if (b.dia !in clases[i].dias) clases[i] = clases[i].copy(dias = (clases[i].dias + b.dia).sorted())
                } else {
                    claves += k
                    clases += Horario.Clase(listOf(b.dia), b.inicio, b.fin, b.nombre, b.aula, b.notas, b.antelacion)
                }
            }
            return clases
        }

        fun asignatura(nombre: String): Int = asignaturas.indexOfFirst { mismo(it.nombre, nombre) }

        fun colorDe(nombre: String): Int = asignatura(nombre).takeIf { it >= 0 }?.let { asignaturas[it].color } ?: colorPorNombre(nombre)

        /** «Nueva asignatura»: lo escrito, con el aula detrás si se pone (`Geometría @A-12`). Null si no hay nombre o ya estaba. */
        fun conAsignatura(escrito: String): Estado? {
            val (nombre, aula) = Horario.nombreYAula(escrito.split(Regex("\\s+")).filter { it.isNotEmpty() })
            if (nombre.isEmpty() || asignatura(nombre) >= 0) return null
            return copy(asignaturas = asignaturas + Horario.Asignatura(nombre, aula, null))
        }

        /** Sin la asignatura [i] ni sus clases. */
        fun sinAsignatura(i: Int): Estado {
            val a = asignaturas.getOrNull(i) ?: return this
            return copy(asignaturas = asignaturas.filterIndexed { j, _ -> j != i }, bloques = bloques.filterNot { mismo(it.nombre, a.nombre) })
        }

        /** El color siguiente de la paleta para la asignatura [i]. */
        fun conOtroColor(i: Int): Estado {
            val a = asignaturas.getOrNull(i) ?: return this
            val k = PALETA.indexOf(colorDe(a.nombre)).let { if (it < 0) 0 else (it + 1) % PALETA.size }
            return copy(asignaturas = asignaturas.mapIndexed { j, x -> if (j == i) x.copy(color = PALETA[k]) else x })
        }

        /** El aula de la asignatura [i] (y de sus clases que tenían la de antes). */
        fun conAula(i: Int, aula: String?): Estado {
            val a = asignaturas.getOrNull(i) ?: return this
            return copy(
                asignaturas = asignaturas.mapIndexed { j, x -> if (j == i) x.copy(aula = aula) else x },
                bloques = bloques.map { if (mismo(it.nombre, a.nombre) && it.aula == a.aula) it.copy(aula = aula) else it }
            )
        }

        /** **Soltar una asignatura en la rejilla**: un bloque de una hora desde la franja soltada. */
        fun soltar(asignatura: Int, dia: Int, minuto: Int): Estado {
            val a = asignaturas.getOrNull(asignatura) ?: return this
            val inicio = redondearAbajo(minuto).coerceIn(DESDE, HASTA - PASO)
            return copy(bloques = bloques + Bloque(dia.coerceIn(0, 6), inicio, minOf(inicio + DURACION, HASTA), a.nombre, a.aula))
        }

        /** **Mover un bloque** al día [dia] empezando en [inicio], con lo que duraba y sin salirse. */
        fun mover(i: Int, dia: Int, inicio: Int): Estado {
            val b = bloques.getOrNull(i) ?: return this
            val dura = (b.fin - b.inicio).coerceIn(PASO, HASTA - DESDE)
            val ini = redondear(inicio.coerceAtLeast(0)).coerceIn(DESDE, HASTA - dura)
            return conBloque(i, b.copy(dia = dia.coerceIn(0, 6), inicio = ini, fin = ini + dura))
        }

        /** **Alargar o acortar** tirando del borde de abajo: un cuarto de hora como poco. */
        fun alargar(i: Int, fin: Int): Estado {
            val b = bloques.getOrNull(i) ?: return this
            return conBloque(i, b.copy(fin = redondear(fin).coerceIn(b.inicio + PASO, maxOf(HASTA, b.inicio + PASO))))
        }

        fun conBloque(i: Int, b: Bloque) = copy(bloques = bloques.mapIndexed { j, x -> if (j == i) b else x })

        fun sinBloque(i: Int) = copy(bloques = bloques.filterIndexed { j, _ -> j != i })
    }

    private fun mismo(a: String, b: String) = Acciones.normalizar(a.trim()) == Acciones.normalizar(b.trim())

    fun redondearAbajo(m: Int) = m / PASO * PASO
    fun redondear(m: Int) = (m + PASO / 2) / PASO * PASO

    /** El color de una asignatura sin color propio: uno de la paleta por una huella de su nombre (FNV-1a, como el PC). */
    fun colorPorNombre(nombre: String): Int {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in Acciones.normalizar(nombre.trim()).toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return PALETA[java.lang.Long.remainderUnsigned(h, PALETA.size.toLong()).toInt()]
    }

    /** La semana de un horario leído: sus asignaturas (también las que solo nombra una clase) y un bloque por clase y día. */
    fun deHorario(h: Horario.Leido): Estado = Estado(
        h.todasLasAsignaturas(),
        h.clases.flatMap { c -> c.dias.map { d -> Bloque(d, c.inicio, c.fin, c.nombre, c.aula, c.notas, c.antelacion) } }
            .sortedWith(compareBy({ it.dia }, { it.inicio }, { it.nombre }))
    )

    private fun hora(m: Int) = "${m / 60}:%02d".format(m % 60)

    /** El aula como se escribe: `@A-12`, o `@(Lab de física)` si lleva blancos. */
    private fun aulaEscrita(a: String) = if (a.trim().split(Regex("\\s+")).size > 1) "@(${a.trim()})" else "@${a.trim()}"

    /** La línea de una clase: `Lun Mie 8:00-10:00 Geometría @A-12 avisar 15`. */
    fun lineaDeClase(c: Horario.Clase): String {
        val s = StringBuilder("${c.dias.joinToString(" ") { DIAS[it.coerceIn(0, 6)] }} ${hora(c.inicio)}-${hora(c.fin)} ${c.nombre.trim()}")
        c.aula?.takeIf { it.isNotBlank() }?.let { s.append(' ').append(aulaEscrita(it)) }
        c.antelacion?.let { s.append(" avisar $it") }
        return s.toString()
    }

    /** La línea de una asignatura: `Asignatura Geometría @A-12 #4F8EF7`. */
    fun lineaDeAsignatura(a: Horario.Asignatura): String {
        val s = StringBuilder("Asignatura ${a.nombre.trim()}")
        a.aula?.takeIf { it.isNotBlank() }?.let { s.append(' ').append(aulaEscrita(it)) }
        a.color?.let { s.append(" #%06X".format(it and 0xFFFFFF)) }
        return s.toString()
    }

    private fun loDeLaPantalla(s: Estado): List<String> {
        val v = ArrayList<String>()
        if (s.asignaturas.isNotEmpty()) {
            v += Horario.TITULO_ASIGNATURAS
            s.asignaturas.forEach { v += lineaDeAsignatura(it) }
            v += ""
        }
        val clases = s.clases()
        if (clases.isNotEmpty()) {
            v += Horario.TITULO_CLASES
            for (c in clases) { v += lineaDeClase(c); c.notas.forEach { v += "> $it" } }
            v += ""
        }
        return v
    }

    /**
     * **El `horario.md` con lo de la pantalla.** Las líneas de clases y de asignaturas salen, y lo de la
     * pantalla entra donde estaba la primera de ellas (o al final). Lo demás, tal cual; sin dos renglones
     * en blanco seguidos.
     */
    fun reescribir(original: String, s: Estado): String {
        val (_, suyas) = Horario.leerConSitios(original, com.forge.pixpin.ia.Momento(0, 0))
        val lineas = original.lines()
        val nuevas = loDeLaPantalla(s)
        val fuera = ArrayList<String>()
        var puesto = false
        for ((i, l) in lineas.withIndex()) {
            if (suyas.getOrElse(i) { false }) {
                if (!puesto) {
                    if (fuera.lastOrNull()?.isNotBlank() == true) fuera += ""
                    fuera += nuevas
                    puesto = true
                }
                continue
            }
            fuera += l
        }
        if (!puesto) {
            if (fuera.lastOrNull()?.isNotBlank() == true) fuera += ""
            fuera += nuevas
        }
        val limpio = ArrayList<String>()
        for (l in fuera) {
            if (l.isBlank() && (limpio.isEmpty() || limpio.last().isBlank())) continue
            limpio += l
        }
        while (limpio.isNotEmpty() && limpio.last().isBlank()) limpio.removeAt(limpio.size - 1)
        return limpio.joinToString("\n") + "\n"
    }
}
