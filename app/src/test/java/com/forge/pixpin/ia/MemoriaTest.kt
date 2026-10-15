package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Pensar, la petición mejorada, la hoja de trabajo y el contexto al 70 %**, como el PC: las
 * pruebas de `pensar.rs`, `hoja_trabajo.rs` y `contexto.rs` portadas, el registro de §3.2 al byte,
 * y el turno con plan y `buscar_historial` con un motor de mentira.
 */
class MemoriaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pasar(vararg trozos: String): Pair<String, String> {
        val s = Pensar.Separador()
        val todo = trozos.flatMap { s.meter(Trozo.Texto(it)) } + s.acabar()
        return todo.filterIsInstance<Trozo.Pensando>().joinToString("") { it.texto } to todo.filterIsInstance<Trozo.Texto>().joinToString("") { it.texto }
    }

    @Test fun `las etiquetas partidas entre trozos se separan`() {
        assertEquals("Veamos: una casa roja." to "Hecho, la dibujé.", pasar("<thi", "nk>Veamos: una ca", "sa roja.</th", "ink>\n\nHecho", ", la dibujé."))
        assertEquals("Pienso que es azul." to "Es azul.", pasar("Pienso que es azul.</think>", "Es azul."))
        val (p, t) = pasar("Pienso.", "</think>", "Es azul.")
        assertEquals("", p); assertEquals("Pienso.</think>Es azul.", t)
        assertEquals("Pienso." to "Es azul.", Pensar.separar(t))
        assertEquals("" to "3 < 4 y a<b <t", pasar("3 <", " 4 y a<b ", "<t"))
        assertEquals("a" to "b", Pensar.separar("<think>a</think>b"))
        assertEquals("sin cerrar" to "", Pensar.separar("<think>sin cerrar"))
        val s = Pensar.Separador()
        assertEquals(listOf(Trozo.Pensando("x")), s.meter(Trozo.Pensando("x")))
        assertEquals(listOf(Trozo.Llamando("ocr")), s.meter(Trozo.Llamando("ocr")))
    }

    @Test fun `el cronometro ignora el pensamiento vacio y resumir toma la primera y la ultima frase`() {
        val c = Pensar.Cronometro()
        c.pensando(""); c.pensando("   \n")
        assertFalse(c.penso()); assertNull(c.otraCosa())
        c.pensando("hmm")
        assertTrue(c.penso()); assertTrue(c.otraCosa() != null); assertNull(c.otraCosa())
        val largo = "El usuario quiere una casa. Primero pienso en el tejado. Luego en las ventanas. Luego en la puerta. Al final usare dibujar_en_lienzo con rojo."
        val r = Pensar.resumir(largo, 120)
        assertTrue(r, r.startsWith("El usuario quiere una casa.") && r.endsWith("rojo.") && r.length <= 120)
        assertEquals("corto ya", Pensar.resumir("  corto \n ya ", 50))
    }

    @Test fun `el plan se lee aunque venga con markdown y tildes, y uno que no es plan no`() {
        val t = "**ENTIENDO:** Dibujar la casa de antes en rojo.\n- HARÉ: llamar a dibujar con rojo.\n" +
            "HERRAMIENTAS: dibujar_en_lienzo, volar\nDATOS: color rojo; 3 ventanas\nDECIDIDO: No se ha decidido todavía."
        val p = Pensar.Plan.leer(t, Herramientas.catalogo())!!
        assertEquals("Dibujar la casa de antes en rojo.", p.entiendo); assertEquals("llamar a dibujar con rojo.", p.hare)
        assertEquals(listOf("dibujar_en_lienzo"), p.herramientas); assertEquals(listOf("color rojo", "3 ventanas"), p.datos)
        assertTrue(p.decidido.isEmpty())
        assertTrue(p.lineas().lines().size in 2..4)
        assertEquals(p, Pensar.Plan.deJson(p.aJson()))
        assertNull(Pensar.Plan.leer("Hola, ¿en qué te ayudo?", Herramientas.catalogo()))
        assertNull(Pensar.Plan.leer("ENTIENDO: nada\nHARE: x", Herramientas.catalogo()))
        assertNull(Pensar.Plan.deJson(Json.parseToJsonElement("[1,2]")))
        val o = Pensar.Plan.porOmision("  dibuja\n una casa  ")
        assertEquals("dibuja una casa", o.entiendo); assertFalse(o.delModelo)
    }

    private fun plan(entiendo: String) = Pensar.Plan(entiendo, "dibujar", listOf("dibujar_en_lienzo"), listOf("color rojo"), listOf("sin texto"), true)

    @Test fun `la hoja se reescribe cada turno sin crecer sin fin, y lo que falta se borra al acabar bien`() {
        val h = HojaTrabajo.Hoja()
        for (i in 0 until 20) h.reescribir(plan("objetivo $i").copy(datos = listOf("dato $i", "color rojo")),
            HojaTrabajo.ResumenDelTurno(listOf("dibujar_en_lienzo"), "Hecho $i.\nmas", false, "Pienso. Mucho."))
        assertEquals("objetivo 19", h.objetivo)
        assertEquals(HojaTrabajo.MAX_HECHOS, h.hechos.size)
        assertEquals(1, h.hechos.count { it == "color rojo" })
        assertEquals(HojaTrabajo.MAX_HECHO, h.hecho.size)
        assertTrue(h.hecho.last().startsWith("T20: [dibujar_en_lienzo] Hecho 19."))
        assertEquals(HojaTrabajo.MAX_TURNOS, h.turnos.size); assertEquals(listOf("sin texto"), h.decisiones)
        val t = h.aTexto()
        assertTrue(t.contains("Objetivo: objetivo 19") && t.contains("Lo ultimo que pense (T20): Pienso. Mucho.") && t.length < 2500)
        val f = HojaTrabajo.Hoja()
        f.reescribir(plan("casa"), HojaTrabajo.ResumenDelTurno(emptyList(), "", true, ""))
        assertEquals(listOf("dibujar"), f.falta); assertTrue(f.hecho[0].endsWith("(sin acabar)"))
        f.reescribir(plan("casa"), HojaTrabajo.ResumenDelTurno(emptyList(), "", false, ""))
        assertTrue(f.falta.isEmpty())
    }

    @Test fun `el registro va y vuelve por el disco, y el del manual del PC se lee igual`() {
        val f = tmp.root.resolve("anot-X.asistente.json")
        assertNull(HojaTrabajo.Registro.leer(f))
        val hoja = HojaTrabajo.Hoja().also { it.reescribir(plan("casa"), HojaTrabajo.ResumenDelTurno(emptyList(), "", false, "")); it.reinicioDesde = "m7" }
        val r = HojaTrabajo.Registro("a".repeat(HojaTrabajo.MAX_PENSAMIENTO + 50), 12.5f, plan("casa"), hoja, true)
        r.escribir(f)
        val leido = HojaTrabajo.Registro.leer(f)!!
        assertEquals(HojaTrabajo.MAX_PENSAMIENTO, leido.pensamiento.length)
        assertEquals(12.5f, leido.segundos); assertEquals(r.plan, leido.plan); assertEquals(r.hoja, leido.hoja); assertTrue(leido.reinicio)
        // El ejemplo de §3.2 del manual del PC: se lee y se vuelve a escribir con las mismas claves y valores.
        val delPc = """{"version": 1, "pensamiento": "El usuario quiere una casa roja. Uso dibujar_en_lienzo con un rectangulo y un triangulo rojos…", "segundos": 12.5,
          "plan": {"entiendo": "Dibujar una casa roja en el lienzo.", "hare": "llamar a dibujar_en_lienzo con figuras rojas", "herramientas": ["dibujar_en_lienzo"], "datos": ["color rojo"], "decidido": [], "del_modelo": true},
          "hoja": {"objetivo": "Dibujar una casa roja en el lienzo.", "hechos": ["color rojo"], "hecho": ["T1: [dibujar_en_lienzo] Listo: dibujé la casa roja…"], "falta": [], "decisiones": [],
            "turnos": [{"n": 1, "peticion": "Dibujar una casa roja en el lienzo.", "pensado": "El usuario quiere una casa roja. … ", "resultado": "Listo: dibujé la casa roja…"}], "reinicio_desde": null, "reinicios": 0},
          "reinicio": false}"""
        val v = Json.parseToJsonElement(delPc)
        assertEquals(ordenado(v).toString(), ordenado(HojaTrabajo.Registro.deJson(v).aJson()).toString())
    }

    @Test fun `un registro roto o de otra version no se lee a medias, y una hoja enorme se recorta`() {
        val f = tmp.root.resolve("rota.json")
        f.writeText("{\"version\":1, \"hoja\": {\"objetivo\": ")
        assertTrue(runCatching { HojaTrabajo.Registro.leer(f) }.isFailure)
        f.writeText("{\"version\":99}")
        assertTrue(runCatching { HojaTrabajo.Registro.leer(f) }.isFailure)
        f.writeText("{\"version\":1,\"hoja\":7}")
        assertTrue(runCatching { HojaTrabajo.Registro.leer(f) }.isFailure)
        val mucho = (0 until 100).joinToString(",") { "\"$it ${"x".repeat(400)}\"" }
        val h = HojaTrabajo.Hoja.deJson(Json.parseToJsonElement("""{"hechos": [$mucho], "turnos": "no es lista"}"""))
        assertEquals(HojaTrabajo.MAX_HECHOS, h.hechos.size)
        assertTrue(h.hechos.all { it.length <= HojaTrabajo.LARGO_LINEA }); assertTrue(h.turnos.isEmpty())
    }

    private fun largo(i: Int) = "mensaje $i ${"bla ".repeat(50)}"

    @Test fun `el contexto se mide, se reinicia con el sistema y dos de antes, y se poda`() {
        val a = Contexto.estimar(listOf(MensajeIa.usuario("hola")), emptyList())
        val b = Contexto.estimar(listOf(MensajeIa.usuario("hola ".repeat(400))), emptyList())
        val c = Contexto.estimar(listOf(MensajeIa.usuarioConImagen("hola", byteArrayOf(1), "image/png")), emptyList())
        val d = Contexto.estimar(listOf(MensajeIa.usuario("hola")), Herramientas.catalogo())
        assertTrue(b > a + 500 && c >= a + 450 && d > a + 300)
        assertFalse(Contexto.pasaDelUmbral(1000, 500, 8192)); assertTrue(Contexto.pasaDelUmbral(5000, 900, 8192)); assertFalse(Contexto.pasaDelUmbral(99_999, 0, 0))
        val h = mutableListOf(MensajeIa.sistema("SIS + hoja"))
        for (i in 0 until 10) { h += MensajeIa.usuario(largo(i)); h += MensajeIa.texto(Rol.ASISTENTE, largo(i)) }
        h += MensajeIa.usuario("lo nuevo")
        val r = Contexto.reiniciar(h)
        assertEquals(4, r.size); assertEquals(Rol.SISTEMA, r[0].rol); assertEquals("lo nuevo", r.last().textoEntero)
        val pares = (0 until 40).map { (it % 2 == 0) to largo(it) }
        val p = Contexto.preparar("SIS", pares, MensajeIa.usuario("nuevo"), Herramientas.catalogo(), 8192, Contexto.RESERVA_RESPUESTA)
        assertTrue(p.reiniciado); assertEquals(4, p.historial.size); assertEquals(38, p.primero)
        val corto = Contexto.preparar("SIS", pares.take(2), MensajeIa.usuario("nuevo"), emptyList(), 8192, Contexto.RESERVA_RESPUESTA)
        assertFalse(corto.reiniciado); assertEquals(4, corto.historial.size)
        val t = mutableListOf(MensajeIa.usuario("x"), MensajeIa.resultado("1", "r".repeat(500)), MensajeIa(Rol.ASISTENTE, listOf(Parte.Texto("")), listOf(LlamadaCruda("2", "ocr", "{}"))))
        assertTrue(Contexto.podarEnTurno(t)); assertTrue(t[1].textoEntero.contains("resultado recortado")); assertFalse(Contexto.podarEnTurno(t))
    }

    @Test fun `buscar_historial puntua por palabra y por raiz`() {
        val f = listOf(Contexto.Fuente("#3 usuario", "El presupuesto de la obra es de 12 500 soles"), Contexto.Fuente("#4 asistente", "Dibujé la casa roja"))
        val r = Contexto.buscar(f, "presupuestos obra", 3)
        assertTrue(r, r.startsWith("[#3 usuario]"))
        assertTrue(Contexto.buscar(f, "zz", 3).startsWith("ERROR"))
        assertTrue(Contexto.buscar(f, "avion", 3).startsWith("No hay nada"))
        assertEquals("cunetas", Contexto.consultaDe("""{"consulta":" cunetas "}""")); assertNull(Contexto.consultaDe("{}"))
    }

    private class Apunta : Agente.Ejecutor {
        val hechas = mutableListOf<Llamada>()
        override fun ejecutar(llamada: Llamada): Result<String> { hechas += llamada; return Result.success("dibujadas 1 figuras") }
    }

    @Test fun `el turno planea, pone la peticion mejorada en el sistema y busca en el historial una sola vez`() {
        val motor = MotorGuionado(listOf(
            Respuesta("ENTIENDO: Dibujar un círculo rojo.\nHARE: dibujarlo\nHERRAMIENTAS: dibujar_en_lienzo\nDATOS: rojo\nDECIDIDO: nada", pensamiento = "Quiere un círculo."),
            Respuesta.deLlamada(Herramientas.BUSCAR_HISTORIAL, """{"consulta":"circulo"}"""),
            Respuesta.deLlamada(Herramientas.BUSCAR_HISTORIAL, """{"consulta":"otra vez"}"""),
            Respuesta.deLlamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"elipse","caja":[10,10,50,50]}]}"""),
            Respuesta.deTexto("Listo.")
        ))
        val h = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario("dibuja un círculo rojo"))
        val ev = mutableListOf<Agente.Evento>()
        val ej = Apunta()
        val hoja = HojaTrabajo.Hoja(objetivo = "casa")
        val f = Agente.turno(motor, h, emptyList(), ej, 8192, Agente.Opciones(planificar = true, hoja = hoja, ventana = 8192,
            buscar = { "[#1 usuario] un circulo azul" }, ampliar = Herramientas.catalogo())) { ev += it; Seguir.SI }
        assertEquals("Listo.", f.texto); assertEquals(1, ej.hechas.size)
        val plan = ev.filterIsInstance<Agente.Evento.Peticion>().single().plan
        assertEquals("Dibujar un círculo rojo.", plan.entiendo)
        assertTrue(ev.any { it is Agente.Evento.Pensando })
        // El plan añadió dibujar_en_lienzo aunque el turno empezó sin herramientas.
        assertTrue(motor.recibido[1].herramientas.any { it.nombre == Herramientas.DIBUJAR })
        val sistema = h[0].textoEntero
        assertTrue(sistema.contains("# Hoja de trabajo") && sistema.contains("# Peticion mejorada"))
        // La segunda búsqueda ya no se hace, y después buscar_historial ni se ofrece.
        assertTrue(h.any { it.rol == Rol.HERRAMIENTA && it.textoEntero.startsWith("Ya buscaste") })
        assertFalse(motor.recibido.last().herramientas.any { it.nombre == Herramientas.BUSCAR_HISTORIAL })
        assertTrue(ev.filterIsInstance<Agente.Evento.Resultado>().count { it.nombre == Herramientas.BUSCAR_HISTORIAL } == 2)
    }

    @Test fun `si el plan no sale se sigue con el pedido tal cual`() {
        val motor = MotorGuionado(listOf(Respuesta.deTexto("Hola, ¿qué tal?"), Respuesta.deTexto("Bien.")))
        val ev = mutableListOf<Agente.Evento>()
        val f = Agente.turno(motor, mutableListOf(MensajeIa.sistema("s"), MensajeIa.usuario("hola amigo")), emptyList(), Apunta(), 8192,
            Agente.Opciones(planificar = true)) { ev += it; Seguir.SI }
        assertEquals("Bien.", f.texto)
        assertEquals("hola amigo", ev.filterIsInstance<Agente.Evento.Peticion>().single().plan.entiendo)
    }
}
