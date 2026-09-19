package com.forge.pixpin

import androidx.compose.ui.geometry.Rect
import com.forge.pixpin.ui.theme.EsquivaDeCamara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Esquivar la cámara: nada debajo del agujero, y las barras apartadas de las esquinas. */
class EsquivaDeCamaraTest {

    private val ancho = 1080f
    private val centro = Rect(500f, 20f, 580f, 100f)
    private val esquina = Rect(40f, 20f, 120f, 100f)
    private val barra = Rect(0f, 0f, ancho, 130f)

    @Test
    fun `con la cámara en el centro ningún botón queda debajo`() {
        val anchos = IntArray(12) { 96 }
        for (inicio in listOf(0f, -37f, -200f, -455f, 13f)) {
            val xs = EsquivaDeCamara.colocar(anchos, 0, inicio, listOf(centro.left..centro.right))
            for (i in xs.indices) {
                val a = inicio + xs[i]
                val b = a + anchos[i]
                assertTrue("botón $i en $a..$b con inicio $inicio", b <= centro.left || a >= centro.right)
            }
            // Y en orden, sin montarse.
            for (i in 1 until xs.size) assertTrue(xs[i] >= xs[i - 1] + anchos[i - 1])
        }
    }

    @Test
    fun `sin cámara se colocan seguidos`() {
        val xs = EsquivaDeCamara.colocar(intArrayOf(10, 20, 30), 5, 0f, emptyList())
        assertEquals(listOf(0, 15, 40), xs.toList())
    }

    @Test
    fun `una cámara de esquina aparta la barra y una del centro no`() {
        val (izq, der) = EsquivaDeCamara.lados(barra, listOf(esquina), ancho)
        assertTrue(izq >= esquina.right)
        assertEquals(0f, der)
        assertEquals(0f to 0f, EsquivaDeCamara.lados(barra, listOf(centro), ancho))
        // Una cámara de la esquina derecha, por la derecha.
        val derecha = Rect(960f, 20f, 1040f, 100f)
        assertTrue(EsquivaDeCamara.lados(barra, listOf(derecha), ancho).second >= ancho - derecha.left)
        // Una cámara que no está a la altura de la barra no la mueve.
        assertEquals(0f to 0f, EsquivaDeCamara.lados(Rect(0f, 300f, ancho, 400f), listOf(esquina), ancho))
    }

    @Test
    fun `lo centrado que pisa la cámara baja por debajo`() {
        val pastilla = Rect(400f, 10f, 680f, 60f)
        val baja = EsquivaDeCamara.bajada(pastilla, listOf(centro))
        assertTrue(pastilla.top + baja >= centro.bottom)
        assertEquals(0f, EsquivaDeCamara.bajada(Rect(0f, 200f, 100f, 250f), listOf(centro)))
    }
}
