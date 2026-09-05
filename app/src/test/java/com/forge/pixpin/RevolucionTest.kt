package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * **El torno**: un perfil dado la vuelta alrededor de una raya. Ver [revolucionado].
 *
 * Lo que se comprueba es lo que le importa a un plano de ingeniería: que la punta de un
 * cono sea una punta, que la proporción sea la que se dibujó, que un escalón siga siendo un
 * escalón y que una pieza sencilla no arrastre anillos que no hacen falta.
 */
class RevolucionTest {

    /** El eje vertical de toda la vida, de arriba abajo por `x = 0`. */
    private fun eje(x: Double = 0.0, alto: Double = 200.0): Element =
        newElement(ElementType.LINE, x, 0.0, ItemStyle())
            .copy(points = listOf(Pt(0.0, 0.0), Pt(0.0, alto)))

    private fun perfil(vararg puntos: Pt): Element {
        val origen = puntos.first()
        return newElement(ElementType.LINE, origen.x, origen.y, ItemStyle())
            .copy(points = puntos.map { Pt(it.x - origen.x, it.y - origen.y) })
    }

    private fun torneado(figura: Element, eje: Element?): Element {
        val cuerpo = revolucionado(figura, eje, Vista.CERO)
        assertNotNull("no ha salido pieza", cuerpo)
        return cuerpo!!
    }

    // ---------------------------------------------------------------------
    // La punta de la pieza
    // ---------------------------------------------------------------------

    @Test
    fun `un cono acaba en punta y no en plano`() {
        // Un triángulo apoyado en el eje: base ancha abajo, vértice sobre el eje arriba.
        val cono = torneado(
            perfil(Pt(0.0, 200.0), Pt(60.0, 200.0), Pt(0.0, 0.0)),
            eje()
        )
        val p = cono.points!!
        // El perfil va de pie a cabeza: el radio de arriba tiene que ser cero.
        assertTrue(
            "la punta del cono salía con radio ${p.last().x}",
            p.last().x < 0.02
        )
        assertTrue("la base tiene que ser ancha", p.first().x > 0.9)
    }

    @Test
    fun `una media circunferencia da una esfera, con sus dos polos`() {
        // Media vuelta a la derecha del eje, de arriba abajo.
        val medio = (0..24).map { k ->
            val t = -Math.PI / 2 + Math.PI * k / 24
            Pt(100.0 * cos(t), 100.0 + 100.0 * sin(t))
        }
        val esfera = torneado(perfil(*medio.toTypedArray()), eje())
        val p = esfera.points!!
        assertTrue("el polo de abajo salía con radio ${p.first().x}", p.first().x < 0.05)
        assertTrue("el polo de arriba salía con radio ${p.last().x}", p.last().x < 0.05)
        // Y por el ecuador tiene que estar a todo radio.
        assertTrue("el ecuador se ha perdido", p.any { it.x > 0.95 })
    }

    // ---------------------------------------------------------------------
    // La proporción
    // ---------------------------------------------------------------------

    @Test
    fun `la altura es la que se dibujo, no la de la reticula`() {
        // Un rectángulo pegado al eje: sale un cilindro de 137 de alto y 40 de radio.
        val cilindro = torneado(
            perfil(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 137.0), Pt(0.0, 137.0)),
            eje(alto = 137.0)
        )
        assertEquals(137.0, cilindro.altura!!, 1e-6)
        assertEquals(80.0, cilindro.width, 1e-6)
    }

    // ---------------------------------------------------------------------
    // La forma
    // ---------------------------------------------------------------------

    @Test
    fun `un escalon sigue siendo un escalon`() {
        // Una arandela con dos diámetros: 30 abajo, 70 arriba, con su hombro.
        val pieza = torneado(
            perfil(
                Pt(0.0, 200.0), Pt(30.0, 200.0), Pt(30.0, 120.0),
                Pt(70.0, 120.0), Pt(70.0, 0.0), Pt(0.0, 0.0)
            ),
            eje()
        )
        val p = pieza.points!!
        // El salto de radio tiene que seguir estando, y de golpe: dos anillos seguidos con
        // radios muy distintos. Redondeado, el máximo salto entre vecinos sería pequeño.
        val mayorSalto = p.zipWithNext().maxOf { (a, b) -> abs(b.x - a.x) }
        assertTrue("el hombro salió redondeado: el mayor salto fue $mayorSalto", mayorSalto > 0.3)
    }

    @Test
    fun `un cilindro no arrastra anillos que no hacen falta`() {
        val cilindro = torneado(
            perfil(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 200.0), Pt(0.0, 200.0)),
            eje()
        )
        // Un cilindro es dos anillos. Con unos pocos de más sigue bien; con dieciséis, no.
        val anillos = anillosDe(solidoDe(cilindro)).size
        assertTrue("un cilindro salía con $anillos anillos", anillos <= 4)
    }

    @Test
    fun `un perfil que se pasa un pelo al otro lado no inventa radio`() {
        // Un rectángulo a la derecha del eje, con una esquina colada dos unidades al otro
        // lado: la mano temblando al cruzar la raya.
        val conTemblor = torneado(
            perfil(Pt(-2.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 200.0), Pt(0.0, 200.0)),
            eje()
        )
        val limpio = torneado(
            perfil(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 200.0), Pt(0.0, 200.0)),
            eje()
        )
        assertEquals(limpio.width, conTemblor.width, 1e-6)
    }

    @Test
    fun `un perfil dibujado a los dos lados se respeta`() {
        // Simétrico a propósito: una raya horizontal que cruza el eje de lado a lado.
        val pieza = torneado(
            perfil(Pt(-50.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 200.0), Pt(-50.0, 200.0)),
            eje()
        )
        assertEquals("el radio es 50 a cada lado", 100.0, pieza.width, 1e-6)
    }

    @Test
    fun `sin eje sigue funcionando un perfil suelto`() {
        assertNotNull(
            revolucionado(
                perfil(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 200.0)), null, Vista.CERO
            )
        )
    }
}
