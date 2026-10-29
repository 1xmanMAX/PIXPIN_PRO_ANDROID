package com.forge.pixpin.guardados

import com.forge.pixpin.mini.TareaInteligente
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Lo que la reunión apunta en Tareas** (29-oct-2026): `el_texto_de_una_tarea_propuesta` de
 * `jev/en_vivo.rs` del PC, con las mismas cadenas: la tarea aceptada aquí se apunta byte a byte como en
 * el PC (`jev::en_vivo::texto_de`).
 */
class ReunionConJevTest {

    private fun propuesta() = ActaViva.Propuesta(
        id = "p1", tipo = ActaViva.Tipo.TAREA, ms = 0, texto = "mandar la cotización",
        responsable = "Luis", fecha = "2026-10-30", hora = null, prioridad = "alta"
    )

    @Test
    fun `el texto de una tarea propuesta`() {
        val t = ReunionConJev.textoDe(propuesta(), "Coordinación")
        assertEquals("mandar la cotización 🔼 💬 Luis · 🎙 Coordinación ⏰ 2026-10-30 09:00", t)
        val m = TareaInteligente.marcas(t)
        assertEquals("mandar la cotización", m.titulo)
        assertEquals(TareaInteligente.Urgencia.URGENTE, m.urgencia)
        // Un evento con su hora, sin nadie ni urgencia.
        val e = propuesta().copy(tipo = ActaViva.Tipo.EVENTO, responsable = null, hora = "15:00", prioridad = null, texto = "nos vemos el martes a las 3")
        assertEquals("nos vemos el martes a las 3 ⏰ 2026-10-30 15:00", ReunionConJev.textoDe(e, ""))
        assertEquals(TareaInteligente.Urgencia.MUY_URGENTE, ReunionConJev.urgenciaDe("urgente"))
        assertEquals(TareaInteligente.Urgencia.NORMAL, ReunionConJev.urgenciaDe("normal"))
    }

    @Test
    fun `un tema sin tratar va a tareas con su reunion de nota`() {
        assertEquals("Viaje a Lima 💬 🎙 Coordinación", ReunionConJev.textoATareas("Viaje a Lima", " Coordinación "))
    }
}
