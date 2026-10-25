package com.forge.pixpin.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Los pasos del pedido** (pruebas de `pasos.rs` y de su sitio en `agente.rs` del PC, portadas). */
class PasosTest {

    @Test fun `la lista se lee y dice lo que queda`() {
        val p = Pasos.leer("""{"pasos":[{"paso":"Agrandar a 30 actividades","estado":"hecho"},{"paso":"Agrandar a 30 semanas","estado":"en curso"},{"paso":"Numerar","estado":"Pendiente"}]}""").getOrThrow()
        assertEquals(3, p.size)
        assertEquals(Pasos.Estado.EN_CURSO, p[1].estado)
        val r = Pasos.resultado(p)
        assertTrue(r, r.contains("1 de 3") && r.contains("«Numerar»"))
        val e = Pasos.empujon(p)!!
        assertTrue(e, e.contains("«Agrandar a 30 semanas»") && !e.contains("actividades»"))
        assertTrue(e.startsWith(Guardas.MARCA_DEL_TURNO))
        assertEquals("☑ Agrandar a 30 actividades\n▶ Agrandar a 30 semanas\n☐ Numerar", Pasos.lineas(p))
    }

    @Test fun `cerrada no empuja`() {
        val p = Pasos.leer("""{"pasos":[{"paso":"Agrandar","estado":"hecho"},{"paso":"Colorear","estado":"no_se_pudo","nota":"no hay colores"}]}""").getOrThrow()
        assertNull(Pasos.empujon(p))
        assertTrue(Pasos.resultado(p).contains("los 2 pasos"))
        assertNull("sin lista no hay empujón", Pasos.empujon(emptyList()))
    }

    @Test fun `una lista mal hecha se corrige`() {
        assertTrue(Pasos.leer("{}").isFailure)
        assertTrue(Pasos.leer("{roto").isFailure)
        assertTrue(Pasos.leer("""{"pasos":[{"paso":"a","estado":"hecho"}]}""").isSuccess)
        assertTrue(Pasos.leer("""{"pasos":[]}""").isFailure)
        assertTrue(Pasos.leer("""{"pasos":[{"paso":"x","estado":"quizas"}]}""").isFailure)
        assertTrue(Pasos.leer("""{"pasos":[{"paso":"  ","estado":"hecho"}]}""").isFailure)
        val muchos = (0..Pasos.MAX_PASOS).joinToString(",") { """{"paso":"p$it","estado":"pendiente"}""" }
        assertTrue(Pasos.leer("""{"pasos":[$muchos]}""").isFailure)
    }

    @Test fun `los pasos abiertos se empujan, apuntar no gasta vuelta y se ven`() {
        val hechas = mutableListOf<Llamada>()
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(Pasos.NOMBRE, """{"pasos":[{"paso":"Dibujar la casa","estado":"en_curso"},{"paso":"Dibujar el árbol","estado":"pendiente"}]}"""),
            Respuesta.deLlamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":10,"y":10}]}"""),
            Respuesta.deTexto("Hice la casa."),
            Respuesta.deLlamada(Pasos.NOMBRE, """{"pasos":[{"paso":"Dibujar la casa","estado":"hecho"},{"paso":"Dibujar el árbol","estado":"hecho"}]}"""),
            Respuesta.deTexto("Listo: la casa y el árbol.")
        ))
        val eventos = mutableListOf<Agente.Evento>()
        val f = Agente.turno(motor, mutableListOf(MensajeIa.sistema("S"), MensajeIa.usuario("dibuja una casa y un árbol")), Herramientas.catalogo(),
            { hechas += it; Result.success("hecho") }, opciones = Agente.Opciones(empujar = true, pasos = true, vueltas = 2)) { eventos += it; Seguir.SI }
        assertEquals("Listo: la casa y el árbol.", f.texto)
        assertTrue(motor.recibido[0].herramientas.any { it.nombre == Pasos.NOMBRE })
        val empujon = motor.recibido[3].mensajes.last().textoEntero
        assertTrue(empujon, empujon.contains("pasos sin cerrar") && empujon.contains("«Dibujar el árbol»"))
        assertEquals(2, eventos.count { it is Agente.Evento.Tareas })
        // Sin la opción, la herramienta no se ofrece.
        val m2 = MotorGuionado(listOf(Respuesta.deTexto("Hola.")))
        Agente.turno(m2, mutableListOf(MensajeIa.usuario("hola")), Herramientas.catalogo(), { Result.success("hecho") }) { Seguir.SI }
        assertTrue(m2.recibido[0].herramientas.none { it.nombre == Pasos.NOMBRE })
    }
}
