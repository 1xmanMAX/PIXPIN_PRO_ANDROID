package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El reparto de herramientas entre la fila principal y el desplegable.
 *
 * Es lo que más fácil se rompe al añadir una herramienta: se mete el tipo, se
 * pinta, se le da gesto… y se olvida meterla en la barra, con lo que queda
 * inalcanzable sin que nada falle.
 */
class DrawToolbarTest {

    /** La imagen no va en las listas: la pone quien tenga selector de archivos. */
    private val fueraDeLasListas = setOf(Tool.IMAGE)

    @Test
    fun `todas las herramientas están en la barra`() {
        val enLaBarra = (MAIN_TOOLS + EXTRA_TOOLS).toSet()
        val faltan = Tool.entries.toSet() - enLaBarra - fueraDeLasListas
        assertTrue("inalcanzables desde la barra: $faltan", faltan.isEmpty())
    }

    @Test
    fun `ninguna herramienta está en los dos sitios`() {
        val repetidas = MAIN_TOOLS.intersect(EXTRA_TOOLS.toSet())
        assertTrue("en la fila y en el desplegable: $repetidas", repetidas.isEmpty())
    }

    /**
     * El orden de la fila principal es el de `Toolbar.tsx`: mano y selección,
     * las formas de más a menos usada, el lápiz, el texto y el borrador.
     * **Copiarlo es el objetivo**, así que se comprueba tal cual.
     */
    @Test
    fun `la fila principal sigue el orden del original`() {
        assertEquals(
            listOf(
                Tool.HAND, Tool.SELECTION,
                Tool.RECTANGLE, Tool.DIAMOND, Tool.ELLIPSE, Tool.ARROW, Tool.LINE,
                Tool.FREEDRAW, Tool.TEXT, Tool.ERASER
            ),
            MAIN_TOOLS
        )
    }

    /**
     * La fila en reposo tiene que caber en un móvil estrecho. Con once botones
     * de 38 dp más los separadores va justa; a partir de ahí se desborda y
     * vuelve el problema que esto venía a arreglar.
     */
    @Test
    fun `la fila principal no crece sin control`() {
        assertTrue("demasiadas a la vista: ${MAIN_TOOLS.size}", MAIN_TOOLS.size <= 11)
        assertTrue("el desplegable está vacío", EXTRA_TOOLS.isNotEmpty())
    }

    /** Las tres propias de PixPin viven en el desplegable, no en la fila. */
    @Test
    fun `las herramientas propias van al desplegable`() {
        for (t in listOf(Tool.MOSAIC, Tool.SPOTLIGHT, Tool.SERIAL)) {
            assertTrue("$t no debería estar a la vista", t !in MAIN_TOOLS)
            assertTrue("$t tiene que estar en el desplegable", t in EXTRA_TOOLS)
        }
    }

    @Test
    fun `todas tienen icono y nombre`() {
        for (t in Tool.entries) {
            assertNotNull("$t sin icono", iconFor(t))
            assertTrue("$t sin nombre", labelFor(t) != 0)
        }
    }

    // ---- La paleta ----

    @Test
    fun `los colores de la paleta se traducen al formato del excalidraw`() {
        for (argb in DRAW_PALETTE) {
            val hex = argb.toHexColor()
            assertTrue("«$hex» no es #rrggbb", Regex("^#[0-9a-f]{6}$").matches(hex))
        }
        assertEquals("#1e1e1e", DRAW_PALETTE.first().toHexColor())
        assertEquals("#ffffff", 0xFFFFFFFF.toInt().toHexColor())
    }

    // ---- Las fuentes ----

    /**
     * Los números de familia van en el `.excalidraw` y deciden con qué letra se
     * reabre un dibujo hecho en la web: no se pueden reordenar ni inventar.
     */
    @Test
    fun `las familias llevan los números del original`() {
        assertEquals(5, ItemStyle.FONT_EXCALIFONT)
        assertEquals(6, ItemStyle.FONT_NUNITO)
        assertEquals(8, ItemStyle.FONT_COMIC_SHANNS)
        assertEquals(listOf(5, 6, 8), ItemStyle.FONT_FAMILIES)
    }

    /** Un dibujo viejo tiene que seguir viéndose con la letra que le tocaba. */
    @Test
    fun `la numeración vieja sigue valiendo`() {
        assertEquals(ItemStyle.FONT_EXCALIFONT, ItemStyle.fontFamilyResuelta(1))
        assertEquals(ItemStyle.FONT_NUNITO, ItemStyle.fontFamilyResuelta(2))
        assertEquals(ItemStyle.FONT_COMIC_SHANNS, ItemStyle.fontFamilyResuelta(3))
        assertEquals(ItemStyle.FONT_EXCALIFONT, ItemStyle.fontFamilyResuelta(null))
        // El 4 quedó libre y el 7 es una fuente que no traemos: a la de a mano.
        assertEquals(ItemStyle.FONT_EXCALIFONT, ItemStyle.fontFamilyResuelta(4))
        assertEquals(ItemStyle.FONT_EXCALIFONT, ItemStyle.fontFamilyResuelta(7))
    }
}
