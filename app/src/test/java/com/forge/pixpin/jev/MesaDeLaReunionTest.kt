package com.forge.pixpin.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las ideas de la reunión y su mesa** (29-oct-2026): las pruebas de `compromisos.rs`, `citas.rs`,
 * `pendientes.rs`, `tiempo_de_temas.rs`, `urgencia_viva.rs` y `en_la_reunion.rs` de `pixpin-typesafe`
 * del PC (una sola petición por frase con respuestas grabadas, un fallo no propone nada, lo tratado fuera
 * no se avisa).
 */
class MesaDeLaReunionTest {

    private fun hoy() = FechasDichas.Fecha.nueva(2026, 10, 26)!!

    // --- compromisos.rs ------------------------------------------------------------------------

    private fun c(p: Double, quienDicho: Double, urgente: Double) = Vivo.Compromiso(p, urgente, quienDicho, "Ana" to 0.9)

    @Test
    fun `la tabla de las tareas dichas`() {
        val f = Vivo.Frase(90_000, "Ana", "yo me encargo de mandar la cotización el viernes")
        val fe = FechasDichas.buscar(f.dice, hoy())
        var r = Compromisos.decidir(f, c(0.92, 0.9, 0.1), Vivo.Plazo(0.9, 0), fe, hoy()) as Compromisos.Resultado.Propuesta
        assertEquals(Compromisos.Responsable.Nombre("Ana"), r.propuesta.responsable)
        assertEquals("2026-10-30", r.propuesta.fecha!!.fecha.toString())
        assertEquals("el viernes", r.propuesta.expresion)
        assertEquals(Urgencia.Prioridad.NORMAL, r.propuesta.prioridad)
        // Nadie dicho; plazo dudoso: sin fecha.
        r = Compromisos.decidir(f, c(0.8, 0.1, 0.1), Vivo.Plazo(0.5, 0), fe, hoy()) as Compromisos.Resultado.Propuesta
        assertEquals(Compromisos.Responsable.Nadie, r.propuesta.responsable)
        assertNull(r.propuesta.fecha)
        // Quién a medias: sin decidir.
        r = Compromisos.decidir(f, c(0.8, 0.5, 0.1), null, fe, hoy()) as Compromisos.Resultado.Propuesta
        assertEquals(Compromisos.Responsable.SinDecidir, r.propuesta.responsable)
        // Alguien se lo queda, pero `quien` adivina: sin decidir.
        r = Compromisos.decidir(f, c(0.95, 0.93, 0.1).copy(quien = "Luis" to 0.37), null, fe, hoy()) as Compromisos.Resultado.Propuesta
        assertEquals(Compromisos.Responsable.SinDecidir, r.propuesta.responsable)
        assertEquals(Compromisos.Resultado.Dudosa(0.5), Compromisos.decidir(f, c(0.5, 0.9, 0.0), null, fe, hoy()))
        assertEquals(Compromisos.Resultado.Nada, Compromisos.decidir(f, c(0.1, 0.9, 0.0), null, fe, hoy()))
    }

    @Test
    fun `la prioridad por lo que falta`() {
        assertEquals(Urgencia.Prioridad.ALTA, Compromisos.prioridad(0, 0.0))
        assertEquals(Urgencia.Prioridad.ALTA, Compromisos.prioridad(1, 0.0))
        assertEquals(Urgencia.Prioridad.NORMAL, Compromisos.prioridad(4, 0.0))
        assertEquals(Urgencia.Prioridad.URGENTE, Compromisos.prioridad(null, 0.9))
        assertEquals(Urgencia.Prioridad.NORMAL, Compromisos.prioridad(null, 0.5))
    }

    // --- citas.rs ------------------------------------------------------------------------------

    @Test
    fun `la tabla de las citas`() {
        val f = Vivo.Frase(1_000, "Ana", "nos vemos el martes a las 3")
        val fe = FechasDichas.buscar(f.dice, hoy())
        val h = Citas.Horario()
        val cita = Vivo.Cita(0.9, 0)
        val e = h.proponer(f, cita, fe) as Citas.Resultado.Propuesto
        assertEquals("mar 2026-10-27 15:00", e.evento.momento.toString())
        assertTrue(h.proponer(f, cita, fe) is Citas.Resultado.Repetido)
        assertEquals(Citas.Resultado.Dudosa(0.5), h.proponer(f, Vivo.Cita(0.5, 0), fe))
        assertEquals(Citas.Resultado.Nada, h.proponer(f, Vivo.Cita(0.1, 0), fe))
        assertEquals(Citas.Resultado.Nada, h.proponer(f, null, fe))
        assertEquals(1, h.eventos.size)
    }

