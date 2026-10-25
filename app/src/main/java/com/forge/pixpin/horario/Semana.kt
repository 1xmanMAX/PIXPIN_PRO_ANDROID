package com.forge.pixpin.horario

import com.forge.pixpin.horario.Horario.Asignatura
import com.forge.pixpin.horario.Horario.Clase
import com.forge.pixpin.ia.Momento
import kotlin.math.floor

/**
 * **La semana de la rejilla del horario**: lo puro de [PanelDelHorario] (25-oct-2026,
 * `horario/semana.rs` del PC, portado tal cual). Sin pintar nada, para poder probarlo.
 *
 * - [Semana]: las asignaturas de la lista y los **bloques** de la rejilla, uno por clase y día. En
 *   el texto una clase puede ser de varios días («Lun Mie 8:00-10:00 Geometría»); en la rejilla cada
 *   día es un bloque suelto que se mueve y se alarga solo. Al escribir, los bloques iguales (misma
 *   asignatura, horas, aula, nota y aviso) vuelven a ir en una línea.
 * - [Rejilla]: dónde cae cada día y cada franja de 15 min, y al revés.
 * - [reescribir]: el `horario.md` con lo de la rejilla, cambiando SOLO las líneas de clases y de
 *   asignaturas: los títulos, los comentarios, las evaluaciones y el «avisar 10 min antes» del
 *   usuario se quedan.
 */
