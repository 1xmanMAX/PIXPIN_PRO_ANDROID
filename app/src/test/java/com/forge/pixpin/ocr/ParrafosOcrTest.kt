package com.forge.pixpin.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las pruebas de `pixpin-geom/src/parrafos.rs` del PC**, con los mismos casos: el texto que se
 * copia y el que viaja con la galería tienen que salir igual en el teléfono y en el PC.
 */
class ParrafosOcrTest {

    private fun l(x: Int, y: Int, ancho: Int, alto: Int, texto: String) = LineaLeida(x, y, ancho, alto, texto)
    private fun textos(g: List<List<LineaLeida>>) = g.map { p -> p.map { it.texto } }

    @Test fun tresLineasJuntasFormanUnSoloParrafo() {
        assertEquals(listOf(listOf("una", "dos", "tres")), textos(ParrafosOcr.agrupar(listOf(l(0, 0, 200, 18, "una"), l(0, 20, 200, 18, "dos"), l(0, 40, 200, 18, "tres")))))
    }

    @Test fun dosParrafosSeparadosPorUnHuecoNoSeJuntan() {
        assertEquals(listOf(listOf("una", "dos"), listOf("otra cosa")), textos(ParrafosOcr.agrupar(listOf(l(0, 0, 200, 18, "una"), l(0, 20, 200, 18, "dos"), l(0, 80, 200, 18, "otra cosa")))))
    }

    @Test fun dosColumnasSeLeenEnterasUnaDespuesDeOtra() {
        val g = ParrafosOcr.agrupar(listOf(
            l(150, 10, 100, 18, "b1"), l(0, 0, 100, 18, "a1"), l(150, 30, 100, 18, "b2"),
            l(0, 20, 100, 18, "a2"), l(150, 50, 100, 18, "b3"), l(0, 40, 100, 18, "a3")
        ))
        assertEquals(listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2", "b3")), textos(g))
    }

    @Test fun unaLineaSueltaALaDerechaNoEntraEnElParrafo() {
        val g = ParrafosOcr.agrupar(listOf(l(0, 0, 200, 18, "una"), l(0, 20, 200, 18, "dos"), l(400, 20, 80, 18, "nota"), l(0, 40, 200, 18, "tres")))
        assertEquals(listOf(listOf("una", "dos", "tres"), listOf("nota")), textos(g))
    }

    @Test fun unTituloAltoNoArrastraElUmbral() {
        val g = ParrafosOcr.agrupar(listOf(
            l(0, 0, 300, 100, "Titulo"), l(0, 116, 300, 20, "cuerpo uno"), l(0, 140, 300, 20, "cuerpo dos"),
            l(0, 164, 300, 20, "cuerpo tres"), l(0, 188, 300, 20, "cuerpo cuatro"), l(0, 212, 300, 20, "cuerpo cinco")
        ))
        assertEquals(2, g.size)
        assertEquals(listOf("Titulo"), textos(g)[0])
        assertEquals(5, g[1].size)
    }

    @Test fun vaciaYUnaSola() {
        assertTrue(ParrafosOcr.agrupar(emptyList()).isEmpty())
        assertEquals("", ParrafosOcr.aTexto(emptyList()))
        assertEquals(listOf(listOf("sola")), textos(ParrafosOcr.agrupar(listOf(l(0, 0, 100, 18, "sola")))))
    }

    @Test fun lasCajasDeTamanoCeroNoRompenNada() {
        val g = ParrafosOcr.agrupar(listOf(l(0, 0, 100, 20, "arriba"), l(10, 30, 0, 0, "fantasma"), l(0, 50, 100, 20, "abajo")))
        val plano = ParrafosOcr.aTexto(g)
        assertTrue(plano.contains("arriba") && plano.contains("fantasma") && plano.contains("abajo"))
        assertEquals(3, g.size)
    }

    @Test fun aTextoSeparaParrafosConUnaLineaEnBlancoYSinSaltoFinal() {
        assertEquals("una dos\n\ntres", ParrafosOcr.texto(listOf(l(0, 0, 200, 18, "una"), l(0, 20, 200, 18, "dos"), l(0, 80, 200, 18, "tres"))))
    }

    @Test fun elResultadoNoDependeDelOrdenDeEntrada() {
        val esperado = listOf(listOf("a1", "a2"), listOf("b1", "b2"))
        assertEquals(esperado, textos(ParrafosOcr.agrupar(listOf(l(0, 0, 100, 18, "a1"), l(0, 20, 100, 18, "a2"), l(150, 0, 100, 18, "b1"), l(150, 20, 100, 18, "b2")))))
        assertEquals(esperado, textos(ParrafosOcr.agrupar(listOf(l(150, 20, 100, 18, "b2"), l(0, 20, 100, 18, "a2"), l(150, 0, 100, 18, "b1"), l(0, 0, 100, 18, "a1")))))
    }
}
