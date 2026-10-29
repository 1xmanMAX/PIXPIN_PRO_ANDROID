package com.forge.pixpin.jev

import com.forge.pixpin.jev.Preguntas.NO
import com.forge.pixpin.jev.Preguntas.SI

/**
 * **Las tablas de cada idea de la reunión** (29-oct-2026; porte de `compromisos.rs`, `citas.rs`,
 * `pendientes.rs`, `tiempo_de_temas.rs` y `urgencia_viva.rs` de `crates/pixpin-typesafe` del PC,
 * `50e87978`). Cada objeto, un módulo del PC, con su tabla tal cual; las juntan [EnLaReunion] y
 * [Vivo] (una sola petición por frase).
 */

/**
 * **Tareas dichas en voz alta** (`compromisos.rs`): «yo me encargo de mandar la cotización el viernes» →
 * una tarea propuesta con su texto, responsable, fecha y prioridad. El día lo resuelve el código
 * ([FechasDichas]) contra la fecha de la reunión.
 *
 * | compromiso | se hace |
 * |---|---|
 * | ≥ SI | tarea propuesta |
 * | entre NO y SI | nada, se apunta como dudosa |
 * | ≤ NO | nada |
 *
 * | quien_dicho | `quien` | responsable |
 * |---|---|---|
 * | ≥ SI | el elegido con ≥ [QUIEN_MINIMO] | ese nombre (o «todos») |
 * | ≥ SI | el elegido con menos | sin decidir («¿quién?») |
 * | ≤ NO | — | nadie |
 * | entre los dos, o sin participantes | — | sin decidir |
 *
 * | plazo_dicho | fecha |
 * |---|---|
 * | ≥ SI | la expresión elegida (o la única), resuelta en código |
 * | < SI | sin fecha |
 *
 * Prioridad: los puntos de [Urgencia.puntosDelPlazo] por los días que faltan, +3 si `urgente` ≥ SI;
 * ≥ 3 urgente, ≥ 1.5 alta (hoy o mañana), si no normal. El texto es la frase tal cual.
 */
object Compromisos {

    /** Lo menos que tiene que tener el elegido de `quien` para ponerlo de responsable (si no, «¿quién?»). */
    const val QUIEN_MINIMO = 0.5

    /** Quién hace la tarea. */
    sealed class Responsable {
        data class Nombre(val nombre: String) : Responsable() {
            override fun toString() = nombre
        }
        data object Nadie : Responsable() {
            override fun toString() = "nadie"
        }
        data object SinDecidir : Responsable() {
            override fun toString() = "¿quién?"
        }
    }

    /** **Una tarea propuesta.** [expresion]: las palabras de la fecha («el viernes»). */
    data class Propuesta(
        val ms: Long,
        val texto: String,
        val responsable: Responsable,
        val fecha: FechasDichas.Momento?,
        val expresion: String?,
        val prioridad: Urgencia.Prioridad,
        val p: Double
    )

    sealed class Resultado {
        data class Propuesta(val propuesta: Compromisos.Propuesta) : Resultado()
        /** Entre NO y SI: no se propone. */
        data class Dudosa(val p: Double) : Resultado()
        data object Nada : Resultado()
    }

    /** La prioridad de una tarea nueva por lo que falta y la prisa. */
    fun prioridad(dias: Long?, urgente: Double): Urgencia.Prioridad {
        val n = Urgencia.puntosDelPlazo(dias) + if (urgente >= SI) 3.0 else 0.0
        return when {
            n >= 3.0 -> Urgencia.Prioridad.URGENTE
            n >= 1.5 -> Urgencia.Prioridad.ALTA
            else -> Urgencia.Prioridad.NORMAL
        }
    }

    /** El plazo que vale de una frase: el elegido, si `plazo_dicho` ≥ SI. */
    fun plazoElegido(plazo: Vivo.Plazo?, fechas: List<FechasDichas.Expresion>): FechasDichas.Expresion? =
        plazo?.takeIf { it.p >= SI }?.let { fechas.getOrNull(it.cual) }

