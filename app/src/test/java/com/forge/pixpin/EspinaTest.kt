package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La espina del trazo: la Catmull-Rom centrípeta con muestreo adaptativo.
 *
 * Lo que se vigila es lo que promete la espina: que **pasa por todos los puntos
 * de control y exactos**, que la centrípeta no se riza donde la uniforme sí, que
 * una esquina querida sigue siendo esquina, que la tolerancia va en unidades del
 * mundo (escalar el mundo con su tolerancia da el mismo muestreo), y que los
 * puntos caen donde hay curva y no donde hay recta.
 */
class EspinaTest {

    private fun p(x: Double, y: Double, z: Double = 0.0) = Pt3(x, y, z)

    /** Un camino genérico, con `z` incluida: nada colineal, nada simétrico. */
    private fun caminoTorcido() = listOf(
        p(0.0, 0.0, 0.0), p(13.0, 7.0, 3.0), p(29.0, -4.0, 9.0),
        p(40.0, 22.0, -6.0), p(61.0, 10.0, 0.0)
    )

    /** El índice de la muestra que es exactamente este punto, o -1. */
    private fun indiceDe(e: Espina, q: Pt3): Int {
        for (j in 0 until e.cuantas) {
            if (e.xyz[3 * j] == q.x && e.xyz[3 * j + 1] == q.y && e.xyz[3 * j + 2] == q.z) return j
        }
        return -1
    }

    /** El giro en grados entre las cuerdas `j-1→j` y `j→j+1`, en el plano. */
    private fun giroEnGrados(e: Espina, j: Int): Double {
        val ax = e.xyz[3 * j] - e.xyz[3 * (j - 1)]
        val ay = e.xyz[3 * j + 1] - e.xyz[3 * (j - 1) + 1]
        val bx = e.xyz[3 * (j + 1)] - e.xyz[3 * j]
        val by = e.xyz[3 * (j + 1) + 1] - e.xyz[3 * j + 1]
        val cruz = ax * by - ay * bx
        val punto = ax * bx + ay * by
        return Math.toDegrees(abs(atan2(cruz, punto)))
    }

    // ---- La promesa de fondo: pasa por donde se dibujó ----

    @Test
    fun `pasa por todos los puntos de control, exactos y en orden`() {
        val puntos = caminoTorcido()
        val e = espinaCentripeta(puntos, toleranciaEnMundo = 0.5)
        var anterior = -1
        for (q in puntos) {
            val j = indiceDe(e, q)
            assertTrue("no pasa exacto por (${q.x}, ${q.y}, ${q.z})", j >= 0)
            assertTrue("los puntos de control salen desordenados", j > anterior)
            anterior = j
        }
        assertEquals("no empieza en el primer punto", 0, indiceDe(e, puntos.first()))
        assertEquals("no acaba en el último", e.cuantas - 1, indiceDe(e, puntos.last()))
    }

    /** El punto de control es el `u = 0` de su tramo (y el último, el `u = 1` del final). */
    @Test
    fun `cada punto de control sabe de que tramo es`() {
        val puntos = caminoTorcido()
        val e = espinaCentripeta(puntos, toleranciaEnMundo = 0.5)
        for (i in 0 until puntos.size - 1) {
            val j = indiceDe(e, puntos[i])
            assertEquals(i, e.deQueTramo[j])
            assertEquals(0.0, e.dondeEnElTramo[j], 0.0)
        }
        val fin = indiceDe(e, puntos.last())
        assertEquals(puntos.size - 2, e.deQueTramo[fin])
        assertEquals(1.0, e.dondeEnElTramo[fin], 0.0)
    }

    @Test
    fun `las tangentes son unitarias y el recorrido solo crece`() {
        val e = espinaCentripeta(caminoTorcido(), toleranciaEnMundo = 0.5)
        assertEquals(0.0, e.recorrido[0], 0.0)
        for (j in 0 until e.cuantas) {
            val t = sqrt(
                e.tangentes[3 * j] * e.tangentes[3 * j] +
                    e.tangentes[3 * j + 1] * e.tangentes[3 * j + 1] +
                    e.tangentes[3 * j + 2] * e.tangentes[3 * j + 2]
            )
            assertEquals("la tangente $j no es unitaria", 1.0, t, 1e-9)
            if (j > 0) assertTrue("el recorrido retrocede en $j", e.recorrido[j] >= e.recorrido[j - 1])
        }
    }

