package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los marcos de rotación mínima por doble reflexión.
 *
 * Lo que se vigila es lo que rompería la cinta: que en lo plano el marco sea
 * **el mismo de siempre** (la normal del plano, clavada), que el triedro no se
 * descuadre, que no haya vueltas fantasma como las de Frenet, que un punto
 * repetido no divida por cero, que extender un trazo dé lo mismo que
 * recalcularlo, y que el error baje **a la cuarta potencia del paso**, que es
 * lo que promete la doble reflexión y lo que la hace mejor que proyectar.
 */
class MarcosMinimosTest {

    // ---- Curvas de prueba ----

    /** Una hélice con tangentes unitarios exactos, muestreada pareja. */
    private fun helice(muestras: Int, vueltaTotal: Double, paso: Double = 0.4): Pair<DoubleArray, DoubleArray> {
        val xyz = DoubleArray(muestras * 3)
        val tangentes = DoubleArray(muestras * 3)
        val largo = sqrt(1.0 + paso * paso)
        for (i in 0 until muestras) {
            val t = vueltaTotal * i / (muestras - 1)
            xyz[i * 3] = cos(t)
            xyz[i * 3 + 1] = sin(t)
            xyz[i * 3 + 2] = paso * t
            tangentes[i * 3] = -sin(t) / largo
            tangentes[i * 3 + 1] = cos(t) / largo
            tangentes[i * 3 + 2] = paso / largo
        }
        return xyz to tangentes
    }

    private fun r(marcos: DoubleArray, i: Int) =
        doubleArrayOf(marcos[i * 6], marcos[i * 6 + 1], marcos[i * 6 + 2])

    private fun s(marcos: DoubleArray, i: Int) =
        doubleArrayOf(marcos[i * 6 + 3], marcos[i * 6 + 4], marcos[i * 6 + 5])