    /** **La tabla.** */
    fun decidir(frase: Vivo.Frase, c: Vivo.Compromiso, plazo: Vivo.Plazo?, fechas: List<FechasDichas.Expresion>, hoy: FechasDichas.Fecha): Resultado {
        if (c.p <= NO) return Resultado.Nada
        if (c.p < SI) return Resultado.Dudosa(c.p)
        val qd = c.quienDicho
        val responsable = when {
            qd != null && c.quien != null && qd >= SI && c.quien.second >= QUIEN_MINIMO -> Responsable.Nombre(c.quien.first)
            qd != null && qd <= NO -> Responsable.Nadie
            else -> Responsable.SinDecidir
        }
        val e = plazoElegido(plazo, fechas)
        val fecha = e?.momento
        val dias = fecha?.let { hoy.hasta(it.fecha) }
        return Resultado.Propuesta(
            Propuesta(frase.ms, recortar(frase.dice, 300), responsable, fecha, e?.texto, prioridad(dias, c.urgente), c.p)
        )
    }
}

/**
 * **Fechas dichas al horario** (`citas.rs`): «nos vemos el martes a las 3» → un evento propuesto. Jev
 * dice si la frase pone un momento a una reunión o un evento (`cita`) y cuál de las expresiones de fecha
 * es (`cita_cuando`); el día y la hora los resuelve el código.
 *
 * | `cita` | expresiones de fecha | se hace |
 * |---|---|---|
 * | ≥ SI | al menos una | evento en la elegida (con hora, o de día entero) |
 * | ≥ SI | el mismo momento ya propuesto | nada (repetido) |
 * | entre NO y SI | — | nada, se apunta como dudosa |
 * | ≤ NO | — | nada |
 */
object Citas {

    data class Evento(val ms: Long, val titulo: String, val momento: FechasDichas.Momento, val expresion: String, val p: Double)

    sealed class Resultado {
        data class Propuesto(val evento: Evento) : Resultado()
        data class Repetido(val momento: FechasDichas.Momento) : Resultado()
        data class Dudosa(val p: Double) : Resultado()
        data object Nada : Resultado()
    }

    /** **Los eventos propuestos en la reunión.** */
    class Horario {
        val eventos = ArrayList<Evento>()

        /** **La tabla.** */
        fun proponer(frase: Vivo.Frase, c: Vivo.Cita?, fechas: List<FechasDichas.Expresion>): Resultado {
            if (c == null || c.p <= NO) return Resultado.Nada
            if (c.p < SI) return Resultado.Dudosa(c.p)
            val e = fechas.getOrNull(c.cual) ?: return Resultado.Nada
            if (eventos.any { it.momento == e.momento }) return Resultado.Repetido(e.momento)
            val ev = Evento(frase.ms, recortar(frase.dice, 80), e.momento, e.texto, c.p)
            eventos += ev
            return Resultado.Propuesto(ev)
        }
    }
}

/**
 * **Preguntas que nadie contestó** (`pendientes.rs`): cada pregunta de verdad queda abierta; en cada
 * frase siguiente va un Noul por pregunta abierta en la misma petición de la frase. Al final, las que
 * siguen abiertas son las pendientes.
 *
 * | señal | se hace |
 * |---|---|
 * | `respondida_<id>` ≥ SI | se cierra (contestada por quien habla) |
 * | `respondida_<id>` entre NO y SI | sigue abierta, marcada «quizá» |
 * | `pregunta` ≥ SI | se abre una nueva con la frase |
 *
 * Primero se cierran y luego se abre: una pregunta no se contesta a sí misma.
 */
object Pendientes {

    data class Abierta(val id: String, val ms: Long, val quien: String, val texto: String, val quiza: Boolean = false)

    data class Contestada(val pregunta: Abierta, val ms: Long, val quien: String, val p: Double)

    sealed class Evento {
        data class Abre(val abierta: Abierta) : Evento()
        data class Cierra(val contestada: Contestada) : Evento()
        data class Quiza(val id: String, val p: Double) : Evento()
    }

    /** **Las preguntas de la reunión.** */
    class DeLaReunion {
        private val abiertasL = ArrayList<Abierta>()
        private val contestadasL = ArrayList<Contestada>()
        private var n = 0

        /** Las que van en la siguiente petición: (id, texto). */
        fun paraPreguntar(): List<Pair<String, String>> = abiertasL.map { it.id to it.texto }

