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
    private val delMensaje = Proyecto(
        id = "p2", nombre = "Plano", tocado = 0,
        hojas = listOf(Hoja(id = "h1", pagina = 0, deMensaje = "m42"))
    )

    @Test fun `un dibujo que es hoja de un proyecto esta`() {
        assertTrue(Proyectos.estaEnLosProyectos(listOf(conDibujo), dibujo = "d7"))
        assertFalse(Proyectos.estaEnLosProyectos(listOf(conDibujo), dibujo = "otro"))
    }

    /**
     * **Lo que de verdad ata una hoja a su mensaje.**
     *
     * Al unir algo a un proyecto el archivo se copia y el dibujo también, con identificador
     * nuevo: ni la ruta ni el identificador del mensaje sobreviven. Sin la seña, el punto
     * salía rojo en todo (usuario, 9-sep-2026). Ver [Hoja.deMensaje].
     */
    @Test fun `una hoja unida recuerda su mensaje`() {
        assertTrue(Proyectos.estaEnLosProyectos(listOf(delMensaje), mensaje = "m42"))
        assertFalse(Proyectos.estaEnLosProyectos(listOf(delMensaje), mensaje = "otro"))
    }

    @Test fun `un pdf copiado a un proyecto ya no se reconoce por su ruta`() {
        // Se copia a la carpeta de proyectos con otro nombre, así que la ruta del chat no
        // vale: por eso hace falta la seña del mensaje y no una comparación de rutas.
        assertFalse(Proyectos.estaEnLosProyectos(listOf(delMensaje), dibujo = "/x/plano.pdf"))
    }

    @Test fun `un proyecto esta mientras exista`() {
        assertTrue(Proyectos.estaEnLosProyectos(listOf(conDibujo), proyecto = "p1"))
        // Y en cuanto se borra de la zona de proyectos, deja de estar: se calcula cada vez,
        // así que el punto pasa de verde a rojo solo.
        assertFalse(Proyectos.estaEnLosProyectos(emptyList(), proyecto = "p1"))
    }

    @Test fun `sin nada que comparar no esta`() {
        assertFalse(Proyectos.estaEnLosProyectos(listOf(conDibujo, delMensaje)))
    }
}
