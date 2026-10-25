package com.forge.pixpin.mini

/**
 * **Las vistas de la pantalla de Tareas**: Tarjetas, Kanban y Horario (25-oct-2026, porte de
 * `crates/pixpin-ui/src/vista_tareas.rs` del PC, `5dab99ac`), las cuentas sin pintar nada. Lo pinta
 * [TodasLasTareasActivity].
 *
 * El usuario en el PC: «ahora quiero que la función de Horario aparezca en la ventana de Tareas; de
 * allí quita la vista de Lista, deja Tarjetas y Kanban y añade Horario».
 *
 * - [Vista]: las tres vistas del conmutador, como se recuerdan (las palabras de `tareas-vista.txt`
 *   del PC; la «lista» de antes se lee como Tarjetas).
 * - [disponerHorario]: dónde van la lista de asignaturas y la semana, en una pantalla ancha.
 * - [chipsDeLaSemana] y [cajaDeChip]: las tareas con hora (`⏰`) de esta semana, como chapitas en
 *   su día y su hora.
 *
 * Lo que no se porta, y por qué: los atajos `Ctrl T/K/H` (`atajo`, `de_atajo`) y el marco de la
 * ventana que se ensancha en el Horario (`marco_para`) son del escritorio; en el teléfono la
 * pantalla mide lo que mide.
 */
object VistaDeTareas {

    /** Cómo se enseñan las tareas, con la palabra que se recuerda (la misma que en el PC). */
    enum class Vista(val texto: String) {
        /** Una tarjeta por tarea, agrupadas por su lista. */
        TARJETAS("tarjetas"),
        /** Una columna por lista; arrastrar una tarjeta a otra la pasa allí (ver [Kanban]). */
        KANBAN("kanban"),
        /** La semana del horario de clases, con sus asignaturas y las tareas con hora. */
        HORARIO("horario");

        /** Si las tarjetas son las de la vista en tarjetas: en la rejilla y en el tablero Kanban. */
        val conCuadrados: Boolean get() = this != HORARIO

        /** Si la vista enseña tareas (y con ellas el buscador y la caja de apuntar). El horario enseña la semana. */
        val conTareas: Boolean get() = this != HORARIO

        companion object {
            /**
             * Lo guardado. Lo que no se entiende (de otra versión, roto o nada) y la «lista» que ya
             * no existe son las tarjetas: quien tenía la lista abre en las tarjetas.
             */
            fun deTexto(t: String?): Vista = entries.firstOrNull { it.texto == t?.trim() } ?: TARJETAS
        }
    }

    // ------------------------------------------------------------------ el horario

    /** Una caja en píxeles de la pantalla. */
    data class Caja(val x: Float, val y: Float, val ancho: Float, val alto: Float) {
        fun contiene(px: Float, py: Float) = px >= x && py >= y && px < x + ancho && py < y + alto
    }

    /** Las medidas del horario, sin la escala (dp). */
    data class MedidasHorario(
        val margen: Float = 16f,
        /** El ancho que se quiere para la lista de asignaturas. */
        val lateral: Float = 280f,
        /** Lo menos que se le deja, en una pantalla estrecha. */
        val lateralMin: Float = 200f,
        val columnaHoras: Float = 48f,
        val cabeceraDias: Float = 36f
    )

    /** Dónde va cada parte del horario. */
    data class Disposicion(
        /** La lista de asignaturas (y debajo, lo demás de la asignatura). */
        val lateral: Caja,
        /** Las franjas de la semana, sin la columna de las horas ni la fila de los días. */
        val semana: Caja
    )

    /**
     * **El horario en [area]** con la escala [s]: la lista de asignaturas mide un cuarto del ancho,
     * entre `lateralMin` y `lateral`. La semana nunca baja de 60 por lado (una pantalla diminuta no
     * da cajas negativas). En un teléfono vertical no se usa: las asignaturas van en una fila
     * encima de la semana, que no cabe a su lado.
     */
    fun disponerHorario(area: Caja, s: Float, m: MedidasHorario = MedidasHorario()): Disposicion {
        val margen = m.margen * s
        val lateral = (area.ancho / 4f).coerceIn(m.lateralMin * s, m.lateral * s)
        val x0 = area.x + margen + lateral + margen + m.columnaHoras * s
        val y0 = area.y + m.cabeceraDias * s
        val ancho = maxOf(area.x + area.ancho - margen - x0, 60f)
        val alto = maxOf(area.y + area.alto - margen - y0, 60f)
        return Disposicion(
            lateral = Caja(area.x + margen, area.y, lateral, maxOf(area.alto - margen, 0f)),
            semana = Caja(x0, y0, ancho, alto)
        )
    }

