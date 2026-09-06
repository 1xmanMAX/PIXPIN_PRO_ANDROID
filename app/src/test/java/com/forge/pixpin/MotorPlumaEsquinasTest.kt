package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.anchosDelMundo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

/**
 * **Las esquinas y las costuras del trazo** — los dos sitios donde el barrido se rompía.
 *
 * La espina emite en cada punto de control la tangente **de salida**, así que en una esquina
 * el último anillo del tramo que llega venía girado el ángulo entero: en una W de verdad,
 * 143° medidos entre dos muestras seguidas. Con ese giro el cuadrilátero entre los dos
 * anillos se dobla sobre sí mismo —lo que se veía como un trazo roto en el pico— y por fuera
 * de la curva queda una cuña sin pintar —los huecos del rodillo al girar—.
 */
class MotorPlumaEsquinasTest {

    private fun trazo(puntos: List<Pt3>, pincel: Pincel) = Trazo3D(
        id = "w", puntos = puntos, color = "#ff0000", grosor = 4.0, pincel = pincel,
        calibre = 4.0, tiempos = List(puntos.size) { it * 0.02 },
        presiones = List(puntos.size) { 0.7 }, normal = Pt3(0.0, 0.0, 1.0)
    )

    /** Una W con dos picos vivos: el de abajo gira 143°. */
    private val laW = listOf(
        Pt3(0.0, 0.0, 0.0), Pt3(10.0, 0.0, 0.0), Pt3(20.0, -30.0, 0.0),
        Pt3(30.0, 0.0, 0.0), Pt3(40.0, -30.0, 0.0), Pt3(50.0, 0.0, 0.0), Pt3(60.0, 0.0, 0.0)
    )

    private fun giroMaximo(e: EsqueletoDelTrazo, lista: DoubleArray, salto: Int = 3): Double {
        var peor = 0.0
        for (i in 0 until e.cuantas - 1) {
            val a = i * salto
            val b = (i + 1) * salto
            val d = lista[a] * lista[b] + lista[a + 1] * lista[b + 1] + lista[a + 2] * lista[b + 2]
            val ang = Math.toDegrees(acos(d.coerceIn(-1.0, 1.0)))
            if (ang > peor) peor = ang
        }
        return peor
    }

    /**
     * **Ningún anillo se da la vuelta.** Entre dos muestras seguidas, ni la tangente ni el
     * través pueden girar más que un paso de la unión: con más, el cuadrilátero que los une
     * se dobla y el trazo sale roto. Vale para todas las puntas, que todas barren su sección.
     */
    @Test
    fun `en una esquina viva el giro por muestra queda acotado`() {
        for (pincel in listOf(Pincel.REDONDO, Pincel.CUADRADO, Pincel.RESALTADOR, Pincel.CUCHILLA)) {
            val e = cocerEsqueleto(trazo(laW, pincel))!!
            val tope = 20.0
            assertTrue(
                "$pincel: la tangente gira ${giroMaximo(e, e.tangentes)}° entre muestras",
                giroMaximo(e, e.tangentes) < tope
            )
            // El través es el que orienta el anillo: es el que de verdad no puede voltear.
            var peorS = 0.0
            for (i in 0 until e.cuantas - 1) {
                val a = 6 * i + 3
                val b = 6 * (i + 1) + 3
                val d = e.marcos[a] * e.marcos[b] + e.marcos[a + 1] * e.marcos[b + 1] +
                    e.marcos[a + 2] * e.marcos[b + 2]
                val ang = Math.toDegrees(acos(d.coerceIn(-1.0, 1.0)))
                if (ang > peorS) peorS = ang
            }
            assertTrue("$pincel: el través gira $peorS° entre muestras", peorS < tope)
        }
    }

    /** La unión no mueve el trazo: solo mete muestras **en el mismo sitio** del vértice. */
    @Test
    fun `la union pivota sobre el vertice y no desplaza nada`() {
        val e = cocerEsqueleto(trazo(laW, Pincel.RESALTADOR))!!
        for (p in laW) {
            var mejor = Double.MAX_VALUE
            for (i in 0 until e.cuantas) {
                val d = Math.hypot(
                    Math.hypot(e.xyz[3 * i] - p.x, e.xyz[3 * i + 1] - p.y), e.xyz[3 * i + 2] - p.z
                )
                if (d < mejor) mejor = d
            }
            assertTrue("la espina se alejó de $p: $mejor", mejor < 1e-6)
        }
    }

    /** Una curva suave no paga nada: la espina ya acota su giro por debajo del umbral. */
    @Test
    fun `una curva suave no mete muestras de mas`() {
        val suave = (0..30).map { Pt3(it * 2.0, Math.sin(it / 4.0) * 6.0, 0.0) }
        val e = cocerEsqueleto(trazo(suave, Pincel.RESALTADOR))!!
        assertTrue("metió muestras donde no hay esquina: ${e.cuantas}", e.cuantas < suave.size * 3)
    }

    /**
     * **El corte entre dos trozos no es una punta.** Un trazo que se está haciendo se pinta
     * por trozos, y afilar el corte dejaba el trazo a menos de la mitad de ancho justo en su
     * mitad: era la «tinta más pequeña en el centro».
     */
    @Test
    fun `el corte interno no se afila`() {
        val n = 40
        val recorrido = DoubleArray(n) { it * 1.0 }
        val tiempos = DoubleArray(n) { it * 0.02 }
        val presiones = DoubleArray(n) { 0.7 }
        val conPunta = anchosDelMundo(4.0, presiones, recorrido, tiempos)
        val sinPunta = anchosDelMundo(4.0, presiones, recorrido, tiempos, afilaElFinal = false)
        // La punta de verdad se afila; el corte interno se queda con el ancho del cuerpo.
        assertTrue("la punta real tiene que afilarse", conPunta[n - 1] < conPunta[n / 2] * 0.8)
        assertEquals("el corte interno no puede adelgazar", sinPunta[n / 2], sinPunta[n - 1], 1e-9)
        // Y el principio sigue afilándose, que sí es punta.
        assertTrue(sinPunta[0] < sinPunta[n / 2] * 0.8)
    }
}
