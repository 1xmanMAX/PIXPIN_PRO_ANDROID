package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las decisiones de sincronizar, sin red y sin dos aparatos.**
 *
 * Aquí está lo que se puede hacer mal sin que se note hasta que alguien pierde un plano: quién
 * gana un empate, qué pasa con lo borrado, dónde cae lo que llega. Ver [Diferencia] y
 * `docs/plan-sincronizacion.md`.
 */
class SincroTest {

    private fun ap(
        sena: String, resumen: String, creado: Long = 0, borrado: Boolean = false
    ) = Diferencia.Apunte(sena, creado, creado, resumen, borrado)

    // ---------------------------------------------------------------- la seña

    @Test
    fun `la sena junta el numero con la letra del aparato`() {
        assertEquals("47a", Sena.de(47, 'a'))
        assertEquals(47, Sena.numeroDe("47a"))
        assertEquals('a', Sena.letraDe("47a"))
    }

    @Test
    fun `un mensaje sin numero no tiene sena`() {
        // Cero es de antes de que los números existieran: inventarle un `0a` lo haría chocar
        // con todos los ceros de todos los aparatos, que es peor que no sincronizarlo.
        assertNull(Sena.de(0, 'a'))
        assertNull(Sena.numeroDe("0a"))
        assertNull(Sena.numeroDe("hola"))
        assertNull(Sena.letraDe("47"))
    }

    @Test
    fun `dos aparatos nunca reparten la misma sena`() {
        // Es la propiedad de la que cuelga todo: sin coordinarse, cada uno con su letra.
        val delTelefono = (1..50).mapNotNull { Sena.de(it, 'a') }.toSet()
        val deLaTableta = (1..50).mapNotNull { Sena.de(it, 't') }.toSet()
        assertTrue(delTelefono.intersect(deLaTableta).isEmpty())
    }

    @Test
    fun `la letra se reparte sola y salta las ocupadas`() {
        assertEquals('a', Sena.libre(emptySet()))
        assertEquals('c', Sena.libre(setOf('a', 'b')))
        assertNull("veintiséis aparatos son todos", Sena.libre(Sena.LETRAS.toSet()))
    }

    // ---------------------------------------------------- lo que hay que hacer

    @Test
    fun `lo que solo tiene el otro me lo traigo`() {
        val p = Diferencia.plan(mio = emptyList(), suyo = listOf(ap("1t", "x")))
        assertEquals(listOf(Diferencia.Paso.Traer("1t")), p)
    }

    @Test
    fun `lo que solo tengo yo se lo mando`() {
        val p = Diferencia.plan(mio = listOf(ap("1a", "x")), suyo = emptyList())
        assertEquals(listOf(Diferencia.Paso.Mandar("1a")), p)
    }

    @Test
    fun `lo identico no mueve nada`() {
        val p = Diferencia.plan(listOf(ap("1a", "x")), listOf(ap("1a", "x")))
        assertTrue("sincronizar dos veces seguidas no manda nada", p.isEmpty())
    }

    @Test
    fun `si cambio solo uno gana ese sin preguntar`() {
        // El caso corriente. Preguntar aquí solo molesta.
        val base = mapOf("1a" to "viejo")
        assertEquals(
            listOf(Diferencia.Paso.Traer("1a")),
            Diferencia.plan(listOf(ap("1a", "viejo")), listOf(ap("1a", "nuevo")), base)
        )
        assertEquals(
            listOf(Diferencia.Paso.Mandar("1a")),
            Diferencia.plan(listOf(ap("1a", "nuevo")), listOf(ap("1a", "viejo")), base)
        )
    }

    @Test
    fun `si cambiaron los dos se fusiona sin preguntar`() {
        val p = Diferencia.plan(
            listOf(ap("1a", "mio")), listOf(ap("1a", "suyo")), mapOf("1a" to "viejo")
        )
        assertEquals(listOf(Diferencia.Paso.Fusionar("1a")), p)
    }

    @Test
    fun `sin haber sincronizado antes dos distintos se fusionan`() {
        // Sin base no se sabe quién se movió: se junta todo, que perder algo es peor que tener de más.
        val p = Diferencia.plan(listOf(ap("1a", "mio")), listOf(ap("1a", "suyo")))
        assertEquals(listOf(Diferencia.Paso.Fusionar("1a")), p)
    }

