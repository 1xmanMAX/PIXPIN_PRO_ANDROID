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

    @Test
    fun `lo de la esquina se aparta de la cámara de esquina por el lado más corto`() {
        // Cámara arriba a la derecha; un candado de 100×60 pegado a esa esquina.
        val camara = Rect(960f, 20f, 1040f, 100f)
        val candado = Rect(940f, 30f, 1060f, 90f)
        val m = EsquivaDeCamara.empuje(candado, listOf(camara), 1080f, 2400f)
        // Bajar son 100-30+12 = 82; apartarse a la izquierda, 1060-960+12 = 112: baja.
        assertEquals(0f, m[0]); assertEquals(82f, m[1]); assertEquals(0f, m[2]); assertEquals(0f, m[3])
    }

    @Test
    fun `de lado, el panel del canto se aparta de la cámara hacia dentro`() {
        // Teléfono de lado: la cámara en el canto izquierdo, a media altura; el panel ocupa ese canto.
        val camara = Rect(0f, 500f, 80f, 580f)
        val panel = Rect(0f, 200f, 120f, 880f)
        val m = EsquivaDeCamara.empuje(panel, listOf(camara), 2400f, 1080f)
        assertEquals(92f, m[0]); assertEquals(0f, m[1])
    }

    @Test
    fun `lo que no la pisa no se mueve`() {
        val m = EsquivaDeCamara.empuje(Rect(0f, 1000f, 100f, 1100f), listOf(Rect(500f, 0f, 580f, 80f)), 1080f, 2400f)
        assertTrue(m.all { it == 0f })
    }
}
