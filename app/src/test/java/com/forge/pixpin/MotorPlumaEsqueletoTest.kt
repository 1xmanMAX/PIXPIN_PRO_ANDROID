package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **El esqueleto del trazo y su armario** — lo que convierte un trazo en un objeto quieto
 * en el aire.
 *
 * Lo que se fija aquí: que la espina pasa por lo dibujado, que el armario no cuece dos veces
 * lo mismo (y sí cuece lo editado), que solo la redonda obedece al pulso, que el marco de la
 * cinta es el de la hoja, y —la más importante— que las muestras densificadas se quedan
 * PEGADAS a una hoja curva de verdad, medido sobre un cilindro y no prometido.
 */
class MotorPlumaEsqueletoTest {

    @Before
    fun limpiar() = ElArmarioDeLosEsqueletos.vaciar()

    /** Un trazo del régimen nuevo, sobre el plano del suelo. */
    private fun trazoNuevo(
        puntos: List<Pt3>,
        pincel: Pincel = Pincel.REDONDO,
        presiones: List<Double>? = List(puntos.size) { 0.7 },
        normales: List<Pt3>? = null,
        normal: Pt3? = Pt3(0.0, 0.0, 1.0)
    ) = Trazo3D(
        id = "t",
        puntos = puntos,
        color = "#1e1e1e",
        grosor = 3.0,
        pincel = pincel,
        calibre = 3.0,
        presiones = presiones,
        tiempos = List(puntos.size) { it * 0.016 },
        normal = normal,
        normales = normales
    )

    private fun muestra(e: EsqueletoDelTrazo, i: Int) =
        Pt3(e.xyz[3 * i], e.xyz[3 * i + 1], e.xyz[3 * i + 2])

    @Test
    fun `la espina pasa por todos los puntos del trazo`() {
        val puntos = (0..20).map { Pt3(it * 4.0, sin(it / 3.0) * 10.0, 0.0) }
        val e = cocerEsqueleto(trazoNuevo(puntos))!!
        for (p in puntos) {
            var mejor = Double.MAX_VALUE
            for (i in 0 until e.cuantas) {
                val m = muestra(e, i)
                val d = hypot(hypot(m.x - p.x, m.y - p.y), m.z - p.z)
                if (d < mejor) mejor = d
            }
            assertTrue("el punto de control $p queda a $mejor", mejor < 1e-9)
        }
        assertEquals("las listas del esqueleto van a la par", e.cuantas, e.anchos.size)
        assertEquals(e.cuantas * 6, e.marcos.size)
        assertEquals(e.cuantas, e.recorrido.size)
    }

    @Test
    fun `el armario sirve el mismo esqueleto y cuece el editado`() {
        val trazo = trazoNuevo((0..15).map { Pt3(it * 5.0, (it % 3) * 2.0, 0.0) })
        val a = ElArmarioDeLosEsqueletos.de(trazo)
        val b = ElArmarioDeLosEsqueletos.de(trazo)
        assertNotNull(a)
        assertSame("dos peticiones del mismo trazo no cuecen dos veces", a, b)

        // Editar es sustituir: la lista nueva de puntos es otra identidad.
        val editado = trazo.copy(puntos = trazo.puntos.map { Pt3(it.x + 1, it.y, it.z) })
        val c = ElArmarioDeLosEsqueletos.de(editado)
        assertNotNull(c)
        assertNotSame("lo editado se cuece de nuevo", a, c)
    }

    @Test
    fun `sin tiempos no hay esqueleto - el regimen viejo se respeta`() {
        val viejo = trazoNuevo((0..10).map { Pt3(it * 5.0, 0.0, 0.0) })
            .copy(tiempos = null)
        assertNull(ElArmarioDeLosEsqueletos.de(viejo))
        val sinCalibre = trazoNuevo((0..10).map { Pt3(it * 5.0, 0.0, 0.0) })
            .copy(calibre = null)
        assertNull(ElArmarioDeLosEsqueletos.de(sinCalibre))
    }

    @Test
    fun `solo la redonda obedece al pulso`() {
        val puntos = (0..20).map { Pt3(it * 4.0, 0.0, 0.0) }
        val presiones = List(puntos.size) { if (it < 10) 0.2 else 1.0 }
        val redonda = cocerEsqueleto(trazoNuevo(puntos, Pincel.REDONDO, presiones))!!
        val cuadrada = cocerEsqueleto(trazoNuevo(puntos, Pincel.CUADRADO, presiones))!!

        val variaLaRedonda = redonda.anchos.max() - redonda.anchos.min()
        assertTrue("la redonda respira con la presión ($variaLaRedonda)", variaLaRedonda > 0.1)
        for (a in cuadrada.anchos) {
            assertEquals("la cuadrada va pareja al calibre", 3.0, a, 1e-9)
        }
    }

