package com.forge.pixpin.jev

/**
 * **Las ideas de la reunión juntas, frase a frase** (29-oct-2026; porte de
 * `crates/pixpin-typesafe/src/en_la_reunion.rs` del PC, `50e87978`): una [Mesa] por reunión guarda el
 * estado de todas (las tareas que ya existen, las preguntas abiertas, los eventos propuestos, el reloj de
 * los temas y sus avisos) y, por cada frase, arma **una sola petición** ([Vivo]) con las partes
 * encendidas ([Ideas]) y pasa las respuestas por la tabla de cada idea. Lo que sale son [Suceso]s.
 *
 * | idea | parte de `vivo` | tabla | suceso |
 * |---|---|---|---|
 * | tareas dichas | `compromiso` | [Compromisos.decidir] | [Suceso.Tarea] |
 * | urgencia en vivo | `tareas` | [UrgenciaViva.actualizar] | [Suceso.Cambio] |
 * | decisiones | `decision` | [Vivo.esDecision] | [Suceso.Decision] |
 * | preguntas pendientes | `pregunta`, `abiertas` | [Pendientes.DeLaReunion.trasFrase] | [Suceso.Abierta], [Suceso.Contestada] |
 * | fechas al horario | `cita` | [Citas.Horario.proponer] | [Suceso.Evento] |
 * | tiempo por tema | `tema` | [TiempoDeTemas.asignar] | [Suceso.Tiempos] |
 * | tema olvidado | `tema`, `cierre` | [TiempoDeTemas.Avisador.avisar] | [Suceso.Aviso] |
 *
 * **Un fallo de Jev no cambia nada** de lo que decide Jev: sin respuesta no hay propuestas, cambios,
 * decisiones ni preguntas. El reloj de los temas sí sigue (la frase va al tema de la anterior) y los
 * avisos que solo dependen de la hora también. Solo se propone lo que Jev da por seguro (SI = 0.7).
 */
object EnLaReunion {

    /** Quién habla, cuando no se sabe (la escucha en vivo no separa voces). Sin participantes no se pregunta quién hace una tarea. */
    const val QUIEN_NO_SE = "Voz"

    /** **Qué ideas están encendidas** (los interruptores de Ajustes › Asistente › Jev). */
    data class Ideas(
        val tareas: Boolean = false,
        val urgencia: Boolean = false,
        val decisiones: Boolean = false,
        val preguntas: Boolean = false,
        val citas: Boolean = false,
        val tiempoPorTema: Boolean = false,
        val temaOlvidado: Boolean = false
    ) {
        /** Si hay alguna encendida. */
        fun alguna(): Boolean = tareas || urgencia || decisiones || preguntas || citas || tiempoPorTema || temaOlvidado

        companion object {
            fun todas() = Ideas(true, true, true, true, true, true, true)
        }
    }

    /** **Lo que sale de una frase.** */
    sealed class Suceso {
        /** Una tarea propuesta (se acepta o se descarta: nunca se apunta sola). */
        data class Tarea(val propuesta: Compromisos.Propuesta) : Suceso()
        /** Un evento propuesto para el horario. */
        data class Evento(val evento: Citas.Evento) : Suceso()
        /** Una tarea que ya existía cambia de prioridad o de fecha, con su texto. */
        data class Cambio(val cambio: UrgenciaViva.Cambio, val tarea: String) : Suceso()
        /** Se decidió algo: el minuto y la frase. */
        data class Decision(val ms: Long, val texto: String) : Suceso()
        /** Una pregunta queda abierta. */
        data class Abierta(val abierta: Pendientes.Abierta) : Suceso()
        /** Una pregunta abierta se contestó. */
        data class Contestada(val contestada: Pendientes.Contestada) : Suceso()
        /** Lo que lleva cada tema de la agenda, y lo de fuera, hasta [hastaMs]. */
        data class Tiempos(val porTema: List<Long>, val otro: Long, val hastaMs: Long) : Suceso()
        /** Queda poco, se pasó la hora o están cerrando y hay temas sin tratar. */
        data class Aviso(val aviso: TiempoDeTemas.Aviso) : Suceso()
    }

