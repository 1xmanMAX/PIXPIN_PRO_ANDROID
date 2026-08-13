package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué ajustes se ofrecen en cada momento.
 *
 * Es la tabla que decide si el panel de estilos enseña algo útil o una parrilla
 * de botones que no hacen nada. Se comprueba entera porque el fallo típico es
 * silencioso: una herramienta nueva que no aparece en el `when` se queda sin
 * ajustes y nadie se entera hasta que alguien la usa.
 */
class DrawPropertiesTest {

    private fun elemento(tipo: ElementType, grupos: List<String> = emptyList()) = Element(
        id = "e$tipo", type = tipo, x = 0.0, y = 0.0, width = 10.0, height = 10.0,
        seed = 1, groupIds = grupos
    )

    // ---- Sin nada seleccionado manda la herramienta ----

    @Test
    fun `las herramientas que no dibujan no ofrecen ajustes`() {
        for (t in listOf(Tool.SELECTION, Tool.LASSO, Tool.HAND, Tool.ERASER)) {
            assertTrue(
                "$t no crea nada: el panel tiene que desaparecer",
                propiedadesPara(t, emptyList()).isEmpty()
            )
        }
    }

    @Test
    fun `con el rectangulo activo se ofrece lo del rectangulo`() {
        val p = propiedadesPara(Tool.RECTANGLE, emptyList())
        assertTrue(Propiedad.ESQUINAS in p)
        assertTrue(Propiedad.RELLENO in p)
        assertTrue(Propiedad.FUENTE !in p)
        assertTrue(Propiedad.PUNTAS !in p)
    }

    /**
     * El lápiz no pasa por rough.js: ofrecerle rugosidad es un botón muerto.
     *
     * Fondo y relleno **sí**, y no por completismo: un garabato cerrado se
     * rellena solo, así que sin estos dos controles no hay forma de quitarle la
     * trama. Quitarlos dejó la trama puesta y sin interruptor.
     */
    @Test
    fun `el lapiz ofrece color fondo relleno grosor y opacidad`() {
        assertEquals(
            setOf(
                Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO,
                Propiedad.GROSOR, Propiedad.OPACIDAD
            ),
            propiedadesPara(Tool.FREEDRAW, emptyList())
        )
        assertTrue(Propiedad.RUGOSIDAD !in propiedadesPara(Tool.FREEDRAW, emptyList()))
        assertEquals(
            propiedadesPara(Tool.FREEDRAW, emptyList()),
            propiedadesPara(Tool.HIGHLIGHTER, emptyList())
        )
    }

    @Test
    fun `solo la flecha ofrece puntas`() {
        assertTrue(Propiedad.PUNTAS in propiedadesPara(Tool.ARROW, emptyList()))
        for (t in Tool.entries.filter { it != Tool.ARROW }) {
            assertTrue("$t no debería ofrecer puntas", Propiedad.PUNTAS !in propiedadesPara(t, emptyList()))
        }
    }

    @Test
    fun `la elipse no ofrece esquinas`() {
        assertTrue(Propiedad.ESQUINAS !in propiedadesPara(Tool.ELLIPSE, emptyList()))
    }

    @Test
    fun `el texto ofrece fuente y no relleno`() {
        val p = propiedadesPara(Tool.TEXT, emptyList())
        assertTrue(Propiedad.FUENTE in p)
        assertTrue(Propiedad.RELLENO !in p)
    }

    /** El foco solo gradúa cuánto oscurece; el mosaico, el grano. */
    @Test
    fun `las herramientas propias ofrecen lo justo`() {
        assertEquals(setOf(Propiedad.OPACIDAD), propiedadesPara(Tool.SPOTLIGHT, emptyList()))
        // El mosaico añade elegir entre bloques y mancha: el campo existía en el
        // modelo desde el principio y no había forma de tocarlo.
        assertEquals(
            setOf(Propiedad.GROSOR, Propiedad.MOSAICO, Propiedad.OPACIDAD),
            propiedadesPara(Tool.MOSAIC, emptyList())
        )
        assertTrue(Propiedad.FUENTE in propiedadesPara(Tool.SERIAL, emptyList()))
    }

    /**
     * Toda herramienta que dibuja tiene que ofrecer **algo**.
     *
     * Con una excepción: la hoja no es un dibujo sino un límite, así que no
     * tiene color, ni grosor, ni relleno. Lo único que se le hace es estirarla.
     */
    @Test
    fun `ninguna herramienta que dibuja se queda sin ajustes`() {
        for (t in Tool.entries) {
            if (tipoQueCrea(t) == null || t == Tool.FRAME) continue
            assertTrue("$t dibuja pero no ofrece nada", propiedadesPara(t, emptyList()).isNotEmpty())
        }
    }

    @Test
    fun `la hoja no ofrece estilo porque no es un dibujo`() {
        assertTrue(propiedadesPara(Tool.FRAME, emptyList()).isEmpty())
    }

    // ---- Con selección manda la selección ----

    @Test
    fun `la seleccion manda sobre la herramienta activa`() {
        // Herramienta de rectángulo, pero lo seleccionado es un texto.
        val p = propiedadesPara(Tool.RECTANGLE, listOf(elemento(ElementType.TEXT)))
        assertTrue(Propiedad.FUENTE in p)
        assertTrue(Propiedad.RELLENO !in p)
    }

    @Test
    fun `con varios elementos se ofrece la union`() {
        val p = propiedadesPara(
            Tool.SELECTION,
            listOf(elemento(ElementType.TEXT), elemento(ElementType.RECTANGLE))
        )
        assertTrue(Propiedad.FUENTE in p)
        assertTrue(Propiedad.ESQUINAS in p)
    }

    // ---- Las acciones ----

    @Test
    fun `sin seleccion no hay acciones`() {
        assertTrue(gruposPara(emptyList()).isEmpty())
    }

    @Test
    fun `con uno solo no se puede agrupar ni alinear`() {
        val g = gruposPara(listOf(elemento(ElementType.RECTANGLE)))
        assertTrue(GrupoAcciones.ORDEN in g)
        assertTrue(GrupoAcciones.VOLTEO in g)
        assertTrue(GrupoAcciones.AGRUPAR !in g)
        assertTrue(GrupoAcciones.ALINEAR !in g)
    }

    @Test
    fun `con dos o mas si`() {
        val g = gruposPara(List(2) { elemento(ElementType.RECTANGLE) })
        assertTrue(GrupoAcciones.AGRUPAR in g)
        assertTrue(GrupoAcciones.ALINEAR in g)
    }

    /** Desagrupar tiene que estar aunque hayas tocado un solo miembro. */
    @Test
    fun `un elemento ya agrupado ofrece desagrupar`() {
        val g = gruposPara(listOf(elemento(ElementType.RECTANGLE, grupos = listOf("g1"))))
        assertTrue(GrupoAcciones.AGRUPAR in g)
        assertTrue(GrupoAcciones.ALINEAR !in g)
    }
}