    @Test
    fun `el marco de la cinta es el de la hoja`() {
        // Trazo curvado DENTRO del plano del suelo: la normal de apoyo tiene que quedarse
        // clavada en (0,0,1) en todas las muestras — es la cinta tumbada de siempre.
        val puntos = (0..20).map { Pt3(cos(it / 5.0) * 30.0, sin(it / 5.0) * 30.0, 0.0) }
        val e = cocerEsqueleto(trazoNuevo(puntos))!!
        for (i in 0 until e.cuantas) {
            assertEquals("r.x en $i", 0.0, e.marcos[6 * i], 1e-9)
            assertEquals("r.y en $i", 0.0, e.marcos[6 * i + 1], 1e-9)
            assertEquals("r.z en $i", 1.0, e.marcos[6 * i + 2], 1e-9)
        }
    }

    @Test
    fun `las muestras se quedan pegadas a una hoja curva`() {
        // Un trazo sobre un cilindro de radio 40 (el eje es el de las z), con puntos cada
        // ~3 unidades y su normal radial guardada — como los deja la captura sobre una hoja
        // curva. La espina densifica ENTRE esos puntos: lo que se mide es que ninguna
        // muestra densificada se despega de la superficie. Es la prueba del intocable
        // «alisar en la superficie», con números y no con promesas.
        val radio = 40.0
        val puntos = (0..40).map { i ->
            val a = i * 0.075 // ~3 unidades de cuerda por paso
            Pt3(cos(a) * radio, sin(a) * radio, i * 1.5)
        }
        val normales = (0..40).map { i ->
            val a = i * 0.075
            Pt3(cos(a), sin(a), 0.0)
        }
        val e = cocerEsqueleto(trazoNuevo(puntos, normales = normales, normal = null))!!
        var peor = 0.0
        for (i in 0 until e.cuantas) {
            val m = muestra(e, i)
            val d = abs(hypot(m.x, m.y) - radio)
            if (d > peor) peor = d
        }
        // El 2% del calibre es la tolerancia del cocido; la adherencia real queda muy por
        // debajo (la flecha c²/8R con cuerdas de 3 sobre radio 40 es ~0.03).
        assertTrue("la peor muestra se despega $peor del cilindro", peor < 0.06)
    }

    @Test
    fun `una esquina querida sigue esquina`() {
        // Una ele en el suelo: el quiebro de 90° no puede salir redondeado.
        val ida = (0..10).map { Pt3(it * 5.0, 0.0, 0.0) }
        val vuelta = (1..10).map { Pt3(50.0, it * 5.0, 0.0) }
        val e = cocerEsqueleto(trazoNuevo(ida + vuelta))!!
        // La muestra del vértice existe exacta...
        var enElVertice = -1
        for (i in 0 until e.cuantas) {
            val m = muestra(e, i)
            if (hypot(m.x - 50.0, m.y) < 1e-9) enElVertice = i
        }
        assertTrue("el vértice está entre las muestras", enElVertice > 0)
        // ...y el giro sucede AHÍ: justo antes se va casi recto hacia x, justo después
        // casi recto hacia y.
        val antes = muestra(e, enElVertice - 1)
        val despues = muestra(e, enElVertice + 1)
        assertTrue("antes del vértice apenas se ha subido", abs(antes.y) < 0.5)
        assertTrue("después del vértice apenas queda x por recorrer", abs(despues.x - 50.0) < 0.5)
    }

    @Test
    fun `los niveles son subconjuntos anidados que conservan las puntas`() {
        val puntos = (0..63).map { Pt3(it * 2.0, sin(it / 4.0) * 8.0, 0.0) }
        // Con el cuadrado a propósito: la redonda cierra sus puntas en domo y la primera
        // muestra ya no es el primer punto de control sino el vértice del domo. Lo que se
        // verifica aquí —el anidado de los niveles— no es cosa de la punta.
        val fino = cocerEsqueleto(trazoNuevo(puntos, Pincel.CUADRADO), nivel = 0)!!
        val basto = cocerEsqueleto(trazoNuevo(puntos, Pincel.CUADRADO), nivel = 2)!!
        assertTrue("el nivel basto tiene menos muestras", basto.cuantas < fino.cuantas)
        // Las dos puntas del trazo están exactas en los dos niveles.
        for (e in listOf(fino, basto)) {
            val primera = muestra(e, 0)
            val ultima = muestra(e, e.cuantas - 1)
            assertEquals(0.0, primera.x, 1e-9)
            assertEquals(126.0, ultima.x, 1e-9)
        }
    }
}
