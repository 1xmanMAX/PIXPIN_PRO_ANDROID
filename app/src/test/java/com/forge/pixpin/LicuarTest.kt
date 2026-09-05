package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Licuar: empujar lo dibujado con el dedo.** Ver [Croquis3DControlador.licuar]. */
class LicuarTest {

    private val ancho = 1000.0
    private val alto = 2000.0

    private fun controlador(): Croquis3DControlador = Croquis3DControlador().apply {
        medida(ancho, alto)
        // De frente y sin inclinar: la x del mundo es la x de la pantalla.
        pedirVista(Postura(0.0, 0.0))
        andarElViaje(1f)
        cargar(
            Croquis(
                trazos = listOf(
                    Trazo3D(
                        "t",
                        (0..10).map { Pt3(-200.0 + it * 40.0, 0.0, 0.0) },
                        "#000000", 4.0
                    )
                )
            )
        )
        herramienta = Herramienta3D.LICUAR
    }

    @Test
    fun `el dedo empuja lo que tiene debajo y deja quietas las puntas`() {
        val c = controlador()
        val centro = c.camara.aPantalla(Pt3(0.0, 0.0, 0.0), ancho, alto)
        val antes = c.croquis.trazos[0].puntos
        c.tocar(centro, Fase.BAJA)
        c.tocar(Pt(centro.x, centro.y - 60.0), Fase.MUEVE)
        c.tocar(Pt(centro.x, centro.y - 60.0), Fase.LEVANTA)
        val despues = c.croquis.trazos[0].puntos
        // El punto de en medio sube (la pantalla va hacia abajo, el mundo hacia arriba).
        assertTrue("no subió: ${despues[5]}", despues[5].z > antes[5].z + 30.0)
        // Las puntas, lejos del pincel, no se mueven.
        assertEquals(antes.first(), despues.first())
        assertEquals(antes.last(), despues.last())
        // Y el resto del trazo sigue con los mismos puntos.
        assertEquals(antes.size, despues.size)
    }

    @Test
    fun `licuar se deshace de una vez`() {
        val c = controlador()
        val antes = c.croquis
        val centro = c.camara.aPantalla(Pt3(0.0, 0.0, 0.0), ancho, alto)
        c.tocar(centro, Fase.BAJA)
        c.tocar(Pt(centro.x + 30.0, centro.y), Fase.MUEVE)
        c.tocar(Pt(centro.x + 60.0, centro.y), Fase.MUEVE)
        c.tocar(Pt(centro.x + 60.0, centro.y), Fase.LEVANTA)
        assertTrue(c.croquis != antes)
        c.deshacer()
        assertEquals(antes, c.croquis)
    }

    @Test
    fun `lo escondido no se licua`() {
        val c = controlador()
        c.cargar(c.croquis.copy(trazos = c.croquis.trazos.map { it.copy(oculto = true) }))
        val antes = c.croquis
        val centro = c.camara.aPantalla(Pt3(0.0, 0.0, 0.0), ancho, alto)
        c.tocar(centro, Fase.BAJA)
        c.tocar(Pt(centro.x, centro.y - 60.0), Fase.MUEVE)
        c.tocar(Pt(centro.x, centro.y - 60.0), Fase.LEVANTA)
        assertEquals(antes.trazos, c.croquis.trazos)
    }
}
