package com.forge.pixpin.motor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** El resumen de un lienzo se recuerda, y se rehace cuando el archivo cambia. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumenDeLienzoTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `marcos y vacio, y se rehace al cambiar`() {
        val ruta = ExcalidrawStore.guardar(context, "res-1", Scene())!!
        val vacio = ExcalidrawStore.resumenDe(ruta)!!
        assertTrue(vacio.vacio); assertEquals(0, vacio.marcos.size)

        val marco = Element(id = "m", type = ElementType.FRAME, x = 0.0, y = 0.0, width = 10.0, height = 10.0, seed = 1)
        val raya = Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 5.0, height = 5.0, seed = 2)
        // Que el sello cambie de verdad: la fecha del archivo va en milisegundos y a veces en segundos.
        File(ruta).setLastModified(System.currentTimeMillis() + 5000)
        ExcalidrawStore.guardar(context, "res-1", Scene(elements = listOf(marco, raya)))
        File(ruta).setLastModified(System.currentTimeMillis() + 10000)
        val lleno = ExcalidrawStore.resumenDe(ruta)!!
        assertFalse(lleno.vacio)
        assertEquals(1, lleno.marcos.size)
        assertEquals(1, lleno.soloMarcos.marcos.size)
    }
}