        /** **La tabla** para una frase. */
        fun trasFrase(frase: Vivo.Frase, pregunta: Double?, respondidas: List<Pair<String, Double>>): List<Evento> {
            val ev = ArrayList<Evento>()
            for ((id, p) in respondidas) {
                val i = abiertasL.indexOfFirst { it.id == id }
                if (i < 0) continue
                if (p >= SI) {
                    val c = Contestada(abiertasL.removeAt(i), frase.ms, frase.quien, p)
                    contestadasL += c
                    ev += Evento.Cierra(c)
                } else if (p > NO) {
                    abiertasL[i] = abiertasL[i].copy(quiza = true)
                    ev += Evento.Quiza(id, p)
                }
            }
            if (pregunta != null && pregunta >= SI) {
                n++
                val a = Abierta("q$n", frase.ms, frase.quien, recortar(frase.dice, 200))
                abiertasL += a
                ev += Evento.Abre(a)
            }
            return ev
        }

        /** Las que siguen sin contestar. */
        fun abiertas(): List<Abierta> = abiertasL.toList()

        fun contestadas(): List<Contestada> = contestadasL.toList()
    }
}

/**
 * **Cuánto tiempo se dedicó a cada tema y qué tema se olvida** (`tiempo_de_temas.rs`).
 *
 * | `de_la_agenda` | va a |
 * |---|---|
 * | ≥ SI | el tema que elige `tema` |
 * | ≤ NO | «otro» |
 * | entre NO y SI | el tema de la frase anterior; si no hay, «otro» |
 *
 * **El tiempo** ([Cronometro]): cada frase cuenta desde que empieza hasta que empieza la siguiente,
 * como mucho [LARGO_MAXIMO_MS]. Un tema con al menos [MINIMO_TRATADO_MS] cuenta como tratado.
 *
 * | situación | aviso (una vez cada uno) |
 * |---|---|
 * | queda ≤ max(5 min, 15 % de la duración) y hay temas sin tratar | «queda poco» |
 * | se pasó la hora y hay temas sin tratar | «tiempo cumplido» |
 * | `cierre` ≥ SI y hay temas sin tratar | «están cerrando» |
 */
object TiempoDeTemas {

    const val LARGO_MAXIMO_MS = 60_000L
    const val MINIMO_TRATADO_MS = 30_000L
    const val AVISO_MINIMO_MS = 5 * 60_000L

    /** A dónde va una frase: un tema de la agenda (por índice) u «otro». */
    sealed class Destino {
        data class Tema(val i: Int) : Destino()
        data object Otro : Destino()
    }

    /** **La tabla de a qué tema va.** null en [t]: Jev no contestó (sigue el anterior, como la duda). */
    fun asignar(t: Vivo.Tema?, anterior: Destino?): Destino = when {
        t != null && t.deLaAgenda >= SI -> Destino.Tema(t.cual)
        t != null && t.deLaAgenda <= NO -> Destino.Otro
        else -> anterior ?: Destino.Otro
    }

    /** **El tiempo de cada tema.** */
    class Cronometro(temas: Int) {
        val porTema: MutableList<Long> = MutableList(temas) { 0L }
        var otro = 0L
            private set
        private var ultima: Pair<Long, Destino>? = null

        private fun sumar(d: Destino, ms0: Long) {
            val ms = ms0.coerceIn(0, LARGO_MAXIMO_MS)
            when (d) {
                is Destino.Tema -> if (d.i in porTema.indices) porTema[d.i] += ms
                Destino.Otro -> otro += ms
            }
        }

        /** Una frase que empieza en [ms] va a [d]: lo de la anterior se cuenta hasta aquí. */
        fun frase(ms: Long, d: Destino) {
            ultima?.let { (desde, prev) -> sumar(prev, ms - desde) }
            ultima = ms to d
        }

        /** El destino de la última frase. */
        fun anterior(): Destino? = ultima?.second

        /** Lo contado hasta [ms] (sin cerrar: la última frase sigue). */
        fun hasta(ms: Long): Cronometro {
            val c = Cronometro(0)
            c.porTema.addAll(porTema)
            c.otro = otro
            ultima?.let { (desde, prev) -> c.sumar(prev, ms - desde) }
            return c
        }

        /** Los temas aún sin tratar (menos de [MINIMO_TRATADO_MS]). */
        fun sinTratar(): List<Int> = porTema.indices.filter { porTema[it] < MINIMO_TRATADO_MS }
    }

