package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué cuenta el chat de un proyecto cuando el proyecto cambia. Ver [AvisosDelProyecto]. */
class AvisosDelProyectoTest {

    private val base = Proyecto(id = "pr-1", nombre = "Casa")

    @Test
    fun `lo creado en el proyecto se cuenta una vez`() {
        val antes = base
        val despues = base.copy(
            hojas = listOf(
                Hoja(id = "h1", dibujo = "dib-1"),
                Hoja(id = "h2", nota = ""),
                Hoja(id = "h3", tabla = "t-1", nombre = "Gastos")
            ),
            croquis = listOf("c3d-1")
        )
        val n = AvisosDelProyecto.novedades(antes, despues)
        assertEquals(
            listOf(
                AvisosDelProyecto.Novedad.Lienzo(despues.hojas[0]),
                AvisosDelProyecto.Novedad.Tabla(despues.hojas[2]),
                AvisosDelProyecto.Novedad.Croquis("c3d-1", 1)
            ),
            n
        )
        assertTrue("guardar otra vez lo mismo no cuenta nada", AvisosDelProyecto.novedades(despues, despues).isEmpty())
    }

    @Test
    fun `la nota se cuenta cuando tiene texto y cada vez que cambia`() {
        val vacia = base.copy(hojas = listOf(Hoja(id = "n1", nota = "")))
        val escrita = base.copy(hojas = listOf(Hoja(id = "n1", nota = "# Presupuesto")))
        assertEquals(listOf(AvisosDelProyecto.Novedad.Nota(escrita.hojas[0])), AvisosDelProyecto.novedades(vacia, escrita))
        val otra = base.copy(hojas = listOf(Hoja(id = "n1", nota = "# Presupuesto final")))
        assertEquals(1, AvisosDelProyecto.novedades(escrita, otra).size)
        assertTrue(AvisosDelProyecto.novedades(otra, otra).isEmpty())
    }

    @Test
    fun `lo que vino del chat y las paginas de un PDF no se cuentan`() {
        val despues = base.copy(
            pdfOrigen = "/plano.pdf",
            hojas = listOf(
                Hoja(id = "p0", pagina = 0),
                Hoja(id = "p1", pagina = 1, dibujo = "dib-p1"),
                Hoja(id = "f", dibujo = "foto-msg", deMensaje = "msg"),
                Hoja(id = "x", tabla = "libro-msg-0", deMensaje = "msg2")
            )
        )
        assertTrue(AvisosDelProyecto.novedades(base, despues).isEmpty())
    }

    @Test
    fun `una hoja de pagina en blanco que recibe su dibujo no se cuenta dos veces`() {
        val antes = base.copy(hojas = listOf(Hoja(id = "h", dibujo = "dib-h")))
        val despues = base.copy(hojas = listOf(Hoja(id = "h", nombre = "Planta", dibujo = "dib-h")))
        assertTrue(AvisosDelProyecto.novedades(antes, despues).isEmpty())
    }
}