    // ---- Lo que da nombre a la centrípeta: la horquilla ----

    /**
     * Dos tramos largos unidos por uno cortísimo: el caso que a la uniforme le
     * saca el bucle —el vecino lejano fabrica un tirador enorme para un tramo
     * diminuto—. La centrípeta tiene que cruzar la horquilla sin cortarse a sí
     * misma ni una vez.
     */
    @Test
    fun `la horquilla con espaciado desigual no hace bucle`() {
        val e = espinaCentripeta(
            listOf(p(0.0, 0.0), p(100.0, 0.0), p(100.0, 4.0), p(0.0, 4.0)),
            toleranciaEnMundo = 0.1
        )
        assertTrue("con esta tolerancia tendría que haber curva de verdad", e.cuantas > 10)
        for (i in 0 until e.cuantas - 1) {
            for (k in i + 2 until e.cuantas - 1) {
                assertFalse(
                    "el trazo se cruza consigo mismo entre los segmentos $i y $k",
                    seCruzan(
                        e.xyz[3 * i], e.xyz[3 * i + 1], e.xyz[3 * i + 3], e.xyz[3 * i + 4],
                        e.xyz[3 * k], e.xyz[3 * k + 1], e.xyz[3 * k + 3], e.xyz[3 * k + 4]
                    )
                )
            }
        }
    }

