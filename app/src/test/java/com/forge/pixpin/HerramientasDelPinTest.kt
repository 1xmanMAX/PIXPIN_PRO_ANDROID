package com.forge.pixpin.data

import com.forge.pixpin.motor.ALL_TOOLS
import com.forge.pixpin.motor.EXTRA_TOOLS
import com.forge.pixpin.motor.MAIN_TOOLS
import com.forge.pixpin.motor.PIN_TOOLS_POR_DEFECTO
import com.forge.pixpin.motor.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué herramientas del motor se quedan en el pin.
 *
 * El pin y el editor a pantalla completa comparten motor, así que **todo lo que
 * se le añade al motor aparece también en una barra flotante de dos dedos de
 * ancho**. Esta es la lista que decide qué va allí y qué se queda solo en la
 * edición avanzada; lo que se rompe fácil es la traducción entre lo guardado
 * —nombres sueltos en disco— y las herramientas de verdad.
 */
class HerramientasDelPinTest {

    /** Sin tocar los ajustes manda la lista de fábrica. */
    @Test
    fun `sin elegir nada valen las de siempre`() {
        assertEquals(PIN_TOOLS_POR_DEFECTO, Settings().pinToolSet)
        assertEquals(PIN_TOOLS_POR_DEFECTO, Settings(pinTools = null).pinToolSet)
    }

    /**
     * **Vacío no es lo mismo que sin elegir.** Quien quita todas las
     * herramientas del pin ha dicho algo, y devolverle las de fábrica sería
     * ignorarlo.
     */
    @Test
    fun `elegir ninguna se respeta`() {
        assertEquals(emptySet<Tool>(), Settings(pinTools = emptySet()).pinToolSet)
    }

    @Test
    fun `lo guardado se traduce a herramientas`() {
        val ajustes = Settings(pinTools = setOf("ARROW", "TEXT"))
        assertEquals(setOf(Tool.ARROW, Tool.TEXT), ajustes.pinToolSet)
    }

    /**
     * Un nombre de una versión que ya no existe no puede tirar los ajustes
     * enteros: se ignora y las demás siguen valiendo.
     */
    @Test
    fun `un nombre desconocido se ignora sin arrastrar a los demás`() {
        val ajustes = Settings(pinTools = setOf("ARROW", "PLANCHA_DE_VAPOR"))
        assertEquals(setOf(Tool.ARROW), ajustes.pinToolSet)
    }

    // ---- La lista de fábrica ----

    @Test
    fun `las de fábrica existen todas en la barra`() {
        val fuera = PIN_TOOLS_POR_DEFECTO - ALL_TOOLS.toSet()
        assertTrue("no están en ninguna fila de la barra: $fuera", fuera.isEmpty())
    }

    /**
     * En el pin la vista no se encuadra —la imagen está siempre entera— y el
     * lazo pide una precisión que ahí no se tiene.
     */
    @Test
    fun `la mano y el lazo no van al pin`() {
        assertFalse(Tool.HAND in PIN_TOOLS_POR_DEFECTO)
        assertFalse(Tool.LASSO in PIN_TOOLS_POR_DEFECTO)
    }

    /** Señalar y tapar es a lo que se viene: eso sí tiene que estar. */
    @Test
    fun `lo que se usa sobre una captura sí va al pin`() {
        for (t in listOf(Tool.ARROW, Tool.RECTANGLE, Tool.TEXT, Tool.MOSAIC, Tool.ERASER)) {
            assertTrue("$t debería venir puesta", t in PIN_TOOLS_POR_DEFECTO)
        }
    }

    /**
     * Si el pin las llevara todas, no habría edición avanzada que valga: la
     * gracia es que el pin lleve lo justo.
     */
    @Test
    fun `algo se queda para la edición avanzada`() {
        assertTrue(PIN_TOOLS_POR_DEFECTO.size < ALL_TOOLS.size)
    }

    // ---- Lo demás que se elige en los ajustes ----

    /** La letra de la edición simple sale resuelta, nunca en un número raro. */
    @Test
    fun `la letra del pin cae siempre en una familia real`() {
        assertEquals(
            com.forge.pixpin.motor.ItemStyle.FONT_EXCALIFONT,
            Settings().pinFont
        )
        assertTrue(Settings().pinFont in com.forge.pixpin.motor.ItemStyle.FONT_FAMILIES)
    }

    /**
     * Las dos formas de copiar son **sin pérdida**: lo que cambia es cuánto
     * ocupa y quién sabe abrirlo, no lo que se ve.
     */
    @Test
    fun `copiar viene en png y la otra opción también es sin pérdida`() {
        assertEquals(CopyFormat.PNG, Settings().copyFormat)
        assertEquals("png", CopyFormat.PNG.extension)
        assertEquals("webp", CopyFormat.WEBP.extension)
        assertEquals("image/png", CopyFormat.PNG.mime)
        assertEquals("image/webp", CopyFormat.WEBP.mime)
    }

    @Test
    fun `la lista de la barra son las dos filas, sin repetir`() {
        assertEquals(MAIN_TOOLS + EXTRA_TOOLS, ALL_TOOLS)
        assertEquals(ALL_TOOLS.size, ALL_TOOLS.toSet().size)
    }
}