    // --- pendientes.rs -------------------------------------------------------------------------

    @Test
    fun `se abren se cierran y quedan las pendientes`() {
        fun f(ms: Long, quien: String, dice: String) = Vivo.Frase(ms, quien, dice)
        val p = Pendientes.DeLaReunion()
        var e = p.trasFrase(f(0, "Ana", "¿cuándo llega el material?"), 0.9, emptyList())
        assertEquals("q1", (e[0] as Pendientes.Evento.Abre).abierta.id)
        // Una pregunta no se contesta a sí misma.
        e = p.trasFrase(f(5_000, "Luis", "¿y quién paga los proyectores?"), 0.85, listOf("q1" to 0.1))
        assertEquals(1, e.size)
        assertEquals(2, p.paraPreguntar().size)
        // A medias: sigue abierta, marcada.
        e = p.trasFrase(f(9_000, "Marta", "creo que pronto"), 0.1, listOf("q1" to 0.5, "q2" to 0.05))
        assertTrue(e[0] is Pendientes.Evento.Quiza)
        assertTrue(p.abiertas()[0].quiza)
        // Contestada.
        e = p.trasFrase(f(12_000, "Marta", "llega el jueves"), 0.05, listOf("q1" to 0.92, "q2" to 0.1))
        assertEquals("Marta", (e[0] as Pendientes.Evento.Cierra).contestada.quien)
        assertEquals(1, p.contestadas().size)
        assertEquals(listOf("q2"), p.abiertas().map { it.id })
        // Una pregunta dudosa no se abre.
        assertTrue(p.trasFrase(f(15_000, "Ana", "¿no?"), 0.5, emptyList()).isEmpty())
    }

    // --- tiempo_de_temas.rs --------------------------------------------------------------------

    private fun t(deLaAgenda: Double, cual: Int) = Vivo.Tema(deLaAgenda, cual, 0.8)

    @Test
    fun `a que tema va cada frase`() {
        val tema = { i: Int -> TiempoDeTemas.Destino.Tema(i) }
        assertEquals(tema(2), TiempoDeTemas.asignar(t(0.9, 2), null))
        assertEquals(TiempoDeTemas.Destino.Otro, TiempoDeTemas.asignar(t(0.1, 2), tema(1)))
        assertEquals(tema(1), TiempoDeTemas.asignar(t(0.5, 2), tema(1)))
        assertEquals(TiempoDeTemas.Destino.Otro, TiempoDeTemas.asignar(t(0.5, 2), null))
        assertEquals(tema(0), TiempoDeTemas.asignar(null, tema(0)))
    }

    @Test
    fun `el cronometro cuenta hasta la siguiente frase`() {
        val c = TiempoDeTemas.Cronometro(3)
        c.frase(0, TiempoDeTemas.Destino.Otro)
        c.frase(10_000, TiempoDeTemas.Destino.Tema(0))
        c.frase(25_000, TiempoDeTemas.Destino.Tema(0))
        // Un silencio de 5 minutos cuenta 60 s.
        c.frase(325_000, TiempoDeTemas.Destino.Tema(1))
        val h = c.hasta(345_000)
        assertEquals(10_000L, h.otro)
        assertEquals(listOf(15_000L + 60_000L, 20_000L, 0L), h.porTema)
        assertEquals(listOf(1, 2), h.sinTratar())
        assertEquals(TiempoDeTemas.Destino.Tema(1), c.anterior())
    }

