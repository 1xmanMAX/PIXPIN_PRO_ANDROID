package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * **La base congelada da EXACTAMENTE lo que da la cámara**, número a número.
 *
 * No «parecido»: exacto. La base existe solo para pagar la trigonometría una vez por
 * fotograma en vez de una vez por punto, así que cualquier diferencia con [Camara3D.aPantalla]
 * —aunque fuera del último bit— sería un cambio de aspecto colado por una optimización, que es
 * la clase de deuda que después nadie sabe rastrear. Misma cuenta, mismos dobles, igualdad
 * exacta con delta cero.
 */
class BaseDeCamaraTest {

    private val ancho = 1080.0
    private val alto = 2400.0

    private fun lasCamaras(): List<Camara3D> = listOf(
        Camara3D(),
        Camara3D(giro = 1.1, inclinacion = 0.6, zoom = 3.5, centro = Pt3(12.0, -7.0, 4.0)),
        Camara3D(lente = 0.7, zoom = 0.8, centro = Pt3(-3.0, 2.0, 9.0)),
        Camara3D(lente = 1.0, giro = -2.2, inclinacion = -0.4, balanceo = 0.35),
        Camara3D(zoom = 40.0, giro = 0.01, inclinacion = 1.2)
    )

    @Test
    fun `la base proyecta identico a la camara, punto a punto`() {
        val azar = Random(7)
        for (camara in lasCamaras()) {
            val base = camara.base(ancho, alto)
            repeat(500) {
                val p = Pt3(
                    azar.nextDouble(-500.0, 500.0),
                    azar.nextDouble(-500.0, 500.0),
                    azar.nextDouble(-500.0, 500.0)
                )
                val deLaCamara = camara.aPantalla(p, ancho, alto)
                val deLaBase = base.aPantalla(p)
                assertEquals(deLaCamara.x, deLaBase.x, 0.0)
                assertEquals(deLaCamara.y, deLaBase.y, 0.0)
            }
        }
    }

    @Test
    fun `el lote empaquetado da lo mismo que el punto a punto`() {
        val azar = Random(11)
        for (camara in lasCamaras()) {
            val base = camara.base(ancho, alto)
            val n = 200
            val xyz = DoubleArray(3 * n) { azar.nextDouble(-300.0, 300.0) }
            val salida = DoubleArray(2 * n)
            base.proyecta(xyz, salida)
            for (i in 0 until n) {
                val suelto = base.aPantalla(Pt3(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]))
                assertEquals(suelto.x, salida[2 * i], 0.0)
                assertEquals(suelto.y, salida[2 * i + 1], 0.0)
            }
        }
    }

    @Test
    fun `el punto en el eje de la mirada, delante y detras`() {
        // El caso que se arregló para el modo realidad: en el eje no hay dirección, y lo de
        // detrás no puede aparecer en el centro. La base tiene que clavar las dos ramas.
        val camara = Camara3D(lente = 0.8, centro = Pt3(0.0, 0.0, 0.0))
        val base = camara.base(ancho, alto)
        val a = camara.adelante
        for (lado in listOf(50.0, -50.0)) {
            val p = Pt3(a.x * lado, a.y * lado, a.z * lado)
            val deLaCamara = camara.aPantalla(p, ancho, alto)
            val deLaBase = base.aPantalla(p)
            assertEquals(deLaCamara.x, deLaBase.x, 0.0)
            assertEquals(deLaCamara.y, deLaBase.y, 0.0)
        }
    }

    @Test
    fun `la hondura ordena igual que la mirada`() {
        val camara = Camara3D(giro = 0.4, inclinacion = 0.3)
        val base = camara.base(ancho, alto)
        val a = camara.adelante
        // Un paso hacia adelante desde cualquier sitio tiene que subir la hondura en
        // exactamente un paso: es la moneda con la que el pintor ordena.
        val desde = Pt3(3.0, -2.0, 5.0)
        val h0 = base.hondo(desde.x, desde.y, desde.z)
        val h1 = base.hondo(desde.x + a.x * 7.0, desde.y + a.y * 7.0, desde.z + a.z * 7.0)
        assertEquals(7.0, h1 - h0, 1e-9)
    }
}
