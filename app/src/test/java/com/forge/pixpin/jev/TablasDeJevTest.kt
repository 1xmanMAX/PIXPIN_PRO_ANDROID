package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **Las preguntas y las tablas de decisión** (28-oct-2026): las pruebas de `preguntas.rs`, `reunion.rs`,
 * `anki.rs`, `urgencia.rs` y `modelo.rs` del PC, fila a fila, y cada función con respuestas grabadas.
 */
class TablasDeJevTest {

    private fun cliente(t: Grabado) = Cliente("k", t, cache = Cache(), registro = {}, dormir = {})

    // --- preguntas.rs -------------------------------------------------------------------------

    @Test
    fun `los umbrales tienen su franja de duda`() {
        assertEquals(Preguntas.Lectura.SI, Preguntas.leer(0.71))
        assertEquals(Preguntas.Lectura.SI, Preguntas.leer(0.7))
        assertEquals(Preguntas.Lectura.DUDOSO, Preguntas.leer(0.5))
        assertEquals(Preguntas.Lectura.NO, Preguntas.leer(0.3))
        assertEquals(Preguntas.Lectura.NO, Preguntas.leer(0.05))
    }

    @Test
    fun `cada pregunta tiene texto y un nombre unico`() {
        for (lista in listOf(Preguntas.Reunion.preguntas(), Preguntas.Anki.preguntas(), Preguntas.Urgencia.preguntas(true), Preguntas.Modelo.preguntas())) {
            assertEquals(lista.size, lista.map { it.first }.distinct().size)
            for ((n, p) in lista) assertTrue("$n no nombra su campo del estado", p.aJson().toString().contains('`'))
        }
        // Sin mensajes recientes, no se pregunta por ellos.
        assertFalse(Preguntas.Urgencia.preguntas(false).any { it.first == Preguntas.Urgencia.PIDEN_YA })
        assertEquals(4, Preguntas.Anki.NIVELES.size)
        assertEquals(4, Preguntas.Urgencia.NIVELES.size)
        assertEquals(4, Preguntas.Modelo.NIVELES.size)
    }

    // --- reunion.rs ---------------------------------------------------------------------------

    private fun s(tarea: Double, decision: Double, pregunta: Double, examen: Double, dato: Double, charla: Double) =
        Reunion.Senales(tarea, 0.5, 0.5, decision, pregunta, examen, dato, charla)

    @Test
    fun `la tabla de las marcas`() {
        // Una tarea clara.
        assertEquals(listOf(Reunion.Tipo.TAREA to 0.92), Reunion.decidir(s(0.92, 0.1, 0.1, 0.1, 0.1, 0.05)))
        // Varias a la vez: son Nouls separados.
        assertEquals(
            listOf(Reunion.Tipo.TAREA, Reunion.Tipo.DECISION, Reunion.Tipo.EXAMEN),
            Reunion.decidir(s(0.8, 0.75, 0.1, 0.9, 0.2, 0.1)).map { it.first }
        )
        // Un dato clave cuenta como «examen» (lo que hay que estudiar).
        assertEquals(listOf(Reunion.Tipo.EXAMEN to 0.85), Reunion.decidir(s(0.1, 0.1, 0.1, 0.2, 0.85, 0.1)))
        // La charla apaga todo menos la tarea.
        assertEquals(listOf(Reunion.Tipo.TAREA to 0.8), Reunion.decidir(s(0.8, 0.9, 0.9, 0.9, 0.9, 0.95)))
        assertTrue(Reunion.decidir(s(0.1, 0.9, 0.9, 0.9, 0.9, 0.95)).isEmpty())
        // Lo que no está claro no se marca.
        assertTrue(Reunion.decidir(s(0.6, 0.5, 0.65, 0.4, 0.5, 0.2)).isEmpty())
        // El umbral es inclusivo.
        assertEquals(1, Reunion.decidir(s(0.7, 0.0, 0.0, 0.0, 0.0, 0.0)).size)
        // La pregunta sola.
        assertEquals(listOf(Reunion.Tipo.PREGUNTA to 0.75), Reunion.decidir(s(0.1, 0.1, 0.75, 0.1, 0.1, 0.1)))
    }