    /** Cruce estricto de segmentos 2D: tocarse en un extremo no cuenta. */
    private fun seCruzan(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double
    ): Boolean {
        fun lado(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) =
            (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = lado(cx, cy, dx, dy, ax, ay)
        val d2 = lado(cx, cy, dx, dy, bx, by)
        val d3 = lado(ax, ay, bx, by, cx, cy)
        val d4 = lado(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    // ---- Las esquinas queridas ----

    /**
     * Una V de 77,32 grados (dos cuerdas de pendiente ±40/50). Declarada esquina,
     * el giro medido en la muestra del pico tiene que ser el de las cuerdas; sin
     * declarar, la spline lo redondea y ahí el giro por muestra es minúsculo. El
     * [anguloTope] fino es para que las muestras vecinas peguen con la tangente y
     * la medida por cuerdas valga como medida del ángulo.
     */
    @Test
    fun `la esquina declarada conserva su angulo`() {
        val puntos = listOf(p(0.0, 0.0), p(50.0, 40.0), p(100.0, 0.0), p(150.0, 40.0), p(200.0, 0.0))
        val esperado = Math.toDegrees(2.0 * atan2(40.0, 50.0))

        val con = espinaCentripeta(puntos, esquinas = intArrayOf(2), toleranciaEnMundo = 0.05, anguloTope = 0.02)
        val pico = indiceDe(con, puntos[2])
        assertTrue(pico > 0 && pico < con.cuantas - 1)
        assertEquals("la esquina querida no conserva su ángulo", esperado, giroEnGrados(con, pico), 2.0)

        val sin = espinaCentripeta(puntos, toleranciaEnMundo = 0.05, anguloTope = 0.02)
        val suave = indiceDe(sin, puntos[2])
        assertTrue("sin declararla, la esquina tendría que redondearse", giroEnGrados(sin, suave) < 20.0)
    }

    // ---- La tolerancia va en unidades del mundo ----

    /** Mundo por mil y tolerancia por mil: mismas muestras, en su sitio relativo. */
    @Test
    fun `multiplicar el mundo por mil no cambia el muestreo`() {
        val puntos = caminoTorcido()
        val chica = espinaCentripeta(puntos, toleranciaEnMundo = 0.7)
        val grande = espinaCentripeta(
            puntos.map { Pt3(it.x * 1000.0, it.y * 1000.0, it.z * 1000.0) },
            toleranciaEnMundo = 700.0
        )
        assertEquals("el conteo cambió con la escala", chica.cuantas, grande.cuantas)
        for (j in 0 until chica.cuantas) {
            assertEquals(chica.deQueTramo[j], grande.deQueTramo[j])
            assertEquals(chica.dondeEnElTramo[j], grande.dondeEnElTramo[j], 1e-9)
            for (c in 0..2) {
                assertEquals(chica.xyz[3 * j + c], grande.xyz[3 * j + c] / 1000.0, 1e-6)
                assertEquals(chica.tangentes[3 * j + c], grande.tangentes[3 * j + c], 1e-6)
            }
            assertEquals(chica.recorrido[j], grande.recorrido[j] / 1000.0, 1e-6)
        }
    }

    // ---- El muestreo adaptativo ----

    /** El tramo recto va con lo puesto; el codo, con las muestras que pida. */
    @Test
    fun `mas denso donde mas se curva`() {
        val e = espinaCentripeta(
            listOf(
                p(0.0, 0.0), p(50.0, 0.0), p(100.0, 0.0),
                p(115.0, 15.0), p(100.0, 30.0), p(50.0, 30.0)
            ),
            toleranciaEnMundo = 0.25
        )
        val enLaRecta = (0 until e.cuantas).count { e.deQueTramo[it] == 0 }
        val enElCodo = (0 until e.cuantas).count { e.deQueTramo[it] == 2 }
        assertEquals("una recta no necesita más que su punto de partida", 1, enLaRecta)
        assertTrue("el codo ($enElCodo) tendría que llevar más muestras que la recta ($enLaRecta)", enElCodo > enLaRecta)
    }

    /** El tope corta el afinado, nunca los puntos de control. */
    @Test
    fun `el tope de muestras se respeta y los puntos de control se quedan`() {
        val puntos = caminoTorcido()
        val e = espinaCentripeta(puntos, toleranciaEnMundo = 1e-6, topeDeMuestras = 20)
        assertTrue("se pasó del tope: ${e.cuantas}", e.cuantas <= 20)
        for (q in puntos) assertTrue("el tope se comió un punto de control", indiceDe(e, q) >= 0)
    }

    // ---- Lo que viaja en paralelo: presión, tiempo ----

    /** En los puntos de control el valor es el del punto, exacto; entre medias, acotado. */
    @Test
    fun `interpolaParalela clava los extremos`() {
        val puntos = caminoTorcido()
        val presion = doubleArrayOf(0.1, 0.9, 0.4, 0.7, 0.3)
        val e = espinaCentripeta(puntos, toleranciaEnMundo = 0.5)
        val valores = interpolaParalela(e, presion)
        assertEquals(e.cuantas, valores.size)
        assertEquals(0.1, valores.first(), 0.0)
        assertEquals(0.3, valores.last(), 0.0)
        for (i in puntos.indices) {
            assertEquals("en el punto $i el valor no es el suyo", presion[i], valores[indiceDe(e, puntos[i])], 0.0)
        }
        for (j in 0 until e.cuantas) {
            assertTrue("el valor $j se sale del rango de sus puntos", valores[j] in 0.1..0.9)
        }
    }

    // ---- Los casos chicos ----

    @Test
    fun `dos puntos son la recta, con dos muestras`() {
        val e = espinaCentripeta(listOf(p(3.0, 4.0, 5.0), p(6.0, 8.0, 5.0)), toleranciaEnMundo = 0.5)
        assertEquals(2, e.cuantas)
        assertEquals(3.0, e.xyz[0], 0.0); assertEquals(4.0, e.xyz[1], 0.0); assertEquals(5.0, e.xyz[2], 0.0)
        assertEquals(6.0, e.xyz[3], 0.0); assertEquals(8.0, e.xyz[4], 0.0); assertEquals(5.0, e.xyz[5], 0.0)
        // La dirección de la recta, unitaria y la misma en las dos muestras.
        val largo = hypot(3.0, 4.0)
        for (j in 0..1) {
            assertEquals(3.0 / largo, e.tangentes[3 * j], 1e-12)
            assertEquals(4.0 / largo, e.tangentes[3 * j + 1], 1e-12)
            assertEquals(0.0, e.tangentes[3 * j + 2], 1e-12)
        }
        assertEquals(0.0, e.recorrido[0], 0.0)
        assertEquals(5.0, e.recorrido[1], 1e-12)
        assertEquals(0.0, e.dondeEnElTramo[0], 0.0)
        assertEquals(1.0, e.dondeEnElTramo[1], 0.0)
        assertEquals(0, e.deQueTramo[0])
        assertEquals(0, e.deQueTramo[1])
    }

    @Test
    fun `sin puntos no hay espina, y con uno hay una muestra`() {
        assertEquals(0, espinaCentripeta(emptyList(), toleranciaEnMundo = 0.5).cuantas)
        val e = espinaCentripeta(listOf(p(7.0, -2.0, 1.0)), toleranciaEnMundo = 0.5)
        assertEquals(1, e.cuantas)
        assertEquals(7.0, e.xyz[0], 0.0)
        assertEquals(0.0, e.recorrido[0], 0.0)
    }
}