    /** Un aviso de tema olvidado (los índices de la agenda). */
    sealed class Aviso {
        data class QuedaPoco(val quedanMs: Long, val temas: List<Int>) : Aviso()
        data class TiempoCumplido(val temas: List<Int>) : Aviso()
        data class Cierran(val temas: List<Int>) : Aviso()
    }

    /** **Los avisos de la reunión.** */
    class Avisador(val duracionMs: Long) {
        private var poco = false
        private var cumplido = false
        private var cierran = false

        /** Lo que queda para avisar «queda poco». */
        fun margenMs(): Long = maxOf(AVISO_MINIMO_MS, duracionMs * 15 / 100)

        /** **La tabla**, a los [ms] de empezar, con lo que dijo Jev del cierre en la última frase y los temas sin tratar. */
        fun avisar(ms: Long, cierre: Double?, sinTratar: List<Int>): List<Aviso> {
            val v = ArrayList<Aviso>()
            if (sinTratar.isEmpty()) return v
            val quedan = duracionMs - ms
            if (quedan <= 0 && !cumplido) {
                cumplido = true
                poco = true
                v += Aviso.TiempoCumplido(sinTratar.toList())
            } else if (quedan > 0 && quedan <= margenMs() && !poco) {
                poco = true
                v += Aviso.QuedaPoco(quedan, sinTratar.toList())
            }
            if (cierre != null && cierre >= SI && !cierran) {
                cierran = true
                v += Aviso.Cierran(sinTratar.toList())
            }
            return v
        }
    }
}

/**
 * **La urgencia de una tarea que cambia mientras se habla** (`urgencia_viva.rs`): «esto es para hoy»
 * sube la prioridad de una tarea que ya existe. La prioridad la sigue calculando [Urgencia.decidir]; lo
 * dicho en la reunión solo cambia dos de sus entradas:
 *
 * | `sobre_<id>` | `apura_<id>` | plazo en la frase (`plazo_dicho` ≥ SI) | cambia |
 * |---|---|---|---|
 * | ≥ SI | — | sí | la fecha: la expresión elegida, resuelta en código |
 * | ≥ SI | ≥ SI | — | `piden_ya` = `apura` (alguien la pide ya, en voz alta) |
 * | < SI | — | — | nada |
 *
 * Sin las señales de la tarea, la prioridad es la de siempre («normal») aunque cambie la fecha.
 */
object UrgenciaViva {

    /** Una tarea que ya existe. */
    data class TareaViva(
        val id: String,
        val texto: String,
        val fecha: FechasDichas.Fecha?,
        val senales: Urgencia.Senales?,
        val prioridad: Urgencia.Prioridad
    ) {
        fun calcular(hoy: FechasDichas.Fecha): Urgencia.Prioridad =
            Urgencia.decidir(Urgencia.Marca.NINGUNA, senales, fecha?.let { hoy.hasta(it) })

        companion object {
            fun nueva(id: String, texto: String, fecha: FechasDichas.Fecha?, senales: Urgencia.Senales?, hoy: FechasDichas.Fecha): TareaViva {
                val t = TareaViva(id, texto, fecha, senales, Urgencia.Prioridad.NORMAL)
                return t.copy(prioridad = t.calcular(hoy))
            }
        }
    }

    /** Lo que cambió de una tarea. */
    data class Cambio(
        val id: String,
        val antes: Urgencia.Prioridad,
        val despues: Urgencia.Prioridad,
        val fechaAntes: FechasDichas.Fecha?,
        val fechaDespues: FechasDichas.Fecha?
    )

    /** **La tabla**: la tarea como queda y el cambio, si cambió la prioridad o la fecha. */
    fun actualizar(t: TareaViva, s: Vivo.SenalTarea, plazo: FechasDichas.Expresion?, hoy: FechasDichas.Fecha): Pair<TareaViva, Cambio?> {
        if (s.sobre < SI) return t to null
        var n = t
        if (plazo != null) n = n.copy(fecha = plazo.momento.fecha)
        if (s.apura >= SI && n.senales != null) {
            n = n.copy(senales = n.senales!!.copy(pidenYa = maxOf(n.senales!!.pidenYa ?: 0.0, s.apura)))
        }
        n = n.copy(prioridad = n.calcular(hoy))
        val cambio = if (n.prioridad != t.prioridad || n.fecha != t.fecha) Cambio(t.id, t.prioridad, n.prioridad, t.fecha, n.fecha) else null
        return n to cambio
    }
}
