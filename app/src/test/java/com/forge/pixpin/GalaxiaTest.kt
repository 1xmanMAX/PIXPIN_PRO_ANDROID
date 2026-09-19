package com.forge.pixpin

import com.forge.pixpin.ui.Galaxia
import com.forge.pixpin.ui.NotaDeGalaxia
import com.forge.pixpin.ui.PuntoDeGalaxia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GalaxiaTest {

    @Test
    fun `la espiral no amontona planetas aunque haya muchos`() {
        val puntos = (0 until 300).map { Galaxia.enEspiral(it) }
        var menor = Float.MAX_VALUE
        for (i in puntos.indices) for (j in i + 1 until puntos.size) {
            menor = minOf(menor, hypot(puntos[i].x - puntos[j].x, puntos[i].y - puntos[j].y))
        }
        // Un planeta mide como mucho 112 dp; con su etiqueta, 150 de ancho.
        assertTrue("distancia mínima $menor", menor >= 150f)
    }

    @Test
    fun `lo movido se queda y lo demás va a la espiral`() {
        val g = Galaxia().conPosicion("b", PuntoDeGalaxia(500f, -20f))
        val sitios = g.colocar(listOf("a", "b", "c"))
        assertEquals(Galaxia.enEspiral(0), sitios["a"])
        assertEquals(PuntoDeGalaxia(500f, -20f), sitios["b"])
        assertEquals(Galaxia.enEspiral(2), sitios["c"])
    }

    @Test
    fun `un proyecto que ya no existe no se pinta`() {
        val g = Galaxia().conPosicion("borrado", PuntoDeGalaxia(1f, 1f))
        assertEquals(setOf("a"), g.colocar(listOf("a")).keys)
    }

    @Test
    fun `se guarda y se lee igual, y un archivo roto no tumba nada`() {
        val g = Galaxia()
            .conPosicion("a", PuntoDeGalaxia(3f, 4f))
            .conNota(NotaDeGalaxia("n1", "revisar planos", 10f, 20f))
        assertEquals(g, Galaxia.leer(Galaxia.escribir(g)))
        assertEquals(Galaxia(), Galaxia.leer("{esto no es json"))
        assertEquals(Galaxia(), Galaxia.leer(null))
    }

    @Test
    fun `las notas se cambian en su sitio y se quitan`() {
        val g = Galaxia()
            .conNota(NotaDeGalaxia("n1", "uno", 0f, 0f))
            .conNota(NotaDeGalaxia("n2", "dos", 0f, 0f))
            .conNota(NotaDeGalaxia("n1", "uno bis", 5f, 5f))
        assertEquals(listOf("uno bis", "dos"), g.notas.map { it.texto })
        assertEquals(listOf("n2"), g.sinNota("n1").notas.map { it.id })
    }

    @Test
    fun `el planeta crece con las hojas pero tiene tope`() {
        assertTrue(Galaxia.diametro(10) > Galaxia.diametro(1))
        assertEquals(112f, Galaxia.diametro(10_000))
    }
}
