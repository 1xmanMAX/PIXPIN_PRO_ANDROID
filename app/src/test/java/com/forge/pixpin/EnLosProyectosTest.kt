package com.forge.pixpin.motor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El punto verde de la burbuja: si eso está también en proyectos. Ver [Proyectos.estaEnLosProyectos]. */
class EnLosProyectosTest {
    private val conDibujo = Proyecto(
        id = "p1", nombre = "Obra", tocado = 0,
        hojas = listOf(Hoja(id = "h1", dibujo = "d7"))
    )
    private val conPdf = Proyecto(id = "p2", nombre = "Plano", tocado = 0, pdfOrigen = "/x/plano.pdf")

    @Test fun `un dibujo que es hoja de un proyecto esta`() {
        assertTrue(Proyectos.estaEnLosProyectos(listOf(conDibujo), dibujo = "d7"))
        assertFalse(Proyectos.estaEnLosProyectos(listOf(conDibujo), dibujo = "otro"))
    }

    @Test fun `un pdf que es el origen de un proyecto esta`() {
        assertTrue(Proyectos.estaEnLosProyectos(listOf(conPdf), pdf = "/x/plano.pdf"))
        assertFalse(Proyectos.estaEnLosProyectos(listOf(conPdf), pdf = "/x/otro.pdf"))
    }

    @Test fun `un proyecto esta mientras exista`() {
        assertTrue(Proyectos.estaEnLosProyectos(listOf(conDibujo), proyecto = "p1"))
        // Y en cuanto se borra de la zona de proyectos, deja de estar: se calcula cada vez,
        // así que el punto pasa de verde a rojo solo.
        assertFalse(Proyectos.estaEnLosProyectos(emptyList(), proyecto = "p1"))
    }

    @Test fun `sin nada que comparar no esta`() {
        assertFalse(Proyectos.estaEnLosProyectos(listOf(conDibujo, conPdf)))
    }

    @Test fun `dos archivos con el mismo nombre no se confunden`() {
        // Se compara por identificador y por ruta entera, no por el nombre suelto.
        val otro = Proyecto(id = "p3", nombre = "x", tocado = 0, pdfOrigen = "/otra/carpeta/plano.pdf")
        assertFalse(Proyectos.estaEnLosProyectos(listOf(otro), pdf = "/x/plano.pdf"))
    }
}
