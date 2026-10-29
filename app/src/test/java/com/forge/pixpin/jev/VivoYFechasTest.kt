package com.forge.pixpin.jev

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una Choice grabada: [elegida] con 0.8 y el resto repartido entre [otras] (`grabadas::choice` del PC). */
internal fun choiceGrabada(nombre: String, elegida: String, otras: List<String>): String {
    val resto = if (otras.isEmpty()) 0.0 else 0.2 / otras.size
    val probs = (listOf("\"$elegida\":0.8") + otras.map { "\"$it\":$resto" }).joinToString(",")
    return "\"$nombre\":{\"type\":\"choice\",\"choice\":\"$elegida\",\"confidence\":0.6,\"probabilities\":{$probs}}"
}

/**
 * **Lo que se dice en vivo y las fechas dichas** (29-oct-2026): las pruebas de `vivo.rs`, `fechas.rs` y
 * `dictado.rs` de `pixpin-typesafe` del PC, con respuestas grabadas.
 */
class VivoYFechasTest {

    private fun lunes() = FechasDichas.Fecha.nueva(2026, 10, 26)!!

    private fun una(frase: String): Pair<String, String> {
        val v = FechasDichas.buscar(frase, lunes())
        assertEquals("$frase: $v", 1, v.size)
        return v[0].texto to v[0].momento.toString()
    }

    // --- fechas.rs -----------------------------------------------------------------------------

    @Test
    fun `el calendario`() {
        val f = lunes()
        assertEquals(0, f.diaSemana())
        assertEquals(f, FechasDichas.Fecha.deDias(f.dias()))
        assertEquals("2026-11-01", f.mas(6).toString())
        assertEquals(f, f.mas(-300).mas(300))
        assertEquals("2028-03-01", FechasDichas.Fecha.nueva(2028, 2, 29)!!.mas(1).toString())
        assertNull(FechasDichas.Fecha.nueva(2027, 2, 29))
        assertEquals(f, FechasDichas.Fecha.deTexto("2026-10-26"))
        assertEquals("2026-10-31", f.ultimoDelMes().toString())
        assertEquals(4L, f.hasta(f.mas(4)))
        assertEquals(0L, FechasDichas.Fecha.nueva(1970, 1, 1)!!.dias())
    }

    @Test
    fun `los dias de la semana`() {
        assertEquals("el viernes" to "vie 2026-10-30", una("yo me encargo de mandar la cotización el viernes"))
        assertEquals("lun 2026-11-02", una("lo vemos el lunes").second)
        assertEquals("lun 2026-10-26", una("este lunes sin falta").second)
        assertEquals("mié 2026-10-28", una("el próximo miércoles").second)
        assertEquals("el jueves que viene", una("el jueves que viene").first)
        assertEquals("el lunes pasado" to "lun 2026-10-19", una("el lunes pasado ya hablamos"))
        assertEquals("vie 2026-10-23", una("el viernes pasado").second)
        assertEquals("el martes de la semana que viene" to "mar 2026-11-03", una("el martes de la semana que viene"))
    }

    @Test
    fun `hoy manana y lo que no es un dia`() {
        assertEquals("lun 2026-10-26", una("esto es para hoy").second)
        assertEquals("mar 2026-10-27", una("mañana lo mando").second)
        assertEquals("mié 2026-10-28", una("pasado mañana").second)
        assertTrue(FechasDichas.buscar("lo hice esta mañana", lunes()).isEmpty())
        assertEquals("mañana por la mañana a las 9" to "mar 2026-10-27 09:00", una("mañana por la mañana a las 9"))
        assertTrue(FechasDichas.buscar("compramos 3 proyectores", lunes()).isEmpty())
        assertEquals("lun 2026-11-02 (aprox.)", una("la semana que viene").second)
        assertEquals("sáb 2026-10-31", una("a fin de mes").second)
    }

    @Test
    fun `las fechas con numero`() {
        assertEquals("dom 2026-11-15", una("el 15 de noviembre").second)
        assertEquals("dom 2027-01-03", una("antes del 3 de enero").second)
        assertEquals("jue 2026-11-05", una("para el 5").second)
        assertEquals("mié 2026-10-28", una("para el 28").second)
        assertEquals("15/11", una("el 15/11").first)
    }

    @Test
    fun `las horas`() {
        assertEquals("el martes a las 3" to "mar 2026-10-27 15:00", una("nos vemos el martes a las 3"))
        assertEquals("jue 2026-10-29 15:30", una("a las tres y media el jueves").second)
        assertEquals("lun 2026-10-26 10:30", una("a las 10:30").second)
        assertEquals("lun 2026-10-26 09:00", una("a las 9 de la mañana").second)
        assertEquals("lun 2026-10-26 20:00", una("a las 8 de la noche").second)
        assertEquals("lun 2026-10-26 12:45", una("a la una menos cuarto").second)
        assertEquals("lun 2026-10-26 12:00", una("al mediodía").second)
    }

