package com.forge.pixpin

import com.forge.pixpin.ui.theme.NombresCortos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los nombres de los botones de barra: cortos y en una línea, cambiados y no cortados. */
class NombresCortosTest {

    @Test
    fun `ningún nombre de la tabla se pasa ni lleva dos líneas`() {
        for ((largo, corto) in NombresCortos.tabla) {
            assertTrue("$largo → $corto", corto.length <= NombresCortos.LARGO_MAXIMO)
            assertTrue(!corto.contains('\n'))
        }
    }

    @Test
    fun `lo corto se queda, lo largo se cambia`() {
        assertEquals("Mover", NombresCortos.de("Mover"))
        assertEquals("Elegir", NombresCortos.de("Seleccionar"))
        assertEquals("Pantalla", NombresCortos.de("Pantalla completa"))
        // Sin entrada en la tabla: la primera palabra.
        assertEquals("Añadir", NombresCortos.de("Añadir una hoja nueva"))
        assertTrue(NombresCortos.de("Supercalifragilístico").length <= NombresCortos.LARGO_MAXIMO)
    }
}