    @Test
    fun `los avisos salen una vez`() {
        val a = TiempoDeTemas.Avisador(20 * 60_000L)
        assertEquals(5 * 60_000L, a.margenMs())
        assertTrue(a.avisar(10 * 60_000L, 0.1, listOf(2)).isEmpty())
        assertEquals(listOf(TiempoDeTemas.Aviso.QuedaPoco(4 * 60_000L, listOf(2))), a.avisar(16 * 60_000L, 0.1, listOf(2)))
        assertTrue(a.avisar(17 * 60_000L, 0.5, listOf(2)).isEmpty())
        assertEquals(listOf(TiempoDeTemas.Aviso.Cierran(listOf(2))), a.avisar(18 * 60_000L, 0.9, listOf(2)))
        assertEquals(listOf(TiempoDeTemas.Aviso.TiempoCumplido(listOf(2))), a.avisar(21 * 60_000L, null, listOf(2)))
        val b = TiempoDeTemas.Avisador(60 * 60_000L)
        assertEquals(9 * 60_000L, b.margenMs())
        assertTrue(b.avisar(70 * 60_000L, 0.95, emptyList()).isEmpty())
    }

    // --- urgencia_viva.rs ----------------------------------------------------------------------

    /** Consecuencia media (1.8), que otro espera: 2.8 sin fecha. */
    private fun senales() = Urgencia.Senales(Escala(1.8, 0.6, listOf(0.05, 0.2, 0.65, 0.1)), 0.9, 0.1, null)

    @Test
    fun `para hoy sube la prioridad`() {
        var t = UrgenciaViva.TareaViva.nueva("t1", "Mandar la cotización", FechasDichas.Fecha.nueva(2026, 10, 30), senales(), hoy())
        // 2.8 + 0.5 (en 4 días) = 3.3: alta.
        assertEquals(Urgencia.Prioridad.ALTA, t.prioridad)
        val e = FechasDichas.buscar("esto es para hoy", hoy())
        val (n, c) = UrgenciaViva.actualizar(t, Vivo.SenalTarea(0.9, 0.9), e.firstOrNull(), hoy())
        // 2.8 + 1 (piden ya) + 2 (hoy): urgente.
        assertEquals(Urgencia.Prioridad.ALTA to Urgencia.Prioridad.URGENTE, c!!.antes to c.despues)
        assertEquals(hoy(), c.fechaDespues)
        t = n
        // Otra frase que no es de la tarea: nada.
        assertNull(UrgenciaViva.actualizar(t, Vivo.SenalTarea(0.2, 0.9), null, hoy()).second)
        // De la tarea, sin prisa ni fecha: nada cambia.
        assertNull(UrgenciaViva.actualizar(t, Vivo.SenalTarea(0.9, 0.1), null, hoy()).second)
    }

    @Test
    fun `mas tiempo la baja y sin senales es normal`() {
        val t = UrgenciaViva.TareaViva.nueva("t1", "x", hoy(), senales(), hoy())
        // 2.8 + 2 (hoy) = 4.8: alta.
        assertEquals(Urgencia.Prioridad.ALTA, t.prioridad)
        val e = FechasDichas.buscar("eso puede esperar al 20 de noviembre", hoy())
        assertEquals(Urgencia.Prioridad.NORMAL, UrgenciaViva.actualizar(t, Vivo.SenalTarea(0.9, 0.05), e.firstOrNull(), hoy()).second!!.despues)
        val sin = UrgenciaViva.TareaViva.nueva("t2", "y", null, null, hoy())
        val c = UrgenciaViva.actualizar(sin, Vivo.SenalTarea(0.9, 0.9), FechasDichas.buscar("para hoy", hoy()).firstOrNull(), hoy()).second!!
        assertEquals(Urgencia.Prioridad.NORMAL, c.despues)
        assertEquals(hoy(), c.fechaDespues)
    }

    // --- en_la_reunion.rs ----------------------------------------------------------------------

    private fun frase(ms: Long, dice: String) = Vivo.Frase(ms, EnLaReunion.QUIEN_NO_SE, dice)

