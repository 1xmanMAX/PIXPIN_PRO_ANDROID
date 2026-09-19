package com.forge.pixpin

import com.forge.pixpin.ui.Cuerpo
import com.forge.pixpin.ui.Espacio
import com.forge.pixpin.ui.Universos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversosTest {
    private val raiz = Universos.deProyecto("p1")
    private fun c(id: String, clase: String = Cuerpo.MENSAJE, ref: String? = id) = Cuerpo(id, clase, texto = id, ref = ref)

    @Test
    fun `un proyecto entra vacío y se llena a mano, sin meter dos veces lo mismo`() {
        var u = Universos()
        assertTrue(u.espacio(raiz, "Obra").cuerpos.isEmpty())
        val e = u.espacio(raiz, "Obra").conCuerpo(c("m1")).conCuerpo(c("m2"))
        u = u.con(e)
        assertEquals(2, u.espacio(raiz).cuerpos.size)
        assertTrue(e.yaEsta(Cuerpo.MENSAJE, "m1"))
        assertFalse(e.yaEsta(Cuerpo.HOJA, "m1"))
        // Lo siguiente nace fuera de lo que ya hay, no encima.
        assertTrue(e.sitioLibre() != e.conCuerpo(c("m3")).sitioLibre())
    }

    @Test
    fun `de un archivo nace su propio espacio, con él de sol, y así hacia dentro`() {
        var u = Universos().con(Espacio(raiz, "Obra").conCuerpo(c("m1")))
        val (con, id) = u.conSubespacio(raiz, "m1", ahora = 10)
        u = con
        assertNotNull(id)
        assertEquals(id, u.espacio(raiz).cuerpos.first().espacio)
        assertEquals("m1", u.espacio(id!!).sol?.ref)
        assertEquals(raiz, u.espacio(id).padre)
        // Pedirlo otra vez entra en el mismo, no crea otro.
        assertEquals(id, u.conSubespacio(raiz, "m1", ahora = 99).second)
        // Y dentro, otro más.
        u = u.con(u.espacio(id).conCuerpo(c("n1", Cuerpo.NOTA, null)))
        val (mas, dentro) = u.conSubespacio(id, "n1", ahora = 20)
        assertEquals(listOf(raiz, id, dentro), mas.camino(dentro!!).map { it.id })
        // Quitar el de fuera se lleva todo lo de dentro; lo demás ni se entera.
        val sin = mas.sinCuerpo(raiz, "m1")
        assertTrue(sin.espacio(raiz).cuerpos.isEmpty())
        assertNull(sin.espacios[id])
        assertNull(sin.espacios[dentro])
    }

    @Test
    fun `los vínculos se ponen y se quitan, y se van con el cuerpo`() {
        var e = Espacio(raiz).conCuerpo(c("a")).conCuerpo(c("b"))
        e = e.alternarVinculo("a", "b")
        assertEquals(1, e.vinculos.size)
        assertEquals(0, e.alternarVinculo("b", "a").vinculos.size)
        assertEquals(1, e.alternarVinculo("a", "a").vinculos.size)
        // También con el sol.
        assertEquals(2, e.alternarVinculo(Espacio.SOL, "a").vinculos.size)
        assertTrue(e.sinCuerpo("a").vinculos.isEmpty())
    }

    @Test
    fun `se guarda y se lee igual, y un archivo roto da un universo vacío`() {
        val u = Universos().con(Espacio(raiz, "Obra").conCuerpo(c("m1").copy(x = 3f, tamano = 2f)))
        assertEquals(u, Universos.leer(Universos.escribir(u)))
        assertEquals(Universos(), Universos.leer("{roto"))
        assertEquals(Universos(), Universos.leer(null))
    }
}