    @Test
    fun `varias en una frase en orden`() {
        val v = FechasDichas.buscar("la cotización del viernes la mando mañana a las 10", lunes())
        assertEquals(listOf("viernes", "mañana a las 10"), v.map { it.texto })
    }

    // --- vivo.rs -------------------------------------------------------------------------------

    private fun frase(quien: String, dice: String) = Vivo.Frase(60_000, quien, dice)

    @Test
    fun `lo que junta la escucha se parte en oraciones`() {
        val f = Vivo.Frase(10_000, "Voz", "Buenos días. Nos piden 2.400 euros, me parece mucho. ¿Cuándo se publican? El viernes, en cuanto las suba. Vale.")
        val v = Vivo.partir(f, 20_000)
        assertEquals(
            listOf("Buenos días. Nos piden 2.400 euros, me parece mucho.", "¿Cuándo se publican?", "El viernes, en cuanto las suba. Vale."),
            v.map { it.first.dice }
        )
        assertEquals(10_000L, v[0].first.ms)
        assertTrue(v[1].first.ms > v[0].first.ms && v[1].first.ms == v[0].second)
        assertEquals(20_000L, v[2].second)
        assertTrue(v.all { it.first.quien == "Voz" })
        // «Oye, ¿empezamos…?» no se corta delante de «¿»; sin puntos, entera.
        val w = Vivo.partir(f.copy(dice = "Oye, ¿empezamos por el paro? Que me tiene preocupada"), 20_000)
        assertEquals("Oye, ¿empezamos por el paro?", w[0].first.dice)
        assertEquals(2, w.size)
        val h = f.copy(dice = "sabemos si el paro afecta también a los envíos")
        assertEquals(h.dice, Vivo.partir(h, 20_000)[0].first.dice)
    }

    @Test
    fun `las claves de las opciones`() {
        assertEquals("cotizacion_del_viernes", Vivo.clave("Cotización del viernes"))
        assertEquals("ana", Vivo.clave("  ¿Ana?  "))
        assertEquals("x", Vivo.clave("¡!"))
        val v = Vivo.claves(listOf("Ana", "ana", "Todos"))
        assertEquals("ana", v[0].first)
        assertEquals("ana_2", v[1].first)
        assertEquals("«todos» es la opción de todos", "todos_2", v[2].first)
    }

    @Test
    fun `el estado solo lleva lo que se pregunta`() {
        val gente = listOf("Ana", "Luis")
        val agenda = listOf("Paro de transportistas")
        val antes = listOf(frase("Luis", "uno"), frase("Ana", "dos"), frase("Luis", "tres"))
        val f = frase("Ana", "yo me encargo de mandar la cotización el viernes")
        val c = Vivo.Contexto("Coordinación", gente, agenda, antes, f, lunes())
        val soloDecision = Vivo.Partes(decision = true)
        val e = Vivo.estado(c, soloDecision)
        assertFalse("participantes" in e)
        assertFalse("agenda" in e)
        assertEquals(JsonArray(listOf(JsonPrimitive("Ana: dos"), JsonPrimitive("Luis: tres"))), e["antes"])
        assertEquals(JsonPrimitive("Ana"), e["frase"]!!.jsonObject["quien"])
        assertEquals(1, Vivo.preparar(c, soloDecision).peticion.preguntas.size)
        // Con compromiso: participantes, quién y el plazo (una sola fecha: sin Choice de plazo).
        val p = Vivo.preparar(c, Vivo.Partes(compromiso = true, tema = true))
        assertEquals(JsonArray(listOf(JsonPrimitive("Ana"), JsonPrimitive("Luis"))), p.peticion.estado["participantes"])
        assertTrue("agenda" in p.peticion.estado)
        assertEquals(listOf("compromiso", "de_la_agenda", "plazo_dicho", "quien", "quien_dicho", "tema", "urgente"), p.peticion.preguntas.keys.toList())
        assertEquals(1, p.fechas.size)
        val quien = p.peticion.preguntas["quien"]!!.aJson().toString()
        assertTrue(quien, quien.contains("«Ana», the person speaking"))
    }

    @Test
    fun `el tema anterior solo va con la parte tema`() {
        val agenda = listOf("Paro de transportistas")
        val f = frase("Ana", "adelantamos el pedido al lunes")
        var c = Vivo.Contexto("", emptyList(), agenda, emptyList(), f, lunes(), "Paro de transportistas")
        val tema = Vivo.Partes(tema = true)
        var p = Vivo.preparar(c, tema)
        assertEquals(JsonPrimitive("Paro de transportistas"), p.peticion.estado["tema_anterior"])
        assertTrue(p.peticion.preguntas["tema"]!!.aJson().toString().contains("`tema_anterior`"))
        c = c.copy(temaAnterior = null)
        p = Vivo.preparar(c, tema)
        assertFalse("tema_anterior" in p.peticion.estado)
        assertFalse(p.peticion.preguntas["tema"]!!.aJson().toString().contains("tema_anterior"))
        c = c.copy(temaAnterior = "Paro de transportistas")
        p = Vivo.preparar(c, Vivo.Partes(decision = true))
        assertFalse("tema_anterior" in p.peticion.estado)
    }