    // ------------------------------------------------------------- lo borrado

    @Test
    fun `borrar deja rastro y el rastro viaja`() {
        // Si la marca no se mandara, el otro me devolvería el mensaje y lo resucitaría.
        val p = Diferencia.plan(listOf(ap("1a", "x", borrado = true)), emptyList())
        assertEquals(listOf(Diferencia.Paso.Mandar("1a")), p)
    }

    @Test
    fun `lo que el otro borro y yo nunca tuve no se trae`() {
        val p = Diferencia.plan(emptyList(), listOf(ap("1t", "x", borrado = true)))
        assertTrue(p.isEmpty())
    }

    @Test
    fun `borrado en uno e intacto en el otro se borra en los dos`() {
        val p = Diferencia.plan(
            listOf(ap("1a", "x", borrado = true)), listOf(ap("1a", "x")), mapOf("1a" to "x")
        )
        assertEquals(listOf(Diferencia.Paso.Mandar("1a")), p)
        val alReves = Diferencia.plan(listOf(ap("1a", "x")), listOf(ap("1a", "x", borrado = true)), mapOf("1a" to "x"))
        assertEquals(listOf(Diferencia.Paso.Traer("1a")), alReves)
    }

    @Test
    fun `borrado en uno y cambiado en el otro se queda lo cambiado`() {
        // Borrar quita lo que uno vio: una mejora que el otro no vio sobrevive (add-wins).
        val p = Diferencia.plan(
            listOf(ap("1a", "x", borrado = true)), listOf(ap("1a", "mejorado")),
            mapOf("1a" to "x")
        )
        assertEquals(listOf(Diferencia.Paso.Traer("1a")), p)
        // Y sin base con que saber si se tocó, también se queda.
        assertEquals(listOf(Diferencia.Paso.Mandar("1a")), Diferencia.plan(listOf(ap("1a", "y")), listOf(ap("1a", "x", borrado = true))))
    }

    @Test
    fun `una marca de borrado de antes de los codigos se reconoce por su sena`() {
        val vivo = Diferencia.Apunte("K7Q2ABCDEF", 0, 0, "x", alias = "47a")
        val marca = Diferencia.Apunte(Diferencia.MARCA_VIEJA + "47a", 0, 0, "borrado", borrado = true, alias = "47a")
        // Lo acordado antes iba por la seña.
        val p = Diferencia.plan(listOf(marca), listOf(vivo), mapOf("47a" to "x"))
        assertEquals(listOf(Diferencia.Paso.Mandar("K7Q2ABCDEF")), p)
    }

    @Test
    fun `si los dos lo borraron no hay nada que hacer`() {
        val p = Diferencia.plan(
            listOf(ap("1a", "x", borrado = true)), listOf(ap("1a", "y", borrado = true))
        )
        assertTrue(p.isEmpty())
    }

    // --------------------------------------------------------------- el orden

    @Test
    fun `el chat se ordena por la hora de creacion y no por cuando llego`() {
        // Es lo que hace que los dos aparatos acaben con la misma lista, y que «mira el 47»
        // señale el mismo sitio en los dos.
        val desordenados = listOf(ap("9a", "x", creado = 300), ap("2t", "x", creado = 100))
        assertEquals(listOf("2t", "9a"), Diferencia.ordenar(desordenados).map { it.sena })
    }

    @Test
    fun `a la misma hora ordena la sena y no el azar`() {
        val a = ap("5t", "x", creado = 100)
        val b = ap("5a", "x", creado = 100)
        assertEquals(listOf("5a", "5t"), Diferencia.ordenar(listOf(a, b)).map { it.sena })
        assertEquals(listOf("5a", "5t"), Diferencia.ordenar(listOf(b, a)).map { it.sena })
    }

    @Test
    fun `el plan sale en el mismo orden en los dos aparatos`() {
        val mio = listOf(ap("9a", "x"), ap("2t", "y"))
        val suyo = listOf(ap("2t", "z"))
        val p = Diferencia.plan(mio, suyo)
        assertEquals(listOf("2t", "9a"), p.map { it.sena })
    }
}