    @Test
    fun `las partes van segun las ideas encendidas`() {
        val tareas = listOf(UrgenciaViva.TareaViva.nueva("t1", "Pagar al proveedor", null, null, hoy()))
        val agenda = listOf("Paro", "Excursión")
        val m = EnLaReunion.Mesa(EnLaReunion.Ideas.todas(), "Coordinación", agenda, hoy(), null, tareas)
        val f = frase(0, "yo me encargo de mandar la cotización el viernes")
        val p = m.preparar(f)
        // Sin duración prevista no se pregunta si se está cerrando; sin participantes, ni quién.
        assertEquals(
            listOf("apura_t1", "cita", "compromiso", "de_la_agenda", "decision", "plazo_dicho", "pregunta", "sobre_t1", "tema", "urgente"),
            p.peticion.preguntas.keys.toList()
        )
        assertFalse("participantes" in p.peticion.estado)
        // Solo las decisiones: una pregunta, sin agenda en el estado.
        val solo = EnLaReunion.Mesa(EnLaReunion.Ideas(decisiones = true), "", agenda, hoy(), 1_800_000, tareas)
        val q = solo.preparar(f)
        assertEquals(1, q.peticion.preguntas.size)
        assertFalse("agenda" in q.peticion.estado)
        assertFalse(EnLaReunion.Ideas().alguna())
    }

    @Test
    fun `una frase da sus sucesos y un fallo no propone nada`() {
        val tareas = listOf(UrgenciaViva.TareaViva.nueva("t1", "Pagar al proveedor", hoy().mas(10), null, hoy()))
        val m = EnLaReunion.Mesa(EnLaReunion.Ideas.todas(), "Coordinación", listOf("Paro", "Excursión"), hoy(), 20 * 60_000L, tareas)
        val r = Grabado.respuesta(listOf(
            Grabado.noul("compromiso", 0.92), Grabado.noul("urgente", 0.1), Grabado.noul("plazo_dicho", 0.9),
            Grabado.noul("decision", 0.85), Grabado.noul("pregunta", 0.05), Grabado.noul("cita", 0.1),
            Grabado.noul("cierre", 0.05), Grabado.noul("de_la_agenda", 0.9), choiceGrabada("tema", "excursion", listOf("paro")),
            Grabado.noul("sobre_t1", 0.9), Grabado.noul("apura_t1", 0.2)
        ))
        val t = Grabado(listOf(Grabado.ok(r)))
        val cl = Cliente("k", t, Cache(), registro = {})
        var f = frase(60_000, "queda decidido: yo mando la cotización el viernes")
        val resp = m.preguntar(cl, f)
        assertEquals("una sola petición por frase", 1, t.llamadas)
        var v = m.trasFrase(f, resp, 64_000)
        assertEquals("2026-10-30", (v[0] as EnLaReunion.Suceso.Tarea).propuesta.fecha!!.fecha.toString())
        // La tarea que ya existía: su fecha pasa al viernes (sin sus señales, la prioridad sigue normal).
        val cambio = v[1] as EnLaReunion.Suceso.Cambio
        assertEquals("Pagar al proveedor", cambio.tarea)
        assertEquals("2026-10-30", cambio.cambio.fechaDespues.toString())
        assertEquals(Urgencia.Prioridad.NORMAL, cambio.cambio.antes)
        assertEquals(60_000L, (v[2] as EnLaReunion.Suceso.Decision).ms)
        assertEquals(64_000L, (v[3] as EnLaReunion.Suceso.Tiempos).hastaMs)
        // Un fallo: el reloj sigue al tema anterior, nada más.
        f = frase(70_000, "y ahora vamos con lo del autobús del viernes")
        v = m.trasFrase(f, null, 75_000)
        assertEquals(1, v.size)
        assertEquals(listOf(0L, 15_000L), (v[0] as EnLaReunion.Suceso.Tiempos).porTema)
        // Se pasa la hora con el paro sin tratar.
        v = m.trasFrase(frase(21 * 60_000L, "bueno, se nos fue la hora"), null, 21 * 60_000L)
        assertTrue(v.toString(), v.any { it is EnLaReunion.Suceso.Aviso && it.aviso == TiempoDeTemas.Aviso.TiempoCumplido(listOf(0)) })
    }

    @Test
    fun `lo tratado fuera no se avisa`() {
        val m = EnLaReunion.Mesa(EnLaReunion.Ideas.todas(), "", listOf("Paro"), hoy(), 60_000, emptyList())
        m.tratados(listOf(0))
        val v = m.trasFrase(frase(120_000, "se acabó el tiempo de verdad"), null, 120_000)
        assertFalse(v.toString(), v.any { it is EnLaReunion.Suceso.Aviso })
    }
}
