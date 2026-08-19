package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El plano cartesiano.
 *
 * Lo que se defiende aquí es **la diferencia entre sus dos formas de agrandarse**,
 * que es lo que lo separa de unos ejes dibujados: por un lado aparecen más
 * números, por una esquina los mismos repartidos en más sitio. Es geometría, y se
 * puede equivocar en silencio: un eje con los números descolocados medio cuadro
 * no se ve mal, se lee mal.
 */
class PlanoCartesianoTest {

    private fun plano(lado: Double = 400.0, unidad: Double = 40.0) = Element(
        id = "p", type = ElementType.PLANO, x = 0.0, y = 0.0,
        width = lado, height = lado, seed = 1,
        unidad = unidad, pasoDeNumeros = 1.0, pasoDeCuadros = 1.0
    )

    /** Cuántos números distintos salen en la regla de abajo. */
    private fun numerosEnX(e: Element) =
        numerosDelPlano(e).filter { it.horizontal }.map { it.texto }

    @Test
    fun `el origen cae en el centro de su caja`() {
        val o = origenDelPlano(plano())
        assertEquals(200.0, o.x, 1e-9)
        assertEquals(200.0, o.y, 1e-9)
    }

    /** Con unidad 40 y caja 400, se ven cinco unidades a cada lado. */
    @Test
    fun `los numeros salen cada paso de unidad`() {
        val textos = numerosEnX(plano())
        assertTrue("falta el 5: $textos", "5" in textos)
        assertTrue("falta el -5: $textos", "-5" in textos)
        assertTrue("se ha colado un 6: $textos", "6" !in textos)
        // El cero se escribe una sola vez, o se lee como un ocho.
        assertEquals(1, textos.count { it == "0" })
    }

    /**
     * **Por un lado: más números.**
     *
     * La caja crece, la unidad no, así que cabe más intervalo. Es lo que se hace
     * cuando la función se sale por la derecha.
     */
    @Test
    fun `estirar por un lado hace aparecer mas numeros`() {
        val antes = plano()
        val c = getElementAbsoluteCoords(antes)
        val despues = resizeSingleElement(antes, HandleType.E, Pt(c.x2 + 200, c.cy))

        assertEquals("la unidad no puede cambiar", 40.0, despues.unidadDelPlano, 1e-9)
        assertTrue(
            "no han aparecido números nuevos",
            numerosEnX(despues).size > numerosEnX(antes).size
        )
    }

    /**
     * **Por una esquina: los mismos, más grandes.**
     *
     * Crecen la caja y la unidad a la vez, así que el intervalo que se ve es el
     * mismo. Es lo que se hace cuando el plano no se lee.
     */
    @Test
    fun `estirar por una esquina reparte los mismos numeros`() {
        val antes = plano()
        val c = getElementAbsoluteCoords(antes)
        val despues = resizeSingleElement(antes, HandleType.SE, Pt(c.x2 + 400, c.y2 + 400))

        assertTrue("la unidad tenía que crecer", despues.unidadDelPlano > 40.0)
        assertEquals(
            "han cambiado los números al escalar",
            numerosEnX(antes), numerosEnX(despues)
        )
    }

    /** Y el plano enseña los ocho tiradores: los del medio son los que extienden. */
    @Test
    fun `el plano enseña tambien los tiradores del medio`() {
        val tipos = getSelectionTransformHandles(listOf(plano()), 1.0).map { it.type }
        assertTrue("faltan los de los lados: $tipos", HandleType.E in tipos)
        assertTrue(HandleType.N in tipos)
        assertTrue(HandleType.SE in tipos)
    }

    /**
     * Se le puede preguntar dónde cae un punto, que es para lo que sirve: sin
     * esto sería un dibujo bonito y habría que contar cuadros con el dedo.
     */
    @Test
    fun `dice donde cae un punto de coordenadas`() {
        val e = plano()
        val p = puntoDelPlano(e, 3.0, -2.0)
        assertEquals(200.0 + 3 * 40.0, p.x, 1e-9)
        // La Y del plano crece hacia arriba y la de la escena hacia abajo.
        assertEquals(200.0 + 2 * 40.0, p.y, 1e-9)
    }

    /**
     * **El paso se ensancha antes de cerrarse.**
     *
     * Es la guarda de rendimiento y la de legibilidad: pedir «de uno en uno» en
     * un plano alejadísimo son rayas a medio píxel, y lo que queda es una mancha.
     */
    @Test
    fun `un paso que no cabe se dobla`() {
        assertEquals(1.0, pasoUtil(1.0, 40.0, 6.0), 1e-9)
        val ensanchado = pasoUtil(1.0, 2.0, 6.0)
        assertTrue("no se ha ensanchado: $ensanchado", ensanchado > 1.0)
        assertTrue("y tiene que caber", ensanchado * 2.0 >= 6.0)
    }

    @Test
    fun `un plano sin tamaño no dibuja nada`() {
        val vacio = plano(lado = 0.0)
        assertTrue(trazosDelPlano(vacio).isEmpty())
        assertTrue(numerosDelPlano(vacio).isEmpty())
    }
}
