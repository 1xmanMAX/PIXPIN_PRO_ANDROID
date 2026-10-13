package com.forge.pixpin.pin

import com.forge.pixpin.guardados.escalaParaLado
import com.forge.pixpin.motor.Crop
import com.forge.pixpin.motor.recorteEnPixeles
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Lo que llega del PC se ve con sus píxeles** (13-oct-2026: «se sincroniza con una calidad
 * pésima, irreconocible, no se lee ni una letra»).
 *
 * El archivo viajaba entero; era la lectura de aquí la que lo partía por dos (y por cuatro) hasta
 * 2048 o hasta 1000. Robolectric no rasteriza: se comparan medidas, no píxeles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalidadDeLasFotosTest {

    @get:Rule val carpeta = TemporaryFolder()

    /** Un PNG de verdad de [ancho]×[alto], escrito con la JVM (no con Android). */
    private fun png(ancho: Int, alto: Int): File {
        val f = carpeta.newFile("captura-${ancho}x$alto.png")
        val img = java.awt.image.BufferedImage(ancho, alto, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(img, "png", f)
        return f
    }

    @Test fun unaCapturaDe2560SeLeeEnteraYNoPartidaPorDos() {
        val f = png(2560, 1440)
        // Lo de antes, que se sigue usando para medir la foto en el lienzo (y el PC lo copia).
        val medida = ImageStore.load(f.absolutePath)!!
        assertEquals(1280, medida.width)
        // Lo que se pinta.
        val nitida = ImageStore.cargarNitida(f.absolutePath)!!
        assertEquals(2560, nitida.width)
        assertEquals(1440, nitida.height)
        assertTrue(ImageStore.estaEntera(f.absolutePath, nitida))
        // Caso negativo: la de 1280 no está entera, así que el editor busca la nítida.
        assertTrue(!ImageStore.estaEntera(f.absolutePath, medida))
    }

    @Test fun elEscritorioEnteroDelPcYaNoQuedaAUnCuarto() {
        // Tres monitores en fila: [load] la dejaba en 1440 (a un cuarto).
        val f = png(5760, 1080)
        assertEquals(1440, ImageStore.load(f.absolutePath)!!.width)
        assertEquals(5760, ImageStore.cargarNitida(f.absolutePath)!!.width)
    }

    @Test fun paraLaBurbujaSeReduceExactaYNoASaltos() {
        val f = png(2560, 1440)
        // Con `load(ruta, 1000)` salía de 640: partida por cuatro.
        assertEquals(640, ImageStore.load(f.absolutePath, 1000)!!.width)
        val b = ImageStore.cargarNitida(f.absolutePath, 1600, 1600L * 1000L)!!
        assertEquals(1600, b.width)
        assertEquals(900, b.height)
        // Una pequeña no se agranda.
        val p = png(800, 600)
        assertEquals(800, ImageStore.cargarNitida(p.absolutePath, 1600, 1600L * 1000L)!!.width)
    }

    @Test fun lasCuentasDeLaMedida() {
        assertEquals(2560 to 1440, ImageStore.medidaNitida(2560, 1440))
        // Una foto de 50 Mpx cabe en 4096² píxeles sin deformarse.
        val (w, h) = ImageStore.medidaNitida(8160, 6120)
        assertTrue(w.toLong() * h <= ImageStore.PIXELES_NITIDOS)
        assertEquals(8160.0 / 6120.0, w.toDouble() / h, 0.01)
        // El lado manda si es más estricto.
        assertEquals(1600 to 900, ImageStore.medidaNitida(3200, 1800, lado = 1600))
        // Casos negativos: medidas sin sentido se devuelven tal cual.
        assertEquals(0 to 10, ImageStore.medidaNitida(0, 10))
    }

    @Test fun laMuestraNuncaBajaDeLoPedido() {
        assertEquals(1, ImageStore.muestraSinPasarse(2560, 2560))
        assertEquals(1, ImageStore.muestraSinPasarse(2560, 1600))
        assertEquals(2, ImageStore.muestraSinPasarse(2560, 1280))
        assertEquals(2, ImageStore.muestraSinPasarse(2560, 1000))
        assertEquals(4, ImageStore.muestraSinPasarse(2560, 640))
        assertEquals(1, ImageStore.muestraSinPasarse(2560, 0))
    }

    @Test fun conMuchasFotosSeRepartePeroNoBajaDeLoDeAntes() {
        assertEquals(ImageStore.PIXELES_NITIDOS, ImageStore.pixelesParaVarias(1))
        assertEquals(ImageStore.PIXELES_NITIDOS, ImageStore.pixelesParaVarias(0))
        assertTrue(ImageStore.pixelesParaVarias(6) < ImageStore.PIXELES_NITIDOS)
        assertEquals(2048L * 2048L, ImageStore.pixelesParaVarias(500))
    }

    @Test fun elRecorteSeLlevaALaMedidaLeida() {
        val c = Crop(x = 100.0, y = 50.0, width = 1000.0, height = 500.0, naturalWidth = 2560.0, naturalHeight = 1440.0)
        // Leída entera, tal cual.
        assertArrayEquals(intArrayOf(100, 50, 1100, 550), recorteEnPixeles(c, 2560, 1440))
        // Leída a la mitad, a la mitad: antes se cogía otro trozo de la foto.
        assertArrayEquals(intArrayOf(50, 25, 550, 275), recorteEnPixeles(c, 1280, 720))
        // Caso negativo: sin medida natural, tal cual.
        assertArrayEquals(intArrayOf(100, 50, 1100, 550), recorteEnPixeles(c.copy(naturalWidth = 0.0, naturalHeight = 0.0), 1280, 720))
    }

    @Test fun laFotoAnotadaSalePorLaBurbujaAlLadoDeLaBurbuja() {
        // El lienzo de una captura de 2560 hecho en el PC mide 1280 unidades: a 0,5 eran 640.
        assertEquals(1.25, escalaParaLado(1280.0, 720.0, 1600), 1e-9)
        assertEquals(1600.0 / 1920.0, escalaParaLado(1920.0, 1080.0, 1600), 1e-9)
        // Acotada: ni un punto se infla sin fin ni un lienzo enorme se queda en nada.
        assertEquals(2.0, escalaParaLado(10.0, 10.0, 1600), 1e-9)
        assertEquals(0.1, escalaParaLado(100000.0, 10.0, 1600), 1e-9)
        assertEquals(0.5, escalaParaLado(0.0, 0.0, 1600), 1e-9)
    }
}
