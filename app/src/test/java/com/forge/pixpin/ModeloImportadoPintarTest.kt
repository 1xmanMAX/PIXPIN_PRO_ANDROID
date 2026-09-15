package com.forge.pixpin.croquis3d

import android.graphics.Bitmap
import android.graphics.Canvas
import com.forge.pixpin.motor.LectorIfc
import com.forge.pixpin.motor.Pt3
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * **El pintor de mallas no revienta** con un modelo de verdad, girando y parado, con y sin
 * lente. Aquí no se rasteriza (ver pixpin-pruebas-de-pintado): lo que se prueba son los
 * índices y los tamaños de los búferes, que es donde fallaría.
 */
@RunWith(RobolectricTestRunner::class)
class ModeloImportadoPintarTest {
    @Test
    fun `se pinta en todas las posturas sin salirse de los bueferes`() {
        val malla = LectorIfc.leer(File(javaClass.classLoader!!.getResource("ifc/revit-ejemplo.ifc")!!.toURI()))
        val c = Croquis3DControlador().apply { medida(1080.0, 1920.0) }
        c.ponerModelo("/tmp/x.malla", "x", malla.caja(), malla.cuantosTriangulos)
        val m = c.croquis.modelos.single()
        val pintor = PintorDeMalla(malla)
        val lienzo = Canvas(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888))
        var camara = c.camara
        for (i in 0 until 12) {
            camara = camara.copy(zoom = 0.05 + i * 0.1)
            pintor.pintar(lienzo, m, camara, 1080.0, 1920.0, Pt3(0.3, -0.2, 0.9), i % 2 == 0, i % 3 == 0)
        }
        pintor.pintar(lienzo, m, camara.copy(lente = 0.8), 1080.0, 1920.0, Pt3(0.0, 0.0, 1.0), false, false)
    }
}