    @Test
    fun `sin fechas no se pregunta por plazos ni citas`() {
        val c = Vivo.Contexto("", emptyList(), emptyList(), emptyList(), frase("Ana", "hay que mandar la cotización"), lunes())
        val p = Vivo.preparar(c, Vivo.Partes(compromiso = true, cita = true))
        assertEquals(listOf("compromiso", "urgente"), p.peticion.preguntas.keys.toList())
    }

    @Test
    fun `pregunta con respuestas grabadas una sola peticion por frase`() {
        val gente = listOf("Ana", "Luis")
        val tareas = listOf("t1" to "Pagar al proveedor")
        val abiertas = listOf("q1" to "¿Cuándo llega el material?")
        val f = frase("Ana", "la cotización del viernes la mando yo mañana a las 10, y el material llega el jueves")
        val c = Vivo.Contexto("Coordinación", gente, emptyList(), emptyList(), f, lunes())
        val partes = Vivo.Partes(compromiso = true, decision = true, pregunta = true, cita = true, tareas = tareas, abiertas = abiertas)
        val p = Vivo.preparar(c, partes)
        assertEquals(listOf("viernes", "mañana a las 10", "el jueves"), p.fechas.map { it.texto })
        val r = Grabado.respuesta(listOf(
            Grabado.noul("compromiso", 0.93), Grabado.noul("urgente", 0.1), Grabado.noul("quien_dicho", 0.9),
            choiceGrabada("quien", "ana", listOf("luis", "todos")), Grabado.noul("plazo_dicho", 0.88),
            choiceGrabada("plazo", "manana_a_las_10", listOf("viernes", "el_jueves")), Grabado.noul("decision", 0.2),
            Grabado.noul("pregunta", 0.05), Grabado.noul("respondida_q1", 0.91), Grabado.noul("cita", 0.15),
            choiceGrabada("cita_cuando", "el_jueves", listOf("viernes", "manana_a_las_10")),
            Grabado.noul("sobre_t1", 0.1), Grabado.noul("apura_t1", 0.05)
        ))
        val t = Grabado(listOf(Grabado.ok(r)))
        val cl = Cliente("k", t, Cache(), registro = {})
        val (s, fechas) = Vivo.preguntar(cl, c, partes)
        assertEquals("una sola petición por frase", 1, t.llamadas)
        assertEquals("Ana", s.compromiso!!.quien!!.first)
        assertEquals("mañana a las 10", fechas[s.plazo!!.cual].texto)
        assertEquals(listOf("q1" to 0.91), s.respondidas)
        assertEquals(2, s.cita!!.cual)
        assertFalse(Vivo.esDecision(Vivo.Senales(decision = 0.5)))
        // Una respuesta que falta: error de la frase.
        val corta = Cliente("k", Grabado(listOf(Grabado.ok(Grabado.respuesta(listOf(Grabado.noul("compromiso", 0.9)))))), Cache(), registro = {})
        assertTrue(runCatching { Vivo.preguntar(corta, c, partes) }.exceptionOrNull() is ErrorJev.FaltaRespuesta)
    }

    // --- dictado.rs ----------------------------------------------------------------------------

    @Test
    fun `los textos que se podrian anotar`() {
        fun f(d: String) = Vivo.Frase(0, "Ana", d)
        val antes = listOf(f("el cemento llega el jueves a las 8"))
        assertEquals(listOf(Preguntas.Vivo.LA_ANTERIOR to "el cemento llega el jueves a las 8"), Dictado.textos(f("anota esto en el chat de la obra"), antes))
        assertEquals(listOf(Preguntas.Vivo.LO_QUE_SIGUE to "faltan tornillos"), Dictado.textos(f("pon en el grupo de compras que faltan tornillos."), emptyList()))
        val t = Dictado.textos(f("Anota en el chat de la obra: Llega la grúa el lunes"), antes)
        assertEquals("Llega la grúa el lunes", t[0].second)
        assertEquals(2, t.size)
        assertTrue(Dictado.textos(f("anótalo"), emptyList()).isEmpty())
    }

    @Test
    fun `la tabla del dictado`() {
        val chats = listOf("Obra Calle Lima", "Compras")
        fun d(p: Double, dicho: Double) = Vivo.Dictado(p, dicho, 0, "faltan tornillos")
        val fr = Vivo.Frase(0, "Ana", "x")
        assertEquals("Obra Calle Lima", (Dictado.decidir(fr, d(0.9, 0.9), chats) as Dictado.Resultado.Anotar).nota.chat)
        assertNull((Dictado.decidir(fr, d(0.9, 0.2), chats) as Dictado.Resultado.Anotar).nota.chat)
        assertEquals(Dictado.Resultado.Dudoso(0.5), Dictado.decidir(fr, d(0.5, 0.9), chats))
        assertEquals(Dictado.Resultado.Nada, Dictado.decidir(fr, d(0.1, 0.9), chats))
        assertEquals(Dictado.Resultado.SinTexto, Dictado.decidir(fr, d(0.9, 0.9).copy(texto = null), chats))
    }
}