    // ------------------------------------------------------- las tareas en la semana

    /** Cuántas chapitas caben una debajo de otra en la misma hora; las de más se cuentan en la última («+2»). */
    const val CARRILES = 3

    /** Una tarea con hora, colocada en la semana. */
    data class Chip(
        /** Su sitio en lo que se pasó a [chipsDeLaSemana]. */
        val indice: Int,
        /** 0 lunes … 6 domingo. */
        val dia: Int,
        /** El minuto del día donde se pinta (ya dentro de la rejilla). */
        val minuto: Int,
        /** 0 la primera de su hora, 1 la de debajo… */
        val carril: Int,
        /** Las de su hora que no caben, si es la última que cabe. */
        val mas: Int = 0
    )

    private const val DIA_MS = 86_400_000L

    /**
     * **Las tareas con hora de la semana que se ve**, como chapitas. [horas] son las horas locales
     * (ms desde 1970) de cada tarea; [lunes], el lunes de la semana (días desde 1970); [dias],
     * cuántos se ven; [desde]/[hasta], los minutos del día de la rejilla. Lo de antes o después de
     * la rejilla se pinta en su borde (una tarea a las 6:30 no desaparece). Dos tareas a menos de
     * [paso] minutos en el mismo día van una debajo de otra; más de [CARRILES], la última lleva la
     * cuenta de las que faltan.
     */
    fun chipsDeLaSemana(horas: List<Long>, lunes: Long, dias: Int, desde: Int, hasta: Int, paso: Int): List<Chip> {
        val puestas = horas.withIndex().mapNotNull { (i, ms) ->
            val dia = Math.floorDiv(ms, DIA_MS) - lunes
            if (dia < 0 || dia >= dias) return@mapNotNull null
            val minuto = (Math.floorMod(ms, DIA_MS) / 60_000L).toInt()
                .coerceIn(desde, maxOf(hasta - paso, desde))
            Triple(i, dia.toInt(), minuto)
        }.sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
        val chips = mutableListOf<Chip>()
        for ((indice, dia, minuto) in puestas) {
            // La primera de su grupo: la de su día de la que está a menos de `paso`, si la hay.
            val cabeza = chips.indexOfLast { it.dia == dia && it.carril == 0 && minuto < it.minuto + paso }.takeIf { it >= 0 }
            val carril = if (cabeza == null) 0 else chips.subList(cabeza, chips.size).count { it.dia == dia }
            if (carril >= CARRILES) {
                val ultima = chips.indexOfLast { it.dia == dia }
                if (ultima >= 0) chips[ultima] = chips[ultima].let { it.copy(mas = it.mas + 1) }
                continue
            }
            chips += Chip(indice, dia, cabeza?.let { chips[it].minuto } ?: minuto, carril)
        }
        return chips
    }

    /**
     * **La caja de una chapita** en la columna de su día ([xDia], [anchoDia]) a la altura [y] de su
     * minuto: pegada a la derecha de la columna (las clases se siguen leyendo a la izquierda), de
     * [alto], y cada carril debajo del anterior. Como mucho, dos tercios del día de ancho.
     */
    fun cajaDeChip(xDia: Float, anchoDia: Float, y: Float, carril: Int, alto: Float, s: Float): Caja {
        val aire = 3f * s
        val ancho = maxOf(anchoDia * 2f / 3f, 1f)
        return Caja(
            x = xDia + anchoDia - ancho - aire,
            y = y + 1f * s + carril * (alto + 2f * s),
            ancho = maxOf(ancho - aire, 1f),
            alto = alto
        )
    }
}