data class Semana(
    val asignaturas: MutableList<Asignatura> = mutableListOf(),
    val bloques: MutableList<Bloque> = mutableListOf()
) {
    /** Una clase en un día: lo que se ve como un bloque en la rejilla. */
    data class Bloque(
        /** 0 lunes … 6 domingo. */
        val dia: Int,
        val inicio: Int,
        val fin: Int,
        val nombre: String,
        val aula: String?,
        /** Lo que lee la voz al contestar la llamada (las líneas `>`). */
        val notas: List<String> = emptyList(),
        /** Minutos de antes del aviso; null es la del horario. */
        val antelacion: Int? = null
    ) {
        internal fun clave() = Clave(nombre, inicio, fin, aula, notas, antelacion)
    }

    internal data class Clave(val nombre: String, val inicio: Int, val fin: Int, val aula: String?, val notas: List<String>, val antelacion: Int?)

    /** Las clases para escribir: los bloques iguales en una sola, con todos sus días. */
    fun clases(): List<Clase> {
        val clases = mutableListOf<Clase>()
        val claves = mutableListOf<Clave>()
        for (b in bloques.sortedWith(ORDEN)) {
            val k = b.clave()
            val i = claves.indexOf(k)
            if (i >= 0) {
                if (b.dia !in clases[i].dias) clases[i] = clases[i].copy(dias = (clases[i].dias + b.dia).sorted())
            } else {
                claves += k
                clases += Clase(listOf(b.dia), b.inicio, b.fin, b.nombre, b.aula, b.notas, b.antelacion)
            }
        }
        return clases
    }

    /** La asignatura de ese nombre (sin mirar mayúsculas ni tildes). */
    fun asignatura(nombre: String): Int? = asignaturas.indexOfFirst { mismo(it.nombre, nombre) }.takeIf { it >= 0 }

    /** El color de una asignatura (`0xRRGGBB`): el suyo o el de su nombre. */
    fun colorDe(nombre: String): Int = asignatura(nombre)?.let { asignaturas[it].color } ?: colorPorNombre(nombre)

    /**
     * **«Nueva asignatura»**: lo escrito, con el aula detrás si se pone (`Geometría @A-12`).
     * [Resultado.Repetida] con la que ya estaba si se repite, o `Nueva(null)` si no hay nombre.
     */
    fun anadirAsignatura(escrito: String): Resultado {
        val (nombre, aula) = Horario.nombreYAula(Horario.palabras(escrito))
        if (nombre.isEmpty()) return Resultado.Nueva(null)
        asignatura(nombre)?.let { return Resultado.Repetida(it) }
        asignaturas += Asignatura(nombre, aula, null)
        return Resultado.Nueva(asignaturas.size - 1)
    }

    /** Lo que da [anadirAsignatura] (el `Result<Option<usize>, usize>` del PC). */
    sealed class Resultado {
        data class Nueva(val i: Int?) : Resultado()
        data class Repetida(val i: Int) : Resultado()
    }

    /** Quita la asignatura [i] de la lista, con todas sus clases. */
    fun borrarAsignatura(i: Int): Asignatura? {
        if (i !in asignaturas.indices) return null
        val a = asignaturas.removeAt(i)
        bloques.removeAll { mismo(it.nombre, a.nombre) }
        return a
    }

    /** El color siguiente de la paleta para la asignatura [i] (tocar su círculo). */
    fun cambiarColor(i: Int) {
        val nombre = asignaturas.getOrNull(i)?.nombre ?: return
        val ahora = colorDe(nombre)
        val k = PALETA.indexOf(ahora).let { if (it < 0) 0 else (it + 1) % PALETA.size }
        asignaturas[i] = asignaturas[i].copy(color = PALETA[k])
    }

    /** Cambia el aula de la asignatura [i] (y de sus clases que tenían la de antes). */
    fun ponerAulaDeAsignatura(i: Int, aula: String?) {
        val a = asignaturas.getOrNull(i) ?: return
        val antes = a.aula
        asignaturas[i] = a.copy(aula = aula)
        for (k in bloques.indices) if (mismo(bloques[k].nombre, a.nombre) && bloques[k].aula == antes) bloques[k] = bloques[k].copy(aula = aula)
    }

    /**
     * **Soltar una asignatura en la rejilla**: un bloque de [DURACION] que empieza en la franja del
     * [minuto] soltado, sin salirse de la rejilla. Devuelve su número.
     */
    fun soltar(asignatura: Int, dia: Int, minuto: Int): Int? {
        val a = asignaturas.getOrNull(asignatura) ?: return null
        val inicio = redondearAbajo(minuto).coerceIn(DESDE, HASTA - PASO)
        val fin = minOf(inicio + DURACION, HASTA)
        bloques += Bloque(minOf(dia, 6), inicio, fin, a.nombre, a.aula)
        return bloques.size - 1
    }

    /** **Mover un bloque** al día [dia] empezando en [inicio] (pegado a la franja más cercana). */
    fun mover(i: Int, dia: Int, inicio: Long) {
        val b = bloques.getOrNull(i) ?: return
        val dura = maxOf(b.fin - b.inicio, 0).coerceIn(PASO, HASTA - DESDE)
        val empieza = redondear(maxOf(inicio, 0).toInt()).coerceIn(DESDE, HASTA - dura)
        bloques[i] = b.copy(dia = minOf(dia, 6), inicio = empieza, fin = empieza + dura)
    }

    /** **Alargar o acortar un bloque** tirando de su borde de abajo, un cuarto de hora como poco. */
    fun alargar(i: Int, fin: Int) {
        val b = bloques.getOrNull(i) ?: return
        bloques[i] = b.copy(fin = redondear(fin).coerceIn(b.inicio + PASO, maxOf(HASTA, b.inicio + PASO)))
    }

    fun borrar(i: Int): Bloque? = if (i in bloques.indices) bloques.removeAt(i) else null

    companion object {
        /** La primera y la última hora de la rejilla, en minutos del día. */
        const val DESDE = 7 * 60
        const val HASTA = 22 * 60
        /** Una franja de la rejilla: todo se pega a esto. */
        const val PASO = 15
        /** Lo que dura una clase recién soltada. */
        const val DURACION = 60
        /** Cuántas franjas tiene la rejilla. */
        const val FRANJAS = (HASTA - DESDE) / PASO

        /** Los días como se escriben en el horario (y como se leen en la rejilla). */
        val DIAS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

        /** Los colores de las asignaturas: vivos, para leer letra blanca encima. */
        val PALETA = listOf(0x0A84FF, 0x30D158, 0xFF9F0A, 0xFF453A, 0xBF5AF2, 0x64D2FF, 0xFF375F, 0xAC8E68, 0x5E5CE6, 0x66D4CF)

        internal val ORDEN = compareBy<Bloque>({ it.dia }, { it.inicio }, { it.nombre })

        private fun mismo(a: String, b: String) = Horario.norm(a.trim()) == Horario.norm(b.trim())

        /** Al múltiplo de [PASO] de abajo. */
        fun redondearAbajo(m: Int) = m / PASO * PASO

        /** Al múltiplo de [PASO] más cercano. */
        fun redondear(m: Int) = (m + PASO / 2) / PASO * PASO

        /**
         * El color de una asignatura sin color propio: uno de la [PALETA] por una huella de su
         * nombre (FNV-1a, como el PC): el mismo nombre, el mismo color siempre, aquí y allí.
         */
        fun colorPorNombre(nombre: String): Int {
            var h = 0xcbf29ce484222325uL
            for (b in Horario.norm(nombre.trim()).toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toInt() and 0xFF).toULong()
                h *= 0x100000001b3uL
            }
            return PALETA[(h % PALETA.size.toULong()).toInt()]
        }

        /**
         * La semana de un horario leído: sus asignaturas (también las que solo nombra una clase) y
         * un bloque por clase y día, ordenados por día y hora.
         */
        fun deHorario(h: Horario.Leido): Semana {
            val bloques = h.clases.flatMap { c -> c.dias.map { Bloque(it, c.inicio, c.fin, c.nombre, c.aula, c.notas, c.antelacion) } }
                .sortedWith(ORDEN)
            return Semana(h.todasLasAsignaturas().toMutableList(), bloques.toMutableList())
        }

        /** «8:00». */
        private fun hora(m: Int) = "%d:%02d".format(m / 60, m % 60)

        /** El aula como se escribe: `@A-12`, o `@(Lab de física)` si lleva blancos. */
        private fun aulaEscrita(a: String) = if (Horario.palabras(a).size > 1) "@(${a.trim()})" else "@${a.trim()}"

        /** La línea de una clase: `Lun Mié 8:00-10:00 Geometría @A-12 avisar 15`. */
        fun lineaDeClase(c: Clase): String {
            val dias = c.dias.map { DIAS[minOf(it, 6)] }
            var s = "${dias.joinToString(" ")} ${hora(c.inicio)}-${hora(c.fin)} ${c.nombre.trim()}"
            c.aula?.takeIf { it.isNotBlank() }?.let { s += " " + aulaEscrita(it) }
            c.antelacion?.let { s += " avisar $it" }
            return s
        }

        /** La línea de una asignatura: `Asignatura Geometría @A-12 #4F8EF7`. */
        fun lineaDeAsignatura(a: Asignatura): String {
            var s = "Asignatura ${a.nombre.trim()}"
            a.aula?.takeIf { it.isNotBlank() }?.let { s += " " + aulaEscrita(it) }
            a.color?.let { s += " #%06X".format(it) }
            return s
        }

        /** Lo que escribe la rejilla: la lista de asignaturas y las clases, cada cosa con su título. */
        private fun loDeLaRejilla(s: Semana): List<String> {
            val v = mutableListOf<String>()
            if (s.asignaturas.isNotEmpty()) {
                v += Horario.TITULO_ASIGNATURAS
                v += s.asignaturas.map { lineaDeAsignatura(it) }
                v += ""
            }
            val clases = s.clases()
            if (clases.isNotEmpty()) {
                v += Horario.TITULO_CLASES
                for (c in clases) { v += lineaDeClase(c); v += c.notas.map { "> $it" } }
                v += ""
            }
            return v
        }

        /**
         * **El `horario.md` con lo de la rejilla.** Las líneas de clases y de asignaturas de
         * [original] salen, y lo de la rejilla entra donde estaba la primera de ellas (o al final,
         * si no había ninguna). Lo demás, tal cual. Sin dejar más de un renglón en blanco seguido.
         */
        fun reescribir(original: String, s: Semana): String {
            val (_, suyas) = Horario.leerConSitios(original, Momento.deMsLocales(0))
            val lineas = Horario.lineas(original)
            val nuevas = loDeLaRejilla(s)
            val fuera = mutableListOf<String>()
            var puesto = false
            for ((i, l) in lineas.withIndex()) {
                if (suyas[i]) {
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
            // Sin dos renglones en blanco seguidos (quitar líneas los junta).
            val limpio = mutableListOf<String>()
            for (l in fuera) {
                if (l.isBlank() && (limpio.lastOrNull()?.isBlank() != false)) continue
                limpio += l
            }
            while (limpio.lastOrNull()?.isBlank() == true) limpio.removeAt(limpio.size - 1)
            return limpio.joinToString("\n") + "\n"
        }
    }
}

/**
 * Dónde está la rejilla en pantalla: la esquina de arriba a la izquierda de la primera franja del
 * lunes, el ancho de cada día y el alto de cada franja de [Semana.PASO] minutos.
 */
data class Rejilla(val x: Float, val y: Float, val anchoDia: Float, val altoFranja: Float, /** 6: lunes a sábado; 7 con el domingo. */ val dias: Int) {

    data class Caja(val x: Float, val y: Float, val ancho: Float, val alto: Float)

    /** El minuto del día a la altura [y] (sin pegar a la franja; puede salirse de la rejilla). */
    fun minutoEn(y: Float): Long = Semana.DESDE + floor((y - this.y) / altoFranja * Semana.PASO).toLong()

    /** **El día y el minuto bajo el punto**, o null fuera de la rejilla. */
    fun en(px: Float, py: Float): Pair<Int, Int>? {
        if (px < x || py < y) return null
        val dia = ((px - x) / anchoDia).toInt()
        val m = minutoEn(py)
        return if (dia < dias && m < Semana.HASTA) dia to m.toInt() else null
    }

    /** El día bajo [px], sin salirse de los que se ven (arrastrando por el borde, no se pierde). */
    fun diaCercano(px: Float): Int = minOf(maxOf(floor((px - x) / anchoDia), 0f).toInt(), dias - 1)

    /** La y de un minuto del día. */
    fun yDe(minuto: Int): Float = y + (minuto - Semana.DESDE).toFloat() / Semana.PASO * altoFranja

    /** La caja de un bloque. */
    fun caja(dia: Int, inicio: Int, fin: Int): Caja {
        val y0 = yDe(maxOf(inicio, Semana.DESDE))
        val y1 = yDe(minOf(fin, Semana.HASTA))
        return Caja(x + dia * anchoDia, y0, anchoDia, maxOf(y1 - y0, 0f))
    }

    /** Lo que mide toda la rejilla de alto. */
    fun alto(): Float = Semana.FRANJAS * altoFranja
}
