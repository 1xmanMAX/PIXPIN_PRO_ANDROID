package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motor del imán.
 *
 * Lo que se comprueba aquí es sobre todo **que no haya que acordarse de nada**:
 * antes, seis sitios decidían por su cuenta si enganchaban y con qué, así que
 * cada herramienta nueva había que añadirla a esa lista a mano y a alguna se le
 * olvidaba. La única forma de encontrar un olvido era tropezarse con él usando
 * la app.
 */
class ImanTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = b.x - a.x, height = b.y - a.y, seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    private fun escenaConUnaLinea() = Scene(
        elements = listOf(linea("l", Pt(0.0, 0.0), Pt(200.0, 0.0)))
    )

    // ---- Lo que el usuario apaga se queda apagado ----

    /**
     * **Ninguna faena puede encender lo que se apagó a mano.**
     *
     * Puede quitar cosas —el lápiz enciende menos que una forma— pero nunca
     * añadir. Al revés sería un ajuste que no se obedece, que es peor que no
     * tenerlo.
     */
    @Test
    fun `con el imán apagado no engancha ninguna faena`() {
        val apagado = AjustesEnganche(activo = false)
        for (faena in Iman.Faena.entries) {
            assertNull(
                "$faena engancha con el imán apagado",
                Iman.sitio(escenaConUnaLinea(), Pt(2.0, 2.0), 1.0, faena, apagado)
            )
        }
    }

    @Test
    fun `apagar una clase la apaga en todas las faenas`() {
        val sinEsquinas = AjustesEnganche(esquinas = false)
        for (faena in Iman.Faena.entries) {
            assertTrue(
                "$faena sigue con las esquinas encendidas",
                !Iman.ajustesPara(faena, sinEsquinas).esquinas
            )
        }
    }

    /** Y el radio configurado se respeta, aunque la faena lo escale. */
    @Test
    fun `el radio de cada faena sale del configurado`() {
        val ancho = AjustesEnganche(radio = 20.0)
        assertEquals(20.0, Iman.ajustesPara(Iman.Faena.TRAZANDO, ancho).radio, 0.001)
        assertTrue(
            "moviendo tiene que ser más generoso",
            Iman.ajustesPara(Iman.Faena.MOVIENDO, ancho).radio > 20.0
        )
    }

    // ---- Cada faena engancha a lo suyo ----

    @Test
    fun `trazando engancha al extremo de una recta`() {
        val a = Iman.sitio(
            escenaConUnaLinea(), Pt(196.0, 4.0), 1.0, Iman.Faena.TRAZANDO, AjustesEnganche()
        )
        assertNotNull("no se ha pegado al extremo", a)
        assertEquals(200.0, a!!.punto.x, 0.001)
        assertEquals(0.0, a.punto.y, 0.001)
    }

    /**
     * A mano alzada no engancha a los vértices, y es a propósito: un trazo que
     * salta a un vértice en mitad del recorrido no se corrige, **se rompe**.
     */
    @Test
    fun `a mano alzada no salta a los vértices`() {
        assertNull(
            Iman.sitio(
                escenaConUnaLinea(), Pt(198.0, 2.0), 1.0, Iman.Faena.A_MANO, AjustesEnganche()
            )
        )
    }

    /** La faena del punto etiquetado exige un sitio con nombre. */
    @Test
    fun `un sitio notable solo acepta lo que puede llevar nombre`() {
        val escena = escenaConUnaLinea()
        // El extremo vale.
        assertNotNull(
            Iman.sitio(escena, Pt(197.0, 3.0), 1.0, Iman.Faena.SITIO_NOTABLE, AjustesEnganche())
        )
        // En medio de la nada, no.
        assertNull(
            Iman.sitio(escena, Pt(800.0, 800.0), 1.0, Iman.Faena.SITIO_NOTABLE, AjustesEnganche())
        )
    }

    // ---- El borde, que volvió ----

    /**
     * **Se puede clavar algo sobre un lado.** Estuvo restringido a las guías y
     * el usuario lo echó de menos: el pie de una altura, un punto de tangencia
     * o el sitio por donde cortar están sobre el lado, no en un vértice.
     */
    @Test
    fun `el borde de una figura normal engancha`() {
        val a = Iman.sitio(
            escenaConUnaLinea(), Pt(100.0, 4.0), 1.0, Iman.Faena.TRAZANDO, AjustesEnganche()
        )
        assertNotNull("no engancha al lado", a)
        assertEquals(TipoAnclaje.BORDE, a!!.tipo)
        assertEquals(0.0, a.punto.y, 0.001)
    }

    /** Pero nunca le quita el sitio a un vértice: va el último de todos. */
    @Test
    fun `el borde no le gana a un extremo`() {
        val a = Iman.sitio(
            escenaConUnaLinea(), Pt(197.0, 3.0), 1.0, Iman.Faena.TRAZANDO, AjustesEnganche()
        )!!
        assertTrue("el borde le ha ganado al extremo", a.tipo != TipoAnclaje.BORDE)
    }

    /** Y se puede apagar por su cuenta, que es lo que pidió quien lo encuentre un estorbo. */
    @Test
    fun `el borde de las figuras se puede apagar`() {
        assertNull(
            Iman.sitio(
                escenaConUnaLinea(), Pt(100.0, 4.0), 1.0, Iman.Faena.TRAZANDO,
                AjustesEnganche(bordeDeFigura = false)
            )
        )
    }
}