    private fun renglones() = listOf(
        Reunion.Renglon(0, "Buenos días, ¿me escuchan bien?"),
        Reunion.Renglon(20_000, "Hoy vemos la derivada como razón de cambio."),
        Reunion.Renglon(45_000, "Para el lunes resuelvan los ejercicios del 1 al 5."),
        Reunion.Renglon(null, "texto pegado sin minuto"),
        Reunion.Renglon(70_000, "eh")
    )

    @Test
    fun `el estado lleva el renglon sus vecinos y el titulo`() {
        val r = renglones()
        val e = Reunion.estado("Cálculo I", r, 2)
        assertEquals("Cálculo I", e["clase"]!!.jsonPrimitive.content)
        assertEquals(r[2].texto, e["linea"]!!.jsonPrimitive.content)
        assertEquals(2, e["antes"]!!.jsonArray.size)
        assertEquals(2, e["despues"]!!.jsonArray.size)
        // El primero no tiene «antes»: el campo no va (una lista vacía se leería como señal).
        val e0 = Reunion.estado("", r, 0)
        assertNull(e0["antes"])
        assertNull(e0["clase"])
        assertEquals(8, Reunion.peticion("Cálculo I", r, 2).preguntas.size)
    }

    private fun respuesta(tarea: Double, quien: Double, cuando: Double, charla: Double) = Grabado.nouls(
        listOf(
            Preguntas.Reunion.TAREA to tarea, Preguntas.Reunion.TAREA_QUIEN to quien, Preguntas.Reunion.TAREA_CUANDO to cuando,
            Preguntas.Reunion.DECISION to 0.05, Preguntas.Reunion.PREGUNTA to 0.1, Preguntas.Reunion.EXAMEN to 0.1,
            Preguntas.Reunion.DATO to 0.2, Preguntas.Reunion.CHARLA to charla
        )
    )

    @Test
    fun `busca con respuestas grabadas`() {
        // Tres renglones se miran (el sin minuto y el «eh», no). Con un hilo, en orden.
        val t = Grabado(
            listOf(
                Grabado.ok(respuesta(0.05, 0.1, 0.1, 0.97)),
                Grabado.ok(respuesta(0.1, 0.1, 0.1, 0.05)),
                Grabado.ok(respuesta(0.94, 0.4, 0.9, 0.02))
            )
        )
        val (v, modelo) = Reunion.buscar(cliente(t), "Cálculo I", renglones(), 1)
        assertEquals(3, t.llamadas)
        assertEquals("jev-1.14.0", modelo)
        assertEquals(listOf(Reunion.Hallazgo(45_000, Reunion.Tipo.TAREA, 0.94, null, true)), v)
        // Lo que viajó: el renglón con sus vecinos y el título.
        val estado = Json.parseToJsonElement(String(t.pedidos[2].cuerpo)).jsonObject["state"]!!.jsonObject
        assertEquals("Para el lunes resuelvan los ejercicios del 1 al 5.", estado["linea"]!!.jsonPrimitive.content)
    }

    @Test
    fun `un fallo no deja un resultado a medias`() {
        val t = Grabado(listOf(Grabado.ok(respuesta(0.05, 0.1, 0.1, 0.97)), Grabado.caida("se cortó")))
        try {
            Reunion.buscar(cliente(t), "x", renglones(), 1)
            fail("debía fallar")
        } catch (e: ErrorJev.Red) { /* un error, no un «no hay nada» */ }
    }

    // --- anki.rs ------------------------------------------------------------------------------

    private fun a(niveles: List<Double>, examen: Double) = Anki.Senales(Escala.de(niveles), examen)

    @Test
    fun `la tabla de cada tarjeta`() {
        assertEquals(Anki.Veredicto.QUEDA, Anki.veredicto(a(listOf(0.0, 0.05, 0.15, 0.8), 0.1)))
        assertEquals(Anki.Veredicto.SOBRA, Anki.veredicto(a(listOf(0.85, 0.1, 0.05, 0.0), 0.02)))
        // Aunque parezca poco, si dice que entra en el examen, queda.
        assertEquals(Anki.Veredicto.QUEDA, Anki.veredicto(a(listOf(0.6, 0.3, 0.1, 0.0), 0.9)))
        assertEquals(Anki.Veredicto.DUDOSA, Anki.veredicto(a(listOf(0.1, 0.4, 0.4, 0.1), 0.1)))
    }