    /** **El estado de una reunión** para las ideas de frase a frase. [duracionMs]: la prevista, si se dijo. */
    class Mesa(
        val ideas: Ideas,
        val reunion: String,
        val agenda: List<String>,
        /** El día de la reunión (para resolver «el viernes»). */
        val hoy: FechasDichas.Fecha,
        duracionMs: Long?,
        tareas: List<UrgenciaViva.TareaViva>
    ) {
        private val antes = ArrayList<Vivo.Frase>()
        private val tareas = ArrayList(tareas)
        private val pendientes = Pendientes.DeLaReunion()
        private val horario = Citas.Horario()
        private val cronometro = TiempoDeTemas.Cronometro(agenda.size)
        /** Sin duración prevista no hay avisos de tiempo. */
        private val avisador = duracionMs?.takeIf { it > 0 }?.let { TiempoDeTemas.Avisador(it) }
        /** Los temas que ya se dieron por tratados fuera de aquí (a mano o por los temas en vivo). */
        private var tratados: List<Int> = emptyList()

        /** Una tarea más (una propuesta aceptada) o la misma cambiada (un «Deshacer»): por su id. */
        @Synchronized
        fun tarea(t: UrgenciaViva.TareaViva) {
            val i = tareas.indexOfFirst { it.id == t.id }
            if (i >= 0) tareas[i] = t else tareas += t
        }

        /** La tarea con este id, como está ahora. */
        @Synchronized
        fun tareaDe(id: String): UrgenciaViva.TareaViva? = tareas.firstOrNull { it.id == id }

        /** Los temas de la agenda que ya están tratados (por índice). */
        @Synchronized
        fun tratados(v: List<Int>) {
            tratados = v.toList()
        }

        /** Las preguntas que siguen sin respuesta. */
        @Synchronized
        fun abiertas(): List<Pendientes.Abierta> = pendientes.abiertas()

        /** Lo contado de cada tema hasta [ms]. */
        @Synchronized
        fun tiempos(ms: Long): TiempoDeTemas.Cronometro = cronometro.hasta(ms)

        private fun conTema() = (ideas.tiempoPorTema || ideas.temaOlvidado) && agenda.isNotEmpty()

        private fun conCierre() = ideas.temaOlvidado && avisador != null && agenda.isNotEmpty()

        /** Si hay algo que preguntar por esta frase (alguna idea encendida y la frase con palabras de sobra). */
        fun valeLaPena(f: Vivo.Frase): Boolean = ideas.alguna() && Vivo.valeLaPena(f)

        private fun contexto(frase: Vivo.Frase): Pair<Vivo.Contexto, Vivo.Partes> {
            val ids = if (ideas.urgencia) tareas.map { it.id to it.texto } else emptyList()
            val abiertas = if (ideas.preguntas) pendientes.paraPreguntar() else emptyList()
            val partes = Vivo.Partes(
                compromiso = ideas.tareas, decision = ideas.decisiones, pregunta = ideas.preguntas, cita = ideas.citas,
                cierre = conCierre(), tema = conTema(), tareas = ids, abiertas = abiertas
            )
            val temaAnterior = (cronometro.anterior() as? TiempoDeTemas.Destino.Tema)?.let { agenda.getOrNull(it.i) }
            val ctx = Vivo.Contexto(
                reunion = reunion, participantes = emptyList(), agenda = if (partes.tema) agenda else emptyList(),
                antes = antes.toList(), frase = frase, hoy = hoy, temaAnterior = temaAnterior
            )
            return ctx to partes
        }

        /** **La petición de una frase** (para verla en las pruebas o el registro). */
        @Synchronized
        fun preparar(frase: Vivo.Frase): Vivo.Preparada {
            val (c, p) = contexto(frase)
            return Vivo.preparar(c, p)
        }

        /** **Pregunta por una frase**: una sola petición con todas las partes encendidas. Un fallo es un error (nunca un «no»). */
        fun preguntar(cliente: Cliente, frase: Vivo.Frase): Pair<Vivo.Senales, List<FechasDichas.Expresion>> {
            val prep = preparar(frase)
            return prep.leer(cliente.preguntar(prep.peticion)) to prep.fechas
        }

        /**
         * **Lo que pasa con una frase**: lo que dijo Jev (null si falló o no se preguntó) por las tablas.
         * [finMs]: cuándo se dejó de hablar en ella (hasta ahí cuenta el reloj de los temas).
         */
        @Synchronized
        fun trasFrase(frase: Vivo.Frase, respuesta: Pair<Vivo.Senales, List<FechasDichas.Expresion>>?, finMs: Long): List<Suceso> {
            val v = ArrayList<Suceso>()
            if (respuesta != null) loQueDijoJev(frase, respuesta.first, respuesta.second, v)
            val sen = respuesta?.first
            if (conTema()) {
                val d = TiempoDeTemas.asignar(sen?.tema, cronometro.anterior())
                cronometro.frase(frase.ms, d)
                if (ideas.tiempoPorTema) {
                    val h = cronometro.hasta(finMs)
                    v += Suceso.Tiempos(h.porTema.toList(), h.otro, finMs)
                }
                if (ideas.temaOlvidado) avisos(sen?.cierre, finMs, v)
            }
            antes += frase
            while (antes.size > Vivo.ANTES) antes.removeAt(0)
            return v
        }

        private fun loQueDijoJev(f: Vivo.Frase, sen: Vivo.Senales, fe: List<FechasDichas.Expresion>, v: MutableList<Suceso>) {
            if (ideas.tareas) {
                val c = sen.compromiso
                if (c != null) {
                    val r = Compromisos.decidir(f, c, sen.plazo, fe, hoy)
                    if (r is Compromisos.Resultado.Propuesta) v += Suceso.Tarea(r.propuesta)
                }
            }
            if (ideas.urgencia) {
                val plazo = Compromisos.plazoElegido(sen.plazo, fe)
                for ((id, st) in sen.tareas) {
                    val i = tareas.indexOfFirst { it.id == id }
                    if (i < 0) continue
                    val (nueva, cambio) = UrgenciaViva.actualizar(tareas[i], st, plazo, hoy)
                    tareas[i] = nueva
                    if (cambio != null) v += Suceso.Cambio(cambio, nueva.texto)
                }
            }
            if (ideas.decisiones && Vivo.esDecision(sen)) v += Suceso.Decision(f.ms, recortar(f.dice, 300))
            if (ideas.preguntas) {
                for (ev in pendientes.trasFrase(f, sen.pregunta, sen.respondidas)) {
                    when (ev) {
                        is Pendientes.Evento.Abre -> v += Suceso.Abierta(ev.abierta)
                        is Pendientes.Evento.Cierra -> v += Suceso.Contestada(ev.contestada)
                        is Pendientes.Evento.Quiza -> {}
                    }
                }
            }
            if (ideas.citas) {
                val r = horario.proponer(f, sen.cita, fe)
                if (r is Citas.Resultado.Propuesto) v += Suceso.Evento(r.evento)
            }
        }

        private fun avisos(cierre: Double?, ms: Long, v: MutableList<Suceso>) {
            val actual = cronometro.anterior()
            val sin = cronometro.hasta(ms).sinTratar().filter { actual != TiempoDeTemas.Destino.Tema(it) && it !in tratados }
            avisador?.let { a -> a.avisar(ms, cierre, sin).forEach { v += Suceso.Aviso(it) } }
        }
    }
}