    private fun punto(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    // ---- La paridad con las hojas planas: la prueba que manda ----

    /**
     * En una curva plana, r se queda **clavado en la normal del plano** en
     * todas las muestras. Es la paridad con el marco actual de las hojas
     * planas: si esto falla, cambiar de marco cambia lo ya dibujado.
     *
     * El plano va inclinado a propósito: en el plano del suelo un fallo de
     * ejes puede esconderse detrás de los ceros.
     */
    @Test
    fun `en una curva plana r se queda clavado en la normal`() {
        // Normal n, y u y w perpendiculares entre sí y a ella: la curva vive
        // en el plano de u y w, y serpentea para que haya giro que tentar.
        val n = doubleArrayOf(1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0)
        val u = doubleArrayOf(-2.0 / sqrt(5.0), 1.0 / sqrt(5.0), 0.0)
        val w = doubleArrayOf(
            n[1] * u[2] - n[2] * u[1],
            n[2] * u[0] - n[0] * u[2],
            n[0] * u[1] - n[1] * u[0]
        )
        val muestras = 200
        val xyz = DoubleArray(muestras * 3)
        val tangentes = DoubleArray(muestras * 3)
        for (i in 0 until muestras) {
            val t = 5.0 * i / (muestras - 1)
            val onda = 0.7 * sin(2 * t)
            val dOnda = 1.4 * cos(2 * t)
            for (k in 0..2) xyz[i * 3 + k] = u[k] * t + w[k] * onda
            val dx = u[0] + w[0] * dOnda
            val dy = u[1] + w[1] * dOnda
            val dz = u[2] + w[2] * dOnda
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            tangentes[i * 3] = dx / d
            tangentes[i * 3 + 1] = dy / d
            tangentes[i * 3 + 2] = dz / d
        }

        val marcos = marcosMinimos(xyz, tangentes, Pt3(n[0], n[1], n[2]))
        for (i in 0 until muestras) {
            val ri = r(marcos, i)
            for (k in 0..2) {
                assertEquals("muestra $i, eje $k", n[k], ri[k], 1e-9)
            }
        }
    }

    // ---- El triedro no se descuadra ----

    /**
     * A lo largo de una hélice, r y s siguen unitarios y perpendiculares
     * entre sí y al tangente. Con 500 muestras se cruza varias veces el
     * renormalizado periódico, que también queda vigilado.
     */
    @Test
    fun `r y s y t se mantienen ortonormales por toda una helice`() {
        val (xyz, tangentes) = helice(500, 6 * PI)
        val marcos = marcosMinimos(xyz, tangentes, Pt3(0.0, 0.0, 1.0))
        for (i in 0 until 500) {
            val ri = r(marcos, i)
            val si = s(marcos, i)
            val ti = doubleArrayOf(tangentes[i * 3], tangentes[i * 3 + 1], tangentes[i * 3 + 2])
            assertEquals("largo de r en $i", 1.0, punto(ri, ri), 1e-9)
            assertEquals("largo de s en $i", 1.0, punto(si, si), 1e-9)
            assertEquals("r contra s en $i", 0.0, punto(ri, si), 1e-9)
            assertEquals("r contra t en $i", 0.0, punto(ri, ti), 1e-9)
            assertEquals("s contra t en $i", 0.0, punto(si, ti), 1e-9)
        }
    }

    // ---- Sin vueltas fantasma ----

    /**
     * En una hélice suave, el giro de r por paso es **pequeño y sin
     * sobresaltos**: nada del salto de media vuelta que pega Frenet en las
     * inflexiones. El giro por paso no es constante en el mundo —r se acerca
     * y se aleja del eje del giro— pero varía despacio, así que se vigilan
     * las dos cosas: el tope y el cambio entre pasos vecinos.
     */
    @Test
    fun `el giro por paso es pequeno y sin sobresaltos`() {
        val muestras = 200
        val (xyz, tangentes) = helice(muestras, 6 * PI)
        val marcos = marcosMinimos(xyz, tangentes, Pt3(0.0, 0.0, 1.0))

        val giros = DoubleArray(muestras - 1)
        for (i in 0 until muestras - 1) {
            val d = punto(r(marcos, i), r(marcos, i + 1)).coerceIn(-1.0, 1.0)
            giros[i] = acos(d)
        }
        for (i in giros.indices) {
            // Medido: el mayor giro real anda en 0,088; media vuelta serían 3,14.
            assertTrue("giro de ${giros[i]} en el paso $i", giros[i] < 0.1)
        }
        for (i in 0 until giros.size - 1) {
            // Medido: el cambio real entre pasos vecinos anda en 0,003.
            assertTrue(
                "sobresalto de ${abs(giros[i + 1] - giros[i])} entre $i y ${i + 1}",
                abs(giros[i + 1] - giros[i]) < 0.01
            )
        }
    }

    // ---- Puntos repetidos ----

    /**
     * Un punto repetido no señala a ningún sitio: no puede romper la cuenta
     * ni girar el marco. Sale **el marco anterior, tal cual**, y la curva
     * sigue después como si la muestra doble no estuviera.
     */
    @Test
    fun `los puntos repetidos no rompen y copian el marco anterior`() {
        val (limpia, tangLimpia) = helice(50, 2 * PI)
        // La muestra 20, cuatro veces seguidas.
        val muestras = 53
        val xyz = DoubleArray(muestras * 3)
        val tangentes = DoubleArray(muestras * 3)
        var j = 0
        for (i in 0 until 50) {
            val veces = if (i == 20) 4 else 1
            repeat(veces) {
                for (k in 0..2) {
                    xyz[j * 3 + k] = limpia[i * 3 + k]
                    tangentes[j * 3 + k] = tangLimpia[i * 3 + k]
                }
                j++
            }
        }

        val marcos = marcosMinimos(xyz, tangentes, Pt3(0.0, 0.0, 1.0))
        for (v in marcos) assertFalse("ha salido un NaN", v.isNaN())
        // Las tres copias llevan el marco de la muestra 20, bit a bit.
        for (copia in 21..23) {
            for (k in 0..5) {
                assertEquals(marcos[20 * 6 + k], marcos[copia * 6 + k], 0.0)
            }
        }
        // Y el triedro sigue entero al final del trazo.
        val ultimo = muestras - 1
        assertEquals(1.0, punto(r(marcos, ultimo), r(marcos, ultimo)), 1e-9)
        assertEquals(1.0, punto(s(marcos, ultimo), s(marcos, ultimo)), 1e-9)
    }

    // ---- La mano incremental ----

    /**
     * Extender un trazo da **lo mismo** que recalcularlo entero: si no, la
     * cinta cambiaría de forma un pelo al levantar el dedo. Con 300 muestras
     * el sufijo cruza el renormalizado periódico dos veces, que es justo
     * donde extender y recalcular podrían separarse si el renormalizado no
     * fuera por índice global.
     */
    @Test
    fun `extender da exactamente lo mismo que recalcular`() {
        val (xyz, tangentes) = helice(300, 6 * PI)
        val enteros = marcosMinimos(xyz, tangentes, Pt3(0.0, 0.0, 1.0))

        // Llegaron 200 marcos de antes y se pide recalcular desde la 150:
        // las 50 de en medio se tiran y se rehacen, como al retocar un tramo.
        val previos = enteros.copyOfRange(0, 200 * 6)
        val extendidos = extenderMarcos(xyz, tangentes, previos, 150)

        assertEquals(enteros.size, extendidos.size)
        for (i in enteros.indices) {
            assertEquals("posición $i", enteros[i], extendidos[i], 1e-12)
        }
    }

    // ---- El orden del error ----

    /**
     * La promesa de la doble reflexión: **error a la cuarta potencia del
     * paso**. Partir el paso por dos en una curva torcida deja la deriva del
     * marco final en un dieciseisavo; proyectar el marco de muestra en
     * muestra solo la deja en un cuarto. Se pide ≥ 8, que separa las dos
     * familias con margen (lo medido ronda 16).
     *
     * La curva lleva la subida ondulada a propósito: torsión que cambia por
     * el camino, para que ninguna simetría regale precisión.
     */
    @Test
    fun `partir el paso por dos reduce la deriva mas de ocho veces`() {
        fun torcida(tramos: Int): Pair<DoubleArray, DoubleArray> {
            val muestras = tramos + 1
            val xyz = DoubleArray(muestras * 3)
            val tangentes = DoubleArray(muestras * 3)
            for (i in 0 until muestras) {
                val t = 2 * PI * i / (muestras - 1)
                xyz[i * 3] = cos(t)
                xyz[i * 3 + 1] = sin(t)
                xyz[i * 3 + 2] = 0.3 * t + 0.2 * sin(2 * t)
                val dx = -sin(t)
                val dy = cos(t)
                val dz = 0.3 + 0.4 * cos(2 * t)
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                tangentes[i * 3] = dx / d
                tangentes[i * 3 + 1] = dy / d
                tangentes[i * 3 + 2] = dz / d
            }
            return xyz to tangentes
        }

        fun rFinal(tramos: Int): DoubleArray {
            val (xyz, tangentes) = torcida(tramos)
            val marcos = marcosMinimos(xyz, tangentes, Pt3(0.0, 0.0, 1.0))
            return r(marcos, tramos)
        }

        // La referencia, con el paso partido por 32: su propio error es un
        // millonésimo del que se está midiendo, así que hace de exacta.
        val exacto = rFinal(1280)
        fun deriva(tramos: Int): Double {
            val rf = rFinal(tramos)
            return sqrt(
                (rf[0] - exacto[0]) * (rf[0] - exacto[0]) +
                    (rf[1] - exacto[1]) * (rf[1] - exacto[1]) +
                    (rf[2] - exacto[2]) * (rf[2] - exacto[2])
            )
        }

        val conPaso = deriva(40)
        val conMedioPaso = deriva(80)
        // Que haya deriva que medir: si saliera cero, la razón no diría nada.
        assertTrue("la deriva medida ($conPaso) se ha quedado en ruido", conPaso > 1e-9)
        assertTrue(
            "la deriva solo ha bajado ${conPaso / conMedioPaso} veces",
            conPaso / conMedioPaso >= 8.0
        )
    }
}