    @Test
    fun `elegir deja menos y mejores`() {
        val v = listOf(
            a(listOf(0.0, 0.05, 0.15, 0.8), 0.1), // queda
            a(listOf(0.85, 0.1, 0.05, 0.0), 0.0), // sobra
            a(listOf(0.1, 0.4, 0.4, 0.1), 0.1),   // dudosa (0.5)
            null,                                 // sin nada dicho: queda
            a(listOf(0.1, 0.3, 0.4, 0.2), 0.1),   // dudosa (0.6)
            a(listOf(0.1, 0.5, 0.3, 0.1), 0.1)    // dudosa (0.4)
        )
        // Dos que quedan; las dudosas rellenan hasta 3, la más útil primero.
        assertEquals(listOf(true, false, false, true, true, false), Anki.elegir(v))
        // Con muchas buenas, las dudosas no entran.
        val buenas = listOf(
            a(listOf(0.0, 0.0, 0.2, 0.8), 0.0), a(listOf(0.0, 0.0, 0.2, 0.8), 0.0),
            a(listOf(0.0, 0.0, 0.2, 0.8), 0.0), a(listOf(0.1, 0.4, 0.4, 0.1), 0.1)
        )
        assertEquals(listOf(true, true, true, false), Anki.elegir(buenas))
        // Si todas sobran, la más útil (no se manda nada vacío).
        assertEquals(listOf(false, true), Anki.elegir(listOf(a(listOf(0.9, 0.1, 0.0, 0.0), 0.0), a(listOf(0.7, 0.1, 0.2, 0.0), 0.0))))
        assertTrue(Anki.elegir(emptyList()).isEmpty())
    }

    @Test
    fun `valora con respuestas grabadas`() {
        val r = Grabado.respuesta(listOf(Grabado.score(Preguntas.Anki.VALOR, listOf(0.0, 0.1, 0.2, 0.7)), Grabado.noul(Preguntas.Anki.EXAMEN, 0.2)))
        val t = Grabado(listOf(Grabado.ok(r)))
        val tramos = listOf(
            Anki.Tramo("0:30", "La derivada es el límite del cociente incremental."),
            Anki.Tramo("1:10", "")
        )
        val v = Anki.valorar(cliente(t), "Cálculo", tramos, 1)
        assertEquals(1, t.llamadas)
        assertEquals(0.9, v[0]!!.util(), 1e-9)
        assertNull(v[1])
        val e = Json.parseToJsonElement(String(t.pedidos[0].cuerpo)).jsonObject["state"]!!.jsonObject
        assertEquals("0:30", e["minuto"]!!.jsonPrimitive.content)
        assertEquals("Cálculo", e["clase"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sin jev o si falla van todas`() {
        // Falla: AnkiEnLaApp.elegir da null y las tarjetas van todas (ver TarjetasConJevTest).
        assertNull(AnkiEnLaApp.elegir(cliente(Grabado(listOf(Grabado.caida()))), "x", listOf(1_000L to "la derivada es un límite")))
        assertEquals("", AnkiEnLaApp.aviso(0))
        assertEquals(" · Jev dejó fuera 2 capturas sin nada que estudiar", AnkiEnLaApp.aviso(2))
    }

    // --- urgencia.rs --------------------------------------------------------------------------

    private fun u(niveles: List<Double>, bloquea: Double, plazo: Double, piden: Double?) = Urgencia.Senales(Escala.de(niveles), bloquea, plazo, piden)

    private val GRAVE = listOf(0.0, 0.05, 0.15, 0.8)
    private val MEDIA = listOf(0.05, 0.2, 0.65, 0.1)
    private val LEVE = listOf(0.6, 0.35, 0.05, 0.0)

    @Test
    fun `la marca del usuario manda`() {
        val leve = u(LEVE, 0.0, 0.0, null)
        assertEquals(Urgencia.Prioridad.URGENTE, Urgencia.decidir(Urgencia.Marca.MUY_URGENTE, leve, null))
        assertEquals(Urgencia.Prioridad.ALTA, Urgencia.decidir(Urgencia.Marca.URGENTE, leve, null))
        assertEquals(Urgencia.Prioridad.ALTA, Urgencia.decidir(Urgencia.Marca.URGENTE, null, null))
    }

    @Test
    fun `sin jev es lo de siempre`() {
        assertEquals(Urgencia.Prioridad.NORMAL, Urgencia.decidir(Urgencia.Marca.NINGUNA, null, 0))
    }

    @Test
    fun `la tabla de puntos`() {
        // Grave (2.75) + plazo ajeno + para hoy: urgente.
        val pago = u(GRAVE, 0.1, 0.9, null)
        assertEquals(Urgencia.Prioridad.URGENTE, Urgencia.decidir(Urgencia.Marca.NINGUNA, pago, 0))
        // La misma sin fecha: 3.75, alta.
        assertEquals(Urgencia.Prioridad.ALTA, Urgencia.decidir(Urgencia.Marca.NINGUNA, pago, null))
        // Media (1.8) que otro espera y que piden en el chat: 3.8, alta.
        assertEquals(Urgencia.Prioridad.ALTA, Urgencia.decidir(Urgencia.Marca.NINGUNA, u(MEDIA, 0.85, 0.2, 0.9), null))
        // Media sola: normal.
        assertEquals(Urgencia.Prioridad.NORMAL, Urgencia.decidir(Urgencia.Marca.NINGUNA, u(MEDIA, 0.1, 0.1, null), null))
        // Leve, nadie la espera, sin fecha: baja.
        val leve = u(LEVE, 0.05, 0.1, 0.1)
        assertEquals(Urgencia.Prioridad.BAJA, Urgencia.decidir(Urgencia.Marca.NINGUNA, leve, null))
        // La misma con hora mañana ya no es baja.
        assertEquals(Urgencia.Prioridad.NORMAL, Urgencia.decidir(Urgencia.Marca.NINGUNA, leve, 1))
        // Y una duda (bloquea 0.5) tampoco: no se baja lo que no está claro.
        assertEquals(Urgencia.Prioridad.NORMAL, Urgencia.decidir(Urgencia.Marca.NINGUNA, u(LEVE, 0.5, 0.1, null), null))
    }

    @Test
    fun `el plazo lo cuenta el codigo`() {
        assertEquals(2.0, Urgencia.puntosDelPlazo(-3), 0.0)
        assertEquals(2.0, Urgencia.puntosDelPlazo(0), 0.0)
        assertEquals(1.5, Urgencia.puntosDelPlazo(1), 0.0)
        assertEquals(1.0, Urgencia.puntosDelPlazo(3), 0.0)
        assertEquals(0.5, Urgencia.puntosDelPlazo(7), 0.0)
        assertEquals(0.0, Urgencia.puntosDelPlazo(30), 0.0)
        assertEquals(0.0, Urgencia.puntosDelPlazo(null), 0.0)
    }

    @Test
    fun `el estado y las preguntas de una tarea`() {
        val c = Urgencia.Contexto("Pagar la matrícula", "Universidad", "Mensajes guardados", (1..8).map { "mensaje $it" })
        val (p, con) = Urgencia.peticion(c)
        assertTrue(con)
        val r = p.estado["mensajes_recientes"]!!.jsonArray
        assertEquals(Urgencia.RECIENTES, r.size)
        assertEquals("mensaje 4", r[0].jsonPrimitive.content)
        assertEquals("mensaje 8", r[4].jsonPrimitive.content)
        assertEquals(4, p.preguntas.size)
        val (p2, con2) = Urgencia.peticion(Urgencia.Contexto("x"))
        assertFalse(con2)
        assertNull(p2.estado["mensajes_recientes"])
        assertEquals(3, p2.preguntas.size)
    }

    @Test
    fun `pregunta con respuestas grabadas`() {
        val r = Grabado.respuesta(
            listOf(
                Grabado.score(Preguntas.Urgencia.CONSECUENCIA, listOf(0.0, 0.1, 0.2, 0.7)),
                Grabado.noul(Preguntas.Urgencia.BLOQUEA, 0.2), Grabado.noul(Preguntas.Urgencia.PLAZO_AJENO, 0.9)
            )
        )
        val t = Grabado(listOf(Grabado.ok(r), Grabado.caida("caida")))
        val v = Urgencia.preguntarVarias(cliente(t), listOf(Urgencia.Contexto("Pagar la matrícula"), Urgencia.Contexto("Leer un libro")), 1)
        val s = v[0].getOrThrow()
        assertNull(s.pidenYa)
        // 2.6 de consecuencia + 1 del plazo ajeno: con hora en 2 días, alta; para hoy, urgente.
        assertEquals(Urgencia.Prioridad.ALTA, Urgencia.decidir(Urgencia.Marca.NINGUNA, s, 2))
        assertEquals(Urgencia.Prioridad.URGENTE, Urgencia.decidir(Urgencia.Marca.NINGUNA, s, 0))
        // La segunda falló: es un error, no una prioridad baja.
        assertTrue(v[1].isFailure)
    }

    // --- modelo.rs ----------------------------------------------------------------------------

    private fun mo(niveles: List<Double>, orden: Double, razonar: Double, importa: Double) = Modelo.Senales(Escala.de(niveles), orden, razonar, importa)

    @Test
    fun `la tabla del modelo`() {
        val facil = listOf(0.6, 0.35, 0.05, 0.0)
        val medio = listOf(0.05, 0.3, 0.55, 0.1)
        val dificil = listOf(0.0, 0.05, 0.3, 0.65)
        assertEquals(Modelo.Ruta.RAPIDO to Modelo.Motivo.ORDEN, Modelo.decidir(mo(facil, 0.95, 0.0, 0.0)))
        assertEquals(Modelo.Ruta.FUERTE to Modelo.Motivo.RAZONAR, Modelo.decidir(mo(medio, 0.1, 0.85, 0.2)))
        assertEquals(Modelo.Ruta.FUERTE to Modelo.Motivo.DIFICIL, Modelo.decidir(mo(dificil, 0.0, 0.5, 0.1)))
        assertEquals(Modelo.Ruta.FUERTE to Modelo.Motivo.IMPORTANTE, Modelo.decidir(mo(medio, 0.1, 0.4, 0.9)))
        assertEquals(Modelo.Ruta.RAPIDO to Modelo.Motivo.FACIL, Modelo.decidir(mo(facil, 0.2, 0.1, 0.9)))
        assertEquals(Modelo.Ruta.RAPIDO to Modelo.Motivo.SIN_DECIDIR, Modelo.decidir(mo(medio, 0.2, 0.4, 0.4)))
        // Una «orden» que parece difícil no va por la fila de la orden.
        assertEquals(Modelo.Ruta.FUERTE to Modelo.Motivo.DIFICIL, Modelo.decidir(mo(dificil, 0.9, 0.1, 0.1)))
    }

    @Test
    fun `el estado lleva solo lo ultimo`() {
        val e = Modelo.estado(Modelo.Contexto("resuelve el problema 3", (1..5).map { "usuario" to "m$it" }))
        val r = e["conversacion_reciente"]!!.jsonArray
        assertEquals(Modelo.RECIENTES, r.size)
        assertEquals("m3", r[0].jsonObject["texto"]!!.jsonPrimitive.content)
        assertNull(Modelo.estado(Modelo.Contexto("")).get("conversacion_reciente"))
    }

    @Test
    fun `elige con una respuesta grabada y falla como error`() {
        val r = Grabado.respuesta(
            listOf(
                Grabado.score(Preguntas.Modelo.DIFICULTAD, listOf(0.0, 0.1, 0.3, 0.6)),
                Grabado.noul(Preguntas.Modelo.ORDEN, 0.02), Grabado.noul(Preguntas.Modelo.RAZONAR, 0.9), Grabado.noul(Preguntas.Modelo.IMPORTA, 0.4)
            )
        )
        val c = cliente(Grabado(listOf(Grabado.ok(r))))
        val ctx = Modelo.Contexto("demuestra que la raíz de 2 es irracional")
        assertEquals(Modelo.Ruta.FUERTE to Modelo.Motivo.RAZONAR, Modelo.elegir(c, ctx))
        // Ya no queda respuesta grabada, pero la misma petición sale del cache; otra distinta falla
        // como red caída (no como «rápido»).
        assertEquals(Modelo.Ruta.FUERTE to Modelo.Motivo.RAZONAR, Modelo.elegir(c, ctx))
        try {
            Modelo.elegir(c, Modelo.Contexto("hola"))
            fail("debía fallar")
        } catch (e: ErrorJev.Red) { /* el de siempre lo pone quien llama */ }
    }
}
